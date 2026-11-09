package dev.kidguard

import dev.kidguard.FocusLogic.Mode
import dev.kidguard.FocusLogic.Slot
import org.junit.Assert.*
import org.junit.Test

class FocusLogicTest {
    private val min = 60_000L

    @Test fun sessionRoundTripAndActive() {
        val s = FocusLogic.begin(1_000_000L, 25).withDnd(1, true)
        assertEquals(s, FocusLogic.parse(s.toPref()))
        assertTrue(FocusLogic.active(s, 1_000_000L + 24 * min))
        assertFalse(FocusLogic.active(s, s.end))
        assertFalse(FocusLogic.active(null, 5))
    }

    @Test fun clockSetBackEndsSession() {
        val s = FocusLogic.begin(10_000_000L, 60)
        assertFalse(FocusLogic.active(s, 10_000_000L - 5 * 60 * min))   // end is now > MAX_MIN ahead
    }

    @Test fun parseRejectsGarbage() {
        assertNull(FocusLogic.parse(null)); assertNull(FocusLogic.parse("1|2")); assertNull(FocusLogic.parse("5|3|0|0")); assertNull(FocusLogic.parse("a|b|c|d"))
    }

    @Test fun leftRoundsUp() {
        val s = FocusLogic.Session(0, 90_000)
        assertEquals(2, FocusLogic.leftMin(s, 0)); assertEquals(1, FocusLogic.leftMin(s, 31_000)); assertEquals(0, FocusLogic.leftMin(s, 200_000))
    }

    @Test fun dndRestore() {
        val on = FocusLogic.Session(0, 1, prev = FocusLogic.ALL, dndSet = true)
        assertEquals(FocusLogic.ALL, FocusLogic.restore(on, FocusLogic.PRIORITY))
        assertEquals(FocusLogic.ALL, FocusLogic.restore(on, null))
        assertNull(FocusLogic.restore(on, 4))                                   // changed by someone else: leave
        assertNull(FocusLogic.restore(on.copy(dndSet = false), FocusLogic.PRIORITY))   // never touched
    }

    @Test fun slots() {
        val l = FocusLogic.slotsParse("31:1140:1260,96:600:720,bad,0:1:2,31:900:800")   // Mon-Fri 19-21, Sat+Sun 10-12; rest invalid
        assertEquals(2, l.size)
        assertEquals(l, FocusLogic.slotsParse(FocusLogic.slotsPref(l)))
        assertTrue(FocusLogic.scheduled(l, 1, 1140)); assertFalse(FocusLogic.scheduled(l, 1, 1260)); assertFalse(FocusLogic.scheduled(l, 6, 1200))
        assertTrue(FocusLogic.scheduled(l, 7, 650))
        assertEquals(Slot(31, 1140, 1260), FocusLogic.next(l, 3, 600))
        assertNull(FocusLogic.next(l, 3, 1200))
    }

    @Test fun modePriorityAndVisibility() {
        val s = FocusLogic.begin(0, 25); val sl = listOf(Slot(FocusLogic.ALL_DAYS, 0, 1440))
        assertEquals(Mode.SESSION, FocusLogic.mode(s, sl, 1, 1, 5))
        assertEquals(Mode.SCHEDULE, FocusLogic.mode(null, sl, 1, 1, 5))
        assertEquals(Mode.OFF, FocusLogic.mode(null, emptyList(), 1, 1, 5))
        val a = FocusLogic.allowed(setOf("math"), listOf("dialer", null))
        assertTrue(FocusLogic.visible(Mode.SESSION, "math", true, a)); assertTrue(FocusLogic.visible(Mode.SESSION, "dialer", true, a))
        assertFalse(FocusLogic.visible(Mode.SESSION, "game", true, a))
        assertTrue(FocusLogic.visible(Mode.OFF, "game", true, a))
        assertFalse(FocusLogic.visible(Mode.SESSION, "math", false, a))      // a parent-hidden app never comes back
        assertFalse(FocusLogic.visible(Mode.OFF, "x", false, a))
    }

    @Test fun pin() {
        assertTrue(FocusLogic.needsPin(false, true)); assertFalse(FocusLogic.needsPin(false, false)); assertFalse(FocusLogic.needsPin(true, true))
    }
}
