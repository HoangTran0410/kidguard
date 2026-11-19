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
}
