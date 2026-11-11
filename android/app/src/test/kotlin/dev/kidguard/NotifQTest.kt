package dev.kidguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class NotifQTest {
    @Test fun kinds() {
        assertEquals(NotifQ.K_CALL, NotifQ.kind("missed_call", false))
        assertEquals(NotifQ.K_MSG, NotifQ.kind("email", false))
        assertEquals(NotifQ.K_MSG, NotifQ.kind("", true))          // MessagingStyle without a category
        assertEquals(NotifQ.K_OTHER, NotifQ.kind("", false))
        assertEquals(NotifQ.K_SYS, NotifQ.kind("progress", false))
        assertEquals(NotifQ.K_TIME, NotifQ.kind("alarm", false))
    }

    @Test fun foldDropsAccents() {
        assertEquals("tin nhan dang di", NotifQ.fold("Tin nhắn ĐANG đi"))
        assertEquals("%tin nhan%", NotifQ.likeArg(" Tin Nhắn "))
        assertEquals("%50\\%\\_off%", NotifQ.likeArg("50%_off"))
    }

    @Test fun dayAndMinute() {
        val c = Calendar.getInstance().apply { clear(); set(2026, 10, 11, 22, 35) }
        assertEquals(20261111, NotifQ.day(c.timeInMillis))
        assertEquals(22 * 60 + 35, NotifQ.minute(c.timeInMillis))
    }

    @Test fun hoursWrapMidnight() {
        assertTrue(NotifQ.inHours(23 * 60, 22 * 60, 6 * 60))
        assertTrue(NotifQ.inHours(5 * 60, 22 * 60, 6 * 60))
        assertFalse(NotifQ.inHours(12 * 60, 22 * 60, 6 * 60))
        assertTrue(NotifQ.inHours(6 * 60, 6 * 60, 12 * 60)); assertFalse(NotifQ.inHours(12 * 60, 6 * 60, 12 * 60))
    }

    @Test fun whereClause() {
        assertEquals("1", NotifQ.where(NotifFilter()).first)
        val (w, a) = NotifQ.where(NotifFilter(d0 = 20261101, d1 = 20261105, pkgs = setOf("a.b"), kinds = setOf("msg"), m0 = 22 * 60, m1 = 6 * 60, q = "hi"))
        assertEquals("d >= ? AND d <= ? AND p IN (?) AND kd IN (?) AND (m >= ? OR m < ?) AND f LIKE ? ESCAPE '\\'", w)
        assertEquals(listOf("20261101", "20261105", "a.b", "msg", "1320", "360", "%hi%"), a.toList())
        val (w2, a2) = NotifQ.where(NotifFilter(pkgs = setOf("a.b"), m0 = 60, m1 = 120), skipPkgs = true)
        assertEquals("m >= ? AND m < ?", w2); assertEquals(listOf("60", "120"), a2.toList())
    }

    @Test fun calendarTaps() {
        assertEquals(5 to 5, NotifQ.tap(0, 0, 5))
        assertEquals(0 to 0, NotifQ.tap(5, 5, 5))
        assertEquals(3 to 5, NotifQ.tap(5, 5, 3))
        assertEquals(9 to 9, NotifQ.tap(3, 5, 9))
    }

    @Test fun shades() {
        assertEquals(0, NotifQ.shade(0, 10)); assertEquals(1, NotifQ.shade(1, 100))
        assertEquals(4, NotifQ.shade(100, 100)); assertEquals(2, NotifQ.shade(30, 100))
    }
}
