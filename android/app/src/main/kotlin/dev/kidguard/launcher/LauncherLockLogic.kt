package dev.kidguard

import java.text.Normalizer
import java.util.Locale

/**
 * Pure decisions for the home lock without Device Owner (LauncherLock + Acc). Android-free, so it is unit-tested.
 * Two things are caught: another launcher coming to the front, and the screens where the home app is chosen.
 */
object LauncherLockLogic {
    /** [pkg]/[cls] is another HOME activity (not ours) in front: bounce to ours. [homeActs] = "pkg/cls" of every real HOME activity. */
    fun otherHome(pkg: String, cls: String?, self: String, homeActs: Set<String>): Boolean =
        pkg != self && cls != null && "$pkg/$cls" in homeActs

    // The "Home app" row / page in Settings > Default apps (exact title, so a page that merely mentions it passes).
    private val PAGE_TITLES = listOf("home app", "default home app", "choose home app", "select a home app", "home screen app",
        "ứng dụng màn hình chính", "ứng dụng màn hình chính mặc định", "chọn ứng dụng màn hình chính", "ứng dụng màn hình chủ")
    // The system chooser (package android) for HOME: its title holds one of these.
    private val CHOOSER_WORDS = listOf("home app", "ứng dụng màn hình chính", "ứng dụng màn hình chủ")

    fun isChooser(pkg: String, cls: String?): Boolean =
        pkg == "android" && cls?.let { it.contains("ResolverActivity") || it.contains("ChooserActivity") } == true

    /** A window where the child could pick another home app: Settings page titled "Home app", or the HOME chooser. */
    fun homePicker(pkg: String, cls: String?, texts: List<String>, isSettings: (String) -> Boolean): Boolean {
        val ts = texts.map { norm(it) }.filter { it.isNotEmpty() }
        if (isChooser(pkg, cls)) return ts.any { t -> CHOOSER_WORDS.any { t.contains(it) } }
        return isSettings(pkg) && ts.any { it in PAGE_TITLES }
    }

    /** Re-run `cmd package set-home-activity` now? Only when the lock is on, HOME is not ours, adb is ready, and 20 s passed. */
    fun adbDue(lockOn: Boolean, homeIsOurs: Boolean, adbReady: Boolean, last: Long, now: Long): Boolean =
        lockOn && !homeIsOurs && adbReady && now - last >= 20_000

    private fun norm(s: String) = Normalizer.normalize(s.trim(), Normalizer.Form.NFC).lowercase(Locale.ROOT)
}
