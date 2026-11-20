package dev.kidguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotifSyncLogicTest {
    @Test fun countDefaultsAndCaps() {
        assertEquals(30, NotifSyncLogic.count(0))
        assertEquals(30, NotifSyncLogic.count(-4))
        assertEquals(12, NotifSyncLogic.count(12))
        assertEquals(50, NotifSyncLogic.count(9999))
    }

    @Test fun sendDecision() {
        val all = NotifWatch(on = true)
        assertTrue(NotifFeedLogic.send(all, null, "a"))
        assertFalse(NotifFeedLogic.send(NotifWatch(), "w", "a"))                          // off
        assertFalse(NotifFeedLogic.send(all.copy(kwOnly = true), null, "a"))              // keywords only, no hit
        assertTrue(NotifFeedLogic.send(all.copy(kwOnly = true), "w", "a"))
        assertFalse(NotifFeedLogic.send(all.copy(apps = "except", list = listOf("a")), "w", "a"))
        assertTrue(NotifFeedLogic.send(all.copy(apps = "only", list = listOf("a")), null, "a"))
    }

    @Test fun feedIdsSortByTimeAndDifferPerRow() {
        val a = NotifFeedLogic.feedId(1000, 7); val b = NotifFeedLogic.feedId(1000, 8); val c = NotifFeedLogic.feedId(1001, 2)
        assertTrue(a < b && b < c)   // one ms: row order; later time: after, whatever the row id
        assertTrue(NotifFeedLogic.feedId(1000, 7) != NotifFeedLogic.feedId(1500, 7))   // a thread updated in place = a new item
    }

    @Test fun keywordsIgnoreCaseAndAccents() {
        val w = listOf("chết", "Ma tuý")
        assertEquals("chết", KeywordLogic.hit(w, listOf("Mình CHET mất")))
        assertEquals("Ma tuý", KeywordLogic.hit(w, listOf("", "bán ma túy")))
        assertNull(KeywordLogic.hit(w, listOf("xin chào")))
        assertNull(KeywordLogic.hit(emptyList(), listOf("chết")))
        assertEquals("đi", KeywordLogic.hit(listOf("đi"), listOf("DI choi")))
    }

    @Test fun cleanKeepsLimits() {
        assertEquals(listOf("a", "b c"), KeywordLogic.clean(listOf(" a ", "A", "", "b   c")))
        assertEquals(KeywordLogic.MAX_WORDS, KeywordLogic.clean((1..100).map { "w$it" }).size)
        assertEquals(KeywordLogic.MAX_LEN, KeywordLogic.clean(listOf("x".repeat(100))).single().length)
    }

    @Test fun snippetStaysShortAroundTheWord() {
        val t = "a".repeat(200) + " chet roi " + "b".repeat(200)
        val s = KeywordLogic.snippet(t, "chet", 80)
        assertTrue(s.length <= 81 && "chet" in s)
        assertEquals("hi", KeywordLogic.snippet("hi", "x"))
    }

    @Test fun throttleLimitsPerKeyAndPerHour() {
        val t = WordThrottle(perKeyMs = 1000, perHour = 3)
        assertTrue(t.allow("a", 0)); assertFalse(t.allow("a", 500)); assertTrue(t.allow("a", 1500))
        assertTrue(t.allow("b", 1600)); assertFalse(t.allow("c", 1700))      // 3 in the hour
        assertTrue(t.allow("c", 3_700_000))
    }

    @Test fun nudgeGateLeadingAndTrailing() {
        val g = NudgeGate(30_000)
        assertEquals("first fires at once", 0L, g.ask(1_000_000))
        assertEquals("burst: one trailing fire at the gap", 25_000L, g.ask(1_005_000))
        assertEquals("already scheduled", -1L, g.ask(1_010_000))
        g.fired(1_030_000)
        assertEquals("right after the trailing fire: waits again", 30_000L, g.ask(1_030_000))
        g.fired(1_060_000)
        assertEquals("quiet for a gap: fires at once", 0L, g.ask(1_100_000))
        assertEquals("clock went back: fires", 0L, g.ask(500_000))
    }
}
