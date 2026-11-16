package dev.kidguard.parent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLockTest {
    private val t = 1_800_000_000_000L
    @Test fun locksOnOpenAndAfterFiveMinutesAway() {
        assertTrue(AppLockLogic.needed(true, true, unlockedAt = 0L, leftAt = 0L, now = t))            // cold open
        assertFalse(AppLockLogic.needed(true, true, unlockedAt = t, leftAt = 0L, now = t + 3_600_000L)) // in use
        assertFalse(AppLockLogic.needed(true, true, t, leftAt = t + 1000, now = t + 1000 + AppLockLogic.AWAY_MS - 1))
        assertTrue(AppLockLogic.needed(true, true, t, leftAt = t + 1000, now = t + 1000 + AppLockLogic.AWAY_MS))
    }
    @Test fun neverWhenOffOrNoScreenLock() {
        assertFalse(AppLockLogic.needed(false, true, 0L, 0L, t))
        assertFalse(AppLockLogic.needed(true, false, 0L, 0L, t))
    }
}
