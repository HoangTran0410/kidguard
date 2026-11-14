package dev.kidguard.parent

import org.junit.Assert.*
import org.junit.Test
import dev.kidguard.parent.WidgetLogic as W

class WidgetLogicTest {
    @Test fun statusKinds() {
        assertEquals(W.Status(W.Kind.UNKNOWN), W.status(null))
        assertEquals(W.Status(W.Kind.LEFT, 45), W.status(W.Snap(limitMin = 120, leftMs = 45 * 60_000L)))
        assertEquals(W.Status(W.Kind.LEFT, 1), W.status(W.Snap(limitMin = 120, leftMs = 1)))   // rounds up
        assertEquals(W.Status(W.Kind.LEFT, 60), W.status(W.Snap(why = "pass", limitMin = 120, leftMs = 3_600_000)))
        assertEquals(W.Status(W.Kind.NO_LIMIT), W.status(W.Snap(limitMin = 0, leftMs = 0)))
        assertEquals(W.Status(W.Kind.LOCKED), W.status(W.Snap(limitMin = 60, leftMs = 0)))
        assertEquals(W.Status(W.Kind.LOCKED), W.status(W.Snap(why = "daily", limitMin = 60, leftMs = 0)))
        assertEquals(W.Status(W.Kind.LOCKED), W.status(W.Snap(why = "lost")))
        assertEquals(W.Status(W.Kind.PAUSED), W.status(W.Snap(why = "paused", limitMin = 60, leftMs = 1000)))
        assertEquals(W.Status(W.Kind.BEDTIME), W.status(W.Snap(why = "bedtime", limitMin = 0)))
        assertEquals(W.Status(W.Kind.UNKNOWN), W.status(W.Snap(limitMin = 60)))   // older phone: no leftMs
    }

    @Test fun duration() {
        assertEquals("0m", W.duration(-3)); assertEquals("45m", W.duration(45)); assertEquals("1h 20m", W.duration(80)); assertEquals("2h", W.duration(120))
    }

    @Test fun ago() {
        val now = 10_000_000_000L
        assertEquals(W.Ago(W.Unit.NEVER), W.ago(0, now))
        assertEquals(W.Ago(W.Unit.NOW), W.ago(now - 30_000, now))
        assertEquals(W.Ago(W.Unit.NOW), W.ago(now + 5_000, now))   // clock skew
        assertEquals(W.Ago(W.Unit.MIN, 59), W.ago(now - 59 * 60_000L, now))
        assertEquals(W.Ago(W.Unit.HOUR, 5), W.ago(now - 5 * 3_600_000L - 1, now))
        assertEquals(W.Ago(W.Unit.DAY, 3), W.ago(now - 3 * 86_400_000L, now))
    }

    @Test fun snapRoundTripKeepsOnlyKnownKids() {
        val s = W.Snap("paused", 90, 1234, 5, 6)
        val json = W.toJsonAll(mapOf("a" to s, "gone" to s), setOf("a"))
        assertEquals(mapOf("a" to s), W.parseAll(json))
        assertTrue(W.parseAll("not json").isEmpty())
    }

    @Test fun feedback() {
        val m = mapOf("a" to W.Fb("sending", 100), "b" to W.Fb("sent", 50))
        assertEquals(mapOf("a" to W.Fb("sending", 100)), W.fbParse(W.fbJson(m, 60)))   // expired entries dropped
        assertTrue(W.busy(W.Fb("sending", 100), 99)); assertFalse(W.busy(W.Fb("sending", 100), 100)); assertFalse(W.busy(W.Fb("sent", 100), 1)); assertFalse(W.busy(null, 1))
    }
}
