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
}
