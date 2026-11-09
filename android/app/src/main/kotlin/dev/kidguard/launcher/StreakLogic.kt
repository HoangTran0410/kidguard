package dev.kidguard

/**
 * Pure logic of the "Chuỗi ngày" widget. A day is an epoch-day number in the phone's local zone ([dayOf]); `done` is the set
 * of days the kid finished the daily list. Missed day = a gap. Days after today (the clock went back) are ignored for the
 * current streak but kept for the best one.
 */
object StreakLogic {
    val BADGES = listOf(3, 7, 14, 30)
    const val KEEP_DAYS = 400

    /** Local epoch day of [ms], [offsetMs] = the zone's offset at that instant. */
    fun dayOf(ms: Long, offsetMs: Long): Long = Math.floorDiv(ms + offsetMs, 86_400_000L)

    /** Consecutive done days ending today, or ending yesterday while today is still open (the streak is alive until midnight). */
    fun current(done: Set<Long>, today: Long): Int {
        var d = if (today in done) today else today - 1
        var n = 0
        while (d in done) { n++; d-- }
        return n
    }

    fun best(done: Set<Long>): Int {
        var best = 0; var run = 0; var prev = Long.MIN_VALUE
        for (d in done.sorted()) { run = if (prev != Long.MIN_VALUE && d == prev + 1) run + 1 else 1; if (run > best) best = run; prev = d }
        return best
    }

    /** Marks [today] done and drops the oldest days beyond [KEEP_DAYS]. */
    fun mark(done: Set<Long>, today: Long): Set<Long> = (done + today).sortedDescending().take(KEEP_DAYS).toSet()

    /** Badges (3, 7, 14, 30) the best streak has reached. */
    fun earned(best: Int): List<Int> = BADGES.filter { it <= best }

    /** The next badge to reach after [streak], or null at the top. */
    fun next(streak: Int): Int? = BADGES.firstOrNull { it > streak }

    /**
     * Celebration: [prev] = the highest badge already celebrated for the current run. Returns (badge to celebrate now or null,
     * new prev). A broken streak lowers prev, so the same badge is celebrated again on the next run.
     */
    fun celebrate(cur: Int, prev: Int): Pair<Int?, Int> {
        val top = BADGES.lastOrNull { it <= cur } ?: 0
        return if (top > prev) top to top else null to minOf(prev, top)
    }

    /** A day counts when the list went from "not all done" to "all done" (a list left ticked from yesterday never counts). */
    fun completes(wasComplete: Boolean, nowComplete: Boolean) = nowComplete && !wasComplete
}
