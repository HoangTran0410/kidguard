package dev.kidguard

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class NotifStatsTest {
    private fun cal(y: Int, m: Int, d: Int) = Calendar.getInstance().apply { clear(); set(y, m - 1, d) }

    @Test fun daysCrossMonthAndYear() {
        assertEquals(listOf(20261229, 20261230, 20261231, 20270101), NotifStats.days(cal(2027, 1, 1), 4))
        assertEquals(listOf(20261130, 20261201), NotifStats.days(cal(2026, 12, 1), 2))
    }

    @Test fun weekdayMondayFirst() {
        assertEquals(3, NotifStats.weekday(20261112))   // Thursday
        assertEquals(0, NotifStats.weekday(20261109))   // Monday
        assertEquals(6, NotifStats.weekday(20261115))   // Sunday
    }

    @Test fun dailyHeatHours() {
        val cells = listOf(NotifStats.Cell(20261109, 8, 3), NotifStats.Cell(20261109, 20, 2), NotifStats.Cell(20261112, 20, 5), NotifStats.Cell(20261101, 1, 9))
        val days = NotifStats.days(cal(2026, 11, 12), 4)   // 9..12
        assertArrayEquals(intArrayOf(5, 0, 0, 5), NotifStats.daily(cells, days))   // the 1st is outside: dropped
        val h = NotifStats.heat(cells)
        assertEquals(3, h[0][8]); assertEquals(2, h[0][20]); assertEquals(5, h[3][20]); assertEquals(9, h[6][1])   // 1/11/2026 = Sunday
        assertEquals(7, NotifStats.hours(cells)[20])
        assertEquals(1, NotifStats.peak(NotifStats.hours(cells)))   // 9 at 1 h beats 7 at 20 h
        assertEquals(-1, NotifStats.peak(IntArray(24)))
    }

    @Test fun ratesAndChange() {
        assertEquals(-1, NotifStats.openRate(0, 0))
        assertEquals(33, NotifStats.openRate(1, 3))
        assertNull(NotifStats.change(10, 0))
        assertEquals(50, NotifStats.change(15, 10))
        assertEquals(-25, NotifStats.change(30, 40))
    }

    @Test fun coveredStartsAtFirstData() {
        val days = NotifStats.days(cal(2026, 11, 12), 30)
        assertEquals(3, NotifStats.covered(days, listOf(NotifStats.Cell(20261110, 0, 1))))
        assertEquals(30, NotifStats.covered(days, emptyList()))
        assertEquals(30, NotifStats.covered(days, listOf(NotifStats.Cell(20261001, 0, 1))))
    }

    @Test fun noisyNeedsVolumeAndNoOpens() {
        assertTrue(NotifStats.noisy(30, 0, 7))      // ~4 a day, never opened
        assertFalse(NotifStats.noisy(30, 3, 7))     // 10 % opened
        assertFalse(NotifStats.noisy(14, 0, 7))     // 2 a day
        assertFalse(NotifStats.noisy(0, 0, 7))
    }

    @Test fun summary() {
        val cells = listOf(NotifStats.Cell(20261111, 20, 6), NotifStats.Cell(20261112, 9, 4))
        val r = NotifStats.Raw(cells, 5, mapOf(NotifLogic.R_CLICK to 1, NotifLogic.R_SWIPE to 9), emptyList(), emptyList(), "")
        val s = NotifStats.summary(r, NotifStats.days(cal(2026, 11, 12), 7))
        assertEquals(10, s.total); assertEquals(5f, s.perDay)   // 2 days of data
        assertEquals(100, s.change); assertEquals(20, s.peakHour); assertEquals(2, s.peakDay); assertEquals(10, s.openRate)
    }

    @Test fun rangesFollowKeepDays() {
        assertEquals(listOf(7), NotifStats.ranges(7))
        assertEquals(listOf(7, 30), NotifStats.ranges(30))
        assertEquals(listOf(7, 30, 90), NotifStats.ranges(90))
    }

    @Test fun channelNameRoundTrips() {
        val e = NotifEntry("k", 1, "p", "A", "t", "x", channel = "c1", channelName = "Tin nhắn")
        assertEquals("Tin nhắn", NotifLogic.fromObj(NotifLogic.toObj(e)).channelName)
        assertEquals("", NotifLogic.fromObj(NotifLogic.toObj(e.copy(channelName = ""))).channelName)
    }
}
