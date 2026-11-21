package dev.kidguard

import org.json.JSONObject
import java.text.Normalizer

/**
 * The timetable as plain text (owner 2026-11-21: "lỡ cài lại phải tạo lại từng tiết"): shared through any app (Zalo,
 * notes, a .txt in Drive), readable and editable by a person, pasted back with "Nhập". One line per session:
 *
 *   Thời khoá biểu (KidGuard)
 *   Giờ sáng: 7:00-7:45, 7:50-8:35
 *   T2 sáng: Toán, Tiếng Việt, -, Anh      ("-" = free period)
 *
 * The reader takes Vietnamese (with or without accents) and English labels, so a text typed by hand or exported in
 * the other language still imports. Pure (unit-tested: TimetableTextTest).
 */
object TimetableText {
    private val VI_DAY = listOf("T2", "T3", "T4", "T5", "T6", "T7", "CN")
    private val EN_DAY = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

    fun write(tt: JSONObject, vi: Boolean): String {
        val am = if (vi) "sáng" else "am"; val pm = if (vi) "chiều" else "pm"
        val sb = StringBuilder(if (vi) "Thời khoá biểu (KidGuard)" else "Timetable (KidGuard)")
        for (p in listOf(false, true)) {
            val b = TimetableLogic.bells(tt, p)
            if (b.isNotEmpty()) sb.append('\n').append(if (vi) "Giờ " else "Bells ").append(if (p) pm else am).append(": ")
                .append(b.joinToString(", ") { TimetableLogic.hm(it[0]) + "-" + TimetableLogic.hm(it[1]) })
        }
        for (d in 1..7) for (p in listOf(false, true)) {
            val s = TimetableLogic.session(tt, d, p)
            if (s.none { it.isNotEmpty() }) continue
            sb.append('\n').append((if (vi) VI_DAY else EN_DAY)[d - 1]).append(' ').append(if (p) pm else am).append(": ")
                .append(s.joinToString(", ") { it.replace(',', ';').ifEmpty { "-" } })
        }
        return sb.toString()
    }

    /** What [read] found. [tt] = the new timetable (bells kept from the old one when the text has none). */
    data class Read(val tt: JSONObject, val sessions: Int, val bells: Int)

    /** Null when the text has no day or bell line at all (wrong text pasted). */
    fun read(text: String, old: JSONObject): Read? {
        var tt = JSONObject()
        TimetableLogic.bells(old, false).takeIf { old.optJSONObject("bells")?.has("am") == true }?.let { tt = TimetableLogic.withBells(tt, false, it) }
        TimetableLogic.bells(old, true).takeIf { old.optJSONObject("bells")?.has("pm") == true }?.let { tt = TimetableLogic.withBells(tt, true, it) }
        var sessions = 0; var bells = 0
        val time = Regex("""(\d{1,2})\s*[:hg.]\s*(\d{2})\s*[-–—]\s*(\d{1,2})\s*[:hg.]\s*(\d{2})""")
        for (raw in text.lines()) {
            val c = raw.indexOf(':'); if (c <= 0) continue
            val w = words(raw.substring(0, c)); val v = raw.substring(c + 1)
            val pm = when {
                w.any { it == "chieu" || it == "pm" || it == "afternoon" } -> true
                w.any { it == "sang" || it == "am" || it == "morning" } -> false
                else -> continue
            }
            if (w.any { it == "gio" || it == "bells" || it == "bell" || it == "times" }) {
                val b = time.findAll(v).mapNotNull { m ->
                    val (h1, m1, h2, m2) = m.destructured
                    val s = h1.toInt() * 60 + m1.toInt(); val e = h2.toInt() * 60 + m2.toInt()
                    if (m1.toInt() < 60 && m2.toInt() < 60 && s in 0 until 24 * 60 && e in (s + 1)..(24 * 60)) intArrayOf(s, e) else null
                }.toList()
                if (b.isNotEmpty()) { tt = TimetableLogic.withBells(tt, pm, b); bells++ }
                continue
            }
            val d = day(w) ?: continue
            val subj = v.split(',').map { it.trim().let { s -> if (s == "-" || s == "–" || s == "—") "" else s } }
            tt = TimetableLogic.withSession(tt, d, pm, subj); sessions++
        }
        return if (sessions + bells == 0) null else Read(tt, sessions, bells)
    }

    /** Lowercase words without accents ("Thứ Hai" -> [thu, hai], "đ" -> d). */
    private fun words(s: String): List<String> =
        Normalizer.normalize(s.lowercase().replace('đ', 'd'), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            .split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }

    private val NUM = mapOf("hai" to 2, "ba" to 3, "tu" to 4, "nam" to 5, "sau" to 6, "bay" to 7)
    private val EN = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")

    /** ISO weekday from a label: T2 / Thứ 2 / Thứ Hai / CN / Chủ nhật / Mon / Monday. */
    private fun day(w: List<String>): Int? {
        for ((i, x) in w.withIndex()) {
            Regex("t([2-7])").matchEntire(x)?.let { return it.groupValues[1].toInt() - 1 }
            if (x == "cn" || (x == "chu" && w.getOrNull(i + 1) == "nhat")) return 7
            if (x == "thu") {   // Vietnamese "thứ 2" / "thứ hai" vs English "thu(rsday)"
                val n = w.getOrNull(i + 1)
                n?.toIntOrNull()?.takeIf { it in 2..7 }?.let { return it - 1 }
                NUM[n]?.let { return it - 1 }
                return 4
            }
            EN.indexOfFirst { x.startsWith(it) }.takeIf { it >= 0 }?.let { return it + 1 }
        }
        return null
    }
}
