package dev.kidguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeLogicTest {
    @Test fun reopen() {
        val r = Rules(bedtimeOn = true, bedStart = "21:30", bedEnd = "06:30",
            schedules = listOf(Schedule("Học", true, setOf(1, 2, 3, 4, 5), "08:00", "11:00"), Schedule("", true, setOf(1), "09:00", "10:00")))
        assertEquals(6 * 60 + 30, HomeLogic.reopenMinute(r, "bedtime", 22 * 60, 1))
        assertEquals(11 * 60, HomeLogic.reopenMinute(r, "schedule", 9 * 60 + 30, 1))      // latest end of the active ones
        assertEquals(HomeLogic.TOMORROW, HomeLogic.reopenMinute(r, "daily", 15 * 60, 1))
        assertNull(HomeLogic.reopenMinute(r, "paused", 15 * 60, 1))
    }

    @Test fun reopenAcrossMidnight() {
        val r = Rules(schedules = listOf(Schedule("", true, setOf(5), "22:00", "01:00"), Schedule("", true, setOf(5), "20:00", "23:00")))
        // 22:30 Friday: both active, 01:00 comes later than 23:00
        assertEquals(60, HomeLogic.reopenMinute(r, "schedule", 22 * 60 + 30, 5))
    }

    @Test fun hmRoundsUp() {
        assertEquals(1 to 25, HomeLogic.hm(84 * 60_000L + 1))
        assertEquals(0 to 1, HomeLogic.hm(1))
        assertEquals(0 to 0, HomeLogic.hm(-5))
        assertEquals(2 to 0, HomeLogic.hm(120 * 60_000L))
        assertEquals("06:05", HomeLogic.clock(6 * 60 + 5))
    }

    @Test fun ask() {
        assertFalse(HomeLogic.canAsk(true, -1, null))          // no limit, nothing to extend
        assertTrue(HomeLogic.canAsk(true, 30 * 60_000L, null))
        assertTrue(HomeLogic.canAsk(true, -1, "bedtime"))
        assertFalse(HomeLogic.canAsk(true, 0, "paused"))
        assertFalse(HomeLogic.canAsk(false, 0, "daily"))
    }
}
