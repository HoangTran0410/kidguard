package dev.kidguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdminAuthTest {
    @Test fun withPinTheCodeIsTheBackup() {
        val w = AdminAuth.ways(pinSet = true, pinLocked = false, totpSet = true, totpLocked = false)
        assertTrue(w.pin); assertTrue(w.code)
    }

    @Test fun lockedPinIsRescuedByTheCode() {
        val w = AdminAuth.ways(pinSet = true, pinLocked = true, totpSet = true, totpLocked = false)
        assertFalse(w.pin); assertTrue(w.code)
        assertTrue(AdminAuth.ways(true, true, true, true).none)
        assertTrue(AdminAuth.ways(true, true, false, false).none)
    }

    @Test fun noPinKeepsTheCode() {
        val w = AdminAuth.ways(pinSet = false, pinLocked = false, totpSet = true, totpLocked = false)
        assertFalse(w.pin); assertTrue(w.code)
        assertTrue(AdminAuth.ways(false, false, true, true).none)
        assertTrue(AdminAuth.ways(false, false, false, false).none)
    }

    @Test fun lockLeftIsTheShorterOfTheWaysInUse() {
        assertEquals(5_000L, AdminAuth.lockLeft(true, 5_000, true, 9_000))
        assertEquals(5_000L, AdminAuth.lockLeft(true, 5_000, false, 9_000))
        assertEquals(9_000L, AdminAuth.lockLeft(false, 5_000, true, 9_000))
        assertEquals(0L, AdminAuth.lockLeft(false, 5_000, false, 9_000))
    }

    @Test fun lockoutIgnoresWallClockWithinOneBoot() {
        // set at wall 1_000 for 60 s (elapsed deadline 70_000); the child moves the clock a day ahead: still locked
        assertEquals(30_000L, Lockout.left(61_000, 70_000, 7, 7, 1_000 + 86_400_000, 40_000, 60_000))
        assertEquals(0L, Lockout.left(61_000, 70_000, 7, 7, 1_000, 70_001, 60_000))
    }

    @Test fun lockoutAfterRebootUsesCappedWallClock() {
        assertEquals(20_000L, Lockout.left(61_000, 70_000, 7, 8, 41_000, 5, 60_000))
        assertEquals(60_000L, Lockout.left(61_000 + 9_000_000, 70_000, 7, 8, 41_000, 5, 60_000))   // clock set back: never longer than the original lock
        assertEquals(0L, Lockout.left(61_000, 70_000, -1, -1, 99_000, 5, 60_000))                   // boot count unknown: wall clock
    }
}
