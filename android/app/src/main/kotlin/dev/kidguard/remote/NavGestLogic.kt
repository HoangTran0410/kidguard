package dev.kidguard

/**
 * Own gesture navigation for Xiaomi / Poco (HyperOS): with KidGuard as home the system turns gestures into 3 buttons.
 * `force_fsg_nav_bar = 1` hides the buttons but then nothing swipes, so [Acc] draws thin touch strips instead
 * (see [NavGest]). Pure decisions here: when the bar may be hidden, and what a finished touch on a strip means.
 */
object NavGestLogic {
    const val KEY = "force_fsg_nav_bar"
    /** A swipe must go at least this far (dp) to count. */
    const val MIN_DP = 48f
    /** Held this long (ms) without moving, after the swipe up: Recents instead of Home. */
    const val HOLD_MS = 350L
    /** Finger counts as "still" while it stays within this many dp of its last anchor. */
    const val STILL_DP = 6f

    enum class Edge { LEFT, RIGHT, BOTTOM }
    enum class Act { NONE, BACK, HOME, RECENTS }

    /**
     * The bar may be hidden ONLY when every part works: switch on, permission, our accessibility service running and
     * not paused, no paused (bank) app in front, not released. Anything else = bar back.
     */
    fun hide(on: Boolean, permission: Boolean, accUp: Boolean, accPaused: Boolean, bankFront: Boolean, released: Boolean): Boolean =
        on && permission && accUp && !accPaused && !bankFront && !released

    /**
     * What to write when the bar must be visible (null = leave it). With our app as home, 1 without our strips = no
     * buttons and no gestures (Poco 2026-11-16: stuck in MoMo), so the answer is ALWAYS 0 there, whoever wrote the 1 and
     * whatever [prev] says (a 1 recorded as "before" after an earlier incident restored the hidden bar every time).
     * Another home: give back what was there before we touched it (its own gestures may need 1).
     */
    fun restoreTo(prev: Int, ourHome: Boolean, cur: Int): Int? = when {
        ourHome -> if (cur != 0) 0 else null
        prev >= 0 && cur != prev -> prev
        else -> null
    }

    /** Manufacturers whose home swap drops the gestures (same list as the launcher warning). */
    fun xiaomi(manufacturer: String): Boolean = manufacturer.lowercase() in setOf("xiaomi", "redmi", "poco")

    /**
     * [dx] / [dy]: finger movement from down to up in px (right / down positive), [still] = ms since the finger last
     * moved more than the still radius, [min] = threshold in px.
     * Sides: inward and mostly horizontal = BACK. Bottom: up and mostly vertical = HOME, or RECENTS after a hold.
     */
    /** How far the finger went inward, as a share of [min] (0 = at the edge or outward, 1 = armed). Live feedback +
     *  cancel: pulling back below 1 before lifting = nothing happens. */
    fun progress(edge: Edge, dx: Float, dy: Float, min: Float): Float = when (edge) {
        Edge.LEFT -> dx; Edge.RIGHT -> -dx; Edge.BOTTOM -> -dy
    }.coerceAtLeast(0f) / min

    fun classify(edge: Edge, dx: Float, dy: Float, still: Long, min: Float): Act {
        val ax = Math.abs(dx); val ay = Math.abs(dy)
        return when (edge) {
            Edge.LEFT -> if (dx >= min && ay <= ax * 1.5f) Act.BACK else Act.NONE
            Edge.RIGHT -> if (-dx >= min && ay <= ax * 1.5f) Act.BACK else Act.NONE
            Edge.BOTTOM -> if (-dy >= min && ax <= ay * 1.5f) (if (still >= HOLD_MS) Act.RECENTS else Act.HOME) else Act.NONE
        }
    }

    /**
     * Full-screen mode (owner 2026-11-16: games): a stray edge swipe must not leave the game. While the app in front
     * hides the status bar, or is a game, only the right edge stays and a swipe there opens a 3-button menu
     * (Back / Home / Recents) instead of acting; a tap anywhere else (the game) closes it. Never on our own screens.
     */
    /** Full-screen mode, bottom edge: the first swipe only arms it (like Android's own "swipe again" over a full-screen
     *  app); a second one within [AGAIN_MS] acts. */
    const val AGAIN_MS = 3000L
    fun again(armedAt: Long, now: Long): Boolean = armedAt > 0L && now - armedAt in 0..AGAIN_MS

    fun calm(statusHidden: Boolean, game: Boolean, ourFront: Boolean): Boolean = !ourFront && (statusHidden || game)

    /** Top of the menu: centred on the finger, kept [margin] inside the screen. */
    fun menuTop(fingerY: Int, menuH: Int, screenH: Int, margin: Int): Int =
        (fingerY - menuH / 2).coerceAtMost(screenH - menuH - margin).coerceAtLeast(margin)

    /** Which app "is in front" for full-screen mode: keyboards, the shade and other windows without a launcher entry
     *  keep the last answer (null), so typing in a game or pulling the shade does not flip the mode. */
    fun frontGame(launchable: Boolean, ours: Boolean, game: Boolean): Boolean? = when {
        ours -> false
        !launchable -> null
        else -> game
    }
}
