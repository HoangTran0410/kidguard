package dev.kidguard

import java.text.Normalizer
import java.util.Calendar

/**
 * What the history screen shows (owner 2026-11-11: "calendar picker, lượng noti mỗi ngày, filter theo tên, app, loại,
 * khoảng ngày giờ"). [d0]..[d1] = local days as yyyymmdd (0 / MAX = no limit); [m0]..[m1) = minutes of the day,
 * m0 > m1 wraps midnight (night 22:00–06:00); [q] is matched without accents against app, title and text.
 */
data class NotifFilter(
    val d0: Int = 0, val d1: Int = Int.MAX_VALUE,
    val pkgs: Set<String> = emptySet(), val kinds: Set<String> = emptySet(),
    val m0: Int = 0, val m1: Int = NotifQ.DAY_MIN, val q: String = "",
) {
    val allDay: Boolean get() = m0 == 0 && m1 == NotifQ.DAY_MIN
    val anyDays: Boolean get() = d0 == 0 && d1 == Int.MAX_VALUE
}

/** Pure part of the SQLite history (unit-tested): kinds, day / minute keys, folding, the WHERE clause. */
object NotifQ {
    const val DAY_MIN = 24 * 60
    val KEEP_DAYS = listOf(7, 30, 90)
    const val KEEP_DEFAULT = 30
    const val IMG_DAYS = 7          // pictures / avatars: kept less long than the text (they are most of the bytes)
    const val MAX_ROWS = 60_000     // ~ 650 a day for 90 days
    const val PAGE = 120

    const val K_MSG = "msg"; const val K_CALL = "call"; const val K_SOCIAL = "social"; const val K_TIME = "time"
    const val K_SYS = "sys"; const val K_PROMO = "promo"; const val K_OTHER = "other"
    val KINDS = listOf(K_MSG, K_CALL, K_SOCIAL, K_TIME, K_SYS, K_PROMO, K_OTHER)

    /** Notification.category (+ a MessagingStyle body) -> one of [KINDS]. */
    fun kind(category: String, hasMsgs: Boolean): String = when (category) {
        "call", "missed_call" -> K_CALL
        "msg", "email" -> K_MSG
        "social" -> K_SOCIAL
        "alarm", "reminder", "event", "stopwatch" -> K_TIME
        "promo", "recommendation" -> K_PROMO
        "sys", "service", "status", "err", "progress", "transport", "navigation", "location_sharing", "workout", "car_emergency", "car_warning" -> K_SYS
        else -> if (hasMsgs) K_MSG else K_OTHER
    }

    /** Lower case, no Vietnamese accents (đ -> d): "Tin nhắn" is found by "tin nhan". */
    fun fold(s: String): String = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "").replace('đ', 'd')

    /** Search text kept per row. */
    fun searchText(e: NotifEntry): String = fold(listOf(e.app, e.title, e.text, e.bigText, e.subText, e.lines.joinToString(" "),
        e.msgs.joinToString(" ") { it.sender + " " + it.text }).filter { it.isNotEmpty() }.joinToString(" ")).take(4000)

    /** yyyymmdd of [t] in the phone's zone. */
    fun day(t: Long, c: Calendar = Calendar.getInstance()): Int { c.timeInMillis = t; return day(c) }
    fun day(c: Calendar): Int = c.get(Calendar.YEAR) * 10000 + (c.get(Calendar.MONTH) + 1) * 100 + c.get(Calendar.DAY_OF_MONTH)
    fun minute(t: Long, c: Calendar = Calendar.getInstance()): Int { c.timeInMillis = t; return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE) }

    fun inHours(m: Int, m0: Int, m1: Int): Boolean = if (m0 <= m1) m in m0 until m1 else m >= m0 || m < m1

    /** Escapes LIKE wildcards (escape char '\'). */
    fun likeArg(q: String): String = "%" + fold(q.trim()).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"

    /**
     * WHERE clause + args for [f]. Columns: d (day), m (minute), p (package), kd (kind), f (folded text).
     * [skipPkgs] / [skipKinds]: leave that filter out (the app / kind pickers show counts over the other filters).
     */
    fun where(f: NotifFilter, skipPkgs: Boolean = false, skipKinds: Boolean = false, skipDays: Boolean = false): Pair<String, Array<String>> {
        val w = ArrayList<String>(); val a = ArrayList<String>()
        if (!skipDays && f.d0 > 0) { w += "d >= ?"; a += f.d0.toString() }
        if (!skipDays && f.d1 != Int.MAX_VALUE) { w += "d <= ?"; a += f.d1.toString() }
        if (!skipPkgs && f.pkgs.isNotEmpty()) { w += "p IN (" + f.pkgs.joinToString(",") { "?" } + ")"; a += f.pkgs }
        if (!skipKinds && f.kinds.isNotEmpty()) { w += "kd IN (" + f.kinds.joinToString(",") { "?" } + ")"; a += f.kinds }
        if (!f.allDay) {
            if (f.m0 <= f.m1) { w += "m >= ? AND m < ?" } else { w += "(m >= ? OR m < ?)" }
            a += f.m0.toString(); a += f.m1.toString()
        }
        if (f.q.isNotBlank()) { w += "f LIKE ? ESCAPE '\\'"; a += likeArg(f.q) }
        return (if (w.isEmpty()) "1" else w.joinToString(" AND ")) to a.toTypedArray()
    }

    /** Calendar tap: none -> that day; one day + same -> none; one day + other -> range; a range -> that day. */
    fun tap(d0: Int, d1: Int, day: Int): Pair<Int, Int> = when {
        d0 == 0 -> day to day
        d0 == d1 && day == d0 -> 0 to 0
        d0 == d1 -> minOf(d0, day) to maxOf(d0, day)
        else -> day to day
    }

    /** 0..4 shade step of a day with [n] notifications when the busiest day of the month has [max]. */
    fun shade(n: Int, max: Int): Int = if (n <= 0 || max <= 0) 0 else (1 + (n * 4 - 1) / max).coerceIn(1, 4)
}
