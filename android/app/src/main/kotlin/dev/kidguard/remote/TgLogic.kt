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
            "⏱ THỜI GIAN\n/bonus 30 – cho thêm 30 phút hôm nay\n/pause 60 – cấm dùng 60 phút\n/resume – gỡ cấm dùng\n/limit 120 – giới hạn mỗi ngày (0 = bỏ)\n/bed – bật/tắt giờ ngủ\n\n" +
            "📱 APP\n/block – chọn app để chặn\n/unblock – bỏ chặn app\n\n" +
            "🎛 ĐIỀU KHIỂN\n/lock – khoá màn hình\n/ring – đổ chuông (tìm máy)\n/msg – hiện lời nhắn trên máy con\n\n" +
            "📸 TỪ XA\n/screen – chụp màn hình\n/rec – ghi âm xung quanh (máy con có thông báo)\n/files – file của con · gửi file vào đây = chuyển sang máy con\n\n" +
            "⚙️ /menu – bảng nút bấm\n/unlink – ngừng nhận ở chat này\n\n" +
            "Số phút viết kiểu 30, 90, 2h, 1h30.",
        "linkAsk" to "👋 Đây là bot KidGuard riêng của nhà bạn. Gửi mã 6 số đang hiện trong KidGuard (Cài đặt > Telegram) để liên kết chat này.",
        "linked" to "✅ Đã liên kết với máy của %1\$s. Từ giờ chat này nhận báo SOS, xin thêm giờ, cảnh báo. Bấm nút bên dưới hoặc /help.",
        "badCode" to "Mã sai hoặc hết hạn. Lấy mã mới trong KidGuard (Cài đặt > Telegram).",
        "full" to "Đã đủ %1\$d chat liên kết. Gỡ bớt trong KidGuard trước.",
        "unlinked" to "Đã ngừng. Chat này không nhận báo và không điều khiển được nữa.",
        "unknown" to "Không hiểu lệnh. Bấm 📋 Menu hoặc gõ /help.",
        "ok" to "Xong.",
        "fail" to "Không làm được: %1\$s",
        "status" to "📱 %1\$s\nĐã dùng: %2\$s%3\$s\nPin: %4\$s\nMạng: %5\$s\nĐang mở: %6\$s\nMàn hình: %7\$s%8\$s%9\$s",
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
        "botShort" to "Bot KidGuard riêng của nhà bạn: xem và quản lý điện thoại của con.",
        "botDesc" to "Bot này chạy trên chính điện thoại của con (KidGuard). Bấm Bắt đầu rồi gửi mã 6 số trong KidGuard (Cài đặt > Telegram) để liên kết. Chỉ chat đã liên kết mới điều khiển được.",
        // alerts
        "aSos" to "🆘 %1\$s vừa bấm SOS!",
        "aReq" to "🙋 %1\$s xin thêm %2\$s%3\$s",
        "aNewApp" to "📦 %1\$s",
        "aTamper" to "⚠️ %1\$s",
        "aEvent" to "🔔 %1\$s",
        "yes" to "Cho %1\$d phút", "no" to "Từ chối",
        "reqYes" to "Đã cho %1\$d phút.", "reqNo" to "Đã từ chối.", "reqGone" to "Lời xin này đã được trả lời.",
        "min" to "%1\$d phút", "time" to "thời gian",
        "e.acc off" to "cần bật Trợ năng KidGuard trên máy con",
        "e.screen off" to "màn hình máy con đang tắt",
        "e.no mic permission" to "chưa có quyền micro",
        "e.notice blocked" to "thông báo của KidGuard đang bị tắt trên máy con",
        "e.not owner" to "cần chế độ Device Owner",
    )
    private val EN = mapOf(
        "help" to "🤖 KidGuard – %1\$s's phone\n" +
            "Tap 📋 Menu (or /menu) for buttons, no typing needed. Or send:\n\n" +
            "👀 SEE\n/status – time used, battery, network, open app\n/locate – where the phone is\n/apps – most used apps today\n/events – recent alerts\n\n" +
            "⏱ TIME\n/bonus 30 – 30 more minutes today\n/pause 60 – no phone for 60 min\n/resume – end the pause\n/limit 120 – daily limit (0 = none)\n/bed – bedtime on/off\n\n" +
            "📱 APPS\n/block – pick an app to block\n/unblock – unblock an app\n\n" +
            "🎛 CONTROL\n/lock – lock the screen\n/ring – ring (find the phone)\n/msg – show a message on the phone\n\n" +
            "📸 REMOTE\n/screen – screenshot\n/rec – record the sound around (the phone shows a notice)\n/files – the child's files · send a file here = to the phone\n\n" +
            "⚙️ /menu – the button panel\n/unlink – stop this chat\n\n" +
            "Minutes: 30, 90, 2h, 1h30.",
        "linkAsk" to "👋 This is your family's own KidGuard bot. Send the 6-digit code shown in KidGuard (Settings > Telegram) to link this chat.",
        "linked" to "✅ Linked to %1\$s's phone. This chat now gets SOS, more-time requests and alerts. Use the buttons below or /help.",
        "badCode" to "Wrong or expired code. Get a new one in KidGuard (Settings > Telegram).",
        "full" to "Already %1\$d linked chats. Remove one in KidGuard first.",
        "unlinked" to "Stopped. This chat gets no alerts and can't control the phone any more.",
        "unknown" to "Unknown command. Tap 📋 Menu or send /help.",
        "ok" to "Done.",
        "fail" to "Could not do it: %1\$s",
        "status" to "📱 %1\$s\nUsed: %2\$s%3\$s\nBattery: %4\$s\nNetwork: %5\$s\nOpen app: %6\$s\nScreen: %7\$s%8\$s%9\$s",
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
        "botShort" to "Your family's own KidGuard bot: see and manage your child's phone.",
        "botDesc" to "This bot runs on your child's phone itself (KidGuard). Press Start, then send the 6-digit code from KidGuard (Settings > Telegram) to link. Only linked chats can control the phone.",
        "aSos" to "🆘 %1\$s pressed SOS!",
        "aReq" to "🙋 %1\$s asks for %2\$s%3\$s",
        "aNewApp" to "📦 %1\$s",
        "aTamper" to "⚠️ %1\$s",
        "aEvent" to "🔔 %1\$s",
        "yes" to "Give %1\$d min", "no" to "Say no",
        "reqYes" to "Gave %1\$d minutes.", "reqNo" to "Said no.", "reqGone" to "This request was already answered.",
        "min" to "%1\$d more minutes", "time" to "more time",
        "e.acc off" to "turn on KidGuard's accessibility service on the phone",
        "e.screen off" to "the phone's screen is off",
        "e.no mic permission" to "no microphone permission",
        "e.notice blocked" to "KidGuard's notifications are off on the phone",
        "e.not owner" to "needs Device Owner mode",
    )

    fun s(lang: String, key: String, vararg a: Any?): String {
        val f = (if (lang == "en") EN else VI)[key] ?: EN[key] ?: key
        return if (a.isEmpty()) f else String.format(java.util.Locale.ROOT, f, *a)
    }
    fun err(lang: String, why: String) = (if (lang == "en") EN else VI)["e.$why"] ?: why

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
            if (reqs > 0) s(lang, "reqs", reqs) else "")
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
            listOf(b("help", "c:help"))))
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

    /** Alert ids already sent: the newest [max] kept. */
    fun remember(seen: List<String>, add: Collection<String>, max: Int = 80) = (seen + add).distinct().takeLast(max)
}
