package dev.kidguard

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class UsageStatsLogicTest {
    private val m = 60_000L
    private val days = listOf(
        mapOf("a" to 10 * m, "kg" to 5 * m),
        emptyMap(),
        mapOf("a" to 20 * m, "b" to 30 * m, "c" to 30_000L),
    )

    @Test fun totalsSkipOwnAndKeepEmptyDays() {
        assertArrayEquals(longArrayOf(10 * m, 0, 50 * m + 30_000L), UsageStatsLogic.totals(days) { it == "kg" })
    }

    @Test fun rowsSortedAndFloor() {
        val r = UsageStatsLogic.rows(days, 7) { it == "kg" }
        assertEquals(listOf("a", "b"), r.map { it.pkg })      // c is under 1 min: dropped; a = b = 30 min: tie by package
        assertEquals(30 * m, r[0].ms); assertEquals(20 * m, r[0].today)
        assertEquals(listOf("b", "a"), UsageStatsLogic.rows(days, 1) { it == "kg" }.map { it.pkg })   // today: b 30, a 20
    }

    @Test fun rowsTieByPackage() {
        val r = UsageStatsLogic.rows(listOf(mapOf("z" to 5 * m, "y" to 5 * m)), 7) { false }
        assertEquals(listOf("y", "z"), r.map { it.pkg })
    }

    @Test fun seriesAndPeak() {
        assertArrayEquals(longArrayOf(10 * m, 0, 20 * m), UsageStatsLogic.series(days, "a"))
        assertEquals(2, UsageStatsLogic.peak(UsageStatsLogic.series(days, "a")))
        assertEquals(-1, UsageStatsLogic.peak(longArrayOf(0, 0)))
    }

    @Test fun avgCoversFromFirstUse() {
        assertEquals(10 * m, UsageStatsLogic.avg(longArrayOf(0, 0, 10 * m, 20 * m, 0)))
        assertEquals(20 * m, UsageStatsLogic.avg(longArrayOf(0, 0, 0, 0, 20 * m)))
        assertEquals(10 * m, UsageStatsLogic.avg(longArrayOf(0, 10 * m, 10 * m, 10 * m)))
        assertEquals(0L, UsageStatsLogic.avg(longArrayOf(0, 0)))
    }

    @Test fun pctAndOpens() {
        assertEquals(100, UsageStatsLogic.pct(5, 5)); assertEquals(3, UsageStatsLogic.pct(1, 1000)); assertEquals(0, UsageStatsLogic.pct(0, 5))
        assertEquals(mapOf("a" to 2, "b" to 1), UsageStatsLogic.opens(listOf("a", "a", "b", "a")))
    }
}
