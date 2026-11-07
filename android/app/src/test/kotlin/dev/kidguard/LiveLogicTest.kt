package dev.kidguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveLogicTest {
    @Test fun wakeDismissOnlyWithoutSecureLock() {
        assertTrue(WakeLogic.dismiss(locked = true, secure = false))
        assertFalse(WakeLogic.dismiss(locked = true, secure = true))
        assertFalse(WakeLogic.dismiss(locked = false, secure = false))
    }

    @Test fun projectionOnlyBelow30() {
        assertTrue(ProjLogic.useProjection(26)); assertTrue(ProjLogic.useProjection(29))
        assertFalse(ProjLogic.useProjection(30)); assertFalse(ProjLogic.useProjection(34))
    }

    @Test fun fitScalesLongestSideEven() {
        assertEquals(332 to 720, ProjLogic.fit(720, 1560, 720))   // 332.3 -> 332
        assertEquals(720 to 404, ProjLogic.fit(1920, 1080, 720))  // 405 -> 404 (even)
        assertEquals(480 to 800, ProjLogic.fit(480, 800, 1600))   // never upscaled
        assertEquals(2 to 2, ProjLogic.fit(0, 100, 720))
    }

    @Test fun rowPxHandlesPadding() {
        assertEquals(336, ProjLogic.rowPx(1344, 4, 332))
        assertEquals(332, ProjLogic.rowPx(1328, 4, 332))
        assertEquals(332, ProjLogic.rowPx(0, 0, 332))
    }

    @Test fun consentStateMachine() {
        assertEquals("ask", ProjLogic.next("off", 1000, 0, 0))
        assertEquals("wait", ProjLogic.next("asking", 10_000, 5_000, 0))
        assertEquals("ask", ProjLogic.next("asking", 70_000, 5_000, 0))
        assertEquals("wait", ProjLogic.next("denied", 30_000, 0, 10_000))
        assertEquals("ask", ProjLogic.next("denied", 80_000, 0, 10_000))
        assertEquals("denied", ProjLogic.waitError("denied"))
        assertEquals("need consent", ProjLogic.waitError("asking"))
    }

    @Test fun micBlockedOnlyAfterSilentWindow() {
        assertFalse(ListenLogic.blocked(listOf(0, 0, 0), 1_000))
        assertTrue(ListenLogic.blocked(listOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0), 2_500))
        assertFalse(ListenLogic.blocked(listOf(0, 0, 37, 0), 3_000))
        assertFalse(ListenLogic.blocked(emptyList(), 3_000))
        assertTrue(ListenLogic.stalled(0, 4_001)); assertFalse(ListenLogic.stalled(1_000, 4_000))
    }

    @Test fun dropTasksOnHomeOrRecentsOnly() {
        val homes = setOf("com.oppo.launcher")
        assertTrue(TaskLogic.dropTasks("com.oppo.launcher", "com.oppo.launcher.Launcher", "dev.kidguard", homes))
        assertTrue(TaskLogic.dropTasks("com.android.systemui", "com.android.systemui.recents.RecentsActivity", "dev.kidguard", homes))
        assertTrue(TaskLogic.dropTasks("com.coloros.recents", null, "dev.kidguard", homes))
        assertFalse(TaskLogic.dropTasks("com.android.systemui", "android.widget.FrameLayout", "dev.kidguard", homes))  // shade
        assertFalse(TaskLogic.dropTasks("com.android.settings", "com.android.settings.SubSettings", "dev.kidguard", homes))
        assertFalse(TaskLogic.dropTasks("dev.kidguard", "dev.kidguard.RecentsLike", "dev.kidguard", homes))
    }

    @Test fun ringAndListenConflict() {
        assertEquals("listening", ActionLogic.conflict("ring", ringing = false, listening = true))
        assertEquals(null, ActionLogic.conflict("ring", ringing = true, listening = false))   // again = restart, fine
        assertEquals("ringing", ActionLogic.conflict("listen", ringing = true, listening = false))
        assertEquals(null, ActionLogic.conflict("listen", ringing = false, listening = true))  // replaces the old session
        assertEquals(null, ActionLogic.conflict("wake", ringing = true, listening = true))
    }
}
