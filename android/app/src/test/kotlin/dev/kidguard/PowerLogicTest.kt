package dev.kidguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerLogicTest {
    private val min = 60_000L

    @Test fun wifiPerf() {
        // Android 8-9: always (ColorOS 8.1 sleeps Wi-Fi with the screen off)
        assertTrue(PowerLogic.wifiPerf(27, 10 * 3600_000L, false))
        assertTrue(PowerLogic.wifiPerf(28, Long.MAX_VALUE / 2, false))
        // Android 10+: only while the LAN page was used in the last 3 min, or a stream runs
        assertFalse(PowerLogic.wifiPerf(29, 10 * 3600_000L, false))
        assertTrue(PowerLogic.wifiPerf(29, 0, false))
        assertTrue(PowerLogic.wifiPerf(34, PowerLogic.LAN_HOT_MS - 1, false))
        assertFalse(PowerLogic.wifiPerf(34, PowerLogic.LAN_HOT_MS, false))
        assertTrue(PowerLogic.wifiPerf(34, 10 * 3600_000L, true))
        assertFalse(PowerLogic.wifiPerf(34, -5, false))   // clock went back: not "just used"
    }

    @Test fun sameSpot() {
        assertTrue(LocPace.same(10.0, 106.0, 20, 10.0003, 106.0, 20))      // ~33 m
        assertFalse(LocPace.same(10.0, 106.0, 20, 10.002, 106.0, 20))      // ~220 m
        assertTrue(LocPace.same(10.0, 106.0, 140, 10.001, 106.0, 20))      // ~110 m, inside the vaguer fix's radius
    }

    @Test fun stillCount() {
        assertEquals(1, LocPace.step(0, hasPrev = true, same = true, screenWasOn = false, acc = 20))
        assertEquals(3, LocPace.step(2, hasPrev = true, same = true, screenWasOn = false, acc = 20))
        assertEquals(0, LocPace.step(2, hasPrev = true, same = false, screenWasOn = false, acc = 20))   // moved
        assertEquals(0, LocPace.step(2, hasPrev = true, same = true, screenWasOn = true, acc = 20))     // in use
        assertEquals(0, LocPace.step(2, hasPrev = false, same = true, screenWasOn = false, acc = 20))   // first fix
        assertEquals(2, LocPace.step(2, hasPrev = true, same = false, screenWasOn = false, acc = Track.BAD_ACC_M + 1))   // vague: proves nothing
        assertEquals(100, LocPace.step(100, hasPrev = true, same = true, screenWasOn = false, acc = 20))
    }

    @Test fun slowOnlyWithSensor() {
        assertFalse(LocPace.slow(5, motionArmed = false))
        assertFalse(LocPace.slow(1, motionArmed = true))
        assertTrue(LocPace.slow(2, motionArmed = true))
        assertEquals(5 * min, LocPace.trackEveryMs(5, false))
        assertEquals(15 * min, LocPace.trackEveryMs(5, true))
        assertEquals(30 * min, LocPace.trackEveryMs(30, true))   // never faster than the parent's own step
    }

    @Test fun locateDue() {
        // not yet 15 min since the last one
        assertFalse(LocPace.locateDue(10 * min, 60 * min, 20, 5, false))
        // history fixes keep the last location fresh and fine: skipped
        assertFalse(LocPace.locateDue(20 * min, 4 * min, 30, 5, false))
        // history fix is vague (cell tower) or unknown: do the real one
        assertTrue(LocPace.locateDue(20 * min, 4 * min, 400, 5, false))
        assertTrue(LocPace.locateDue(20 * min, 4 * min, 0, 5, false))
        // history fixes stopped coming (no fix for > 2 steps)
        assertTrue(LocPace.locateDue(20 * min, 12 * min, 30, 5, false))
        // history off: always the 15-min refresh
        assertTrue(LocPace.locateDue(20 * min, 1 * min, 30, 0, false))
        // still phone: once an hour, and a fresh history fix (15-min step) still covers it
        assertFalse(LocPace.locateDue(40 * min, 50 * min, 30, 0, true))
        assertTrue(LocPace.locateDue(61 * min, 50 * min, 30, 0, true))
        assertFalse(LocPace.locateDue(61 * min, 20 * min, 30, 5, true))
    }
}
