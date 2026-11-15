package dev.kidguard

/**
 * Own gesture navigation for Xiaomi / Poco (HyperOS): with KidGuard as home the system turns gestures into 3 buttons.
 * `force_fsg_nav_bar = 1` hides the buttons but then nothing swipes, so [Acc] draws thin touch strips instead
 * (see [NavGest]). Pure decisions here: when the bar may be hidden, and what a finished touch on a strip means.
 */
object NavGestLogic {
    const val KEY = "force_fsg_nav_bar"
    /** A swipe must go at least this far (dp) to count. */
    const val MIN_DP = 40f
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

    /** Manufacturers whose home swap drops the gestures (same list as the launcher warning). */
    fun xiaomi(manufacturer: String): Boolean = manufacturer.lowercase() in setOf("xiaomi", "redmi", "poco")

    /**
     * [dx] / [dy]: finger movement from down to up in px (right / down positive), [still] = ms since the finger last
     * moved more than the still radius, [min] = threshold in px.
     * Sides: inward and mostly horizontal = BACK. Bottom: up and mostly vertical = HOME, or RECENTS after a hold.
     */
    fun classify(edge: Edge, dx: Float, dy: Float, still: Long, min: Float): Act {
        val ax = Math.abs(dx); val ay = Math.abs(dy)
        return when (edge) {
            Edge.LEFT -> if (dx >= min && ay <= ax * 1.5f) Act.BACK else Act.NONE
            Edge.RIGHT -> if (-dx >= min && ay <= ax * 1.5f) Act.BACK else Act.NONE
            Edge.BOTTOM -> if (-dy >= min && ax <= ay * 1.5f) (if (still >= HOLD_MS) Act.RECENTS else Act.HOME) else Act.NONE
        }
    }
}
