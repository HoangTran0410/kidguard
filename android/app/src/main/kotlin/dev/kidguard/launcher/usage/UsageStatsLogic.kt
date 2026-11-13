package dev.kidguard

/**
 * The numbers behind the app-usage screen (UsageStatsActivity), pure so they are unit-tested. A "day" is the foreground
 * ms per package ([Usage.range]); [days] lists are oldest first, the last one is today (still running).
 */
object UsageStatsLogic {
    const val DAYS = 7
    /** Apps used less than this in the period are not worth a row (same floor as the home widget). */
    const val MIN_MS = 60_000L

    class Row(val pkg: String, val ms: Long, val today: Long)

    /** Total ms per day, [skip] packages (KidGuard itself) left out. */
    fun totals(days: List<Map<String, Long>>, skip: (String) -> Boolean): LongArray =
        LongArray(days.size) { i -> days[i].entries.filter { !skip(it.key) }.sumOf { it.value } }

    /** One app's ms per day. */
    fun series(days: List<Map<String, Long>>, pkg: String): LongArray = LongArray(days.size) { days[it][pkg] ?: 0L }

    /** Apps of the last [n] days (n = 1: today only), most used first, ties by package so the order is stable. */
    fun rows(days: List<Map<String, Long>>, n: Int, skip: (String) -> Boolean): List<Row> {
        val from = (days.size - n).coerceAtLeast(0)
        val sum = HashMap<String, Long>()
        for (d in days.subList(from, days.size)) for ((p, ms) in d) if (!skip(p)) sum[p] = (sum[p] ?: 0L) + ms
        val today = days.lastOrNull().orEmpty()
        return sum.filter { it.value >= MIN_MS }.map { Row(it.key, it.value, today[it.key] ?: 0L) }
            .sortedWith(compareByDescending<Row> { it.ms }.thenBy { it.pkg })
    }

    /** Days the average really covers: the first day with any use to the last (a phone set up 2 days ago = 2 days). */
    fun covered(totals: LongArray): Int {
        val i = totals.indexOfFirst { it > 0 }
        return if (i < 0) totals.size else totals.size - i
    }

    fun avg(totals: LongArray): Long = if (totals.isEmpty()) 0L else totals.sum() / covered(totals)

    /** Percent of [max] (0..100), at least 3 for any use so the bar shows. */
    fun pct(ms: Long, max: Long): Int = if (ms <= 0 || max <= 0) 0 else (ms * 100 / max).toInt().coerceIn(3, 100)

    /** Index of the largest value, -1 when all are 0. */
    fun peak(a: LongArray): Int { var b = -1; var m = 0L; for (i in a.indices) if (a[i] > m) { m = a[i]; b = i }; return b }

    /** Foreground starts per package in [events] (pkg to a resume flag), a start only when another app was in front before. */
    fun opens(pkgs: List<String>): Map<String, Int> {
        val out = HashMap<String, Int>(); var last: String? = null
        for (p in pkgs) { if (p != last) out[p] = (out[p] ?: 0) + 1; last = p }
        return out
    }
}
