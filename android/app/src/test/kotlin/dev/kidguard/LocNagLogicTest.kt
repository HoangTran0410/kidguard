package dev.kidguard

import org.junit.Assert.assertEquals
import org.junit.Test

class LocNagLogicTest {
    @Test fun oneEventPerSwitchOff() {
        // off -> START once, KEEP while off (no second event), END when back on, then nothing
        assertEquals(100L to LocNagLogic.Act.START, LocNagLogic.step(0, true, 100))
        assertEquals(100L to LocNagLogic.Act.KEEP, LocNagLogic.step(100, true, 200))
        assertEquals(0L to LocNagLogic.Act.END, LocNagLogic.step(100, false, 300))
        assertEquals(0L to LocNagLogic.Act.NONE, LocNagLogic.step(0, false, 400))
    }

    @Test fun cardNeverCoversACallAndWaitsAfterSnoozeOrTurnOn() {
        val m = 60_000L
        assertEquals(true, LocNagLogic.pop(10 * m, 0, 0, 0, busy = false))          // first time: at once
        assertEquals(false, LocNagLogic.pop(10 * m, 0, 0, 0, busy = true))          // call / locked / SOS
        assertEquals(false, LocNagLogic.pop(11 * m, 10 * m, 0, 0, busy = false))    // Home pressed: not before 2 min
        assertEquals(true, LocNagLogic.pop(12 * m, 10 * m, 0, 0, busy = false))
        assertEquals(false, LocNagLogic.pop(15 * m, 0, 20 * m, 0, busy = false))    // snoozed
        assertEquals(false, LocNagLogic.pop(15 * m, 0, 0, 15 * m + 1, busy = false)) // just sent to Settings
    }

    @Test fun threeSnoozesADay() {
        assertEquals(3, LocNagLogic.snoozesLeft("2026-11-20", "2026-11-19", 3))
        assertEquals(1, LocNagLogic.snoozesLeft("2026-11-19", "2026-11-19", 2))
        assertEquals(0, LocNagLogic.snoozesLeft("2026-11-19", "2026-11-19", 5))
    }
}
