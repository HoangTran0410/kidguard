package dev.kidguard

import java.util.Locale

/**
 * Pure part of the child's shortcuts (lock button, time-left tile / widget, screen-off gestures helper).
 * No Android classes, unit-tested in QuickLogicTest. The Android side is Quick.kt.
 */
object QuickLogic {
    /** decide() reasons that lock the whole phone (not one app), most important first: the same order decide() uses. */
    private val PHONE = listOf("paused", "schedule", "bedtime", "daily")

    /** Why the whole phone is locked right now, from decide()'s result; null when it isn't (per-app reasons don't count). */
    fun phoneBlock(want: Map<String, String>): String? = PHONE.firstOrNull { it in want.values }

    /**
     * The tile / widget text: the block reason while the phone is locked, "No limit" without a daily limit,
     * else the time left rounded up to the minute ("45 min left", "1 h 5 min left"). [leftMs]: dailyLeftMs (-1 = no limit).
     */
    fun timeLeftText(leftMs: Long, block: String?, tr: Tr): String {
        if (block != null) return cap(BasicLogic.reasonText(block, tr))
        if (leftMs < 0) return tr.s(T.tl_none)
        val min = ((leftMs + 59_999) / 60_000).toInt()
        val h = min / 60; val m = min % 60
        return when {
            h == 0 -> tr.s(T.tl_min, m)
            m == 0 -> tr.s(T.tl_h, h)
            else -> tr.s(T.tl_hm, h, m)
        }
    }

    private fun cap(s: String) = s.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }

    /** One way to open a settings screen: an intent [action], or an explicit [pkg]/[cls] component. */
    data class Target(val action: String? = null, val pkg: String? = null, val cls: String? = null)

    const val SETTINGS_PKG = "com.android.settings"

    /**
     * Where "double tap to wake / screen-off gestures" lives, best guess first. Apps can't turn these on themselves:
     * the most we can do is open the right page. [maker]: Build.MANUFACTURER (or BRAND). [settingsActivities]: the
     * exported activity class names of the Settings app, scanned for gesture pages (OEM names change every version).
     * The caller resolves each target and starts the first one that exists; the last ones always exist.
     */
    fun gestureTargets(maker: String, settingsActivities: List<String>): List<Target> {
        val m = maker.lowercase(Locale.ROOT)
        val known = when {
            // ColorOS (Oppo, Realme, OnePlus on ColorOS 11+): "Gestures & motions" > "Screen-off gestures"
            m in setOf("oppo", "realme", "oneplus") -> listOf(
                Target("oplus.intent.action.settings.GESTURE_MOTION"),
                Target("oppo.intent.action.settings.GESTURE_MOTION"),
                Target(pkg = "com.coloros.gesture", cls = "com.coloros.gesture.ColorGestureActivity"),
                Target(pkg = "com.oplus.gesture", cls = "com.oplus.gesture.GestureActivity"),
            )
            // MIUI / HyperOS: "Double tap to wake" is under Lock screen
            m in setOf("xiaomi", "redmi", "poco") -> listOf(
                Target(pkg = SETTINGS_PKG, cls = "com.android.settings.Settings\$MiuiLockScreenSettingsActivity"),
                Target(pkg = SETTINGS_PKG, cls = "com.android.settings.KeyShortcutSettingsActivity"),
            )
            // One UI: Advanced features > Motions and gestures > Double tap to turn on screen
            m == "samsung" -> listOf(
                Target("com.samsung.settings.MOTIONS_AND_GESTURES_SETTINGS"),
                Target(pkg = SETTINGS_PKG, cls = "com.android.settings.Settings\$MotionAndGestureSettingsActivity"),
            )
            // Funtouch / OriginOS: Shortcuts & accessibility > Smart motion
            m in setOf("vivo", "iqoo") -> listOf(
                Target("vivo.intent.action.SMART_MOTION_SETTINGS"),
                Target(pkg = SETTINGS_PKG, cls = "com.vivo.settings.smartmotion.SmartMotionActivity"),
            )
            else -> emptyList()
        }
        val scanned = rankGestureActivities(settingsActivities).map { Target(pkg = SETTINGS_PKG, cls = it) }
        val fallback = listOf(
            Target("android.settings.LOCK_SCREEN_SETTINGS"),   // AOSP / Pixel: "Double-tap to check phone", "Tap to wake"
            Target("android.settings.DISPLAY_SETTINGS"),
            Target("android.settings.SETTINGS"),
        )
        return (known + scanned + fallback).distinct()
    }

    /** Settings activities that look like a gesture page, best first; navigation / fingerprint / assist pages are left out. */
    fun rankGestureActivities(names: List<String>): List<String> {
        fun score(n: String): Int {
            val s = n.substringAfterLast('.').substringAfterLast('$').lowercase(Locale.ROOT)
            if (listOf("navigation", "fingerprint", "assist", "tutorial", "edgeback", "swipeup").any { it in s }) return 0
            return when {
                listOf("screenoffgesture", "blackscreengesture", "offscreengesture", "doubletap", "taptowake", "wakeup").any { it in s } -> 5
                listOf("gesturemotion", "motionandgesture", "motionsandgestures", "smartmotion").any { it in s } -> 4
                "gesture" in s -> 3
                else -> 0
            }
        }
        return names.distinct().map { it to score(it) }.filter { it.second > 0 }.sortedByDescending { it.second }.map { it.first }.take(4)
    }
}
