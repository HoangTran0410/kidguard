package dev.kidguard

/** Pure part of the child's home screen (the status card). No Android classes, unit-tested in HomeLogicTest. */
object HomeLogic {
    /** [reopenMinute] for the daily limit: it resets at midnight. */
    const val TOMORROW = -2

    /**
     * When a phone-wide [block] (QuickLogic.phoneBlock) ends, as a minute of day, [TOMORROW], or null when it has no
     * fixed end (paused: the caller reads Rules.pausedUntil). Schedule: the latest end among the windows active now.
     */
    fun reopenMinute(r: Rules, block: String, minute: Int, dow: Int): Int? = when (block) {
        "bedtime" -> Rules.parseHm(r.bedEnd)
        "schedule" -> r.schedules.filter { it.active(minute, dow) }.mapNotNull { Rules.parseHm(it.end) }
            .maxByOrNull { (it - minute + 1440) % 1440 }
        "daily" -> TOMORROW
        else -> null
    }

    /** ms → (hours, minutes), rounded up to the minute (the tile does the same: "1 min left" until it is 0). */
    fun hm(ms: Long): Pair<Int, Int> {
        val min = ((ms.coerceAtLeast(0) + 59_999) / 60_000).toInt()
        return min / 60 to min % 60
    }

    fun clock(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)

    /** The "ask for more time" button only where a yes changes something: a daily limit, a schedule or bedtime. */
    fun canAsk(paired: Boolean, leftMs: Long, block: String?): Boolean =
        paired && block != "paused" && (block != null || leftMs >= 0)
}
