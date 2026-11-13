package dev.kidguard

/**
 * "More time" that always works (0.9.16, docs/protocol.md "Timed pass"). One rule in three places (page JS, parent app, here):
 * when nothing phone-wide limits the child now ([why] "" or "daily") the minutes go to today's bonus, as they always did;
 * for anything else (bedtime, schedule, pause, an earlier pass) they open a pass: `passUntil` = max(now, passUntil) + min.
 * Pure, unit-tested. The result always carries a newer `rev`.
 */
object Grant {
    const val MAX_MIN = 24 * 60

    fun apply(r: Rules, nowMs: Long, why: String, min: Int, today: String): Rules {
        val m = min.coerceIn(0, MAX_MIN)
        val rev = maxOf(nowMs, r.rev + 1)
        return if (why == "" || why == "daily")
            r.copy(rev = rev, bonusDay = today, bonusMin = ((if (r.bonusDay == today) r.bonusMin else 0) + m).coerceIn(0, MAX_MIN))
        else r.copy(rev = rev, passUntil = maxOf(nowMs, r.passUntil) + m * 60_000L)
    }

    /** End a pass early (the page's "Kết thúc"): passUntil = 0. */
    fun end(r: Rules, nowMs: Long): Rules = r.copy(rev = maxOf(nowMs, r.rev + 1), passUntil = 0)
}

/** "Thêm 1 phút" on the block sheet: once per lock period, only while a limit the parent set is what locks the phone. Pure. */
object OneMore {
    const val MS = 60_000L

    /** Identity of the current lock period, or null when a minute may not be taken (free, paused, lost, a pass already open). */
    fun key(nw: NowWhy): String? = when (nw.why) {
        "schedule", "bedtime", "daily" -> "${nw.why}:${nw.until}"
        else -> null
    }

    fun allowed(r: Rules, nw: NowWhy, usedKey: String): Boolean = r.oneMore && key(nw).let { it != null && it != usedKey }
}
