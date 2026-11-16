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

    /** Our service really up: the object exists AND it is still listed as enabled (a bank-app pause or the user can
     *  remove it from settings before onUnbind reaches us). */
    private fun accUp(ctx: Context): Boolean = Acc.running != null && runCatching {
        (Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "")
            .contains("${ctx.packageName}/${Acc::class.java.name}", true)
    }.getOrDefault(false)

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
            NavGestLogic.hide(s.navGest, permitted(ctx), accUp(ctx), s.accPaused.isNotEmpty(), bank, s.released)
        if (hide) {
            // never keep 1 as "before" (a leftover from an earlier stuck state would be restored as hidden again)
            if (s.fsgPrev < 0) s.fsgPrev = read(ctx).let { if (it == 1) 0 else it }
            if (read(ctx) != 1) write(ctx, 1)
        } else restoreLocked(ctx, s)
        strips(ctx, hide, s)
    }

    /** Strips while hidden, and also while a restore is still pending (write failed: no bar yet, so keep our gestures);
     *  off on the lock screen (its own unlock swipe; no navigation needed there). */
    private fun strips(ctx: Context, hide: Boolean, s: Store) {
        val locked = runCatching { (ctx.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager).isKeyguardLocked }.getOrDefault(false)
        Acc.running?.navStrips(((hide || test(ctx)) && !locked) || s.fsgPrev >= 0)
    }

    /** adb-only test of the strips on any phone (never touches the bar): `settings put global kg_nav_test <epoch ms>`
     *  shows them until that time. Only the shell (or a WRITE_SECURE_SETTINGS app) can set it. */
    private fun test(ctx: Context): Boolean =
        runCatching { Settings.Global.getLong(ctx.contentResolver, "kg_nav_test", 0L) > System.currentTimeMillis() }.getOrDefault(false)

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
        if (!supported()) return
        val home = ourHome(ctx)
        val to = NavGestLogic.restoreTo(s.fsgPrev, home, read(ctx))
        // keep the marker when the write failed: the next call retries
        val ok = to == null || write(ctx, to)
        if (ok) s.fsgPrev = -1
        // last resort: bar still hidden, our home, no strips -> a notification that brings the 3 buttons back
        val stuck = home && read(ctx) == 1 && Acc.running?.stripsShown != true
        stuckNotice(ctx, stuck)
    }

    // ---- "no buttons, no gestures" rescue (notification shade still opens from the top) ----
    private const val CH = "nav"
    private const val NOTIF = 7400

    private fun stuckNotice(ctx: Context, on: Boolean) = runCatching {
        val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
        if (!on) { nm.cancel(NOTIF); return@runCatching }
        val ch = Chan.open(nm, CH, ctx.getString(R.string.ch_nav), android.app.NotificationManager.IMPORTANCE_HIGH)
        val pi = fixIntent(ctx)
        nm.notify(NOTIF, android.app.Notification.Builder(ctx, ch).setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(ctx.getString(R.string.nav_stuck_t)).setContentText(ctx.getString(R.string.nav_stuck_b))
            .setContentIntent(pi).setOngoing(true)
            .addAction(android.app.Notification.Action.Builder(null, ctx.getString(R.string.nav_stuck_btn), pi).build()).build())
    }

    fun fixIntent(ctx: Context): android.app.PendingIntent = android.app.PendingIntent.getBroadcast(ctx, 0,
        android.content.Intent(ctx, NavFixReceiver::class.java), android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)

    /** The notification's button: buttons back by our permission, else by our own adb, else open Settings; switch off. */
    fun fix(ctx: Context) {
        val s = Store.of(ctx)
        s.navGest = false
        var ok = write(ctx, 0)
        if (!ok && !Flavor.HOME && SelfAdb.hasKey(ctx)) { runCatching { SelfAdb.run(ctx.applicationContext, "settings put global ${NavGestLogic.KEY} 0") }; ok = read(ctx) == 0 }
        if (ok) { s.fsgPrev = -1; stuckNotice(ctx, false); Acc.running?.navStrips(false) }
        else runCatching { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
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
        private val menu = Menu(svc, wm)

        // full-screen mode ([NavGestLogic.calm]): status bar hidden by the app in front, or a game in front
        private var barHidden = false
        private var game = false
        private var calm = false
        private val games = HashMap<String, Boolean>()

        /** Status bar shown / hidden, as the right strip's window sees it (insets on 30+, system UI flags before). */
        fun statusHidden(h: Boolean, src: String) {
            if (h == barHidden) return
            barHidden = h; android.util.Log.i("kgNav", "status bar hidden=$h ($src)"); apply(false)
        }

        /** Android 11+: the status bar window is (not) in the accessibility window list (Acc.barCheck, absent twice in a
         *  row before it counts: a moment without it while apps switch). */
        fun barWindow(present: Boolean) { if (Build.VERSION.SDK_INT >= 30) statusHidden(!present, "windows") }

        /** The app in front changed (Acc window event). Keyboards / shade keep the last answer. */
        fun front(pkg: String) {
            val g = games[pkg] ?: gameOf(pkg)?.also { games[pkg] = it } ?: return
            if (g == game) return
            game = g; android.util.Log.i("kgNav", "game in front=$g ($pkg)"); apply(false)
        }

        private fun gameOf(pkg: String): Boolean? = runCatching {
            val pm = svc.packageManager
            val ai = pm.getApplicationInfo(pkg, 0)
            @Suppress("DEPRECATION")
            val g = (Build.VERSION.SDK_INT >= 26 && ai.category == android.content.pm.ApplicationInfo.CATEGORY_GAME) ||
                ai.flags and android.content.pm.ApplicationInfo.FLAG_IS_GAME != 0
            NavGestLogic.frontGame(pm.getLaunchIntentForPackage(pkg) != null, pkg == svc.packageName || pkg == "dev.kidguard.home", g)
        }.getOrNull()

        /** Left strip off, bottom needs two swipes (owner 2026-11-16: "vuốt lần 2 về home như máy") and the right one opens the menu while calm; [force] = re-apply to fresh views. */
        private fun apply(force: Boolean) {
            val c = NavGestLogic.calm(barHidden, game, false)
            if (c == calm && !force) return
            calm = c
            if (!force) android.util.Log.i("kgNav", "full-screen mode=$c")
            views[0]?.setOff(c); views[2]?.calm = c; views[1]?.calm = c
            if (!c) menu.close()
        }

        /** All three or none (a half set would be re-added every tick). False = the overlay was refused. */
        fun show(): Boolean {
            if (shown) return true
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
            val d = dm.density
            // bottom 24 dp like HyperOS's own GestureStubHome (77-81 of 2712 px): at 12 dp a thumb's long press for Circle to
            // Search landed above it, on the app (Poco 2026-11-15). Taps on any strip are passed through ([Strip.passTap]),
            // so the keyboard's lowest row / an app's bottom bar still get them.
            val w = (18 * d).toInt(); val h = (24 * d).toInt()
            val top = dm.heightPixels / 10                        // like HyperOS's own stubs (260 of 2712 px): not the status bar corner
            // NOT LayoutParams(w, h, x, y, gravity): that 5-int constructor is (w, h, type, flags, format), so the gravity
            // was lost and every strip sat in the middle of the screen (Poco 2026-11-15: "swipes do nothing")
            // sides too: HyperOS's own edge Back (GestureStubLeft/Right, made by the system launcher) is NOT made while
            // another app is home; it only seemed to work because stubs created while the home chooser was up (no
            // default home) stayed until the home changed (Poco 2026-11-15, launcher restart did not bring them back)
            add(0, NavGestLogic.Edge.LEFT, w, dm.heightPixels - top - h, top, Gravity.TOP or Gravity.LEFT)
            add(1, NavGestLogic.Edge.RIGHT, w, dm.heightPixels - top - h, top, Gravity.TOP or Gravity.RIGHT)
            add(2, NavGestLogic.Edge.BOTTOM, dm.widthPixels, h, 0, Gravity.BOTTOM or Gravity.LEFT)
            if (views.any { it == null }) { hide(); return false }
            views[1]?.onMenu = { y -> menu.show(y) }
            if (Build.VERSION.SDK_INT < 30) probe()
            apply(true)
            return true
        }

        private fun add(i: Int, edge: NavGestLogic.Edge, w: Int, h: Int, y: Int, gravity: Int) {
            val lp = params(w, h, y, gravity, touchable = true)
            lp.title = "kgNav$i"
            val dm = svc.resources.displayMetrics.density
            val v = Strip(svc, wm, edge, NavGestLogic.MIN_DP * dm, NavGestLogic.STILL_DP * dm)
            v.wantW = w; v.wantH = h; v.lp = lp
            if (runCatching { wm.addView(v, lp) }.isSuccess) views[i] = v
        }

        /** Size check after layout: a strip bigger than asked would sit over the whole screen and eat every touch
         *  (2026-11-15: LayoutParams(w, h) is really (type, flags) -> MATCH_PARENT, the Poco was stuck). */
        // only TOO BIG is dangerous; 0 = not laid out yet (a rotation / config re-show just before the 400 ms check turned
        // the strips off for good on the Poco, 2026-11-15)
        fun sane(): Boolean = (views.all { v -> v != null && v.width <= v.wantW + 2 && v.height <= v.wantH + 2 } &&
            probe.let { it == null || it.width <= 3 && it.height <= 3 }).also {
            if (!it) android.util.Log.w("kgNav", "strips not sane: " + views.joinToString { v -> "${v?.width}x${v?.height}/${v?.wantW}x${v?.wantH}" })
        }

        // before Android 11 only: 1x1 px, no touches; gets the global system UI flags. From 11 on, an accessibility overlay
        // never sees the status bar in its insets (it sits above it: "hidden" with the bar shown, Poco 2026-11-16), so
        // there the accessibility window list decides ([barWindow], Acc.barCheck)
        private var probe: Probe? = null
        private fun probe() {
            if (probe != null) return
            val lp = params(1, 1, 0, Gravity.TOP or Gravity.LEFT, touchable = false).also { it.title = "kgNavProbe" }
            val v = Probe(svc) { h, src -> statusHidden(h, src) }
            if (runCatching { wm.addView(v, lp) }.isSuccess) probe = v
        }

        fun hide() {
            menu.close()
            probe?.let { runCatching { wm.removeViewImmediate(it) } }; probe = null; barHidden = false
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

    /** Is the app in front full screen? Before Android 11: the global system UI flags every window gets. */
    private class Probe(ctx: Context, private val onBar: (Boolean, String) -> Unit) : View(ctx) {
        @Suppress("DEPRECATION")
        override fun onWindowSystemUiVisibilityChanged(v: Int) {
            super.onWindowSystemUiVisibilityChanged(v)
            if (Build.VERSION.SDK_INT < 30) onBar(v and View.SYSTEM_UI_FLAG_FULLSCREEN != 0, "sysui")
        }
    }

    /**
     * One edge. While the finger is down, a small NOT_TOUCHABLE window ([Arrow]) shows a bubble that follows the
     * finger and grows with [NavGestLogic.progress]; at 1 it is armed (haptic). Lift while armed = the action; pull back
     * below the threshold first = nothing (like HyperOS's own back arrow).
     */
    private class Strip(private val svc: AccessibilityService, private val wm: WindowManager, private val edge: NavGestLogic.Edge,
                        private val min: Float, private val still: Float) : View(svc) {
        var wantW = 0; var wantH = 0
        var lp: WindowManager.LayoutParams? = null
        private var downAt = 0L
        /** Full-screen mode: right = an inward swipe opens the menu instead of Back; bottom = two swipes ([NavGestLogic.again]). */
        var calm = false
            set(v) { field = v; armedAt = 0L }
        private var armedAt = 0L
        var onMenu: ((Int) -> Unit)? = null
        private var off = false

        /** Full-screen mode: this strip takes no touches at all (left / bottom). */
        fun setOff(o: Boolean) {
            if (o == off) return
            off = o
            val l = lp ?: return
            l.flags = if (o) l.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else l.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            visibility = if (o) GONE else VISIBLE
            if (o) { removeCallbacks(catchAgain); arrowOff() }
            if (isAttachedToWindow) runCatching { wm.updateViewLayout(this, l) }
        }


        /** A tap (no move, short) belongs to the app below: stop catching touches, replay it there, catch again. */
        private fun passTap(x: Float, y: Float) {
            val l = lp ?: return
            l.flags = l.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            if (runCatching { wm.updateViewLayout(this, l) }.isFailure) return
            removeCallbacks(catchAgain); postDelayed(catchAgain, 500)          // whatever the gesture callback does
            postDelayed({
                val path = android.graphics.Path().apply { moveTo(x, y) }
                val g = android.accessibilityservice.GestureDescription.Builder()
                    .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 40)).build()
                val cb = object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(d: android.accessibilityservice.GestureDescription?) { removeCallbacks(catchAgain); post(catchAgain) }
                    override fun onCancelled(d: android.accessibilityservice.GestureDescription?) { removeCallbacks(catchAgain); post(catchAgain) }
                }
                if (!svc.dispatchGesture(g, cb, null)) { removeCallbacks(catchAgain); catchAgain.run() }
            }, 20)
        }
        private val catchAgain = Runnable {
            val l = lp ?: return@Runnable
            if (off || l.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE == 0) return@Runnable
            l.flags = l.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            if (isAttachedToWindow) runCatching { wm.updateViewLayout(this, l) }
        }
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

        override fun onDetachedFromWindow() { arrowOff(); removeCallbacks(catchAgain); super.onDetachedFromWindow() }

        // Recents: a timer, not the next MOVE (a finger held still sends no events) and not the lift (lifting drifts a
        // few px, which reset the old "still since" check: 2026-11-15 Poco "swipe up and hold does nothing")
        private var hold = false
        private var lastP = 0f
        // Circle to Search: finger held still on the bottom edge (POCO: long-press the handle)
        private var moved = false
        private var cts = false
        private val ctsCheck = Runnable {
            if (edge == NavGestLogic.Edge.BOTTOM && !moved && !armed) {
                cts = true; performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); circleToSearch(svc)
            }
        }
        private val holdCheck = Runnable {
            if (edge == NavGestLogic.Edge.BOTTOM && armed && !hold) {
                hold = true; performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); arrow?.set(lastP, true)
            }
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    x0 = e.rawX; y0 = e.rawY; ax = x0; ay = y0; movedAt = e.eventTime; downAt = e.eventTime; armed = false; hold = false
                    removeCallbacks(holdCheck); moved = false; cts = false
                    removeCallbacks(ctsCheck); if (edge == NavGestLogic.Edge.BOTTOM && !calm) postDelayed(ctsCheck, CTS_MS)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - ax; val dy = e.rawY - ay
                    if (cts) return true
                    if (!moved && (e.rawX - x0).let { it * it } + (e.rawY - y0).let { it * it } > still * still) { moved = true; removeCallbacks(ctsCheck) }
                    val p = NavGestLogic.progress(edge, e.rawX - x0, e.rawY - y0, min)
                    lastP = p
                    if (dx * dx + dy * dy > still * still) {
                        ax = e.rawX; ay = e.rawY; movedAt = e.eventTime
                        if (!hold) { removeCallbacks(holdCheck); postDelayed(holdCheck, NavGestLogic.HOLD_MS) }
                    }
                    val now = p >= 1f
                    if (now && !armed) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    armed = now
                    if (!armed) hold = false                       // pulled back: neither Home nor Recents
                    if (p > 0.15f) { arrowAt(e.rawX, e.rawY); arrow?.set(p, hold) }
                }
                MotionEvent.ACTION_UP -> {
                    removeCallbacks(holdCheck); removeCallbacks(ctsCheck)
                    arrowOff()
                    if (cts) return true
                    if (!moved && e.eventTime - downAt < CTS_MS) { passTap(e.rawX, e.rawY); return true }
                    // full-screen bottom, second swipe: half the distance is enough (seconds swipes are quick flicks: Poco 2026-11-16, 2 of 3 too short)
                    val second = calm && edge == NavGestLogic.Edge.BOTTOM && NavGestLogic.again(armedAt, e.eventTime)
                    val act = NavGestLogic.classify(edge, e.rawX - x0, e.rawY - y0, if (hold) NavGestLogic.HOLD_MS else 0L, if (second) min / 2 else min)
                    if (calm && act == NavGestLogic.Act.BACK) { onMenu?.invoke(e.rawY.toInt()); return true }
                    if (calm && edge == NavGestLogic.Edge.BOTTOM && act != NavGestLogic.Act.NONE) {
                        if (!NavGestLogic.again(armedAt, e.eventTime)) {
                            armedAt = e.eventTime; performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            runCatching { android.widget.Toast.makeText(svc, R.string.nav_again, android.widget.Toast.LENGTH_SHORT).show() }
                            android.util.Log.i("kgNav", "$edge $act armed"); return true
                        }
                        armedAt = 0L
                    }
                    val ok = go(svc, act)
                    android.util.Log.i("kgNav", "$edge $act ok=$ok")
                }
                MotionEvent.ACTION_CANCEL -> { removeCallbacks(holdCheck); removeCallbacks(ctsCheck); arrowOff() }
            }
            return true
        }
    }


    private const val CTS_MS = 500L

    /** Run a navigation action. HyperOS with another home: GLOBAL_ACTION_RECENTS only "blinks" home (the launcher loads
     *  its task list but can't show it); opening the launcher's RecentsActivity directly works (Poco 2026-11-15). */
    private fun go(svc: AccessibilityService, act: NavGestLogic.Act): Boolean =
        act != NavGestLogic.Act.NONE && (act == NavGestLogic.Act.RECENTS && miuiRecents(svc) || svc.performGlobalAction(when (act) {
            NavGestLogic.Act.BACK -> AccessibilityService.GLOBAL_ACTION_BACK
            NavGestLogic.Act.HOME -> AccessibilityService.GLOBAL_ACTION_HOME
            else -> AccessibilityService.GLOBAL_ACTION_RECENTS
        }))

    /**
     * Full-screen mode menu (owner 2026-11-16): a swipe from the right edge in a game shows Back / Home / Recents next
     * to the finger instead of acting. A touch anywhere else goes to the game AND closes the menu (watch-outside);
     * it also closes by itself after [MENU_MS]. Fixed size set as fields + size check after layout (2026-11-15 incident).
     */
    private class Menu(private val svc: AccessibilityService, private val wm: WindowManager) {
        private var v: View? = null
        private val main = android.os.Handler(android.os.Looper.getMainLooper())
        private val auto = Runnable { close() }

        fun show(y: Int) {
            close()
            val d = svc.resources.displayMetrics.density
            val btn = (56 * d).toInt(); val pad = (6 * d).toInt()
            val w = btn + 2 * pad; val h = 3 * btn + 2 * pad
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
            val lp = params(w, h, NavGestLogic.menuTop(y, h, dm.heightPixels, (24 * d).toInt()), Gravity.TOP or Gravity.RIGHT, true)
            lp.x = (16 * d).toInt()
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
            lp.title = "kgNavMenu"
            val box = object : android.widget.LinearLayout(svc) {
                // a touch outside (the game): close, and let the game have it (NOT_TOUCH_MODAL already passes it on).
                // Never hit-test it: another app's outside touch comes as (0, 0)
                override fun dispatchTouchEvent(e: MotionEvent): Boolean {
                    if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) { close(); return true }
                    return super.dispatchTouchEvent(e)
                }
            }
            box.orientation = android.widget.LinearLayout.VERTICAL
            box.setPadding(pad, pad, pad, pad)
            box.background = android.graphics.drawable.GradientDrawable().apply { setColor(0xE6202124.toInt()); cornerRadius = w / 2f }
            for ((icon, label, act) in listOf(
                Triple(R.drawable.li_arrow_left, R.string.nav_back, NavGestLogic.Act.BACK),
                Triple(R.drawable.li_house, R.string.nav_home, NavGestLogic.Act.HOME),
                Triple(R.drawable.li_square, R.string.nav_recents, NavGestLogic.Act.RECENTS))) {
                val b = android.widget.ImageView(svc)
                b.setImageResource(icon); b.setColorFilter(android.graphics.Color.WHITE)
                b.scaleType = android.widget.ImageView.ScaleType.CENTER
                b.contentDescription = svc.getString(label)
                b.setOnTouchListener { iv, e -> iv.alpha = if (e.actionMasked == MotionEvent.ACTION_DOWN) 0.5f else 1f; false }
                b.setOnClickListener { iv ->
                    iv.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    close()
                    android.util.Log.i("kgNav", "menu $act ok=${go(svc, act)}")
                }
                box.addView(b, android.widget.LinearLayout.LayoutParams(btn, btn))
            }
            if (runCatching { wm.addView(box, lp) }.isFailure) return
            v = box
            main.postDelayed(auto, MENU_MS)
            main.postDelayed({ if (v === box && (box.width > w + 2 || box.height > h + 2)) { android.util.Log.w("kgNav", "menu not sane"); close() } }, 400)
        }

        fun close() {
            main.removeCallbacks(auto)
            v?.let { runCatching { wm.removeViewImmediate(it) } }; v = null
        }
    }
    private const val MENU_MS = 5000L

    /**
     * Circle to Search: ask the voice-interaction service (the default assistant = the Google app) for a session the way
     * the nav-handle long press does: IVoiceInteractionManagerService.showSessionFromSession (transaction 3) with
     * `omni.entry_point` = 1. No permission needed (unlike `cmd voiceinteraction show` / ContextualSearchManager).
     * Same call as github.com/chenxiaolong/CTSLauncher. The system launcher's own path refuses while another app is home
     * (HyperOS CircleToSearchHelper `isThirdHome:true`, Poco 2026-11-15). Needs Google as the default assistant;
     * otherwise nothing happens (logged).
     */
    private fun circleToSearch(ctx: Context) = Thread {
        val ok = runCatching {
            val b = Class.forName("android.os.ServiceManager").getDeclaredMethod("getService", String::class.java)
                .invoke(null, "voiceinteraction") as android.os.IBinder
            val args = android.os.Bundle().apply {
                putLong("invocation_time_ms", android.os.SystemClock.elapsedRealtime()); putInt("omni.entry_point", 1)
            }
            val data = android.os.Parcel.obtain(); val reply = android.os.Parcel.obtain()
            try {
                data.writeInterfaceToken("com.android.internal.app.IVoiceInteractionManagerService")
                data.writeStrongBinder(null)
                data.writeInt(1); args.writeToParcel(data, 0)
                data.writeInt(android.service.voice.VoiceInteractionSession.SHOW_WITH_ASSIST or
                    android.service.voice.VoiceInteractionSession.SHOW_WITH_SCREENSHOT or
                    android.service.voice.VoiceInteractionSession.SHOW_SOURCE_ASSIST_GESTURE)
                if (Build.VERSION.SDK_INT >= 34) data.writeString(ctx.attributionTag)
                b.transact(3, data, reply, 0)
                reply.readException(); reply.readInt() != 0
            } finally { reply.recycle(); data.recycle() }
        }.onFailure { android.util.Log.w("kgNav", "circle to search", it) }.getOrDefault(false)
        android.util.Log.i("kgNav", "circle to search ok=$ok")
    }.start()

    /** The system launcher's own Recents screen (MIUI / HyperOS / POCO launcher), false when there is none. */
    private fun miuiRecents(ctx: Context): Boolean {
        for (pkg in listOf("com.mi.android.globallauncher", "com.miui.home")) {
            val i = android.content.Intent().setClassName(pkg, "com.miui.home.recents.RecentsActivity")
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            // the app on screen shrinks into the Recents screen; the real animation (the app becoming its card) is the system
            // launcher's RecentsAnimation, which only the nav bar / the launcher's own gestures can start
            val o = android.app.ActivityOptions.makeCustomAnimation(ctx, dev.kidguard.R.anim.kg_recents_in, dev.kidguard.R.anim.kg_recents_out).toBundle()
            if (runCatching { ctx.startActivity(i, o) }.isSuccess) return true
        }
        return false
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

/** Button of the "no navigation" notification ([NavGest.fix]). Explicit intent, not exported. */
class NavFixReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(c: Context, i: android.content.Intent?) {
        val r = goAsync()
        Thread({ try { NavGest.fix(c.applicationContext) } finally { r.finish() } }, "nav-fix").start()
    }
}
