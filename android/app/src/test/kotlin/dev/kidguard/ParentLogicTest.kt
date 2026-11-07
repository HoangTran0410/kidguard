package dev.kidguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParentLogicTest {
    @Test fun pairNeedsPinWhenNoneSet() {
        assertEquals(PairPin.Check.Bad("pin required"), PairPin.decide(pinSet = false, newPin = ""))
        assertEquals(PairPin.Check.Bad("bad pin"), PairPin.decide(false, "12"))
        assertEquals(PairPin.Check.Bad("bad pin"), PairPin.decide(false, "123456789"))
        assertEquals(PairPin.Check.Bad("bad pin"), PairPin.decide(false, "12a4"))
        assertEquals(PairPin.Check.SetPin("1234"), PairPin.decide(false, "1234"))
        assertEquals(PairPin.Check.SetPin("12345678"), PairPin.decide(false, "12345678"))
    }

    @Test fun pairWithPinSetUsesIt() {
        assertEquals(PairPin.Check.UsePin, PairPin.decide(pinSet = true, newPin = ""))
        // a new PIN can't replace the old one at pairing (the old one must be known)
        assertEquals(PairPin.Check.UsePin, PairPin.decide(true, "9999"))
    }

    @Test fun legacyState() {
        assertTrue(PairPin.legacyNoPin(everPaired = true, pinSet = false))
        assertFalse(PairPin.legacyNoPin(everPaired = true, pinSet = true))
        assertFalse(PairPin.legacyNoPin(everPaired = false, pinSet = false))   // fresh phone: the pairing sets it
        assertFalse(PairPin.adminAvailable(pinSet = false, totpOn = false))   // legacy without TOTP: no on-device admin
        assertTrue(PairPin.adminAvailable(pinSet = false, totpOn = true))
        assertTrue(PairPin.adminAvailable(pinSet = true, totpOn = false))
    }

    @Test fun pendingPairExpiresAndIsTakenOnce() {
        val p = PendingPairs<String>(ttlMs = 1000, max = 2)
        p.add("a", "A", 0)
        assertNull(p.take("a", 1001))          // page closed mid-way: nothing to commit
        p.add("a", "A", 2000)
        assertEquals("A", p.take("a", 2500))
        assertNull(p.take("a", 2600))          // used once
        p.add("x", "1", 0); p.add("y", "2", 0); p.add("z", "3", 0)
        assertEquals(2, p.size(0))             // oldest dropped
        assertNull(p.take("x", 0))
        p.clear(); assertEquals(0, p.size(0))
    }

    @Test fun sessionIdleExpiry() {
        val s = AdminSession(idleMs = 5 * 60_000L)
        assertFalse(s.valid("h", 0))
        s.open("h", 1000)
        assertTrue(s.valid("h", 1000))
        assertFalse(s.valid("other", 1000))
        assertFalse(s.valid(null, 1000))
        assertTrue(s.valid("h", 1000 + 5 * 60_000L))
        s.touch(200_000)
        assertTrue(s.valid("h", 200_000 + 5 * 60_000L))       // a touch extends it
        assertFalse(s.valid("h", 200_001 + 5 * 60_000L))      // idle too long
        assertFalse(s.active(200_000))                        // revoked for good
        s.touch(200_000); assertFalse(s.active(200_000))       // a touch does not revive it
    }

    @Test fun sessionRevokeAndClockBack() {
        val s = AdminSession(1000)
        s.open("h", 500); s.revoke(); assertFalse(s.valid("h", 500))
        s.open("h", 500); assertFalse(s.valid("h", 100))     // clock went back: end it
        s.open("h", 0); assertEquals(400, s.leftMs(600))
    }

    @Test fun holdCountdown() {
        assertEquals(5, Hold.secondsLeft(0, 5000))
        assertEquals(5, Hold.secondsLeft(1, 5000))
        assertEquals(3, Hold.secondsLeft(2000, 5000))
        assertEquals(1, Hold.secondsLeft(4999, 5000))
        assertEquals(0, Hold.secondsLeft(5000, 5000))
        assertEquals(10, Hold.secondsLeft(-5, 10_000))
        assertEquals(0.5f, Hold.fraction(2500, 5000), 0.001f)
        assertEquals(1f, Hold.fraction(9000, 5000), 0.001f)
    }
}
