package dev.kidguard

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/** Pure parts of [PermMon] (unit tested). */
object PermMonLogic {
    /** ColorOS family (Oppo / Realme / OnePlus): its ROM props, or the maker as a fallback. */
    fun colorOs(manufacturer: String, brand: String, opporom: String, oplusrom: String): Boolean =
        opporom.isNotBlank() || oplusrom.isNotBlank() || listOf("oppo", "realme", "oneplus").any { it in (manufacturer + " " + brand).lowercase() }

    /** Lower case, no Vietnamese marks (đ -> d), single spaces: "Tắt giám sát quyền" -> "tat giam sat quyen". */
    fun fold(s: String): String = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "").replace('đ', 'd').replace(Regex("\\s+"), " ").trim()

    /** The Developer options row "Disable permission monitoring" in vi / en / zh (ColorOS 6..11+, Realme, OnePlus). */
    fun isRow(text: String): Boolean {
        val f = fold(text)
        return "giam sat quyen" in f || "permission monitor" in f || "权限监控" in f || "權限監控" in f
    }

    enum class Probe { OK, BLOCKED, OTHER }
    /** Output of the probe `appops set <pkg> GET_USAGE_STATS allow`: BLOCKED = permission monitoring is still on. */
    fun probe(out: String): Probe = when {
        SelfSetupLogic.permMonitoring(out) -> Probe.BLOCKED
        SelfSetupLogic.ok(out) -> Probe.OK
        else -> Probe.OTHER
    }

    /** `persist.sys.permission.enable` (unverified semantics, a hint only): "true" = monitoring on, "false" = off, else unknown. */
    fun hintOn(prop: String): Boolean? = when (prop.trim().lowercase()) { "true", "1" -> true; "false", "0" -> false; else -> null }

    /** An outline we may draw: inside the screen and row-sized (never a full-screen overlay: overlay-stuck incident 2026-11-15). */
    fun sane(l: Int, t: Int, r: Int, b: Int, screenW: Int, screenH: Int): Boolean =
        r > l && b > t && l >= -8 && t >= 0 && r <= screenW + 8 && b <= screenH && (b - t) <= screenH / 4
}

/**
 * "Disable permission monitoring" (ColorOS, last row of Developer options): adb `appops set` / `pm grant` / `settings put
 * secure` fail with "uid 2000 does not have MANAGE_APP_OPS_MODES" until it is on (owner 2026-11-20, new Oppo). [open] takes
 * the user there; when KidGuard's accessibility service runs, [onEvent] scrolls to the row and outlines it for 8 s
 * (non-touchable, row-sized overlay).
 */
object PermMon {
    private fun prop(k: String): String = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, k) as String
    }.getOrDefault("")

    fun colorOs(): Boolean = PermMonLogic.colorOs(Build.MANUFACTURER, Build.BRAND, prop("ro.build.version.opporom"), prop("ro.build.version.oplusrom"))
    /** Hint only: the adb probe in [SelfSetup] decides. */
    fun hintOn(): Boolean? = PermMonLogic.hintOn(prop("persist.sys.permission.enable"))

    /** Opens Developer options at the row where the Settings app supports it, says where the row is, arms the outline. */
    fun open(ctx: Context) {
        if (runCatching { Settings.Global.getInt(ctx.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) != 1 }.getOrDefault(false)) {
            Toasts.show(ctx, R.string.ssa_dev_first, true)
            Intents.startFirst(ctx, Intent(Settings.ACTION_DEVICE_INFO_SETTINGS), Intent(Settings.ACTION_SETTINGS)); return
        }
        armedUntil = SystemClock.elapsedRealtime() + 30_000L; tries = 0
        // ColorOS key names unverified: harmless when the page does not know them
        val key = "disable_permission_monitor"
        val i = DevOpts.pageIntent(ctx).putExtra(":settings:fragment_args_key", key)
            .putExtra(":settings:show_fragment_args", android.os.Bundle().apply { putString(":settings:fragment_args_key", key) })
        Intents.startFirst(ctx, i, Intent(Settings.ACTION_SETTINGS))
        Toasts.show(ctx, R.string.pm_toast, true)
        // already in front (the window event came before we armed): look now too
        Acc.running?.let { svc -> main.postDelayed({ look(svc) }, 900) }
    }

    @Volatile private var armedUntil = 0L
    private var tries = 0
    private val main = Handler(Looper.getMainLooper())
    private var outline: View? = null
    private var shownPkg = ""

    /** From Acc.onAccessibilityEvent (window state changes only). */
    fun onEvent(svc: AccessibilityService, e: AccessibilityEvent) {
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString() ?: return
        if (outline != null && pkg != shownPkg && pkg != svc.packageName) hide(svc)
        if (SystemClock.elapsedRealtime() > armedUntil || !BasicLogic.isSettingsPkg(pkg)) return
        main.removeCallbacksAndMessages(LOOK); main.postAtTime({ look(svc) }, LOOK, SystemClock.uptimeMillis() + 500)
    }
    private val LOOK = Any()

    /** Finds the row; not there yet: one scroll down, look again (at most 25 times, stops at the list end). */
    private fun look(svc: AccessibilityService) {
        if (SystemClock.elapsedRealtime() > armedUntil) return
        val root = runCatching { svc.rootInActiveWindow }.getOrNull() ?: return
        if (!BasicLogic.isSettingsPkg(root.packageName?.toString().orEmpty())) return
        val row = find(root) { n -> listOfNotNull(n.text, n.contentDescription).any { PermMonLogic.isRow(it.toString()) } }
        if (row != null) {
            armedUntil = 0L
            var n: AccessibilityNodeInfo = row
            for (k in 0 until 3) { if (n.isClickable) break; n = n.parent ?: break }   // the whole preference row
            val r = Rect(); n.getBoundsInScreen(r)
            show(svc, r, root.packageName?.toString().orEmpty()); return
        }
        if (++tries > 25) { armedUntil = 0L; return }
        val list = biggestScrollable(root) ?: return
        if (!list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) { armedUntil = 0L; return }   // at the end, not found
        main.postAtTime({ look(svc) }, LOOK, SystemClock.uptimeMillis() + 350)
    }

    private fun find(root: AccessibilityNodeInfo, hit: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val q = ArrayDeque<AccessibilityNodeInfo>(); q.add(root); var seen = 0
        while (q.isNotEmpty() && seen++ < 500) {
            val n = q.removeFirst()
            if (hit(n)) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { q.add(it) }
        }
        return null
    }

    private fun biggestScrollable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null; var area = 0
        val q = ArrayDeque<AccessibilityNodeInfo>(); q.add(root); var seen = 0; val r = Rect()
        while (q.isNotEmpty() && seen++ < 500) {
            val n = q.removeFirst()
            if (n.isScrollable) { n.getBoundsInScreen(r); val a = r.width() * r.height(); if (a > area) { area = a; best = n } }
            for (i in 0 until n.childCount) n.getChild(i)?.let { q.add(it) }
        }
        return best
    }

    /** A 3 dp outline over [r]: TYPE_ACCESSIBILITY_OVERLAY, not touchable, not focusable, explicit size, gone after 8 s. */
    private fun show(svc: AccessibilityService, r: Rect, pkg: String) {
        hide(svc)
        val dm = svc.resources.displayMetrics
        val (w, h) = runCatching {
            val wm = svc.getSystemService(WindowManager::class.java)
            if (Build.VERSION.SDK_INT >= 30) wm.maximumWindowMetrics.bounds.let { it.width() to it.height() } else dm.widthPixels to dm.heightPixels
        }.getOrDefault(dm.widthPixels to dm.heightPixels)
        if (!PermMonLogic.sane(r.left, r.top, r.right, r.bottom, w, h)) return
        val px = (3 * dm.density).toInt()
        val v = View(svc).apply {
            background = GradientDrawable().apply { setColor(0); cornerRadius = 12 * dm.density; setStroke(px, 0xFFF59E0B.toInt()) }
        }
        val lp = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            flags = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            format = PixelFormat.TRANSLUCENT; gravity = Gravity.TOP or Gravity.START
            x = r.left; y = r.top; width = r.width(); height = r.height()
        }
        if (lp.width <= 0 || lp.height <= 0 || lp.width > w + 16 || lp.height > h / 4) return   // never MATCH_PARENT / full screen
        if (runCatching { svc.getSystemService(WindowManager::class.java).addView(v, lp); true }.getOrDefault(false)) {
            outline = v; shownPkg = pkg
            main.postDelayed({ hide(svc) }, 8_000L)
        }
    }

    private fun hide(svc: AccessibilityService) {
        val v = outline ?: return
        outline = null; shownPkg = ""
        runCatching { svc.getSystemService(WindowManager::class.java).removeViewImmediate(v) }
    }
}
