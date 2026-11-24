package dev.kidguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExitLogicTest {
    private val D = 86_400_000L
    private fun e(t: Long, r: Int) = ExitLogic.Exit(t, r, "")

    @Test fun sendsOnlyFaultsNewerThanSeen() {   // 2026-11-24 ColorOS "crash on every open": ANRs never reached the census
        val now = 10 * D
        val all = listOf(e(now - 1000, 10), e(now - 2000, 6), e(now - 3000, 8), e(now - 4000, 4), e(now - 4 * D, 6), e(now - 5000, 5))
        val s = ExitLogic.toSend(all, seen = now - 6000, now = now)
        assertEquals(listOf(5, 8, 6), s.map { it.reason })                 // user swipe (10) and Java crash (4) not; older than seen not
        assertTrue(ExitLogic.toSend(all, seen = now, now = now).isEmpty())
        val many = (1..10).map { e(now - it * 1000L, 6) }
        assertEquals(ExitLogic.SEND_MAX, ExitLogic.toSend(many, 0, now).size)
        assertEquals(now - 1000, ExitLogic.toSend(many, 0, now).last().t)  // newest kept
    }

    @Test fun anrMainThreadBlock() {
        val tr = "----- pid 1 -----\n\n\"Signal Catcher\" daemon\n  at x\n\n\"main\" prio=5 tid=1 Blocked\n  at dev.kidguard.SelfAdb.ready(SelfAdb.kt:44)\n  - waiting to lock\n\n\"guard\" prio=5\n  at y\n"
        assertEquals("\"main\" prio=5 tid=1 Blocked\n  at dev.kidguard.SelfAdb.ready(SelfAdb.kt:44)\n  - waiting to lock", ExitLogic.mainThread(tr))
        assertEquals("abc", ExitLogic.mainThread("abcdef", 3))
        assertEquals("ANR", ExitLogic.name(6)); assertEquals("R99", ExitLogic.name(99))
    }
}
