package dev.kidguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KidWinsTest {
    private fun d(l: Int, u: Int) = KidWins.Day(l, u)

    @Test fun countsWithinAndStreak() {
        val w = KidWins.wins(listOf(d(120, 100), d(120, 150), d(120, 60), d(120, 120), d(120, 30)), 0)
        assertEquals(4, w.within); assertEquals(5, w.counted); assertEquals(3, w.streak)
    }

    @Test fun unknownDaysAreSkipped() {
        val w = KidWins.wins(listOf(d(0, 50), d(120, 0), d(120, 90), d(120, 200)), 0)
        assertEquals(1, w.within); assertEquals(2, w.counted); assertEquals(0, w.streak)
    }

    @Test fun nothingKnownMeansNoWins() {
        assertFalse(KidWins.wins(emptyList(), 0).any)
        assertFalse(KidWins.wins(listOf(d(0, 10)), 0).any)
        assertFalse(KidWins.wins(listOf(d(60, 100)), 0).any)
    }

    @Test fun listDaysAloneIsAWin() {
        assertTrue(KidWins.wins(emptyList(), 3).any)
    }

    @Test fun listInWeekWindow() {
        assertEquals(3, KidWins.listInWeek(setOf(100L, 94L, 93L, 99L), 100L))
    }
}
