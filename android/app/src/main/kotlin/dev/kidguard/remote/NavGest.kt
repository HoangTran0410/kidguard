package dev.kidguard

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

/**
 * Own gesture navigation (Xiaomi / Poco, docs/decisions/launcher.md 2026-11-15). While the switch is on AND our
 * accessibility service runs, not paused, no bank app in front: `force_fsg_nav_bar = 1` hides the 3 buttons and thin
 * [Acc] overlay strips turn swipes into Back / Home / Recents. In EVERY other case the setting goes back to what it
 * was ([Store.fsgPrev]): the phone must never have no bar and no gestures. Restore paths: [sync] (called by Acc on
 * connect / unbind / destroy / interrupt, AccPause.pause, Guard tick, Guard.start, the switch), [release].
 */
object NavGest {
    private const val PERM = "android.permission.WRITE_SECURE_SETTINGS"

    /** Show the switch at all: kid flavor, Xiaomi family, SDK 29+ (the setting is read by HyperOS / MIUI from there). */
    fun supported(): Boolean = !Flavor.HOME && Build.VERSION.SDK_INT >= 29 && NavGestLogic.xiaomi(Build.MANUFACTURER)

    fun permitted(ctx: Context): Boolean = ctx.checkSelfPermission(PERM) == PackageManager.PERMISSION_GRANTED

    fun on(ctx: Context): Boolean = Store.of(ctx).navGest

    /** The switch. False = refused (no WRITE_SECURE_SETTINGS or unsupported): the UI says so. */
    fun set(ctx: Context, v: Boolean): Boolean {
        val s = Store.of(ctx)
        if (v && (!supported() || !permitted(ctx))) return false
        s.navGest = v
        sync(ctx, null)
        return true
    }

    private fun read(ctx: Context): Int = runCatching { Settings.Global.getInt(ctx.contentResolver, NavGestLogic.KEY, 0) }.getOrDefault(0)
    private fun write(ctx: Context, v: Int): Boolean = runCatching { Settings.Global.putInt(ctx.contentResolver, NavGestLogic.KEY, v) }.getOrDefault(false)

    /**
     * The one place that decides. [current] = app in front when the caller knows it (Guard tick), null = unknown
     * (then only the stored pause counts; the Guard tick and Acc's window events catch a bank app within seconds).
     */
    @Synchronized fun sync(ctx: Context, current: String?) {
        val s = Store.of(ctx)
        val bank = current != null && current in s.rules.accPause
        val hide = NavGestLogic.hide(s.navGest, permitted(ctx), Acc.running != null, s.accPaused.isNotEmpty(), bank, s.released)
        if (hide) {
            if (s.fsgPrev < 0) s.fsgPrev = read(ctx)
            if (read(ctx) != 1) write(ctx, 1)
        } else restoreLocked(ctx, s)
        Acc.running?.navStrips(hide)
    }

    /** Release / Acc gone: bar back now, strips off, whatever the switch says (the next [sync] may hide it again). */
    @Synchronized fun restore(ctx: Context) {
        restoreLocked(ctx, Store.of(ctx))
        Acc.running?.navStrips(false)
    }

    private fun restoreLocked(ctx: Context, s: Store) {
        val prev = s.fsgPrev
        if (prev < 0) return
        // keep the marker when the write failed: the next call retries
        if (write(ctx, prev)) s.fsgPrev = -1
    }

    /** Guard tick (also the watchdog: Acc gone or paused -> restore). */
    fun tick(ctx: Context, current: String?) { sync(ctx, current) }

    /** Guard.start (boot / process start): until Acc is connected the bar must be visible. */
    fun boot(ctx: Context) { if (Acc.running == null) restore(ctx) }

    /** Policy.release: undo it, and the switch too once released. */
    fun release(ctx: Context) { Store.of(ctx).navGest = false; restore(ctx) }

    // ---- strips (main thread, owned by one Acc instance) ----

    /** Three overlay windows. No per-frame allocation: fields are reused; touches only compare floats. */
    class Strips(private val svc: AccessibilityService) {
        private val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        private val views = arrayOfNulls<Strip>(3)
        val shown get() = views[0] != null

        fun show() {
            if (shown) return
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
            val d = dm.density
            val w = (18 * d).toInt(); val h = (16 * d).toInt()
            val top = dm.heightPixels / 4
            add(0, NavGestLogic.Edge.LEFT, WindowManager.LayoutParams(w, dm.heightPixels - top - h, 0, top, Gravity.TOP or Gravity.START))
            add(1, NavGestLogic.Edge.RIGHT, WindowManager.LayoutParams(w, dm.heightPixels - top - h, 0, top, Gravity.TOP or Gravity.END))
            add(2, NavGestLogic.Edge.BOTTOM, WindowManager.LayoutParams(dm.widthPixels, h, 0, 0, Gravity.BOTTOM or Gravity.START))
        }

        private fun add(i: Int, edge: NavGestLogic.Edge, base: WindowManager.LayoutParams) {
            val lp = base
            lp.type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            lp.format = PixelFormat.TRANSLUCENT
            if (Build.VERSION.SDK_INT >= 28) lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            lp.title = "kgNav$i"
            val v = Strip(svc, edge, (NavGestLogic.MIN_DP * svc.resources.displayMetrics.density), (NavGestLogic.STILL_DP * svc.resources.displayMetrics.density))
            if (runCatching { wm.addView(v, lp) }.isSuccess) views[i] = v
        }

        fun hide() {
            for (i in views.indices) { views[i]?.let { v -> runCatching { wm.removeViewImmediate(v) } }; views[i] = null }
        }
    }

    private class Strip(private val svc: AccessibilityService, private val edge: NavGestLogic.Edge, private val min: Float, private val still: Float) : View(svc) {
        private var x0 = 0f; private var y0 = 0f
        private var ax = 0f; private var ay = 0f      // anchor for "still"
        private var movedAt = 0L

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { x0 = e.rawX; y0 = e.rawY; ax = x0; ay = y0; movedAt = e.eventTime }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - ax; val dy = e.rawY - ay
                    if (dx * dx + dy * dy > still * still) { ax = e.rawX; ay = e.rawY; movedAt = e.eventTime }
                }
                MotionEvent.ACTION_UP -> {
                    val act = NavGestLogic.classify(edge, e.rawX - x0, e.rawY - y0, e.eventTime - movedAt, min)
                    if (act != NavGestLogic.Act.NONE) {
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        svc.performGlobalAction(when (act) {
                            NavGestLogic.Act.BACK -> AccessibilityService.GLOBAL_ACTION_BACK
                            NavGestLogic.Act.HOME -> AccessibilityService.GLOBAL_ACTION_HOME
                            else -> AccessibilityService.GLOBAL_ACTION_RECENTS
                        })
                    }
                }
            }
            return true
        }
    }
}
