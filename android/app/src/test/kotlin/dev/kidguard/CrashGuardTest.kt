package dev.kidguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashGuardTest {
    // 2026-11-18 INCIDENT: a settings-sheet crash killed the kid home; it must now only close the screen
    @Test fun screenCrashKeepsTheProcess() = assertTrue(CrashGuardLogic.survive(outOfMemory = false, crashesInWindow = 1))
    @Test fun outOfMemoryDies() = assertFalse(CrashGuardLogic.survive(true, 1))
    @Test fun crashLoopDies() {
        assertTrue(CrashGuardLogic.survive(false, CrashGuardLogic.MAX_IN_WINDOW))
        assertFalse(CrashGuardLogic.survive(false, CrashGuardLogic.MAX_IN_WINDOW + 1))
    }
    // a home that crashes on every start: the 3rd launcher crash (SafeHome) comes before the loop limit kills the process
    @Test fun homeCrashLoopReachesSafeHomeFirst() {
        var times = emptyList<Long>(); var act = LauncherLogic.CrashAct.NONE; var n = 0
        while (act != LauncherLogic.CrashAct.DISABLE) { n++; LauncherLogic.crash(times, 1_000L * n).let { times = it.first; act = it.second } }
        assertEquals(3, n)
        assertTrue(CrashGuardLogic.survive(false, n))
    }
}
