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
        return if (lang == "en") (if (h > 0) "${h}h ${m % 60}m" else "${m}m") else (if (h > 0) "${h} giờ ${m % 60} phút" else "$m phút")
    }

    private val VI = mapOf(
        "help" to "Lệnh KidGuard cho %1\$s:\n" +
            "/status – giờ dùng, pin, mạng, app đang mở\n/apps – app dùng nhiều hôm nay\n/locate – vị trí\n/events – cảnh báo gần đây\n" +
            "/bonus 30 – cho thêm 30 phút hôm nay\n/pause 60 – cấm dùng 60 phút · /resume – gỡ\n/lock – khoá màn hình\n/ring – đổ chuông\n/msg <lời nhắn> – hiện lời nhắn trên máy con\n" +
            "/block – chọn app để chặn · /unblock – bỏ chặn\n/limit 120 – giới hạn mỗi ngày (0 = bỏ)\n/bed on|off – giờ ngủ\n" +
            "/screen – chụp màn hình\n/rec 20 – ghi âm 20 giây (máy con có thông báo)\n/files – file của con · gửi file vào đây = chuyển sang máy con\n/unlink – ngừng nhận ở chat này",
        "linkAsk" to "Gửi mã 6 số đang hiện trong KidGuard (Cài đặt > Telegram) để liên kết chat này.",
        "linked" to "Đã liên kết với máy của %1\$s. Gõ /help để xem lệnh.",
        "badCode" to "Mã sai hoặc hết hạn. Lấy mã mới trong KidGuard (Cài đặt > Telegram).",
        "full" to "Đã đủ %1\$d chat liên kết. Gỡ bớt trong KidGuard trước.",
        "unlinked" to "Đã ngừng. Chat này không nhận báo và không điều khiển được nữa.",
        "unknown" to "Không hiểu lệnh. Gõ /help.",
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
        "help" to "KidGuard commands for %1\$s:\n" +
            "/status – time used, battery, network, open app\n/apps – most used apps today\n/locate – location\n/events – recent alerts\n" +
            "/bonus 30 – 30 more minutes today\n/pause 60 – no phone for 60 min · /resume – undo\n/lock – lock the screen\n/ring – ring\n/msg <text> – show a message on the phone\n" +
            "/block – pick an app to block · /unblock – unblock\n/limit 120 – daily limit (0 = none)\n/bed on|off – bedtime\n" +
            "/screen – screenshot\n/rec 20 – record 20 s of sound (the phone shows a notice)\n/files – the child's files · send a file here = to the phone\n/unlink – stop this chat",
        "linkAsk" to "Send the 6-digit code shown in KidGuard (Settings > Telegram) to link this chat.",
        "linked" to "Linked to %1\$s's phone. Send /help for the commands.",
        "badCode" to "Wrong or expired code. Get a new one in KidGuard (Settings > Telegram).",
        "full" to "Already %1\$d linked chats. Remove one in KidGuard first.",
        "unlinked" to "Stopped. This chat gets no alerts and can't control the phone any more.",
        "unknown" to "Unknown command. Send /help.",
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

    /** Alert ids already sent: the newest [max] kept. */
    fun remember(seen: List<String>, add: Collection<String>, max: Int = 80) = (seen + add).distinct().takeLast(max)
}
