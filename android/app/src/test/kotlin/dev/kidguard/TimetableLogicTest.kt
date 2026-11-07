package dev.kidguard

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimetableLogicTest {
    @Test fun cleanTrimsCapsAndLimits() {
        val l = TimetableLogic.clean(listOf("  Toán ", "", "x".repeat(50)) + (1..12).map { "m$it" })
        assertEquals("Toán", l[0])
        assertEquals(30, l[1].length)
        assertEquals(10, l.size)
    }
    @Test fun withReplacesAndDropsEmptyDay() {
        val a = TimetableLogic.with(JSONObject(), 2, listOf("Văn", "Anh"))
        assertEquals(listOf("Văn", "Anh"), TimetableLogic.day(a, 2))
        assertFalse(TimetableLogic.isEmpty(a))
        val b = TimetableLogic.with(a, 2, listOf(" "))
        assertTrue(TimetableLogic.isEmpty(b))
        assertFalse(b.has("2"))
    }
    @Test fun tomorrowWrapsAndDistinct() {
        assertEquals(1, TimetableLogic.tomorrow(7)); assertEquals(6, TimetableLogic.tomorrow(5))
        assertEquals(listOf("Toán", "Văn"), TimetableLogic.distinct(listOf("Toán", "toán", "Văn")))
        assertTrue(TimetableLogic.eveningFirst(17 * 60)); assertFalse(TimetableLogic.eveningFirst(17 * 60 - 1))
    }
    @Test fun badJsonIsEmpty() {
        assertEquals(emptyList<String>(), TimetableLogic.day(JSONObject("""{"1":"oops"}"""), 1))
    }
}
