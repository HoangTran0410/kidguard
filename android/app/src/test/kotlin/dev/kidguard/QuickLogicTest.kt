package dev.kidguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickLogicTest {
    private val vi = TestTr.VI
    private val en = TestTr.EN

    @Test fun timeLeft() {
        assertEquals("Còn 45 phút", QuickLogic.timeLeftText(45 * 60_000L, null, vi))
        assertEquals("Còn 1 phút", QuickLogic.timeLeftText(10_000L, null, vi))          // rounded up: "0" only when it's really 0
        assertEquals("Còn 0 phút", QuickLogic.timeLeftText(0, null, vi))
        assertEquals("Còn 2 giờ", QuickLogic.timeLeftText(120 * 60_000L, null, vi))
        assertEquals("Còn 1 giờ 5 phút", QuickLogic.timeLeftText(65 * 60_000L - 30_000L, null, vi))
        assertEquals("Không giới hạn", QuickLogic.timeLeftText(-1, null, vi))
        assertEquals("45 min left", QuickLogic.timeLeftText(45 * 60_000L, null, en))
        assertEquals("1 h 5 min left", QuickLogic.timeLeftText(65 * 60_000L, null, en))
        assertEquals("No limit", QuickLogic.timeLeftText(-1, null, en))
    }

    @Test fun blockedShowsTheReason() {
        assertEquals("It's bedtime", QuickLogic.timeLeftText(30 * 60_000L, "bedtime", en))
        assertEquals("Screen time is up for today", QuickLogic.timeLeftText(0, "daily", en))
        assertEquals(vi.s(T.reason_paused).replaceFirstChar { it.uppercase() }, QuickLogic.timeLeftText(-1, "paused", vi))
    }

    @Test fun phoneBlockIgnoresPerAppReasons() {
        assertNull(QuickLogic.phoneBlock(emptyMap()))
        assertNull(QuickLogic.phoneBlock(mapOf("a" to "blocked", "b" to "appLimit", "c" to "groupHours", "d" to "new")))
        assertEquals("daily", QuickLogic.phoneBlock(mapOf("a" to "blocked", "b" to "daily")))
        assertEquals("paused", QuickLogic.phoneBlock(mapOf("a" to "bedtime", "b" to "paused")))
        // straight from decide(): bedtime locks every countable app
        val r = Rules(bedtimeOn = true, bedStart = "21:00", bedEnd = "06:30")
        val want = decide(r, emptyMap(), 22 * 60, 0, "2026-11-06", setOf("a", "b"), setOf("p"))
        assertEquals("bedtime", QuickLogic.phoneBlock(want))
    }

    @Test fun gestureTargetsKnownFirstThenScanThenFallback() {
        val acts = listOf(
            "com.android.settings.Settings\$GestureNavigationSettingsActivity",   // nav bar gestures: not what we want
            "com.android.settings.Settings\$AssistGestureSettingsActivity",
            "com.android.settings.Settings\$DisplaySettingsActivity",
            "com.oplus.settings.feature.gesture.ScreenOffGestureActivity",
            "com.android.settings.Settings\$GestureSettingsActivity",
        )
        val t = QuickLogic.gestureTargets("OPPO", acts)
        assertEquals("oplus.intent.action.settings.GESTURE_MOTION", t.first().action)
        val cls = t.mapNotNull { it.cls }
        assertTrue(cls.indexOf("com.oplus.settings.feature.gesture.ScreenOffGestureActivity") < cls.indexOf("com.android.settings.Settings\$GestureSettingsActivity"))
        assertTrue(cls.none { "Navigation" in it || "Assist" in it || "DisplaySettings" in it })
        assertEquals(listOf("android.settings.LOCK_SCREEN_SETTINGS", "android.settings.DISPLAY_SETTINGS", "android.settings.SETTINGS"), t.takeLast(3).map { it.action })
        // an unknown maker: scan + fallbacks only
        val g = QuickLogic.gestureTargets("Google", emptyList())
        assertEquals(3, g.size)
        assertEquals("android.settings.SETTINGS", g.last().action)
        assertTrue(QuickLogic.gestureTargets("samsung", emptyList()).first().action!!.startsWith("com.samsung"))
    }
}
