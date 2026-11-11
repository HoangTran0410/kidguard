package dev.kidguard

import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistLogicTest {
    private val hcm = TimeZone.getTimeZone("Asia/Ho_Chi_Minh")
    private val utc = TimeZone.getTimeZone("UTC")

    @Test fun dayKeyFollowsTheZone() {
        val t = 1_700_000_000_000L   // 2023-11-14 22:13:20 UTC = 2023-11-15 05:13 in Ho Chi Minh
        assertEquals("2023-11-14", HistLogic.dayKey(t, utc))
        assertEquals("2023-11-15", HistLogic.dayKey(t, hcm))
    }

    @Test fun dayRangeIsHalfOpenAndContainsItsRows() {
        val (s, e) = HistLogic.dayRange("2023-11-15", hcm)!!
        assertEquals(86_400_000L, e - s)
        assertEquals("2023-11-15", HistLogic.dayKey(s, hcm)); assertEquals("2023-11-14", HistLogic.dayKey(s - 1, hcm))
        assertEquals("2023-11-16", HistLogic.dayKey(e, hcm))
    }

    @Test fun dstDayIsNot24Hours() {
        val ny = TimeZone.getTimeZone("America/New_York")
        val (s, e) = HistLogic.dayRange("2024-03-10", ny)!!
        assertEquals(23 * 3_600_000L, e - s)
    }

    @Test fun badDaysAreRejected() {
        for (d in listOf("", "2023-1-5", "20231105", "2023-13-01", "2023-02-30", "abcd-ef-gh", "2023-11-15; DROP")) assertNull(d, HistLogic.dayRange(d, utc))
        assertFalse(HistLogic.validDay(null)); assertTrue(HistLogic.validDay("2023-11-15"))
    }

    @Test fun countsBucketPerDayAndDropOldRows() {
        val now = 1_700_000_000_000L; val d = 86_400_000L
        val ts = longArrayOf(now - 100 * d, now - 2 * d, now - 2 * d + 1000, now - 1000, now, now + 10 * d)
        val c = HistLogic.counts(ts, utc, now)
        assertEquals(mapOf("2023-11-12" to 2, "2023-11-14" to 2), c)
    }
}
