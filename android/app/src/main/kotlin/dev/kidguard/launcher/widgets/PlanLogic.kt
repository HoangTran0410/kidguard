package dev.kidguard

/** Pure part of the "when can I play" widget (HomeWidgetsPlan.kt). Android-free, unit-tested in PlanLogicTest. */
object PlanLogic {
    /** Blocked stretches of the day [start, end) in minutes: schedule locks and bedtime (open hours of a group are not blocks). Overnight runs to 24:00. */
    fun blocks(evs: List<LauncherLogic.Ev>): List<Pair<Int, Int>> =
        evs.filter { it.kind != "group" }.map { it.start to (if (it.end > it.start) it.end else 1440) }

    /** When the lock running now ends, in minutes from today's 00:00 (>= 1440 = tomorrow); null when no schedule / bedtime is on now. */
    fun reopenAt(evs: List<LauncherLogic.Ev>): Int? =
        evs.filter { it.kind != "group" && it.state == "now" }.maxOfOrNull { if (it.end > it.start) it.end else it.end + 1440 }

    /** ms left of a group limit of [limitMin] minutes after [usedMs]; never below 0. */
    fun groupLeftMs(limitMin: Int, usedMs: Long): Long = (limitMin * 60_000L - usedMs).coerceAtLeast(0)

    /** Fraction of the limit still left, 0..1. */
    fun groupFrac(limitMin: Int, usedMs: Long): Float = if (limitMin <= 0) 1f else (groupLeftMs(limitMin, usedMs) / (limitMin * 60_000f)).coerceIn(0f, 1f)
}
