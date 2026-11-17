package dev.kidguard

import org.junit.Assert.*
import org.junit.Test

class SmsLogicTest {
    private fun r(id: Long, t: Long, text: String, out: Boolean = false) = SmsRow(id, t, "0901", text, out)

    @Test fun pageCapsAndSaysMore() {
        val rows = (1L..4L).map { r(it, 100 - it, "m$it") }
        val p = SmsLogic.page(rows, 3, true, true)
        assertEquals(3, p.getJSONArray("sms").length()); assertTrue(p.getBoolean("more"))
        assertFalse(SmsLogic.page(rows.take(3), 3, true, true).getBoolean("more"))
    }

    @Test fun offOrNoPermissionIsEmptyButSaysWhy() {
        val p = SmsLogic.page(emptyList(), 30, true, false)
        assertTrue(p.getBoolean("on")); assertFalse(p.getBoolean("access")); assertEquals(0, p.getJSONArray("sms").length())
    }

    @Test fun countDefaultsAndCaps() { assertEquals(30, SmsLogic.count(0)); assertEquals(50, SmsLogic.count(900)); assertEquals(7, SmsLogic.count(7)) }

    @Test fun hitsOnlyNewIncomingWithAWord() {
        val rows = listOf(r(1, 50, "chet roi"), r(2, 150, "xin chao"), r(3, 160, "ban ma tuy"), r(4, 170, "chet", out = true))
        val h = SmsLogic.hits(rows, 100, listOf("chết", "ma túy"))
        assertEquals(listOf(3L), h.map { it.first.id }); assertEquals("ma túy", h.single().second)
    }

    @Test fun longTextIsCut() {
        val p = SmsLogic.page(listOf(r(1, 1, "x".repeat(5000))), 5, true, true)
        assertEquals(SmsLogic.MAX_TEXT, p.getJSONArray("sms").getJSONObject(0).getString("text").length)
    }
}
