package dev.kidguard

import java.util.Calendar
import kotlin.math.roundToInt

/**
 * The numbers behind the notification stats screen (NotifStatsActivity), pure so they are unit-tested. [NotifLog.stats]
 * reads the raw counts from SQLite; this turns them into a summary, a bar per day, a weekday x hour heat map and per-app
 * / per-channel rows. Days are yyyymmdd ints (NotifQ.day), weekdays Monday = 0.
 */
object NotifStats {
    class Cell(val day: Int, val hour: Int, val n: Int)
    class AppRaw(val pkg: String, val app: String, val n: Int, val clicked: Int, val promo: Int, val hours: IntArray)
    class ChanRaw(val id: String, val name: String, val n: Int, val clicked: Int, val importance: Int)
    /** [reasons]: removal reason (NotifLogic.R_*, "" = still showing) -> count. */
    class Raw(val cells: List<Cell>, val prev: Int, val reasons: Map<String, Int>, val apps: List<AppRaw>, val chans: List<ChanRaw>, val name: String)

    val RANGES = listOf(7, 30, 90)

    /** The ranges offered when [keepDays] of history are kept (the longer ones would only show empty days). */
    fun ranges(keepDays: Int): List<Int> = RANGES.filter { it <= keepDays }.ifEmpty { listOf(RANGES[0]) }

    /** The [n] days ending with [today], oldest first. */
    fun days(today: Calendar, n: Int): List<Int> {
        val c = today.clone() as Calendar
        c.add(Calendar.DAY_OF_MONTH, -(n - 1))
        return List(n) { NotifQ.day(c).also { c.add(Calendar.DAY_OF_MONTH, 1) } }
    }

    /** Monday = 0 .. Sunday = 6. */
    fun weekday(day: Int): Int {
        val c = Calendar.getInstance().apply { clear(); set(day / 10000, day / 100 % 100 - 1, day % 100) }
        return (c.get(Calendar.DAY_OF_WEEK) + 5) % 7
    }

    fun daily(cells: List<Cell>, days: List<Int>): IntArray {
        val at = days.withIndex().associate { it.value to it.index }
        val out = IntArray(days.size)
        for (c in cells) at[c.day]?.let { out[it] += c.n }
        return out
    }

    /** 7 rows (Mon..Sun) x 24 hours. */
    fun heat(cells: List<Cell>): Array<IntArray> {
        val out = Array(7) { IntArray(24) }
        for (c in cells) out[weekday(c.day)][c.hour.coerceIn(0, 23)] += c.n
        return out
    }

    fun hours(cells: List<Cell>): IntArray = IntArray(24).also { h -> for (c in cells) h[c.hour.coerceIn(0, 23)] += c.n }

    /** Index of the largest value, -1 when all are 0. */
    fun peak(a: IntArray): Int { var b = -1; var m = 0; for (i in a.indices) if (a[i] > m) { m = a[i]; b = i }; return b }

    /** Percent of [n] that was opened (tapped), -1 when there is nothing. */
    fun openRate(clicked: Int, n: Int): Int = if (n <= 0) -1 else (clicked * 100f / n).roundToInt()

    /** Percent change from [prev] to [cur], null when there was nothing before (no base to compare). */
    fun change(cur: Int, prev: Int): Int? = if (prev <= 0) null else ((cur - prev) * 100f / prev).roundToInt()

    /**
     * Days the range really covers: from its first day or the first day with data, whichever is later, to the last.
     * A history 3 days old shown as "30 days" is 3 days for the per-day average.
     */
    fun covered(days: List<Int>, cells: List<Cell>): Int {
        val first = cells.minOfOrNull { it.day } ?: return days.size
        val i = days.indexOfFirst { it >= first }
        return if (i < 0) days.size else days.size - i
    }

    fun perDay(n: Int, days: Int): Float = if (days <= 0) 0f else n.toFloat() / days

    /** Worth turning down: at least 3 a day on average and almost never opened (< 5 %). */
    fun noisy(n: Int, clicked: Int, days: Int): Boolean = perDay(n, days) >= 3f && openRate(clicked, n) in 0..4

    class Summary(val total: Int, val perDay: Float, val change: Int?, val peakHour: Int, val peakDay: Int, val openRate: Int)

    fun summary(r: Raw, days: List<Int>): Summary {
        val total = r.cells.sumOf { it.n }
        val byDay = IntArray(7).also { w -> heat(r.cells).forEachIndexed { i, row -> w[i] = row.sum() } }
        return Summary(total, perDay(total, covered(days, r.cells)), change(total, r.prev), peak(hours(r.cells)), peak(byDay),
            openRate(r.reasons[NotifLogic.R_CLICK] ?: 0, total))
    }

    /** Heat level 0..4 of [n] against [max] (0 only for nothing). */
    fun level(n: Int, max: Int): Int = NotifQ.shade(n, max)
}
