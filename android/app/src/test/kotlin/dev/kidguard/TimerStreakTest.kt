package dev.kidguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimerStreakTest {
    private val m = 60_000L

    @Test fun timerStartPauseResume() {
        val s0 = TimerLogic.choose(10)
        assertTrue(s0.idle)
        val s1 = TimerLogic.start(s0, 1000)
        assertEquals(10 * m, TimerLogic.remaining(s1, 1000))
        val p = TimerLogic.pause(s1, 1000 + 3 * m)
        assertEquals(7 * m, p.left); assertFalse(p.running)
        val r = TimerLogic.start(p, 50_000)
        assertEquals(50_000 + 7 * m, r.endAt)
        assertEquals(0.7f, TimerLogic.fraction(r, 50_000), 0.001f)
    }

    @Test fun clockGoingBackNeverExceedsTotal() {
        val s = TimerLogic.start(TimerLogic.choose(5), 1_000_000)
        assertEquals(5 * m, TimerLogic.remaining(s, 1_000_000 - 3_600_000))
        assertEquals(1f, TimerLogic.fraction(s, 0), 0f)
    }

    @Test fun finishPlainAndSettle() {
        val s = TimerLogic.start(TimerLogic.choose(5), 0)
        assertEquals(s, TimerLogic.settle(s, 5 * m - 1))
        val d = TimerLogic.settle(s, 5 * m)
        assertTrue(d.done)
        assertEquals(0L, TimerLogic.remaining(d, 9 * m))
        val again = TimerLogic.start(d, 10 * m)           // tap on a finished timer: runs again from the full time
        assertEquals(10 * m + 5 * m, again.endAt)
    }

    @Test fun pomodoroCycle() {
        var s = TimerLogic.pomodoro()
        s = TimerLogic.finish(TimerLogic.start(s, 0))
        assertTrue(s.onBreak); assertEquals(5 * m, s.total); assertEquals(1, s.cycles); assertTrue(s.idle)
        s = TimerLogic.finish(TimerLogic.start(s, 0))
        assertFalse(s.onBreak); assertEquals(25 * m, s.total); assertEquals(1, s.cycles)
        assertEquals(0, TimerLogic.reset(s).cycles)
    }

    @Test fun jsonRoundTripAndGarbage() {
        val s = TimerLogic.start(TimerLogic.pomodoro(), 123)
        assertEquals(s, TimerLogic.fromJson(s.toJson()))
        val bad = TimerLogic.fromJson(org.json.JSONObject().put("total", 10).put("left", 9_999_999_999L).put("endAt", -5))
        assertEquals(m, bad.total); assertEquals(m, bad.left); assertFalse(bad.running)
        assertEquals("05:00", TimerLogic.mmss(5 * m)); assertEquals("1:00:00", TimerLogic.mmss(60 * m)); assertEquals("00:01", TimerLogic.mmss(1))
    }

    @Test fun streakCurrentAndMissedDay() {
        val done = setOf(10L, 11, 12, 14)
        assertEquals(1, StreakLogic.current(done, 14))
        assertEquals(1, StreakLogic.current(done, 15))      // today open, yesterday done: alive
        assertEquals(0, StreakLogic.current(done, 16))      // a full day missed: broken
        assertEquals(3, StreakLogic.current(done, 12))
        assertEquals(3, StreakLogic.best(done))
        assertEquals(0, StreakLogic.best(emptySet()))
    }

    @Test fun streakClockBackIgnoresFuture() {
        val done = setOf(10L, 11, 12, 20, 21)
        assertEquals(3, StreakLogic.current(done, 12))
        assertEquals(0, StreakLogic.current(done, 5))
        assertEquals(3, StreakLogic.best(done))
    }

    @Test fun dayRolloverInZone() {
        val h = 3_600_000L
        val utcMidnightMinus = 86_400_000L - 1
        assertEquals(0L, StreakLogic.dayOf(utcMidnightMinus, 0))
        assertEquals(1L, StreakLogic.dayOf(utcMidnightMinus, 7 * h))      // Vietnam is already on the next day
        assertEquals(-1L, StreakLogic.dayOf(0, -h))                       // west of UTC, before the epoch day
    }

    @Test fun markPrunesOldest() {
        var d = emptySet<Long>()
        for (i in 1..450L) d = StreakLogic.mark(d, i)
        assertEquals(StreakLogic.KEEP_DAYS, d.size); assertTrue(450L in d); assertFalse(1L in d)
    }

    @Test fun badgesAndCelebration() {
        assertEquals(listOf(3, 7), StreakLogic.earned(9))
        assertEquals(7, StreakLogic.next(3)); assertNull(StreakLogic.next(30))
        assertEquals(null to 0, StreakLogic.celebrate(2, 0))
        assertEquals(3 to 3, StreakLogic.celebrate(3, 0))
        assertEquals(null to 3, StreakLogic.celebrate(5, 3))
        assertEquals(7 to 7, StreakLogic.celebrate(7, 3))
        assertEquals(null to 0, StreakLogic.celebrate(1, 7))      // broken streak: forgets, celebrates again later
        assertTrue(StreakLogic.completes(false, true)); assertFalse(StreakLogic.completes(true, true)); assertFalse(StreakLogic.completes(false, false))
    }
}
