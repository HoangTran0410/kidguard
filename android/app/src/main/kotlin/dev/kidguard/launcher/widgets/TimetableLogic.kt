package dev.kidguard

import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure helpers of the "Thời khoá biểu" widget.
 * Timetable = {"1".."7": day, "bells": {"am":[[start,end]…], "pm":[…]}} (ISO weekday, Mon=1; minutes since midnight).
 * A day is {"am":[subject…], "pm":[subject…]} (index = period, "" = free period), or the old plain array (= morning).
 * Bells are shared by every day (one school bell). No "bells" = [DEF_AM] / [DEF_PM]. A gap between two periods of
 * [RECESS] min or more is "ra chơi"; the gap between the last morning and the first afternoon period is "nghỉ trưa".
 */
object TimetableLogic {
    const val MAX_DAY = 10          // old flat list
    const val MAX_SESSION = 8       // periods in one session
    const val MAX_LEN = 30
    const val EVENING = 17 * 60
    const val RECESS = 15

    /** Vietnamese school defaults: 45-min periods, 5-min changes, 20-min recess after period 2. */
    val DEF_AM = gen(7 * 60, 45, 5, 5, 2, 20)
    val DEF_PM = gen(13 * 60 + 30, 45, 5, 5, 2, 20)

    fun clean(l: List<String>): List<String> = l.map { it.trim().take(MAX_LEN).trim() }.filter { it.isNotEmpty() }.take(MAX_DAY)

    /** A session's periods: trimmed, blanks kept inside (free periods), trailing blanks dropped. */
    fun cleanSession(l: List<String>): List<String> {
        val c = l.map { it.trim().take(MAX_LEN).trim() }.take(MAX_SESSION)
        return c.dropLastWhile { it.isEmpty() }
    }

    private fun arr(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it, "") }

    /** One session ([pm] = afternoon) of [dow]. An old plain-array day is its morning. */
    fun session(tt: JSONObject, dow: Int, pm: Boolean): List<String> {
        val k = dow.toString()
        tt.optJSONObject(k)?.let { return cleanSession(arr(it.optJSONArray(if (pm) "pm" else "am"))) }
        val a = tt.optJSONArray(k) ?: return emptyList()
        return if (pm) emptyList() else cleanSession(arr(a))
    }

    /** Every subject of [dow], morning then afternoon (free periods left out): the bag list, a11y text. */
    fun day(tt: JSONObject, dow: Int): List<String> = (session(tt, dow, false) + session(tt, dow, true)).filter { it.isNotEmpty() }

    /** A copy of [tt] with one session of [dow] replaced (a day with nothing left drops its key). */
    fun withSession(tt: JSONObject, dow: Int, pm: Boolean, l: List<String>): JSONObject {
        val o = JSONObject(tt.toString())
        val am = if (pm) session(tt, dow, false) else cleanSession(l)
        val p = if (pm) cleanSession(l) else session(tt, dow, true)
        if (am.none { it.isNotEmpty() } && p.none { it.isNotEmpty() }) o.remove(dow.toString())
        else o.put(dow.toString(), JSONObject().put("am", JSONArray(am)).put("pm", JSONArray(p)))
        return o
    }

    /** Old flat setter (a list = the morning). */
    fun with(tt: JSONObject, dow: Int, l: List<String>): JSONObject = withSession(tt, dow, false, clean(l))

    /** Copies both sessions of [from] onto [to]. */
    fun copyDay(tt: JSONObject, from: Int, to: Int): JSONObject =
        withSession(withSession(tt, to, false, session(tt, from, false)), to, true, session(tt, from, true))

    fun isEmpty(tt: JSONObject) = (1..7).all { day(tt, it).isEmpty() }
    fun tomorrow(dow: Int) = dow % 7 + 1
    fun distinct(l: List<String>): List<String> = l.distinctBy { it.lowercase() }
    /** After 17:00 the widget leads with tomorrow's bag. */
    fun eveningFirst(minute: Int) = minute >= EVENING

    // ---- bells ----

    /** [n] periods from [start]: [len] min each, [gap] min between, [recessLen] min after period [recessAfter] (1-based, 0 = none). */
    fun gen(start: Int, len: Int, gap: Int, n: Int, recessAfter: Int, recessLen: Int): List<IntArray> {
        val out = ArrayList<IntArray>(); var t = start
        for (i in 1..n.coerceIn(1, MAX_SESSION)) {
            if (t + len > 24 * 60) break
            out += intArrayOf(t, t + len)
            t += len + if (i == recessAfter) maxOf(recessLen, gap) else gap
        }
        return out
    }

    fun bells(tt: JSONObject, pm: Boolean): List<IntArray> {
        val a = tt.optJSONObject("bells")?.optJSONArray(if (pm) "pm" else "am") ?: return if (pm) DEF_PM else DEF_AM
        val out = ArrayList<IntArray>()
        for (i in 0 until minOf(a.length(), MAX_SESSION)) {
            val p = a.optJSONArray(i) ?: continue
            val s = p.optInt(0, -1); val e = p.optInt(1, -1)
            if (s in 0 until 24 * 60 && e in (s + 1)..(24 * 60)) out += intArrayOf(s, e)
        }
        return out.sortedBy { it[0] }
    }

    fun withBells(tt: JSONObject, pm: Boolean, l: List<IntArray>): JSONObject {
        val o = JSONObject(tt.toString())
        val b = o.optJSONObject("bells") ?: JSONObject()
        b.put(if (pm) "pm" else "am", JSONArray(l.sortedBy { it[0] }.take(MAX_SESSION).map { JSONArray(listOf(it[0], it[1])) }))
        o.put("bells", b)
        return o
    }

    // ---- the day as a timeline ----

    enum class Kind { CLASS, RECESS, LUNCH }
    /** One stretch of the school day. [period] is 1-based within its session; [subject] only for CLASS. */
    data class Slot(val kind: Kind, val start: Int, val end: Int, val pm: Boolean = false, val period: Int = 0, val subject: String = "")

    /**
     * The timed day: the periods that have a subject (free periods, and periods with no bell time, left out), the
     * recesses between them, and lunch between the sessions.
     */
    fun slots(tt: JSONObject, dow: Int): List<Slot> {
        val cls = ArrayList<Slot>()
        for (pm in listOf(false, true)) {
            val b = bells(tt, pm)
            session(tt, dow, pm).forEachIndexed { i, s -> if (s.isNotEmpty() && i < b.size) cls += Slot(Kind.CLASS, b[i][0], b[i][1], pm, i + 1, s) }
        }
        cls.sortBy { it.start }
        val out = ArrayList<Slot>()
        for ((i, c) in cls.withIndex()) {
            if (i > 0) {
                val p = cls[i - 1]
                if (p.pm != c.pm && c.start > p.end) out += Slot(Kind.LUNCH, p.end, c.start, c.pm)
                else if (c.start - p.end >= RECESS && c.period == p.period + 1) out += Slot(Kind.RECESS, p.end, c.start, c.pm)
            }
            out += c
        }
        return out
    }

    /** Where [minute] falls: the slot it is inside, else null + the next slot (null after school). */
    fun at(slots: List<Slot>, minute: Int): Pair<Slot?, Slot?> {
        val cur = slots.firstOrNull { minute >= it.start && minute < it.end }
        val next = slots.firstOrNull { it.start > minute && it.kind == Kind.CLASS }
        return cur to next
    }

    fun hm(m: Int) = "%d:%02d".format(m / 60, m % 60)

    /** Days the week view shows: Mon–Sat (Vietnamese schools often teach on Saturday), plus Sun when it has a subject. */
    fun weekDays(tt: JSONObject): List<Int> = (1..6).toList() + listOf(7).filter { day(tt, it).isNotEmpty() }

    /** Periods [from] onwards moved by [delta] min (a longer / shorter break before them); stays within the day. */
    fun shift(b: List<IntArray>, from: Int, delta: Int): List<IntArray> = b.mapIndexed { i, p ->
        if (i < from) p else intArrayOf((p[0] + delta).coerceIn(0, 24 * 60 - 1), (p[1] + delta).coerceIn(1, 24 * 60))
    }

    /** Rows of the week view for one session: the longest list among [days] (0 = session not used). */
    fun rows(tt: JSONObject, days: List<Int>, pm: Boolean): Int = days.maxOfOrNull { session(tt, it, pm).size } ?: 0
}
