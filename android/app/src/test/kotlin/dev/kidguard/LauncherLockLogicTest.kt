package dev.kidguard

import org.junit.Assert.*
import org.junit.Test

class LauncherLockLogicTest {
    private val homes = setOf("com.miui.home/com.miui.home.launcher.Launcher", "dev.kidguard/dev.kidguard.HomeActivity")
    private val st = BasicLogic::isSettingsPkg

    @Test fun otherLauncherOnly() {
        assertTrue(LauncherLockLogic.otherHome("com.miui.home", "com.miui.home.launcher.Launcher", "dev.kidguard", homes))
        assertFalse(LauncherLockLogic.otherHome("com.miui.home", "com.miui.home.recents.RecentsActivity", "dev.kidguard", homes))
        assertFalse(LauncherLockLogic.otherHome("dev.kidguard", "dev.kidguard.HomeActivity", "dev.kidguard", homes))
        assertFalse(LauncherLockLogic.otherHome("com.miui.home", null, "dev.kidguard", homes))
    }

    @Test fun settingsHomeAppPage() {
        assertTrue(LauncherLockLogic.homePicker("com.android.settings", "X", listOf("Default apps", " Home app "), st))
        assertTrue(LauncherLockLogic.homePicker("com.android.settings", "X", listOf("Ứng dụng màn hình chính"), st))
        assertFalse(LauncherLockLogic.homePicker("com.android.settings", "X", listOf("Home app settings help"), st))   // exact title only
        assertFalse(LauncherLockLogic.homePicker("com.example.app", "X", listOf("Home app"), st))
    }

    @Test fun homeChooser() {
        val c = "com.android.internal.app.ResolverActivity"
        assertTrue(LauncherLockLogic.homePicker("android", c, listOf("Select a Home app"), st))
        assertFalse(LauncherLockLogic.homePicker("android", c, listOf("Open with"), st))   // other chooser: untouched
        assertFalse(LauncherLockLogic.homePicker("android", "Other", listOf("Select a Home app"), st))
    }

    @Test fun adbRateLimitAndConditions() {
        assertTrue(LauncherLockLogic.adbDue(true, false, true, 0, 100_000))
        assertFalse(LauncherLockLogic.adbDue(true, false, true, 90_000, 100_000))
        assertFalse(LauncherLockLogic.adbDue(true, true, true, 0, 100_000))
        assertFalse(LauncherLockLogic.adbDue(true, false, false, 0, 100_000))
        assertFalse(LauncherLockLogic.adbDue(false, false, true, 0, 100_000))
    }
}
