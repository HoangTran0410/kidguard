package dev.kidguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DegradedLogicTest {
    @Test fun firstIsDue() = assertTrue(DegradedLogic.due(null, 1000))

    @Test fun throttledWithinGap() {
        assertFalse(DegradedLogic.due(1000, 1000 + DegradedLogic.GAP_MS - 1))
        assertTrue(DegradedLogic.due(1000, 1000 + DegradedLogic.GAP_MS))
    }

    @Test fun clockGoingBackIsDue() = assertTrue(DegradedLogic.due(5000, 1000))

    @Test fun idStableAndInRange() {
        assertEquals(DegradedLogic.notifId("loc"), DegradedLogic.notifId("loc"))
        for (k in listOf("loc", "bank.vn.com.x", "", "￿ zz")) assertTrue(DegradedLogic.notifId(k) in 6000..6899)
    }

    /** Incident class 2026-11-20: a condition stuck true (permission denied) re-alerted every 30 min forever. */
    @Test fun toldOncePerStateChange() {
        assertTrue(DegradedLogic.tell(known = false, last = null, now = 1000))
        // still degraded: never again, however long (a whole day of ticks)
        for (m in 1..1440) assertFalse(DegradedLogic.tell(known = true, last = 1000, now = 1000 + m * 60_000L))
        // cleared and back within the gap: quiet (flapping); after the gap: told again
        assertFalse(DegradedLogic.tell(known = false, last = 1000, now = 1000 + 60_000))
        assertTrue(DegradedLogic.tell(known = false, last = 1000, now = 1000 + DegradedLogic.GAP_MS))
    }

    /** Flapped key: skipped by the throttle, so it is NOT stored (known stays false) and the next report after the gap tells it. */
    @Test fun flappedKeyIsToldLater() {
        val stored = HashSet<String>(); val last = HashMap<String, Long>()
        fun report(k: String, now: Long): Boolean {
            val known = k in stored
            if (!DegradedLogic.tell(known, last[k], now)) return false
            stored += k; last[k] = now; return true
        }
        assertTrue(report("loc", 0)); stored -= "loc"        // told, then cleared
        assertFalse(report("loc", 60_000))                   // flap within the gap: quiet, not marked told
        assertFalse("loc" in stored)
        assertTrue(report("loc", DegradedLogic.GAP_MS + 1))  // still broken after the gap: told
    }
}
