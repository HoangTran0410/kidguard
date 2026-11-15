package dev.kidguard

import dev.kidguard.NavGestLogic.Act
import dev.kidguard.NavGestLogic.Edge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavGestLogicTest {
    private val min = 100f

    @Test fun hideNeedsEverything() {
        assertTrue(NavGestLogic.hide(on = true, permission = true, accUp = true, accPaused = false, bankFront = false, released = false))
        assertFalse(NavGestLogic.hide(false, true, true, false, false, false))
        assertFalse(NavGestLogic.hide(true, false, true, false, false, false))
        assertFalse(NavGestLogic.hide(true, true, false, false, false, false))   // accessibility not running
        assertFalse(NavGestLogic.hide(true, true, true, true, false, false))     // bank pause
        assertFalse(NavGestLogic.hide(true, true, true, false, true, false))     // bank app in front
        assertFalse(NavGestLogic.hide(true, true, true, false, false, true))     // released
    }

    @Test fun xiaomiNames() {
        assertTrue(NavGestLogic.xiaomi("Xiaomi")); assertTrue(NavGestLogic.xiaomi("POCO")); assertTrue(NavGestLogic.xiaomi("Redmi"))
        assertFalse(NavGestLogic.xiaomi("samsung"))
    }

    @Test fun backFromBothSides() {
        assertEquals(Act.BACK, NavGestLogic.classify(Edge.LEFT, 120f, 10f, 0, min))
        assertEquals(Act.BACK, NavGestLogic.classify(Edge.RIGHT, -120f, -20f, 0, min))
        assertEquals(Act.NONE, NavGestLogic.classify(Edge.LEFT, -120f, 0f, 0, min))    // outward
        assertEquals(Act.NONE, NavGestLogic.classify(Edge.RIGHT, 120f, 0f, 0, min))
        assertEquals(Act.NONE, NavGestLogic.classify(Edge.LEFT, 60f, 0f, 0, min))      // too short
        assertEquals(Act.NONE, NavGestLogic.classify(Edge.LEFT, 110f, 300f, 0, min))   // a vertical scroll
    }

    @Test fun bottomHomeAndRecents() {
        assertEquals(Act.HOME, NavGestLogic.classify(Edge.BOTTOM, 5f, -200f, 20, min))
        assertEquals(Act.RECENTS, NavGestLogic.classify(Edge.BOTTOM, 5f, -200f, NavGestLogic.HOLD_MS, min))
        assertEquals(Act.NONE, NavGestLogic.classify(Edge.BOTTOM, 0f, -50f, 0, min))   // short
        assertEquals(Act.NONE, NavGestLogic.classify(Edge.BOTTOM, 0f, 200f, 0, min))   // downward
        assertEquals(Act.NONE, NavGestLogic.classify(Edge.BOTTOM, 400f, -120f, 0, min)) // sideways
    }

    @Test fun progressArmsAtMinAndDropsWhenPulledBack() {
        assertEquals(0f, NavGestLogic.progress(Edge.LEFT, -30f, 0f, 100f), 0.001f)      // outward
        assertEquals(0.5f, NavGestLogic.progress(Edge.LEFT, 50f, 0f, 100f), 0.001f)
        assertEquals(1.2f, NavGestLogic.progress(Edge.RIGHT, -120f, 0f, 100f), 0.001f)
        assertEquals(1f, NavGestLogic.progress(Edge.BOTTOM, 0f, -100f, 100f), 0.001f)
        // pulled back below the threshold before lifting: nothing happens
        assertEquals(Act.NONE, NavGestLogic.classify(Edge.LEFT, 60f, 0f, 0, 100f))
    }
}
