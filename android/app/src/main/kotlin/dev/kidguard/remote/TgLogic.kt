package dev.kidguard

import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure parts of the Telegram bot (Tg.kt): command parsing, the link code, rule edits, message texts (vi / en),
 * keyboards. Android-free, unit-tested (TgLogicTest).
 */
object TgLogic {
    const val MAX_CHATS = 5
    const val CODE_MS = 15 * 60_000L
    const val CODE_TRIES = 5
    const val CHAT_TRIES = 3   // wrong codes from one not-linked chat before it is ignored (until the app restarts)
    const val POLL_S = 50                       // getUpdates long-poll seconds (one open request, ~1.5 KB / min idle)
    const val REC_DEF = 20; const val REC_MAX = 60
    const val TG_FILE_MAX = 20L * 1024 * 1024   // Bot API download limit (parent -> kid documents)
    const val TG_SEND_MAX = 50L * 1024 * 1024   // Bot API upload limit (kid -> parent)

    data class Cmd(val name: String, val args: List<String>, val rest: String)

    /** "/limit@MyBot 90" -> Cmd("limit", ["90"], "90"); null when not a command. */
    fun parse(text: String?): Cmd? {
        val t = text?.trim().orEmpty()
        if (!t.startsWith("/") || t.length > 4096) return null
        val head = t.substringBefore(' ').substringBefore('\n').removePrefix("/").substringBefore('@').lowercase()
        if (head.isEmpty() || head.length > 32 || !head.all { it in 'a'..'z' || it in '0'..'9' || it == '_' }) return null
        val i = t.indexOfFirst { it == ' ' || it == '\n' }
        val rest = if (i < 0) "" else t.substring(i).trim()
        return Cmd(head, rest.split(Regex("\\s+")).filter { it.isNotEmpty() }, rest)
    }

    /** Telegram's language_code ("vi", "vi-VN", "en-US"...) -> "vi" / "en"; blank = [fallback]. */
    fun langOf(code: String?, fallback: String) = when {
        code.isNullOrBlank() -> fallback
        code.lowercase().let { it.startsWith("vi") || it == "vn" || it == "tv" } -> "vi"
        else -> "en"
    }
    fun other(lang: String) = if (lang == "en") "vi" else "en"

    /** BotFather token shape: "123456789:AA…" (35 chars after the colon today; a little slack). */
    fun tokenOk(t: String) = Regex("^\\d{5,15}:[A-Za-z0-9_-]{30,60}$").matches(t.trim())

    /** "30" / "30m" / "2h" / "1h30" -> minutes in [lo, hi]; null = not a number or out of range. */
    fun minutes(s: String?, lo: Int, hi: Int): Int? {
        val v = s?.trim()?.lowercase() ?: return null
        val m = Regex("^(?:(\\d{1,3})h)?(\\d{1,4})?m?$").matchEntire(v) ?: return null
        if (m.groupValues[1].isEmpty() && m.groupValues[2].isEmpty()) return null
        val n = (m.groupValues[1].toIntOrNull() ?: 0) * 60 + (m.groupValues[2].toIntOrNull() ?: 0)
        return n.takeIf { it in lo..hi }
    }

    /** 6-digit link code check: right code, not expired. */
    fun codeOk(given: String, code: String, until: Long, now: Long) =
        code.length == 6 && now < until && given.trim().filter { it.isDigit() } == code

    // ---------------- rule edits (the result always gets a newer rev) ----------------
    fun bump(old: Rules, r: Rules, now: Long) = r.copy(rev = maxOf(now, old.rev + 1))
    fun bonus(r: Rules, today: String, add: Int, now: Long): Rules =
        bump(r, r.copy(bonusDay = today, bonusMin = ((if (r.bonusDay == today) r.bonusMin else 0) + add).coerceIn(0, 24 * 60)), now)
    fun pause(r: Rules, min: Int, now: Long) = bump(r, r.copy(pausedUntil = now + min * 60_000L), now)
    fun resume(r: Rules, now: Long) = bump(r, r.copy(pausedUntil = 0), now)
    fun block(r: Rules, pkg: String, on: Boolean, now: Long) = bump(r, r.copy(blocked = if (on) r.blocked + pkg else r.blocked - pkg), now)
    /** One daily limit for every day (0 = none): a per-weekday list becomes 7 x [min]. */
    fun limit(r: Rules, min: Int, now: Long) =
        bump(r, r.copy(dailyMin = min, dailyByDay = if (r.dailyByDay.size == 7) List(7) { min } else r.dailyByDay), now)
    fun bed(r: Rules, on: Boolean, now: Long) = bump(r, r.copy(bedtimeOn = on), now)

    // ---------------- texts ----------------
    fun fmt(ms: Long, lang: String): String {
        val m = (ms / 60_000).coerceAtLeast(0); val h = m / 60
        val r = m % 60
        return if (lang == "en") (if (h > 0) "${h}h" + (if (r > 0) " ${r}m" else "") else "${m}m")
        else (if (h > 0) "$h giờ" + (if (r > 0) " $r phút" else "") else "$m phút")
    }

    private val VI = mapOf(
        "help" to "🤖 KidGuard – máy của %1\$s\n" +
            "Bấm 📋 Menu (hoặc /menu) để dùng nút bấm, khỏi gõ lệnh. Hoặc gõ:\n\n" +
            "👀 XEM\n/status – tình hình: giờ dùng, pin, mạng, app đang mở\n/locate – vị trí hiện tại\n/apps – app dùng nhiều hôm nay\n/events – cảnh báo gần đây\n\n" +
            "⏱ THỜI GIAN\n/requests – lời xin đang chờ (bấm nút để trả lời)\n/code – mã mở khoá 6 số (đọc cho con, hoặc mở phần bố mẹ trên máy con)\n/bonus 30 – cho thêm 30 phút (qua cả giờ ngủ / lịch / cấm)\n/pause 60 – cấm dùng 60 phút\n/resume – gỡ cấm dùng\n/limit 120 – giới hạn mỗi ngày (0 = bỏ; giới hạn khác nhau theo thứ thì hỏi trước)\n/bed – bật/tắt giờ ngủ\n\n" +
            "📱 APP\n/block – chọn app để chặn\n/unblock – bỏ chặn app\n\n" +
            "🎛 ĐIỀU KHIỂN\n/lock – khoá màn hình\n/ring – đổ chuông (tìm máy)\n/msg – hiện lời nhắn trên máy con\n/lost – chế độ thất lạc (bật cần xác nhận; /lost off để tắt)\n\n" +
            "📸 TỪ XA\n/screen – chụp màn hình\n/rec – ghi âm xung quanh (máy con có thông báo)\n/files – file của con · gửi file vào đây = chuyển sang máy con\n\n" +
            "⚙️ /menu – bảng nút bấm\n/quiet – mức báo của chat này: mọi cảnh báo · quan trọng · chỉ SOS\n/digest – tóm tắt mỗi tối (bật · tắt · giờ)\n/lang – đổi ngôn ngữ (English)\n/unlink – ngừng nhận ở chat này\n\n" +
            "Số phút viết kiểu 30, 90, 2h, 1h30.",
        "linkAsk" to "👋 Đây là bot KidGuard riêng của nhà bạn. Gửi mã 6 số đang hiện trong KidGuard (Cài đặt > Telegram) để liên kết chat này.",
        "linked" to "✅ Đã liên kết với máy của %1\$s. Từ giờ chat này nhận báo SOS, xin thêm giờ, cảnh báo. Bấm nút bên dưới hoặc /help.",
        "badCode" to "Mã sai hoặc hết hạn. Lấy mã mới trong KidGuard (Cài đặt > Telegram).",
        "full" to "Đã đủ %1\$d chat liên kết. Gỡ bớt trong KidGuard trước.",
        "unlinked" to "Đã ngừng. Chat này không nhận báo và không điều khiển được nữa.",
        "unknown" to "Không hiểu lệnh. Bấm 📋 Menu hoặc gõ /help.",
        "ok" to "Xong.",
        "fail" to "Không làm được: %1\$s",
        "status" to "📱 %1\$s\nĐã dùng: %2\$s%3\$s\nPin: %4\$s\nMạng: %5\$s\nĐang mở: %6\$s\nMàn hình: %7\$s%10\$s%8\$s%9\$s",
        "left" to " · còn %1\$s",
        "noLimit" to " (không giới hạn)",
        "pausedTo" to "\n⛔ Đang cấm dùng tới %1\$s",
        "reqs" to "\n🙋 %1\$d lời xin thêm giờ đang chờ",
        "on" to "bật", "off" to "tắt", "none" to "—",
        "apps" to "App dùng nhiều hôm nay:",
        "noApps" to "Hôm nay chưa dùng app nào.",
        "noLoc" to "Chưa có vị trí. Đã yêu cầu máy con định vị, thử lại /locate sau 1 phút.",
        "locWait" to "Đang lấy vị trí mới…",
        "locAt" to "Vị trí lúc %1\$s (sai số ~%2\$d m)",
        "events" to "Cảnh báo gần đây:",
        "noEvents" to "Không có cảnh báo nào.",
        "bonusDone" to "Đã cho thêm %1\$d phút hôm nay (tổng thêm %2\$d phút).",
        "pauseDone" to "Cấm dùng tới %1\$s.",
        "resumeDone" to "Đã gỡ cấm dùng.",
        "limitDone" to "Giới hạn mỗi ngày: %1\$s.",
        "limitOff" to "Đã bỏ giới hạn mỗi ngày.",
        "bedDone" to "Giờ ngủ: %1\$s.",
        "blockPick" to "Bấm app để chặn:",
        "unblockPick" to "Bấm app để bỏ chặn:",
        "noBlocked" to "Không có app nào bị chặn.",
        "blocked" to "Đã chặn %1\$s.",
        "unblockedOne" to "Đã bỏ chặn %1\$s.",
        "usage" to "Cách dùng: %1\$s",
        "msgSent" to "Đã hiện lời nhắn trên máy con.",
        "ringing" to "Đang đổ chuông.",
        "locked" to "Đã khoá màn hình.",
        "shotWait" to "Đang chụp màn hình…",
        "recWait" to "Đang ghi âm %1\$d giây (máy con hiện thông báo)…",
        "recCap" to "Ghi âm %1\$d giây lúc %2\$s",
        "files" to "File của con (bấm để tải):",
        "noFiles" to "Chưa có file nào. Con chia sẻ file vào KidGuard thì file hiện ở đây.",
        "fileIn" to "Đã chuyển \"%1\$s\" sang máy con (Download/KidGuard).",
        "fileBig" to "File quá 20 MB, Telegram không cho bot tải về. Dùng trang KidGuard.",
        // menu (buttons)
        "menu" to "📋 %1\$s – chọn việc cần làm:",
        "subBonus" to "➕ Cho thêm bao nhiêu phút hôm nay?", "subPause" to "⏸ Cấm dùng máy trong bao lâu?",
        "subLimit" to "⏱ Giới hạn mỗi ngày (hiện tại: %1\$s):", "subBed" to "🌙 Giờ ngủ (hiện tại: %1\$s):", "subRec" to "🎙 Ghi âm bao lâu? Máy con sẽ hiện thông báo đang ghi.",
        "msgAsk" to "💬 Gõ lời nhắn cho con (hiện to trên máy con):", "msgPh" to "Về ăn cơm nhé",
        "b.status" to "📊 Tình hình", "b.locate" to "📍 Vị trí", "b.bonus" to "➕ Thêm giờ", "b.pause" to "⏸ Tạm cấm",
        "b.lock" to "🔒 Khoá màn hình", "b.ring" to "🔔 Đổ chuông", "b.msg" to "💬 Nhắn cho con", "b.screen" to "📸 Xem màn hình",
        "b.block" to "⛔ Chặn app", "b.unblock" to "✅ Bỏ chặn app", "b.limit" to "⏱ Giới hạn/ngày", "b.bed" to "🌙 Giờ ngủ",
        "b.apps" to "📱 App hôm nay", "b.events" to "⚠️ Cảnh báo", "b.rec" to "🎙 Ghi âm", "b.files" to "📁 File",
        "b.help" to "❓ Hướng dẫn", "b.menu" to "📋 Menu", "b.back" to "« Menu", "b.refresh" to "🔄 Làm mới",
        "b.resume" to "▶️ Gỡ cấm", "b.noLimit" to "Bỏ giới hạn", "b.bedOn" to "🌙 Bật", "b.bedOff" to "☀️ Tắt",
        "b.plus" to "+%1\$s", "b.sec" to "%1\$d giây",
        "kbPh" to "Gõ lệnh hoặc bấm nút…",
        "b.lang" to "🇬🇧 English", "b.code" to "🔑 Mã mở khoá",
        "code" to "🔑 Mã: %1\$s (còn %2\$d giây)\nMã kế tiếp: %3\$s\nCon nhập ở ô Mở khoá để hết giới hạn; mã cũng mở được phần bố mẹ trên máy con (bạn sẽ nhận cảnh báo). Mỗi mã dùng 1 lần.",
        "codeOff" to "🔑 Chưa bật mã mở khoá. Bật ở trang phụ huynh, mục Mã mở khoá (Google Authenticator).", "langDone" to "🇻🇳 Đã chuyển sang Tiếng Việt.",
        "busy" to "⏳ Đang làm…", "b.recFile" to "⬇️ Tải file ghi âm", "busyOne" to "⏳ Đang làm việc này cho một yêu cầu khác, đợi xong rồi thử lại.",
        "newLink" to "🔗 Một chat Telegram mới vừa liên kết với máy: %1\$s. Không phải bạn hay người nhà? Gỡ trong KidGuard (Cài đặt > Telegram).",
        "locOld" to "Vị trí cũ lúc %1\$s (sai số ~%2\$d m), đang lấy vị trí mới…", "b.blockMore" to "⛔ Chặn app khác", "b.unblockMore" to "✅ Bỏ chặn app khác",
        "botShort" to "Bot KidGuard riêng của nhà bạn: xem và quản lý điện thoại của con.",
        "botDesc" to "Bot này chạy trên chính điện thoại của con (KidGuard). Bấm Bắt đầu rồi gửi mã 6 số trong KidGuard (Cài đặt > Telegram) để liên kết. Chỉ chat đã liên kết mới điều khiển được.",
        // alerts
        "aSos" to "🆘 %1\$s vừa bấm SOS!",
        "aReq" to "🙋 %1\$s xin thêm %2\$s%3\$s",
        "aNewApp" to "📦 %2\$s: %1\$s",
        "aTamper" to "⚠️ %2\$s: %1\$s",
        "aEvent" to "🔔 %2\$s: %1\$s",
        "aDigest" to "🔔 %2\$s: %1\$d thông báo mới",
        "yes" to "Cho %1\$d phút", "no" to "Từ chối",
        "reqYes" to "Đã cho %1\$d phút.", "reqNo" to "Đã từ chối.", "reqGone" to "Lời xin này đã được trả lời.",
        "min" to "%1\$d phút", "time" to "thời gian",
        "e.acc off" to "cần bật Trợ năng KidGuard trên máy con",
        "e.screen off" to "màn hình máy con đang tắt",
        "e.upload" to "gửi lên Telegram không được (mạng yếu?), thử lại sau", "e.gone" to "không còn trên máy", "e.no audio" to "không thu được âm thanh (micro đang bận?)",
        "e.no mic permission" to "chưa có quyền micro",
        "e.notice blocked" to "thông báo của KidGuard đang bị tắt trên máy con",
        "e.not owner" to "cần chế độ Device Owner",
        "e.empty" to "tệp trống (0 byte)", "e.quota" to "thư mục KidGuard trên máy con đã đủ 1 GB, xoá bớt rồi gửi lại", "e.no space" to "máy con hết chỗ trống", "e.too big" to "tệp quá lớn", "e.incomplete" to "tệp gửi chưa đủ, thử lại",
        "e.timeout" to "máy con không trả lời kịp, thử lại", "e.busy" to "máy con đang bận việc khác, thử lại sau vài giây",
        "e.capture failed" to "chụp màn hình không được, thử lại", "e.need consent" to "máy con chưa cho phép chia sẻ màn hình (hộp xin phép hiện trên máy con)",
        "e.denied" to "máy con đã từ chối chia sẻ màn hình", "e.listening" to "đang nghe xung quanh, dừng lại rồi thử lại", "e.ringing" to "máy con đang đổ chuông, chờ xong rồi thử lại",
        "e.mic blocked" to "micro bị chặn (đang có cuộc gọi / app khác dùng micro)", "e.mic failed" to "không mở được micro",
        "b.requests" to "🙋 Lời xin", "b.quiet" to "🔕 Mức báo", "b.lost" to "📵 Thất lạc", "b.sosAck" to "✅ Đã nhận", "b.undo" to "↩️ Hoàn tác",
        "b.allow" to "✅ Cho dùng", "b.deny" to "⛔ Chặn", "b.all" to "Mọi ngày", "b.today" to "Chỉ hôm nay", "b.cancel" to "Huỷ",
        "b.lostGo" to "📵 Bật chế độ thất lạc", "b.lostOff" to "✅ Tắt chế độ thất lạc",
        "noReqs" to "Không có lời xin nào đang chờ.", "reqsTitle" to "🙋 %1\$d lời xin đang chờ:",
        "gapOld" to "⏳ Máy con vừa online lại sau %1\$d phút, bấm lại nếu vẫn cần.",
        "groupHint" to "Mở chat riêng với bot để liên kết (bot không điều khiển trong nhóm).",
        "subQuiet" to "🔕 Chat này muốn nhận báo mức nào? (hiện tại: %1\$s)",
        "q.all" to "Mọi cảnh báo", "q.important" to "Quan trọng", "q.sos" to "Chỉ SOS",
        "digestOn" to "Đã bật tóm tắt mỗi tối lúc %1\$s (Chủ nhật thêm tóm tắt tuần). Đổi giờ: /digest 21:00. Tắt: /digest off.",
        "digestOff" to "Đã tắt tóm tắt mỗi tối.",
        "digestIs" to "Tóm tắt mỗi tối: bật lúc %1\$s. Đổi giờ: /digest 21:00. Tắt: /digest off. Hôm nay đến giờ này:",
        "digestIsOff" to "Tóm tắt mỗi tối: tắt. Bật: /digest on (lúc %1\$s) hoặc /digest 21:00. Hôm nay đến giờ này:",
        "quietDone" to "Mức báo của chat này: %1\$s. (Cảnh báo thường luôn tới không kêu chuông.)",
        "passDone" to "Đã cho thêm %1\$d phút, dùng được tới %2\$s (đang %3\$s nên không tính vào giới hạn ngày).",
        "bonusPanel" to "✅ Đã cho thêm %1\$d phút hôm nay (tổng thêm %2\$d phút).",
        "pausePanel" to "⏸ Cấm dùng tới %1\$s.", "limitPanel" to "⏱ Giới hạn mỗi ngày: %1\$s.",
        "undone" to "↩️ Đã hoàn tác.", "undoGone" to "Không hoàn tác được nữa.",
        "limitAsk" to "Giới hạn đang khác nhau theo thứ (%1\$s). Đặt %2\$s cho:", "limitTodayDone" to "Giới hạn hôm nay: %1\$s (các ngày khác giữ nguyên).",
        "lostAsk" to "📵 Bật chế độ thất lạc cho %1\$s?\nMáy khoá ngay và khoá lại mỗi lần mở, hiện lời nhắn + số gọi lại, đổ chuông, định vị mỗi 5 phút.%2\$s",
        "lostMsgLine" to "\nLời nhắn: “%1\$s”", "lostIs" to "📵 Chế độ thất lạc đang BẬT.",
        "lostOnDone" to "📵 Đã bật chế độ thất lạc.", "lostOffDone" to "Đã tắt chế độ thất lạc.", "lostNone" to "Chế độ thất lạc đang tắt.",
        "aReqApp" to "🙋 %1\$s xin mở app %2\$s%3\$s", "aNewAppQ" to "Cho %1\$s dùng?",
        "who.app" to "Bố mẹ (trang / app)",
        "ansYes" to "✅ %1\$s đã cho %2\$d phút lúc %3\$s", "ansNo" to "❎ %1\$s đã từ chối lúc %2\$s",
        "ansAppYes" to "✅ %1\$s đã cho dùng %2\$s lúc %3\$s", "ansAppNo" to "⛔ %1\$s đã chặn %2\$s lúc %3\$s",
        "ansGone" to "Lời xin này đã được trả lời hoặc hết hạn.", "ansSos" to "✅ %1\$s đã nhận SOS lúc %2\$s",
        "w.lost" to "thất lạc", "w.paused" to "cấm dùng", "w.schedule" to "giờ học / lịch", "w.bedtime" to "giờ ngủ", "w.daily" to "hết giờ trong ngày",
        "w.pass" to "được cho thêm giờ", "w.code" to "đã mở khoá bằng mã",
        "nowLine" to "\n⏱ Bây giờ: %1\$s%2\$s", "nowUntil" to " (tới %1\$s)",
        "sosGot" to "Đã nhận SOS.",
        "tooOld" to "⏳ Lệnh này gửi đã lâu (%1\$d phút trước) nên chưa chạy. Gửi lại nếu vẫn cần.",
    )
    private val EN = mapOf(
        "help" to "🤖 KidGuard – %1\$s's phone\n" +
            "Tap 📋 Menu (or /menu) for buttons, no typing needed. Or send:\n\n" +
            "👀 SEE\n/status – time used, battery, network, open app\n/locate – where the phone is\n/apps – most used apps today\n/events – recent alerts\n\n" +
            "⏱ TIME\n/requests – open requests (tap a button to answer)\n/code – 6-digit unlock code (read it to your kid, or open the parent page on the phone)\n/bonus 30 – 30 more minutes (also through bedtime / schedule / pause)\n/pause 60 – no phone for 60 min\n/resume – end the pause\n/limit 120 – daily limit (0 = none; asks first when the limit differs by weekday)\n/bed – bedtime on/off\n\n" +
            "📱 APPS\n/block – pick an app to block\n/unblock – unblock an app\n\n" +
            "🎛 CONTROL\n/lock – lock the screen\n/ring – ring (find the phone)\n/msg – show a message on the phone\n/lost – lost mode (needs a confirm; /lost off to end)\n\n" +
            "📸 REMOTE\n/screen – screenshot\n/rec – record the sound around (the phone shows a notice)\n/files – the child's files · send a file here = to the phone\n\n" +
            "⚙️ /menu – the button panel\n/quiet – this chat's alert level: all · important · SOS only\n/digest – evening summary (on · off · time)\n/lang – language (Tiếng Việt)\n/unlink – stop this chat\n\n" +
            "Minutes: 30, 90, 2h, 1h30.",
        "linkAsk" to "👋 This is your family's own KidGuard bot. Send the 6-digit code shown in KidGuard (Settings > Telegram) to link this chat.",
        "linked" to "✅ Linked to %1\$s's phone. This chat now gets SOS, more-time requests and alerts. Use the buttons below or /help.",
        "badCode" to "Wrong or expired code. Get a new one in KidGuard (Settings > Telegram).",
        "full" to "Already %1\$d linked chats. Remove one in KidGuard first.",
        "unlinked" to "Stopped. This chat gets no alerts and can't control the phone any more.",
        "unknown" to "Unknown command. Tap 📋 Menu or send /help.",
        "ok" to "Done.",
        "fail" to "Could not do it: %1\$s",
        "status" to "📱 %1\$s\nUsed: %2\$s%3\$s\nBattery: %4\$s\nNetwork: %5\$s\nOpen app: %6\$s\nScreen: %7\$s%10\$s%8\$s%9\$s",
        "left" to " · %1\$s left",
        "noLimit" to " (no limit)",
        "pausedTo" to "\n⛔ No phone until %1\$s",
        "reqs" to "\n🙋 %1\$d request(s) for more time waiting",
        "on" to "on", "off" to "off", "none" to "—",
        "apps" to "Most used today:",
        "noApps" to "No app used today yet.",
        "noLoc" to "No location yet. The phone was asked for one; try /locate again in a minute.",
        "locWait" to "Getting a fresh location…",
        "locAt" to "Location at %1\$s (~%2\$d m)",
        "events" to "Recent alerts:",
        "noEvents" to "No alerts.",
        "bonusDone" to "%1\$d more minutes today (%2\$d extra in total).",
        "pauseDone" to "No phone until %1\$s.",
        "resumeDone" to "Pause removed.",
        "limitDone" to "Daily limit: %1\$s.",
        "limitOff" to "Daily limit removed.",
        "bedDone" to "Bedtime: %1\$s.",
        "blockPick" to "Tap an app to block it:",
        "unblockPick" to "Tap an app to unblock it:",
        "noBlocked" to "No app is blocked.",
        "blocked" to "%1\$s blocked.",
        "unblockedOne" to "%1\$s unblocked.",
        "usage" to "Usage: %1\$s",
        "msgSent" to "The message is shown on the phone.",
        "ringing" to "Ringing.",
        "locked" to "Screen locked.",
        "shotWait" to "Taking a screenshot…",
        "recWait" to "Recording %1\$d s (the phone shows a notice)…",
        "recCap" to "%1\$d s recorded at %2\$s",
        "files" to "The child's files (tap to download):",
        "noFiles" to "No files yet. Files the child shares into KidGuard show up here.",
        "fileIn" to "\"%1\$s\" sent to the phone (Download/KidGuard).",
        "fileBig" to "Over 20 MB: Telegram doesn't let bots download it. Use the KidGuard page.",
        "menu" to "📋 %1\$s – pick what to do:",
        "subBonus" to "➕ How many more minutes today?", "subPause" to "⏸ No phone for how long?",
        "subLimit" to "⏱ Daily limit (now: %1\$s):", "subBed" to "🌙 Bedtime (now: %1\$s):", "subRec" to "🎙 Record for how long? The phone shows a recording notice.",
        "msgAsk" to "💬 Type a message for your child (shown big on the phone):", "msgPh" to "Dinner time, come home",
        "b.status" to "📊 Status", "b.locate" to "📍 Location", "b.bonus" to "➕ More time", "b.pause" to "⏸ Pause",
        "b.lock" to "🔒 Lock screen", "b.ring" to "🔔 Ring", "b.msg" to "💬 Message", "b.screen" to "📸 See screen",
        "b.block" to "⛔ Block app", "b.unblock" to "✅ Unblock app", "b.limit" to "⏱ Daily limit", "b.bed" to "🌙 Bedtime",
        "b.apps" to "📱 Apps today", "b.events" to "⚠️ Alerts", "b.rec" to "🎙 Record", "b.files" to "📁 Files",
        "b.help" to "❓ Help", "b.menu" to "📋 Menu", "b.back" to "« Menu", "b.refresh" to "🔄 Refresh",
        "b.resume" to "▶️ End pause", "b.noLimit" to "No limit", "b.bedOn" to "🌙 On", "b.bedOff" to "☀️ Off",
        "b.plus" to "+%1\$s", "b.sec" to "%1\$d s",
        "kbPh" to "Send a command or tap a button…",
        "b.lang" to "🇻🇳 Tiếng Việt", "b.code" to "🔑 Unlock code",
        "code" to "🔑 Code: %1\$s (%2\$d s left)\nNext code: %3\$s\nYour kid types it in the Unlock box to lift the limits; it also opens the parent page on the phone (you get an alert). Each code works once.",
        "codeOff" to "🔑 Unlock codes are off. Turn them on in the parent page, Unlock codes (Google Authenticator).", "langDone" to "🇬🇧 Switched to English.",
        "busy" to "⏳ Working…", "b.recFile" to "⬇️ Download the recording", "busyOne" to "⏳ Already doing this for another request, try again when it's done.",
        "newLink" to "🔗 A new Telegram chat just linked to the phone: %1\$s. Not you or family? Remove it in KidGuard (Settings > Telegram).",
        "locOld" to "Old location from %1\$s (~%2\$d m), getting a fresh one…", "b.blockMore" to "⛔ Block another", "b.unblockMore" to "✅ Unblock another",
        "botShort" to "Your family's own KidGuard bot: see and manage your child's phone.",
        "botDesc" to "This bot runs on your child's phone itself (KidGuard). Press Start, then send the 6-digit code from KidGuard (Settings > Telegram) to link. Only linked chats can control the phone.",
        "aSos" to "🆘 %1\$s pressed SOS!",
        "aReq" to "🙋 %1\$s asks for %2\$s%3\$s",
        "aNewApp" to "📦 %2\$s: %1\$s",
        "aTamper" to "⚠️ %2\$s: %1\$s",
        "aEvent" to "🔔 %2\$s: %1\$s",
        "aDigest" to "🔔 %2\$s: %1\$d new alerts",
        "yes" to "Give %1\$d min", "no" to "Say no",
        "reqYes" to "Gave %1\$d minutes.", "reqNo" to "Said no.", "reqGone" to "This request was already answered.",
        "min" to "%1\$d more minutes", "time" to "more time",
        "e.acc off" to "turn on KidGuard's accessibility service on the phone",
        "e.screen off" to "the phone's screen is off",
        "e.upload" to "couldn't upload to Telegram (weak network?), try again later", "e.gone" to "no longer on the phone", "e.no audio" to "no sound recorded (microphone busy?)",
        "e.no mic permission" to "no microphone permission",
        "e.notice blocked" to "KidGuard's notifications are off on the phone",
        "e.not owner" to "needs Device Owner mode",
        "e.empty" to "the file is empty (0 bytes)", "e.quota" to "KidGuard's folder on the phone already holds 1 GB, delete some and send again", "e.no space" to "the phone is out of space", "e.too big" to "the file is too big", "e.incomplete" to "the file did not arrive completely, try again",
        "e.timeout" to "the phone did not answer in time, try again", "e.busy" to "the phone is busy, try again in a few seconds",
        "e.capture failed" to "couldn't capture the screen, try again", "e.need consent" to "screen sharing isn't allowed on the phone yet (a prompt shows on the phone)",
        "e.denied" to "screen sharing was refused on the phone", "e.listening" to "listening around is on, stop it and try again", "e.ringing" to "the phone is ringing, wait until it stops",
        "e.mic blocked" to "the microphone is blocked (a call / another app uses it)", "e.mic failed" to "couldn't open the microphone",
        "b.requests" to "🙋 Requests", "b.quiet" to "🔕 Alert level", "b.lost" to "📵 Lost", "b.sosAck" to "✅ Got it", "b.undo" to "↩️ Undo",
        "b.allow" to "✅ Allow", "b.deny" to "⛔ Block", "b.all" to "Every day", "b.today" to "Today only", "b.cancel" to "Cancel",
        "b.lostGo" to "📵 Turn on lost mode", "b.lostOff" to "✅ Turn off lost mode",
        "noReqs" to "No request is waiting.", "reqsTitle" to "🙋 %1\$d request(s) waiting:",
        "gapOld" to "⏳ The phone just came back online after %1\$d min, tap again if you still need it.",
        "groupHint" to "Open a private chat with the bot to link (the bot does not take commands in groups).",
        "subQuiet" to "🔕 Which alerts should this chat get? (now: %1\$s)",
        "q.all" to "All alerts", "q.important" to "Important", "q.sos" to "SOS only",
        "digestOn" to "Evening summary on at %1\$s (plus a weekly one on Sunday). Change the time: /digest 21:00. Stop: /digest off.",
        "digestOff" to "Evening summary off.",
        "digestIs" to "Evening summary: on at %1\$s. Change the time: /digest 21:00. Stop: /digest off. Today so far:",
        "digestIsOff" to "Evening summary: off. Start: /digest on (at %1\$s) or /digest 21:00. Today so far:",
        "quietDone" to "This chat's alert level: %1\$s. (Normal alerts always arrive without a ring.)",
        "passDone" to "Gave %1\$d more minutes, usable until %2\$s (it is %3\$s now, so it does not count against the daily limit).",
        "bonusPanel" to "✅ %1\$d more minutes today (%2\$d extra in total).",
        "pausePanel" to "⏸ No phone until %1\$s.", "limitPanel" to "⏱ Daily limit: %1\$s.",
        "undone" to "↩️ Undone.", "undoGone" to "Can't undo any more.",
        "limitAsk" to "The limit differs by weekday (%1\$s). Set %2\$s for:", "limitTodayDone" to "Today's limit: %1\$s (other days unchanged).",
        "lostAsk" to "📵 Turn on lost mode for %1\$s?\nThe phone locks now and on every unlock, shows a message + call-back number, rings, and locates every 5 min.%2\$s",
        "lostMsgLine" to "\nMessage: “%1\$s”", "lostIs" to "📵 Lost mode is ON.",
        "lostOnDone" to "📵 Lost mode is on.", "lostOffDone" to "Lost mode is off.", "lostNone" to "Lost mode is off.",
        "aReqApp" to "🙋 %1\$s asks to open %2\$s%3\$s", "aNewAppQ" to "Allow %1\$s?",
        "who.app" to "A parent (page / app)",
        "ansYes" to "✅ %1\$s gave %2\$d min at %3\$s", "ansNo" to "❎ %1\$s said no at %2\$s",
        "ansAppYes" to "✅ %1\$s allowed %2\$s at %3\$s", "ansAppNo" to "⛔ %1\$s blocked %2\$s at %3\$s",
        "ansGone" to "This request was already answered or expired.", "ansSos" to "✅ %1\$s got the SOS at %2\$s",
        "w.lost" to "lost mode", "w.paused" to "paused", "w.schedule" to "a schedule", "w.bedtime" to "bedtime", "w.daily" to "out of time today",
        "w.pass" to "extra time", "w.code" to "unlocked by code",
        "nowLine" to "\n⏱ Right now: %1\$s%2\$s", "nowUntil" to " (until %1\$s)",
        "sosGot" to "SOS acknowledged.",
        "tooOld" to "⏳ This command was sent a while ago (%1\$d min) so it did not run. Send it again if you still need it.",
    )

    fun s(lang: String, key: String, vararg a: Any?): String {
        val f = (if (lang == "en") EN else VI)[key] ?: EN[key] ?: key
        return if (a.isEmpty()) f else String.format(java.util.Locale.ROOT, f, *a)
    }
    /** A reason from the phone as a sentence in [lang]; "capture failed (5)" matches "capture failed"; unknown = as is. */
    fun err(lang: String, why: String): String { val m = if (lang == "en") EN else VI; return m["e.$why"] ?: m["e." + why.substringBefore(" (")] ?: why }

    /** Typed commands that change something on the phone (the rest only read). Too old ones are not run ([tooOld]). */
    private val READ_ONLY = setOf("start", "help", "menu", "lang", "status", "apps", "events", "files", "code", "requests", "quiet")
    fun isAction(name: String) = name !in READ_ONLY
    const val MAX_AGE_MS = 10 * 60_000L

    /** `/code`: the current Authenticator code, seconds it has left, and the next one; null = no valid secret. */
    fun codeNow(secret: String, nowMs: Long): Triple<String, Int, String>? {
        val key = Totp.decode(secret) ?: return null
        val t = nowMs / 30_000
        return Triple(Totp.code(key, t), (30 - (nowMs / 1000) % 30).toInt(), Totp.code(key, t + 1))
    }
    /** [dateSec] = Telegram message.date (0 = unknown, never old). */
    fun tooOld(dateSec: Long, nowMs: Long): Boolean = dateSec > 0 && nowMs - dateSec * 1000 > MAX_AGE_MS

    /** /status text from the state JSON (Guard.currentState). [hm] formats a time of day. */
    fun status(st: JSONObject, name: String, lang: String, now: Long, hm: (Long) -> String): String {
        val used = st.optLong("usedMs"); val lim = st.optInt("limitMin")
        val left = if (lim > 0) s(lang, "left", fmt(st.optLong("leftMs"), lang)) else s(lang, "noLimit")
        val bat = st.optInt("battery", -1).let { if (it < 0) s(lang, "none") else "$it%" + if (st.optBoolean("charging")) " ⚡" else "" }
        val n = st.optJSONObject("net")
        val net = when (n?.optString("type")) {
            "wifi" -> "Wi-Fi" + (n.optString("ssid").takeIf { it.isNotBlank() }?.let { " $it" } ?: "")
            "cell" -> (if (lang == "en") "mobile data" else "dữ liệu di động") + (n.optString("carrier").takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "")
            "none" -> if (lang == "en") "offline" else "mất mạng"
            null, "" -> s(lang, "none")
            else -> n.optString("type")
        }
        val cur = st.optString("current").takeIf { it.isNotBlank() && it != "null" } ?: s(lang, "none")
        val pu = st.optJSONObject("rules")?.optLong("pausedUntil") ?: 0
        val paused = if (pu > now) s(lang, "pausedTo", hm(pu)) else ""
        val reqs = st.optJSONArray("requests")?.length() ?: 0
        return s(lang, "status", name, fmt(used, lang), left, bat, net, cur, s(lang, if (st.optBoolean("screenOn")) "on" else "off"), paused,
            if (reqs > 0) s(lang, "reqs", reqs) else "", nowLine(st, lang, hm))
    }

    /** Top [n] apps used today (> 1 min): "• YouTube – 1 giờ 5 phút". */
    fun apps(st: JSONObject, lang: String, n: Int = 10): String {
        val a = st.optJSONArray("apps") ?: JSONArray()
        val rows = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.filter { it.optLong("ms") >= 60_000 }.take(n)
        if (rows.isEmpty()) return s(lang, "noApps")
        return s(lang, "apps") + "\n" + rows.joinToString("\n") { "• ${it.optString("label")} – ${fmt(it.optLong("ms"), lang)}" + if (!it.isNull("blocked")) " ⛔" else "" }
    }

    /** App picker rows for /block (not protected, not blocked, most used first) or /unblock (the blocked set). */
    fun pickApps(st: JSONObject, blocked: Set<String>, unblock: Boolean, n: Int = 24): List<Pair<String, String>> {
        val a = st.optJSONArray("apps") ?: JSONArray()
        val all = (0 until a.length()).mapNotNull { a.optJSONObject(it) }
        return if (unblock) blocked.map { p -> p to (all.firstOrNull { it.optString("pkg") == p }?.optString("label") ?: p) }.sortedBy { it.second.lowercase() }.take(n)
        else all.filter { !it.optBoolean("protected") && it.optString("pkg") !in blocked }.take(n).map { it.optString("pkg") to it.optString("label") }
    }

    /** Inline keyboard JSON: rows of (text, callback data <= 64 bytes). */
    fun keyboard(rows: List<List<Pair<String, String>>>): JSONObject = JSONObject().put("inline_keyboard", JSONArray().apply {
        rows.forEach { r -> put(JSONArray().apply { r.forEach { (t, d) -> put(JSONObject().put("text", t.take(60)).put("callback_data", d.take(64))) } }) }
    })

    // ---------------- buttons ----------------
    // Callback data: "c:<command line>" runs it (as if typed), "n:<menu>" swaps the panel in place, "r:status" refreshes in place.
    /** The main panel: every command as a button, 2 per row. */
    fun menu(lang: String): JSONObject {
        fun b(k: String, d: String) = s(lang, "b.$k") to d
        return keyboard(listOf(
            listOf(b("status", "r:status"), b("locate", "c:locate")),
            listOf(b("bonus", "n:bonus"), b("pause", "n:pause")),
            listOf(b("lock", "c:lock"), b("ring", "c:ring")),
            listOf(b("msg", "c:msg"), b("screen", "c:screen")),
            listOf(b("block", "c:block"), b("unblock", "c:unblock")),
            listOf(b("limit", "n:limit"), b("bed", "n:bed")),
            listOf(b("apps", "c:apps"), b("events", "c:events")),
            listOf(b("rec", "n:rec"), b("files", "c:files")),
            listOf(b("requests", "c:requests"), b("lost", "c:lost")),
            listOf(b("quiet", "c:quiet"), b("code", "c:code")),
            listOf(b("help", "c:help"), b("lang", "c:lang " + other(lang)))))
    }

    /** A sub-panel (bonus / pause / limit / bed / rec) with a way back; null = no such panel. */
    fun sub(lang: String, name: String): JSONObject? {
        val back = listOf(s(lang, "b.back") to "n:main")
        fun mins(cmd: String, l: List<Int>) = l.map { s(lang, "b.plus", fmt(it * 60_000L, lang)).removePrefix(if (cmd == "bonus") "" else "+") to "c:$cmd $it" }
        val rows = when (name) {
            "bonus" -> listOf(mins("bonus", listOf(15, 30)), mins("bonus", listOf(60, 120)))
            "pause" -> listOf(mins("pause", listOf(15, 30)), mins("pause", listOf(60, 120)), listOf(s(lang, "b.resume") to "c:resume"))
            "limit" -> listOf(mins("limit", listOf(60, 90)), mins("limit", listOf(120, 180)), listOf(s(lang, "b.noLimit") to "c:limit 0"))
            "bed" -> listOf(listOf(s(lang, "b.bedOn") to "c:bed on", s(lang, "b.bedOff") to "c:bed off"))
            "rec" -> listOf(listOf(10, 20).map { s(lang, "b.sec", it) to "c:rec $it" }, listOf(30, 60).map { s(lang, "b.sec", it) to "c:rec $it" })
            else -> return null
        }
        return keyboard(rows + listOf(back))
    }

    /** Buttons under /status. */
    fun statusKb(lang: String) = keyboard(listOf(
        listOf(s(lang, "b.refresh") to "r:status", s(lang, "b.locate") to "c:locate"),
        listOf(s(lang, "b.bonus") to "n:bonus", s(lang, "b.lock") to "c:lock"),
        listOf(s(lang, "b.menu") to "n:main")))

    /** The keyboard that stays under the typing box (sends its label as text: [fromButton] maps it back). */
    private val KB = listOf(listOf("status", "locate", "bonus"), listOf("lock", "menu"))
    fun replyKb(lang: String): JSONObject = JSONObject().put("is_persistent", true).put("resize_keyboard", true)
        .put("input_field_placeholder", s(lang, "kbPh"))
        .put("keyboard", JSONArray().apply { KB.forEach { r -> put(JSONArray().apply { r.forEach { put(JSONObject().put("text", s(lang, "b.$it"))) } }) } })
    /** A reply-keyboard label (either language) -> its command; null = plain text. */
    fun fromButton(text: String?): Cmd? {
        val t = text?.trim() ?: return null
        val k = KB.flatten().firstOrNull { k -> t == s("vi", "b.$k") || t == s("en", "b.$k") } ?: return null
        return Cmd(k, emptyList(), "")
    }
    /** "c:bonus 30" -> Cmd("bonus", ["30"]) (callback data never has the slash). */
    fun fromData(d: String) = if (d.startsWith("c:")) parse("/" + d.removePrefix("c:")) else null

    // ---------------- 0.9.16: grant, requests, alert levels, lost, undo ----------------
    const val GAP_MS = 10 * 60_000L
    /** Minutes of offline gap before this poll (0 = no gap worth mentioning). [prevOk] = 0 unknown. */
    fun gapMin(prevOk: Long, nowMs: Long): Int = if (prevOk > 0 && nowMs - prevOk > GAP_MS) ((nowMs - prevOk) / 60_000).toInt() else 0
    /** Button data that changes something (an old tap of it is not run after an offline gap). */
    fun isActionData(d: String): Boolean = when {
        d.startsWith("c:") -> fromData(d)?.let { isAction(it.name) } ?: false
        d.startsWith("n:") || d == "r:status" || d.startsWith("f:") || d.startsWith("a:") -> false
        else -> true
    }

    /** Request buttons: N/2, N, 2N (halves / doubles rounded to 5, 5..600), N alone as "N" when [min] > 0; no amount = 15 / 30 / 60. */
    fun reqOpts(min: Int): List<Int> {
        fun r5(x: Int) = maxOf(5, ((x + 2) / 5) * 5)
        if (min <= 0) return listOf(15, 30, 60)
        val n = min.coerceIn(1, 600)
        return listOf(r5(n / 2), n, minOf(600, r5(n * 2))).distinct().sorted()
    }
    fun reqId(o: JSONObject) = o.optString("id")
    fun reqAt(o: JSONObject) = o.optLong("at", o.optLong("t"))
    fun reqText(lang: String, name: String, o: JSONObject): String {
        val note = o.optString("note").takeIf { it.isNotBlank() }?.let { "\n“$it”" } ?: ""
        val min = o.optInt("min")
        return if (o.optString("kind") == "app") s(lang, "aReqApp", name, o.optString("app").ifBlank { o.optString("pkg") }, note)
        else s(lang, "aReq", name, if (min > 0) s(lang, "min", min) else s(lang, "time"), note)
    }
    fun reqKb(lang: String, o: JSONObject): JSONObject {
        val id = reqId(o)
        if (o.optString("kind") == "app") return keyboard(listOf(listOf(s(lang, "b.allow") to "ra:$id", s(lang, "b.deny") to "rb:$id")))
        return keyboard(listOf(reqOpts(o.optInt("min")).map { s(lang, "yes", it) to "ry:$id:$it" } + (s(lang, "no") to "rn:$id")))
    }
    /** Alert key of a request: a remind (remindAt after at) is a new alert. */
    fun reqKey(o: JSONObject): String = "r:" + reqId(o) + (o.optLong("remindAt").takeIf { it > reqAt(o) }?.let { "@$it" } ?: "")

    /** "More time" from every Telegram path: [Grant.apply] picks pass (bedtime / school / pause) or the daily bonus. Second = it was a pass. */
    fun give(r: Rules, why: String, min: Int, now: Long, today: String): Pair<Rules, Boolean> {
        val g = Grant.apply(r, now, why, min, today)
        return bump(r, g, now) to (g.passUntil != r.passUntil)
    }
    fun whyOf(st: JSONObject): String = st.optJSONObject("now")?.optString("why").orEmpty()
    fun whyName(lang: String, why: String) = s(lang, "w.$why")
    fun nowLine(st: JSONObject, lang: String, hm: (Long) -> String): String {
        val n = st.optJSONObject("now") ?: return ""
        val w = n.optString("why"); if (w.isEmpty()) return ""
        val name = if (w == "schedule" && n.optString("name").isNotBlank()) whyName(lang, w) + " " + n.optString("name") else whyName(lang, w)
        val until = n.optLong("until")
        return s(lang, "nowLine", name, if (until > 0) s(lang, "nowUntil", hm(until)) else "")
    }

    /** App request / new app: approved = allowed (off the blocked list), otherwise blocked (off the approved list). */
    fun approve(r: Rules, pkg: String, ok: Boolean, now: Long) = bump(r, r.copy(
        approved = if (ok) r.approved + pkg else r.approved - pkg, blocked = if (ok) r.blocked - pkg else r.blocked + pkg), now)
    /** The package of an "installed X" event: the apps the state flags as new whose label is in the text (longest label wins). */
    fun newAppPkg(st: JSONObject, text: String): String? {
        val a = st.optJSONArray("apps") ?: return null
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.filter { it.optBoolean("new") && it.optString("label").isNotBlank() && text.contains(it.optString("label")) }
            .maxByOrNull { it.optString("label").length }?.optString("pkg")?.takeIf { ("ay:$it").toByteArray().size <= 64 }
    }
    fun newAppKb(lang: String, pkg: String) = keyboard(listOf(listOf(s(lang, "b.allow") to "ay:$pkg", s(lang, "b.deny") to "an:$pkg")))

    /** Text of an answered alert: [who] answered at [at]. [ok] false = declined. [kind] time | app. */
    fun answered(lang: String, who: String, ok: Boolean, kind: String, min: Int, app: String, at: Long, hm: (Long) -> String): String = when {
        kind == "app" -> s(lang, if (ok) "ansAppYes" else "ansAppNo", who, app, hm(at))
        ok -> s(lang, "ansYes", who, min, hm(at))
        else -> s(lang, "ansNo", who, hm(at))
    }

    /** Alert importance -> does a chat at [level] (all | important | sos) get it? importance: "sos" | "important" | "info". */
    fun quietOk(level: String, importance: String) = when (importance) { "sos" -> true; "important" -> level != "sos"; else -> level == "all" }
    fun importance(kind: String, important: Boolean) = if (kind == "sos") "sos" else if (important) "important" else "info"
    fun quietKb(lang: String, cur: String) = keyboard(listOf(listOf("all", "important", "sos").map { (if (it == cur) "✔ " else "") + s(lang, "q.$it") to "c:quiet $it" }, listOf(s(lang, "b.back") to "n:main")))

    /** True when the per-weekday limits are not all the same (a plain "/limit 120" would flatten them). */
    fun limitDiffers(r: Rules) = r.dailyByDay.size == 7 && r.dailyByDay.distinct().size > 1
    /** Only [dow] (ISO 1..7) gets [min]; the other days keep their value. */
    fun limitToday(r: Rules, min: Int, dow: Int, now: Long): Rules {
        val l = (if (r.dailyByDay.size == 7) r.dailyByDay else List(7) { r.dailyMin }).toMutableList().also { it[(dow - 1).coerceIn(0, 6)] = min }
        return bump(r, r.copy(dailyByDay = l), now)
    }
    fun limitAskKb(lang: String, min: Int) = keyboard(listOf(listOf(s(lang, "b.all") to "c:limit all $min", s(lang, "b.today") to "c:limit today $min"), listOf(s(lang, "b.cancel") to "n:main")))
    /** "/limit all 120" / "/limit today 120" / "/limit 120" -> (scope, minutes text). */
    fun limitArgs(args: List<String>): Pair<String, String?> = when (args.firstOrNull()?.lowercase()) {
        "all", "every" -> "all" to args.getOrNull(1); "today", "homnay" -> "today" to args.getOrNull(1); else -> "" to args.firstOrNull() }
    fun limitSummary(r: Rules, lang: String): String { val d = if (lang == "en") listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su") else listOf("T2", "T3", "T4", "T5", "T6", "T7", "CN")
        return r.dailyByDay.mapIndexed { i, m -> d[i] + " " + (if (m > 0) fmt(m * 60_000L, lang) else "—") }.joinToString(" · ") }

    fun lost(r: Rules, on: Boolean, msg: String?, now: Long) = bump(r, r.copy(lostOn = on, lostMsg = (msg ?: r.lostMsg).take(200)), now)
    fun lostKb(lang: String, on: Boolean) = keyboard(listOf(
        if (on) listOf(s(lang, "b.lostOff") to "c:lost off") else listOf(s(lang, "b.lostGo") to "c:lost go"),
        listOf(s(lang, "b.cancel") to "n:main")))
    /** "/lost", "/lost on <msg>", "/lost <msg>" -> optional message; "off" / "go" are not messages. */
    fun lostMsgOf(c: Cmd): String? {
        val f = c.args.firstOrNull()?.lowercase() ?: return null
        if (f == "off") return null
        return (if (f in setOf("on", "go")) c.rest.drop(f.length) else c.rest).trim().takeIf { it.isNotEmpty() }
    }

    /** Undo of a panel change: only the fields that [after] changed against [before] go back (other edits since stay). */
    fun undo(cur: Rules, before: Rules, after: Rules, now: Long): Rules {
        var r = cur
        if (after.bonusDay != before.bonusDay || after.bonusMin != before.bonusMin) r = r.copy(bonusDay = before.bonusDay, bonusMin = before.bonusMin)
        if (after.passUntil != before.passUntil) r = r.copy(passUntil = before.passUntil)
        if (after.pausedUntil != before.pausedUntil) r = r.copy(pausedUntil = before.pausedUntil)
        if (after.dailyMin != before.dailyMin) r = r.copy(dailyMin = before.dailyMin)
        if (after.dailyByDay != before.dailyByDay) r = r.copy(dailyByDay = before.dailyByDay)
        return bump(cur, r, now)
    }
    fun undoKb(lang: String, key: String) = keyboard(listOf(listOf(s(lang, "b.undo") to "z:$key", s(lang, "b.back") to "n:main")))
    fun sosKb(lang: String, id: String) = keyboard(listOf(listOf(s(lang, "b.sosAck") to "k:$id"), listOf(s(lang, "b.locate") to "c:locate", s(lang, "b.status") to "r:status")))
    /** Label for a map pin: "Vị trí lúc 20:14 (sai số ~30 m)" or the "old" wording when over 5 min old. */
    fun pinLabel(lang: String, loc: JSONObject, now: Long, hm: (Long) -> String): String =
        s(lang, if (now - loc.optLong("t") > 5 * 60_000L) "locOld" else "locAt", hm(loc.optLong("t")), loc.optDouble("acc", 0.0).toInt())

    // ---------------- long texts, bursts, poll errors ----------------
    const val MSG_MAX = 4000        // Telegram refuses a text above 4096 characters
    /**
     * A long answer (/events, /apps on a full phone) as several messages instead of one cut at the limit: split at line
     * ends, a single over-long line is cut hard. Never an empty part; a short text is one part.
     */
    fun split(text: String, max: Int = MSG_MAX): List<String> {
        if (text.length <= max) return listOf(text)
        val out = ArrayList<String>(); val cur = StringBuilder()
        fun flush() { if (cur.isNotEmpty()) { out += cur.toString(); cur.setLength(0) } }
        for (line in text.split('\n')) {
            var l = line
            if (cur.isNotEmpty() && cur.length + 1 + l.length > max) flush()
            while (l.length > max) { flush(); out += l.take(max); l = l.drop(max) }
            if (cur.isNotEmpty()) cur.append('\n')
            cur.append(l)
        }
        flush()
        return out.ifEmpty { listOf(text.take(max)) }
    }

    const val DIGEST_MIN = 4        // this many plain events waiting at once (the phone was offline) = one message, not a ping each
    /** Plain events (no buttons, no photo) can share one message; tamper / new app / SOS / requests never do. */
    fun digestable(key: String, kind: String, hasShot: Boolean) = key.startsWith("e:") && kind != "tamper" && kind != "newApp" && !hasShot
    /** One message for several events, oldest first: a header with the count, then "• HH:mm text" lines. */
    fun digest(lang: String, name: String, items: List<Pair<Long, String>>, hm: (Long) -> String): String =
        s(lang, "aDigest", items.size, name) + "\n" + items.sortedBy { it.first }.joinToString("\n") { (t, x) -> "• " + hm(t) + " " + x }

    /**
     * How long to wait after getUpdates answered "not ok" (not 401 / 404 / 409, those have their own handling):
     * 429 = as long as Telegram says (retry_after, 1 s .. 5 min); anything else (5xx) doubles from 5 s up to 1 min.
     */
    fun pollWaitMs(code: Int, retryAfterS: Long, fails: Int): Long =
        if (code == 429) retryAfterS.coerceIn(1, 300) * 1000 else minOf(60_000L, 5_000L shl fails.coerceIn(0, 4))

    /** Alert ids already sent: the newest [max] kept. */
    fun remember(seen: List<String>, add: Collection<String>, max: Int = 80) = (seen + add).distinct().takeLast(max)
}
