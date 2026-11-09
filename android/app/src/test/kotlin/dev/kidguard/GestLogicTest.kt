package dev.kidguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GestLogicTest {
    @Test fun swipeDirections() {
        assertEquals(GestLogic.Dir.UP, GestLogic.swipe(2f, -20f, 8f))
        assertNull(GestLogic.swipe(0f, -6f, 8f))             // under slop
        assertNull(GestLogic.swipe(20f, -22f, 8f))           // too diagonal
        assertNull(GestLogic.swipe(0f, 12f, 8f))             // down needs 2x slop
        assertEquals(GestLogic.Dir.DOWN, GestLogic.swipe(3f, 17f, 8f))
    }

    @Test fun doubleTap() {
        val t = GestLogic.TapTracker(8f)
        t.down(1000, 100f, 100f); assertFalse(t.up(1060, 100f, 100f))
        t.down(1200, 104f, 98f); assertTrue(t.up(1250, 104f, 98f))
        t.down(1300, 104f, 98f); assertFalse(t.up(1340, 104f, 98f))   // consumed: a third tap starts over
    }

    @Test fun doubleTapRejects() {
        val t = GestLogic.TapTracker(8f)
        t.down(0, 0f, 0f); t.up(50, 0f, 0f)
        t.down(500, 0f, 0f); assertFalse(t.up(540, 0f, 0f))          // too slow
        val u = GestLogic.TapTracker(8f)
        u.down(0, 0f, 0f); u.up(50, 0f, 0f)
        u.down(200, 200f, 0f); assertFalse(u.up(240, 200f, 0f))      // too far
        val v = GestLogic.TapTracker(8f)
        v.down(0, 0f, 0f); assertFalse(v.up(600, 0f, 0f))            // long press is not a tap
        v.down(700, 0f, 0f); assertFalse(v.up(740, 0f, 0f))
        val w = GestLogic.TapTracker(8f)
        w.down(0, 0f, 0f); w.up(50, 0f, 0f)
        w.down(150, 0f, 0f); assertFalse(w.up(260, 40f, 0f))         // second tap dragged
    }

    @Test fun cleanActions() {
        assertEquals(GestLogic.NONE, GestLogic.clean(GestLogic.DBL, null, "", true))
        assertEquals(GestLogic.DEFAULT, GestLogic.clean(GestLogic.UP, "bogus", "", true))
        assertEquals(GestLogic.NONE, GestLogic.clean(GestLogic.DBL, GestLogic.DEFAULT, "", true))
        assertEquals(GestLogic.DEFAULT, GestLogic.clean(GestLogic.DOWN, GestLogic.LOCK, "", false))
        assertEquals(GestLogic.LOCK, GestLogic.clean(GestLogic.DBL, GestLogic.LOCK, "", true))
        assertEquals(GestLogic.NONE, GestLogic.clean(GestLogic.DBL, GestLogic.APP, "", true))
        assertEquals(GestLogic.APP, GestLogic.clean(GestLogic.DBL, GestLogic.APP, "a.b", true))
    }
}
