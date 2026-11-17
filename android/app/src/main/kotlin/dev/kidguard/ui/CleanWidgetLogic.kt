package dev.kidguard

/** What the launcher "Dọn dẹp" widget shows at rest (pure, unit-tested). */
object CleanWidgetLogic {
    const val OFF = 0; const val BUSY = 1; const val WAIT = 2; const val READY = 3
    class St(val kind: Int, val waitMin: Int = 0)
    fun state(ruleOn: Boolean, running: Boolean, waitMin: Int): St = when {
        !ruleOn -> St(OFF)
        running -> St(BUSY)
        waitMin > 0 -> St(WAIT, waitMin)
        else -> St(READY)
    }
}
