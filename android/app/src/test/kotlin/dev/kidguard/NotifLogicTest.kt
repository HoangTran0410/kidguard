package dev.kidguard

import org.junit.Assert.assertEquals
import org.junit.Test

class NotifLogicTest {
    private fun e(k: String, t: Long, title: String = "a", text: String = "b") = NotifEntry(k, t, "p", "App", title, text)
    private val day = NotifLogic.DAY_MS

    @Test fun addAppends() = assertEquals(listOf("1", "2"), NotifLogic.add(listOf(e("1", 1)), e("2", 2)).map { it.key })

    @Test fun sameKeyReplaces() {
        val l = NotifLogic.add(listOf(e("1", 1), e("2", 2)), e("1", 3, text = "new"))
        assertEquals(listOf("2", "1"), l.map { it.key }); assertEquals("new", l.last().text)
    }

    @Test fun exactDuplicateIgnored() {
        val l = listOf(e("1", 1))
        assertEquals(l, NotifLogic.add(l, e("1", 5)))
    }

    @Test fun emptyIgnored() = assertEquals(emptyList<NotifEntry>(), NotifLogic.add(emptyList(), e("1", 1, "", " ")))

    @Test fun trimsAgeAndCount() {
        val now = 10 * day
        assertEquals(listOf("new"), NotifLogic.trim(listOf(e("old", now - 8 * day), e("new", now - day)), now).map { it.key })
        val many = (1..350).map { e("k$it", now - 1000 + it) }
        val t = NotifLogic.trim(many, now)
        assertEquals(300, t.size); assertEquals("k350", t.last().key); assertEquals("k51", t.first().key)
    }

    @Test fun daysAgo() {
        val t0 = 100 * day
        assertEquals(0, NotifLogic.daysAgo(t0 + 5, t0)); assertEquals(1, NotifLogic.daysAgo(t0 - 1, t0))
        assertEquals(1, NotifLogic.daysAgo(t0 - day, t0)); assertEquals(2, NotifLogic.daysAgo(t0 - day - 1, t0))
    }

    @Test fun jsonRoundTrip() {
        val l = listOf(e("1", 1, "x\"y", "line\nbreak"), e("2", 2))
        assertEquals(l, NotifLogic.fromJson(NotifLogic.toJson(l)))
        assertEquals(emptyList<NotifEntry>(), NotifLogic.fromJson("garbage"))
    }
}
