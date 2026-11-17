package dev.kidguard

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ChatLogicTest {
    @Test fun addKeepsOrderAndSides() {
        var a = JSONArray()
        a = ChatLogic.add(a, "p", "Mẹ", "Về ăn cơm", 1, "a")
        a = ChatLogic.add(a, "k", "ignored", "Dạ", 2, "b")
        assertEquals(2, a.length())
        assertEquals("Mẹ", a.getJSONObject(0).getString("who"))
        assertEquals("", a.getJSONObject(1).getString("who"))   // a child message has no name
        assertEquals("k", a.getJSONObject(1).getString("from"))
    }

    @Test fun emptyBadSideAndRepeatedIdAreIgnored() {
        val a = ChatLogic.add(JSONArray(), "p", "", "hi", 1, "a")
        assertSame(a, ChatLogic.add(a, "p", "", "   ", 2, "b"))
        assertSame(a, ChatLogic.add(a, "x", "", "hi", 2, "b"))
        assertSame(a, ChatLogic.add(a, "k", "", "again", 3, "a"))   // same id (a re-delivered command)
    }

    @Test fun keepsLast100AndTailIsLast30() {
        var a = JSONArray()
        for (i in 1..130) a = ChatLogic.add(a, "k", "", "m$i", i.toLong(), "id$i")
        assertEquals(ChatLogic.KEEP, a.length())
        assertEquals("m31", a.getJSONObject(0).getString("text"))
        val t = ChatLogic.tail(a)
        assertEquals(ChatLogic.STATE, t.length())
        assertEquals("m130", t.getJSONObject(29).getString("text"))
        assertEquals(3, ChatLogic.tail(ChatLogic.tail(a, 3), 30).length())
    }

    @Test fun cleanLimitsAndStripsControls() {
        assertEquals("a\nb", ChatLogic.clean("  a\u0000\nb \u0007"))
        assertEquals(ChatLogic.MAX_TEXT, ChatLogic.clean("x".repeat(900)).length)
        assertEquals("", ChatLogic.clean(null))
        assertEquals("Ba", ChatLogic.who(" <B>a "))
    }

    @Test fun pageWalksBackwardsInPages() {
        var a = JSONArray()
        for (i in 1..100) a = ChatLogic.add(a, "k", "", "m$i", i.toLong(), "id$i")
        val p1 = ChatLogic.page(a, 0, 30)
        assertEquals(30, p1.getJSONArray("chat").length())
        assertEquals("m71", p1.getJSONArray("chat").getJSONObject(0).getString("text"))   // oldest first inside a page
        assertEquals(true, p1.getBoolean("more"))
        val p2 = ChatLogic.page(a, 71, 30)   // before = t of the oldest one already shown
        assertEquals("m41", p2.getJSONArray("chat").getJSONObject(0).getString("text"))
        assertEquals("m70", p2.getJSONArray("chat").getJSONObject(29).getString("text"))
        val last = ChatLogic.page(a, 11, 30)
        assertEquals(10, last.getJSONArray("chat").length())
        assertEquals(false, last.getBoolean("more"))
        assertEquals(0, ChatLogic.page(JSONArray(), 0, 30).getJSONArray("chat").length())
    }

    @Test fun countAndUnread() {
        assertEquals(30, ChatLogic.count(0)); assertEquals(50, ChatLogic.count(999)); assertEquals(7, ChatLogic.count(7))
        assertEquals(false, ChatLogic.unread(0, 0))      // no parent message ever
        assertEquals(true, ChatLogic.unread(10, 5))
        assertEquals(false, ChatLogic.unread(10, 10))    // read exactly then
    }
}
