package dev.kidguard

import kotlin.math.abs

/** Pure part of the home-grid gestures (HomeGestures.kt): swipe direction, tap + double-tap detection, action ids. No Android imports. */
object GestLogic {
    // action ids (stored as text in prefs "hm_gest" and in backups)
    const val DEFAULT = "default"   // swipe up / down only: what the launcher always did (drawer / shade / control center)
    const val NONE = "none"
    const val SEARCH = "search"
    const val DRAWER = "drawer"
    const val SHADE = "shade"
    const val LOCK = "lock"
    const val TODAY = "today"
    const val APP = "app"
    val ACTIONS = listOf(NONE, SEARCH, DRAWER, SHADE, LOCK, TODAY, APP)

    const val DBL = "dbl"; const val UP = "up"; const val DOWN = "down"
    val GESTURES = listOf(DBL, UP, DOWN)

    /** Double tap defaults to nothing; swipes keep today's behaviour. */
    fun default(g: String) = if (g == DBL) NONE else DEFAULT

    /** Unknown / impossible ids fall back to the default (a backup from a newer app, a flavor without lock, an app gesture with no app). */
    fun clean(g: String, id: String?, pkg: String, lockOk: Boolean): String = when {
        g !in GESTURES -> NONE
        id == null || (id != DEFAULT && id !in ACTIONS) -> default(g)
        id == DEFAULT && g == DBL -> NONE
        id == LOCK && !lockOk -> default(g)
        id == APP && pkg.isEmpty() -> default(g)
        else -> id
    }

    const val DOUBLE_MS = 280L

    enum class Dir { UP, DOWN }

    /** Same thresholds the launcher always used: up = past slop and mostly vertical; down needs twice the slop (the shade is a bigger commitment). */
    fun swipe(dx: Float, dy: Float, slop: Float): Dir? = when {
        dy < -slop && abs(dy) > abs(dx) * 1.5f -> Dir.UP
        dy > slop * 2 && dy > abs(dx) * 1.5f -> Dir.DOWN
        else -> null
    }

    /**
     * Tracks taps on empty home space. [up] returns true when the finger lifted as the second tap of a double tap: both taps short
     * (shorter than [longMs], moved at most [slop]) and the second one down within [DOUBLE_MS] of the first one's lift, within
     * [slop] * 4 of where it was. A completed double tap is consumed (a third quick tap starts over).
     */
    class TapTracker(private val slop: Float, private val longMs: Long = 400L) {
        private var dt = 0L; private var dx = 0f; private var dy = 0f
        private var lt = -1L; private var lx = 0f; private var ly = 0f
        private var second = false

        fun down(t: Long, x: Float, y: Float) {
            dt = t; dx = x; dy = y
            second = lt >= 0 && t - lt in 0..DOUBLE_MS && abs(x - lx) <= slop * 4 && abs(y - ly) <= slop * 4
        }

        fun up(t: Long, x: Float, y: Float): Boolean {
            val tap = t - dt < longMs && abs(x - dx) <= slop && abs(y - dy) <= slop
            if (!tap) { lt = -1; second = false; return false }
            if (second) { lt = -1; second = false; return true }
            lt = t; lx = x; ly = y; return false
        }

        fun reset() { lt = -1; second = false }
    }
}
