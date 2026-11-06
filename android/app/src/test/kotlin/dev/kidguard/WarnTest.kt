package dev.kidguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WarnTest {
    private val day = "2026-11-06"   // Friday (ISO 5)

    @Test fun dailyFiveAndOne() {
        val r = Rules(dailyMin = 60)
        assertNull(Warn.due(r, 10 * 60_000L, 600, day, emptySet()))
        assertEquals("Còn 5 phút nữa là hết giờ hôm nay", Warn.due(r, 4 * 60_000L, 600, day, emptySet())?.text)
        assertNull(Warn.due(r, 4 * 60_000L, 600, day, setOf("$day:d5")))
        val one = Warn.due(r, 50_000L, 600, day, setOf("$day:d5"))!!
        assertEquals("Còn 1 phút: lưu lại việc đang làm nhé", one.text)
        // jumping straight to the last minute also retires the 5-minute warning
        assertEquals(setOf("$day:d1", "$day:d5"), one.keys)
        assertNull(Warn.due(r, 50_000L, 600, day, one.keys))
        assertNull(Warn.due(r, 0, 600, day, emptySet()))      // already locked
        assertNull(Warn.due(r, -1, 600, day, emptySet()))     // no limit
    }

    @Test fun bedtime() {
        val r = Rules(bedtimeOn = true, bedStart = "21:30", bedEnd = "06:30")
        assertNull(Warn.due(r, -1, 21 * 60 + 20, day, emptySet()))
        assertEquals("5 phút nữa đến giờ ngủ", Warn.due(r, -1, 21 * 60 + 26, day, emptySet())?.text)
        assertNull(Warn.due(r, -1, 21 * 60 + 26, day, setOf("$day:bed")))
        assertNull(Warn.due(r, -1, 21 * 60 + 30, day, emptySet()))   // already inside
        assertNull(Warn.due(r, -1, 23 * 60, day, emptySet()))
    }

    @Test fun bedtimeAcrossMidnight() {
        val r = Rules(bedtimeOn = true, bedStart = "00:02", bedEnd = "06:00")
        assertEquals("5 phút nữa đến giờ ngủ", Warn.due(r, -1, 23 * 60 + 58, day, emptySet())?.text)
    }

    @Test fun scheduleDayMatters() {
        val sc = Schedule("Học bài", true, setOf(5), "14:00", "16:00")   // Friday only
        val r = Rules(schedules = listOf(sc))
        assertEquals("5 phút nữa đến giờ nghỉ: Học bài", Warn.due(r, -1, 13 * 60 + 56, day, emptySet())?.text)
        assertNull(Warn.due(r, -1, 13 * 60 + 56, "2026-11-07", emptySet()))   // Saturday
        assertNull(Warn.due(r.copy(schedules = listOf(sc.copy(on = false))), -1, 13 * 60 + 56, day, emptySet()))
    }
}
