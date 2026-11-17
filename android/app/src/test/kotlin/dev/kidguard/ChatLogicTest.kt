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
}
