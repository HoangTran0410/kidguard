package dev.kidguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlanLogicTest {
    private val r = Rules(bedtimeOn = true, bedStart = "21:00", bedEnd = "06:30",
        schedules = listOf(Schedule("Học", true, setOf(1, 2, 3, 4, 5), "08:00", "11:00")))

    @Test fun blocksAndReopen() {
        val evs = LauncherLogic.today(r, 9 * 60, 1)
        assertEquals(listOf(0 to 390, 480 to 660, 1260 to 1440), PlanLogic.blocks(evs))
        assertEquals(660, PlanLogic.reopenAt(evs))
        assertNull(PlanLogic.reopenAt(LauncherLogic.today(r, 12 * 60, 1)))
        assertEquals(390 + 1440, PlanLogic.reopenAt(LauncherLogic.today(r, 22 * 60, 1)))
    }

    @Test fun groups() {
        assertEquals(20 * 60_000L, PlanLogic.groupLeftMs(60, 40 * 60_000L))
        assertEquals(0L, PlanLogic.groupLeftMs(30, 40 * 60_000L))
        assertEquals(0.5f, PlanLogic.groupFrac(60, 30 * 60_000L), 0.001f)
    }
}
