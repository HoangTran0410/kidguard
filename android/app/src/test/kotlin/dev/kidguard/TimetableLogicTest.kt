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

    // an old phone's flat list is the morning; afternoon kept apart; free periods keep their place
    @Test fun sessionsAndOldDays() {
        val old = JSONObject("""{"2":["Toán","Văn"]}""")
        assertEquals(listOf("Toán", "Văn"), TimetableLogic.session(old, 2, false))
        assertEquals(emptyList<String>(), TimetableLogic.session(old, 2, true))
        val n = TimetableLogic.withSession(old, 2, true, listOf("", "Thể dục", " "))
        assertEquals(listOf("", "Thể dục"), TimetableLogic.session(n, 2, true))
        assertEquals(listOf("Toán", "Văn", "Thể dục"), TimetableLogic.day(n, 2))
        assertTrue(TimetableLogic.isEmpty(TimetableLogic.withSession(TimetableLogic.withSession(n, 2, false, emptyList()), 2, true, listOf(""))))
        assertEquals(TimetableLogic.day(n, 2), TimetableLogic.day(TimetableLogic.copyDay(n, 2, 3), 3))
    }

    @Test fun bellGenerator() {
        val b = TimetableLogic.gen(420, 45, 5, 5, 2, 20)
        assertEquals(listOf(420 to 465, 470 to 515, 535 to 580, 585 to 630, 635 to 680), b.map { it[0] to it[1] })
        val t = TimetableLogic.withBells(JSONObject(), true, listOf(intArrayOf(800, 840), intArrayOf(760, 795)))
        assertEquals(listOf(760, 800), TimetableLogic.bells(t, true).map { it[0] })
        assertEquals(5, TimetableLogic.bells(t, false).size)   // morning still the default
        assertEquals(1, TimetableLogic.bells(JSONObject("""{"bells":{"am":[[500,400],[480,525],"x"]}}"""), false).size)
    }

    // Toán Văn | ra chơi | Anh ; nghỉ trưa ; (free) Thể dục
    @Test fun timeline() {
        var tt = TimetableLogic.withSession(JSONObject(), 1, false, listOf("Toán", "Văn", "Anh"))
        tt = TimetableLogic.withSession(tt, 1, true, listOf("", "Thể dục"))
        val s = TimetableLogic.slots(tt, 1)
        assertEquals(listOf("CLASS", "CLASS", "RECESS", "CLASS", "LUNCH", "CLASS"), s.map { it.kind.name })
        assertEquals(515 to 535, s[2].start to s[2].end)
        assertEquals(580 to 860, s[4].start to s[4].end)
        val (cur, next) = TimetableLogic.at(s, 520)
        assertEquals(TimetableLogic.Kind.RECESS, cur!!.kind); assertEquals("Anh", next!!.subject)
        assertEquals(null to null, TimetableLogic.at(s, 1200))
        assertEquals(listOf(1, 2, 3, 4, 5, 6), TimetableLogic.weekDays(tt))
        // the break before period 3 from 20 to 30 min: periods 3.. move 10 min later, 1-2 stay
        val b = TimetableLogic.shift(TimetableLogic.DEF_AM, 2, 10)
        assertEquals(listOf(420, 470, 545, 595, 645), b.map { it[0] })
        assertEquals(3, TimetableLogic.rows(tt, listOf(1, 2), false)); assertEquals(2, TimetableLogic.rows(tt, listOf(1), true))
    }
}
