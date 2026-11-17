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

    @Test fun idsIncreaseEvenInOneMillisecond() {
        val a = NotifFeedLogic.nextId(0, 1000); val b = NotifFeedLogic.nextId(a, 1000); val c = NotifFeedLogic.nextId(b, 5)
        assertTrue(a < b && b < c)
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
}
