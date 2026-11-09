package dev.kidguard

import org.json.JSONObject

/**
 * Pure state machine of the "Hẹn giờ" widget (visual timer + pomodoro). Times are wall-clock ms ([now] is passed in, so it is
 * unit-tested). States: idle (not running, left == total), running (endAt > 0), paused (not running, 0 < left < total),
 * done (not running, left == 0). A clock that jumps back can never show more than [S.total] left.
 */
object TimerLogic {
    val PRESETS = listOf(5, 10, 15, 25)
    const val WORK_MIN = 25
    const val BREAK_MIN = 5
    const val MAX_MIN = 180
    private const val MIN = 60_000L

    data class S(
        val total: Long = 25 * MIN, val endAt: Long = 0, val left: Long = 25 * MIN,
        val pomo: Boolean = false, val onBreak: Boolean = false, val cycles: Int = 0,
    ) {
        val running get() = endAt > 0
        val done get() = !running && left <= 0
        val idle get() = !running && left == total
        fun toJson(): JSONObject = JSONObject().put("total", total).put("endAt", endAt).put("left", left)
            .put("pomo", pomo).put("brk", onBreak).put("cycles", cycles)
    }

    fun fromJson(j: JSONObject?): S {
        if (j == null) return S()
        val total = j.optLong("total", 25 * MIN).coerceIn(MIN, MAX_MIN * MIN)
        return S(total, j.optLong("endAt").coerceAtLeast(0), j.optLong("left", total).coerceIn(0, total),
            j.optBoolean("pomo"), j.optBoolean("brk"), j.optInt("cycles").coerceIn(0, 999))
    }

    fun remaining(s: S, now: Long): Long = if (s.running) (s.endAt - now).coerceIn(0, s.total) else s.left

    /** 0..1 of the disc still coloured. */
    fun fraction(s: S, now: Long): Float = if (s.total <= 0) 0f else (remaining(s, now).toFloat() / s.total).coerceIn(0f, 1f)

    fun start(s: S, now: Long): S {
        if (s.running) return s
        val base = if (s.done) reset(s) else s
        return base.copy(endAt = now + base.left.coerceAtLeast(1), left = base.left)
    }

    fun pause(s: S, now: Long): S = if (!s.running) s else s.copy(endAt = 0, left = remaining(s, now).coerceAtLeast(1))

    /** Back to the start of the current phase; a pomodoro goes back to the first work phase. */
    fun reset(s: S): S = if (s.pomo) pomodoro() else s.copy(endAt = 0, left = s.total)

    fun choose(minutes: Int): S { val t = minutes.coerceIn(1, MAX_MIN) * MIN; return S(t, 0, t) }

    fun pomodoro(): S = S(WORK_MIN * MIN, 0, WORK_MIN * MIN, true, false, 0)

    /** The running phase ended: a plain timer is done; a pomodoro waits, ready, at the next phase (work -> break -> work). */
    fun finish(s: S): S {
        if (!s.pomo) return s.copy(endAt = 0, left = 0)
        val brk = !s.onBreak
        val t = (if (brk) BREAK_MIN else WORK_MIN) * MIN
        return S(t, 0, t, true, brk, if (brk) s.cycles + 1 else s.cycles)
    }

    /** A running state whose end time passed (alarm missed, e.g. after a reboot): finished, no notification. */
    fun settle(s: S, now: Long): S = if (s.running && now >= s.endAt) finish(s) else s

    fun mmss(ms: Long): String {
        val sec = (ms + 999) / 1000
        return if (sec >= 3600) "%d:%02d:%02d".format(sec / 3600, sec / 60 % 60, sec % 60) else "%02d:%02d".format(sec / 60, sec % 60)
    }
}
