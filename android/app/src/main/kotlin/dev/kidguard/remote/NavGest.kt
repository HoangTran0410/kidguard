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
        // another home (POCO Launcher) gives its own gestures with fsg=1: ours would double them, so only while we are home;
        // strips that failed to attach once (overlay refused) = never hide until Acc reconnects
        val hide = !stripsFailed && ourHome(ctx) &&
            NavGestLogic.hide(s.navGest, permitted(ctx), Acc.running != null, s.accPaused.isNotEmpty(), bank, s.released)
        if (hide) {
            if (s.fsgPrev < 0) s.fsgPrev = read(ctx)
            if (read(ctx) != 1) write(ctx, 1)
        } else restoreLocked(ctx, s)
        strips(ctx, hide, s)
    }

    /** Strips while hidden, and also while a restore is still pending (write failed: no bar yet, so keep our gestures);
     *  off on the lock screen (its own unlock swipe; no navigation needed there). */
    private fun strips(ctx: Context, hide: Boolean, s: Store) {
        val locked = runCatching { (ctx.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager).isKeyguardLocked }.getOrDefault(false)
        Acc.running?.navStrips((hide && !locked) || s.fsgPrev >= 0)
    }

    /** Set from Acc when a strip could not be attached; cleared on Acc connect. */
    @Volatile var stripsFailed = false

    private fun ourHome(ctx: Context): Boolean = runCatching {
        ctx.packageManager.resolveActivity(android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME),
            PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName.let { it == ctx.packageName || it == "dev.kidguard.home" }
    }.getOrDefault(true)

    /** Release / Acc gone: bar back now, strips off, whatever the switch says (the next [sync] may hide it again). */
    @Synchronized fun restore(ctx: Context) {
        val s = Store.of(ctx)
        restoreLocked(ctx, s)
        Acc.running?.navStrips(s.fsgPrev >= 0)   // write failed: no bar yet, keep our gestures
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

        /** All three or none (a half set would be re-added every tick). False = the overlay was refused. */
        fun show(): Boolean {
            if (shown) return true
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
            val d = dm.density
            val w = (18 * d).toInt(); val h = (12 * d).toInt()   // bottom thin: it sits over the keyboard's lowest edge
            val top = dm.heightPixels / 4
            // NOT LayoutParams(w, h, x, y, gravity): that 5-int constructor is (w, h, type, flags, format), so the gravity
            // was lost and every strip sat in the middle of the screen (Poco 2026-11-15: "swipes do nothing")
            add(0, NavGestLogic.Edge.LEFT, w, dm.heightPixels - top - h, top, Gravity.TOP or Gravity.LEFT)
            add(1, NavGestLogic.Edge.RIGHT, w, dm.heightPixels - top - h, top, Gravity.TOP or Gravity.RIGHT)
            add(2, NavGestLogic.Edge.BOTTOM, dm.widthPixels, h, 0, Gravity.BOTTOM or Gravity.LEFT)
            if (views.any { it == null }) { hide(); return false }
            return true
        }

        private fun add(i: Int, edge: NavGestLogic.Edge, w: Int, h: Int, y: Int, gravity: Int) {
            val lp = params(w, h, y, gravity, touchable = true)
            lp.title = "kgNav$i"
            val dm = svc.resources.displayMetrics.density
            val v = Strip(svc, wm, edge, NavGestLogic.MIN_DP * dm, NavGestLogic.STILL_DP * dm)
            v.wantW = w; v.wantH = h
            if (runCatching { wm.addView(v, lp) }.isSuccess) views[i] = v
        }

        /** Size check after layout: a strip bigger than asked would sit over the whole screen and eat every touch
         *  (2026-11-15: LayoutParams(w, h) is really (type, flags) -> MATCH_PARENT, the Poco was stuck). */
        fun sane(): Boolean = views.all { v -> v != null && v.width in 1..maxOf(v.wantW, 1) + 2 && v.height in 1..maxOf(v.wantH, 1) + 2 }

        fun hide() {
            for (i in views.indices) { views[i]?.let { v -> runCatching { wm.removeViewImmediate(v) } }; views[i] = null }
        }
    }

    /** Overlay params. Width / height set as fields: NEVER LayoutParams(w, h) = (type, flags), which leaves MATCH_PARENT. */
    private fun params(w: Int, h: Int, y: Int, gravity: Int, touchable: Boolean) = WindowManager.LayoutParams().apply {
        width = w; height = h; this.gravity = gravity; x = 0; this.y = y
        type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        format = PixelFormat.TRANSLUCENT
        if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }

    /**
     * One edge. While the finger is down, a small NOT_TOUCHABLE window ([Arrow]) shows a bubble that follows the
     * finger and grows with [NavGestLogic.progress]; at 1 it is armed (haptic). Lift while armed = the action; pull back
     * below the threshold first = nothing (like HyperOS's own back arrow).
     */
    private class Strip(private val svc: AccessibilityService, private val wm: WindowManager, private val edge: NavGestLogic.Edge,
                        private val min: Float, private val still: Float) : View(svc) {
        var wantW = 0; var wantH = 0
        private var x0 = 0f; private var y0 = 0f
        private var ax = 0f; private var ay = 0f      // anchor for "still"
        private var movedAt = 0L
        private var armed = false
        private var arrow: Arrow? = null
        private var arrowLp: WindowManager.LayoutParams? = null
        private val box = (96 * svc.resources.displayMetrics.density).toInt()

        private fun arrowAt(rx: Float, ry: Float) {
            val dm = svc.resources.displayMetrics
            val lp = arrowLp ?: when (edge) {
                NavGestLogic.Edge.LEFT -> params(box, box, 0, Gravity.TOP or Gravity.LEFT, false)
                NavGestLogic.Edge.RIGHT -> params(box, box, 0, Gravity.TOP or Gravity.RIGHT, false)
                NavGestLogic.Edge.BOTTOM -> params(box, box, 0, Gravity.BOTTOM or Gravity.LEFT, false)
            }.also { it.title = "kgNavArrow" }
            if (edge == NavGestLogic.Edge.BOTTOM) lp.x = (rx - box / 2).toInt().coerceIn(0, maxOf(0, dm.widthPixels - box))
            else lp.y = (ry - box / 2).toInt().coerceAtLeast(0)
            val a = arrow
            if (a == null) {
                val n = Arrow(svc, edge)
                if (runCatching { wm.addView(n, lp) }.isSuccess) { arrow = n; arrowLp = lp }
            } else runCatching { wm.updateViewLayout(a, lp) }
        }

        private fun arrowOff() { arrow?.let { a -> runCatching { wm.removeViewImmediate(a) } }; arrow = null; arrowLp = null }

        override fun onDetachedFromWindow() { arrowOff(); super.onDetachedFromWindow() }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { x0 = e.rawX; y0 = e.rawY; ax = x0; ay = y0; movedAt = e.eventTime; armed = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - ax; val dy = e.rawY - ay
                    if (dx * dx + dy * dy > still * still) { ax = e.rawX; ay = e.rawY; movedAt = e.eventTime }
                    val p = NavGestLogic.progress(edge, e.rawX - x0, e.rawY - y0, min)
                    if (p > 0.15f) { arrowAt(e.rawX, e.rawY); arrow?.set(p, edge == NavGestLogic.Edge.BOTTOM && p >= 1f && e.eventTime - movedAt >= NavGestLogic.HOLD_MS) }
                    val now = p >= 1f
                    if (now && !armed) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    armed = now
                }
                MotionEvent.ACTION_UP -> {
                    arrowOff()
                    val act = NavGestLogic.classify(edge, e.rawX - x0, e.rawY - y0, e.eventTime - movedAt, min)
                    if (act != NavGestLogic.Act.NONE) svc.performGlobalAction(when (act) {
                        NavGestLogic.Act.BACK -> AccessibilityService.GLOBAL_ACTION_BACK
                        NavGestLogic.Act.HOME -> AccessibilityService.GLOBAL_ACTION_HOME
                        else -> AccessibilityService.GLOBAL_ACTION_RECENTS
                    })
                }
                MotionEvent.ACTION_CANCEL -> arrowOff()
            }
            return true
        }
    }

    /** The bubble: a dark circle with an arrow (back: < or >, home: ^, recents after the hold: a square). No allocation per frame. */
    private class Arrow(ctx: Context, private val edge: NavGestLogic.Edge) : View(ctx) {
        private val d = ctx.resources.displayMetrics.density
        private val bg = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC202124.toInt() }
        private val fg = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE; style = android.graphics.Paint.Style.STROKE; strokeWidth = 3 * d
            strokeCap = android.graphics.Paint.Cap.ROUND; strokeJoin = android.graphics.Paint.Join.ROUND
        }
        private var p = 0f; private var hold = false
        fun set(progress: Float, recents: Boolean) { if (progress != p || recents != hold) { p = progress; hold = recents; invalidate() } }

        override fun onDraw(c: android.graphics.Canvas) {
            val k = p.coerceIn(0f, 1f)
            val r = (10 + 14 * k) * d
            val travel = (width / 2f - r) * k           // the bubble slides in from the edge as the finger goes
            val cx: Float; val cy: Float
            when (edge) {
                NavGestLogic.Edge.LEFT -> { cx = r + travel; cy = height / 2f }
                NavGestLogic.Edge.RIGHT -> { cx = width - r - travel; cy = height / 2f }
                NavGestLogic.Edge.BOTTOM -> { cx = width / 2f; cy = height - r - travel }
            }
            bg.alpha = (120 + 135 * k).toInt()
            c.drawCircle(cx, cy, r, bg)
            fg.alpha = if (p >= 1f) 255 else 150
            val a = 6 * d
            when {
                hold -> c.drawRect(cx - a, cy - a, cx + a, cy + a, fg)
                edge == NavGestLogic.Edge.LEFT -> { c.drawLine(cx + a / 2, cy - a, cx - a / 2, cy, fg); c.drawLine(cx - a / 2, cy, cx + a / 2, cy + a, fg) }
                edge == NavGestLogic.Edge.RIGHT -> { c.drawLine(cx - a / 2, cy - a, cx + a / 2, cy, fg); c.drawLine(cx + a / 2, cy, cx - a / 2, cy + a, fg) }
                else -> { c.drawLine(cx - a, cy + a / 2, cx, cy - a / 2, fg); c.drawLine(cx, cy - a / 2, cx + a, cy + a / 2, fg) }
            }
        }
    }
}
