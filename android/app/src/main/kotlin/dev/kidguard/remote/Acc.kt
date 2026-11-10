package dev.kidguard

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Accessibility side: live screen, remote input, instant app-switch events. See docs/protocol.md. */
class Acc : AccessibilityService() {
    override fun getResources(): android.content.res.Resources = Lang.res(baseContext) ?: super.getResources()   // in-app language (Android 8-12)
    private var lastPkg: String? = null
    private var lastPoke = 0L

    /** Screen on / unlock: bring Guard back if it was stopped while this service lives (Guard.start is a no-op after release). */
    private val wakeUp = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context, i: android.content.Intent) { if (Guard.running == null) Guard.start(c) }
    }

    // The system rebinds an enabled accessibility service after its process was killed or crashed (not after a real
    // force-stop): that is the first code of ours to run again, so restart Guard + its watchdog alarm here.
    override fun onServiceConnected() {
        running = this; lastError = ""
        runCatching { Guard.start(this); Watchdog.schedule(this) }
        runCatching { registerReceiver(wakeUp, android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_ON).apply { addAction(android.content.Intent.ACTION_USER_PRESENT) }) }
        // back on (after a bank pause, a restart): no window event comes for the app already in front, so check it now
        main.postDelayed({ runCatching { rootInActiveWindow?.packageName?.toString()?.let { if (it != packageName && it !in Store.of(this).rules.accPause) basicGuard(it) } } }, 600)
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e == null) return
        if (LauncherSafe.shadeBlocked && (e.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED || e.packageName?.toString() in SHADE_PKGS)) {
            main.removeCallbacks(shadeCheck); main.post(shadeCheck)
        }
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString() ?: return
        // a bank app that refuses accessibility services came up without our launcher (Recents, a link): step aside now
        if (pkg in Store.of(this).rules.accPause) { AccPause.pauseLate(this, pkg); return }
        if (pkg != packageName) basicGuard(pkg)
        if (TaskLogic.dropTasks(pkg, e.className?.toString(), packageName, homes())) dropTasks()
        if (pkg == lastPkg) return
        lastPkg = pkg
        val wait = 1000 - (System.currentTimeMillis() - lastPoke)
        if (wait > 0) {
            // inside the throttle window: poke once more when it ends so the last app is never missed
            main.removeCallbacks(trail)
            main.postDelayed(trail, wait + 20)
        } else pokeNow()
    }

    /**
     * Rule launcher.shade: the system quick settings (control center) opened -> close it; the notification shade alone
     * stays. Found by its window: a tall SystemUI window showing quick_settings_panel (AOSP, ColorOS: measured), or
     * HyperOS's control center plugin window. Not on the lock screen.
     * Checked again 0.3 s later: a finger still pulling can re-open it.
     */
    private var shadeTries = 0
    private val shadeCheck = Runnable { shadeTries = 0; closeShade() }
    private fun closeShade() {
        if (!LauncherSafe.shadeBlocked) return
        if (runCatching { getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == true }.getOrDefault(true)) return
        if (!shadeOpen()) return
        val ok = if (Build.VERSION.SDK_INT >= 31) performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE) else performGlobalAction(GLOBAL_ACTION_BACK)
        android.util.Log.i("KidGuard", "shade closed ok=$ok")
        if (++shadeTries < 4) main.postDelayed({ closeShade() }, 300)
    }

    private fun shadeOpen(): Boolean = runCatching {
        val h = resources.displayMetrics.heightPixels
        val b = Rect()
        windows.any { w ->
            if (w.type != android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM) return@any false
            w.getBoundsInScreen(b)
            if (b.height() < h / 2) return@any false
            val root = w.root ?: return@any false
            val pkg = root.packageName?.toString() ?: return@any false
            when (pkg) {
                "com.android.systemui" -> SHADE_IDS.any { id -> root.findAccessibilityNodeInfosByViewId("$pkg:id/$id").any { it.isVisibleToUser } }
                in SHADE_PKGS -> true   // OEM control-center plugins: their whole window is the panel
                else -> false
            }
        }
    }.getOrDefault(false)

    private var homeSet: Set<String>? = null
    private fun homes(): Set<String> = homeSet ?: runCatching {
        packageManager.queryIntentActivities(android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME), 0)
            .map { it.activityInfo.packageName to it.activityInfo.name }.let { TaskLogic.homes(it, BasicLogic::isSettingsPkg) }
    }.getOrDefault(emptySet()).also { homeSet = it }

    /**
     * The child went Home or opened Recents: finish every KidGuard task so Recents has no KidGuard card. Recents still
     * shows the front-most task even with excludeFromRecents, and on ColorOS swiping that card is a real force-stop
     * (com.coloros.athena): Guard dies, alarms go, and Android drops our accessibility service from the enabled list.
     * The kid launcher's own task (HomeActivity, the HOME screen) is kept.
     */
    private fun dropTasks() {
        val am = getSystemService(android.app.ActivityManager::class.java) ?: return
        // never our own launcher's task: it IS the home screen, finishing it rebuilt the whole home on every return (slow phones lagged)
        runCatching { am.appTasks.forEach { t -> runCatching { if (t.taskInfo.baseIntent.component?.className != HomeActivity::class.java.name) t.finishAndRemoveTask() } } }
    }

    /** Basic mode (no Device Owner): bounce blocked apps and Settings pages that would remove KidGuard. Owner mode: no-op. */
    private fun basicGuard(pkg: String) {
        if (!basicOn()) return
        val reason = BasicLogic.bounceReason(pkg, packageName, blocked, prot)
        if (reason != null) { bounceApp(this, pkg, reason); return }
        if (BasicLogic.isSettingsPkg(pkg)) {
            // the page text is often not there yet when the event fires: look at 0.2 s, 0.8 s and 1.8 s
            main.removeCallbacks(scan1); main.removeCallbacks(scan2); main.removeCallbacks(scan3)
            scanPkg = pkg
            main.postDelayed(scan1, 200); main.postDelayed(scan2, 800); main.postDelayed(scan3, 1800)
        }
    }

    private var scanPkg = ""
    private val scan1 = Runnable { scan() }
    private val scan2 = Runnable { scan() }
    private val scan3 = Runnable { scan() }
    private var lastProtectLog = 0L

    /** "KidGuard Home" may be uninstalled / stopped unless it is the phone's HOME now (then it is the kid's launcher). */
    private fun freeNames(): List<String> {
        val home = runCatching {
            packageManager.resolveActivity(android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME),
                android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        }.getOrNull()
        return if (home == "dev.kidguard.home") emptyList() else listOf(getString(R.string.home_app))
    }

    private fun scan() {
        if (!basicOn()) return
        // nothing to protect before a parent pairs, and setup itself happens on these Settings pages (accessibility detail, autostart…)
        if (!Store.of(this).everPaired) return
        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return
        val pkg = root.packageName?.toString() ?: return
        if (pkg != scanPkg || !BasicLogic.isSettingsPkg(pkg)) return
        val labels = listOf("KidGuard", runCatching { applicationInfo.loadLabel(packageManager).toString() }.getOrDefault("KidGuard"))
        val nodes = collectNodes(root, 300)
        if (BasicLogic.isDateTimePage(pkg, nodes)) {
            // basic mode has no DISALLOW_CONFIG_DATE_TIME: the clock page is closed like the uninstall pages
            performGlobalAction(GLOBAL_ACTION_HOME)
            val now = System.currentTimeMillis()
            if (now - lastProtectLog > 30_000) { lastProtectLog = now; runCatching { Store.of(this).log(R.string.log_acc_clock) } }
        } else if (BasicLogic.selfProtect(pkg, nodes, labels, freeNames())) {
            performGlobalAction(GLOBAL_ACTION_HOME)
            val now = System.currentTimeMillis()
            if (now - lastProtectLog > 30_000) {
                lastProtectLog = now
                runCatching { Store.of(this).log(R.string.log_acc_settings) }
            }
        }
    }

    /** Breadth-first text of the window, at most [budget] nodes (so a huge list never stalls the service). */
    private fun collectNodes(root: AccessibilityNodeInfo, budget: Int): List<BasicLogic.Node> {
        val out = ArrayList<BasicLogic.Node>()
        val q = ArrayDeque<Pair<AccessibilityNodeInfo, Boolean>>()
        q.add(root to false)
        var seen = 0
        while (q.isNotEmpty() && seen < budget) {
            val (n, parentClickable) = q.removeFirst(); seen++
            val click = n.isClickable || parentClickable
            n.text?.toString()?.takeIf { it.isNotBlank() }?.let { out += BasicLogic.Node(it, click, n.isCheckable) }
            n.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { out += BasicLogic.Node(it, click, n.isCheckable) }
            for (i in 0 until n.childCount) n.getChild(i)?.let { q.add(it to click) }
        }
        return out
    }

    private val trail = Runnable { pokeNow() }
    private fun pokeNow() { lastPoke = System.currentTimeMillis(); runCatching { Guard.poke(this) } }

    override fun onInterrupt() {}

    /** Find-my-phone ring: a volume key stops it (and is eaten). Every other key passes straight through. */
    override fun onKeyEvent(e: android.view.KeyEvent): Boolean {
        if (!Ring.active || (e.keyCode != android.view.KeyEvent.KEYCODE_VOLUME_UP && e.keyCode != android.view.KeyEvent.KEYCODE_VOLUME_DOWN)) return false
        if (e.action == android.view.KeyEvent.ACTION_UP) { runCatching { Store.of(this).log(R.string.log_ring_stopped) }; Ring.stop(this) }
        return true
    }
    override fun onUnbind(intent: android.content.Intent?): Boolean { drop(); return super.onUnbind(intent) }
    override fun onDestroy() { drop(); super.onDestroy() }
    private fun drop() { runCatching { unregisterReceiver(wakeUp) }; main.removeCallbacks(trail); main.removeCallbacks(scan1); main.removeCallbacks(scan2); main.removeCallbacks(scan3); if (running === this) running = null; lastError = "acc off"; hideNotice() }

    companion object {
        @Volatile var running: Acc? = null
        @Volatile var lastError: String = "acc off"
        /** Basic mode: what the rules block right now (package -> reason code), set by Guard each tick; empty in owner mode. */
        @Volatile var blocked: Map<String, String> = emptyMap()
        @Volatile var prot: Set<String> = emptySet()
        private var basicAt = 0L
        private var basicVal = false
        private val lastBounce = HashMap<String, Long>()

        /** [Basic.enforcing] cached for 3 s (window events come in bursts). */
        fun basicOn(): Boolean {
            val ctx = running ?: return false
            val now = System.currentTimeMillis()
            if (now - basicAt > 3_000) { basicAt = now; basicVal = Basic.enforcing(ctx) }
            return basicVal
        }

        /** Send the child HOME and tell them why (once per 10 s per app). Used by the window events and the guard tick. */
        fun bounceApp(ctx: Context, pkg: String, code: String) {
            val a = running
            if (a?.performGlobalAction(GLOBAL_ACTION_HOME) != true) runCatching {
                ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            // the app's audio would play on behind the home screen (every bounce, not only the notified one)
            BasicMedia.pauseOf(ctx, pkg)
            val now = System.currentTimeMillis()
            synchronized(lastBounce) {
                if (now - (lastBounce[pkg] ?: 0L) < 10_000) return
                lastBounce[pkg] = now
            }
            runCatching {
                val label = runCatching { ctx.packageManager.getApplicationInfo(pkg, 0).loadLabel(ctx.packageManager).toString() }.getOrDefault(pkg)
                val nm = ctx.getSystemService(NotificationManager::class.java)
                nm.createNotificationChannel(NotificationChannel("kg_block", ctx.getString(R.string.ch_block), NotificationManager.IMPORTANCE_HIGH))
                nm.notify(7400 + (pkg.hashCode() and 0xff), Notification.Builder(ctx, "kg_block").setSmallIcon(android.R.drawable.ic_lock_lock)
                    .setContentTitle(ctx.getString(R.string.notif_blocked, label, BasicLogic.reasonText(code, ResTr(ctx))))
                    // v0.9.7: the parent's note, else the friendly default
                    .setContentText(Notes.clean(Store.of(ctx).rules.blockNote, Notes.NOTE_MAX).let { n -> if (n.isEmpty()) ctx.getString(R.string.policy_support) else ctx.getString(R.string.policy_note, n) }).setAutoCancel(true)
                    .setCategory(Notification.CATEGORY_STATUS).build())
            }
        }

        private const val CH = "kg_watch"
        private const val NID = 7301
        private val main = Handler(Looper.getMainLooper())
        // the shade's windows: AOSP / ColorOS / One UI SystemUI, and HyperOS / MIUI's control center plugin (unverified)
        private val SHADE_PKGS = setOf("com.android.systemui", "miui.systemui.plugin")
        // only the expanded quick settings: the notification shade alone shows quick_qs_panel (one row), kept usable
        private val SHADE_IDS = listOf("quick_settings_panel")
        private val exec = Executors.newSingleThreadExecutor()
        private val lock = Any()
        private var lastFrame: Frame? = null
        private var lastFrameAt = 0L
        private var lastFrameKey = ""
        private var lastCapture = 0L
        private var viewing = false
        private var controlled = false
        private var lastReEnableLog = 0L
        private var misses = 0
        private var rebind = false
        private val hide = Runnable { hideNotice() }

        /** Enabled in Settings and connected right now. */
        fun isOn(ctx: Context): Boolean = running != null

        /** Android 13+ "Restricted setting": an APK installed from a browser / file manager may not turn on Accessibility
         *  until the user allows it in App info → ⋮ → "Allow restricted settings". True while that block is likely on. */
        fun restricted(ctx: Context): Boolean {
            if (Build.VERSION.SDK_INT < 33 || running != null) return false
            if (ctx.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED) return false   // setup turns it on itself
            return runCatching {
                val ao = ctx.getSystemService(android.app.AppOpsManager::class.java)
                ao.unsafeCheckOpNoThrow("android:access_restricted_settings", android.os.Process.myUid(), ctx.packageName) != android.app.AppOpsManager.MODE_ALLOWED
            }.getOrDefault(true)
        }

        /** App info page, where the ⋮ menu has "Allow restricted settings". */
        fun appInfo(ctx: Context) = android.content.Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${ctx.packageName}")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)

        /**
         * Turns our service back on if the child turned it off (needs WRITE_SECURE_SETTINGS from setup).
         * True only when the service is connected now. If our entry is present but never connects (crashed service),
         * after 3 misses the entry is removed; the next call re-adds it, which forces a rebind.
         */
        fun ensureEnabled(ctx: Context, quiet: Boolean = false): Boolean {
            if (running != null) { misses = 0; return true }
            if (ctx.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") != PackageManager.PERMISSION_GRANTED) return false
            val cr = ctx.contentResolver
            val me = "${ctx.packageName}/${Acc::class.java.name}"
            val cur = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            val has = cur.split(':').any { it.equals(me, true) }
            runCatching {
                if (has) {
                    if (++misses > 2) {
                        misses = 0; rebind = true
                        Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, without(cur, me))
                        Store.of(ctx).log(R.string.log_acc_rebind)
                    }
                } else {
                    Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, if (cur.isEmpty()) me else "$cur:$me")
                    Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
                    if (rebind || quiet) rebind = false   // quiet: our own bank-app pause ended, not the child
                    else {
                        val now = System.currentTimeMillis()
                        if (now - lastReEnableLog > 3_600_000) { lastReEnableLog = now; Store.of(ctx).log(R.string.log_acc_reenabled) }
                    }
                }
            }
            return running != null
        }

        private fun without(cur: String, me: String) = cur.split(':').filter { it.isNotEmpty() && !it.equals(me, true) }.joinToString(":")

        /** Removes our entry from the enabled accessibility services, keeping the others. Used by release. */
        fun release(ctx: Context) {
            runCatching {
                val cr = ctx.contentResolver
                val me = "${ctx.packageName}/${Acc::class.java.name}"
                val cur = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
                Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, without(cur, me))
            }
        }

        /** Pause between two relay frames: projection frames (API < 30) come at most once a second. */
        fun frameGapMs(): Long = if (ProjLogic.useProjection(Build.VERSION.SDK_INT)) ProjLogic.GAP_MS else 333

        /** One JPEG of the screen, longest side <= [maxSide]. Blocks up to 3 s. Null + [lastError] when it cannot. */
        fun capture(maxSide: Int = 720, quality: Int = 50): Frame? {
            val svc = running ?: run { lastError = "acc off"; return null }
            if (Looper.myLooper() == Looper.getMainLooper()) { lastError = "busy"; return null }
            if (!(svc.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive) { lastError = "screen off"; return null }
            // Android 8-10: no takeScreenshot, frames come from MediaProjection (child consents once, see Proj.kt)
            if (ProjLogic.useProjection(Build.VERSION.SDK_INT)) {
                val f = Proj.capture(svc, maxSide, quality)
                if (f == null) lastError = Proj.err else { lastError = ""; noteViewing(svc) }
                return f
            }
            synchronized(lock) {
                val key = "$maxSide/$quality"
                val now = System.currentTimeMillis()
                lastFrame?.let { if (now - lastFrameAt < 350 && key == lastFrameKey) { noteViewing(svc); return it } }
                val f = shoot(svc, maxSide, quality)
                if (f != null) { lastFrame = f; lastFrameAt = System.currentTimeMillis(); lastFrameKey = key; lastError = ""; noteViewing(svc) }
                return f
            }
        }

        private fun shoot(svc: Acc, maxSide: Int, quality: Int): Frame? {
            var bmp: Bitmap? = null
            var err = ""
            val latch = CountDownLatch(1)
            svc.takeScreenshot(Display.DEFAULT_DISPLAY, exec, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(r: AccessibilityService.ScreenshotResult) {
                    try {
                        val hb = r.hardwareBuffer
                        try {
                            val hw = Bitmap.wrapHardwareBuffer(hb, r.colorSpace)
                            try { bmp = hw?.copy(Bitmap.Config.ARGB_8888, false) } finally { hw?.recycle() }
                        } finally { hb.close() }
                        if (bmp == null) err = "capture failed"
                    } catch (e: Throwable) { err = "capture failed" }
                    finally { latch.countDown() }
                }
                override fun onFailure(code: Int) {
                    err = if (code == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) "busy" else "capture failed ($code)"
                    latch.countDown()
                }
            })
            if (!latch.await(3, TimeUnit.SECONDS)) { lastError = "timeout"; return null }
            val b = bmp ?: run { lastError = err.ifEmpty { "capture failed" }; return null }
            try {
                val (w, h) = realSize(svc)
                val k = maxSide.toFloat() / maxOf(b.width, b.height)
                val s = if (k < 1f) Bitmap.createScaledBitmap(b, (b.width * k).toInt().coerceAtLeast(1), (b.height * k).toInt().coerceAtLeast(1), true) else b
                val out = ByteArrayOutputStream()
                try { s.compress(Bitmap.CompressFormat.JPEG, quality, out) } finally { if (s !== b) s.recycle() }
                return Frame(out.toByteArray(), w, h)
            } catch (e: Throwable) { lastError = "capture failed"; return null } finally { b.recycle() }
        }

        @Suppress("DEPRECATION")
        private fun realSize(svc: Acc): Pair<Int, Int> {
            val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val r: Rect = if (Build.VERSION.SDK_INT >= 30) wm.maximumWindowMetrics.bounds
            else android.util.DisplayMetrics().also { wm.defaultDisplay.getRealMetrics(it) }.let { Rect(0, 0, it.widthPixels, it.heightPixels) }
            return r.width() to r.height()
        }

        private fun noteViewing(svc: Acc) {
            lastCapture = System.currentTimeMillis()
            main.post {
                if (!viewing) {
                    viewing = true
                    Store.of(svc).log(R.string.log_view_start)
                    notice(svc, svc.getString(R.string.notice_viewing))
                }
                main.removeCallbacks(hide)
                main.postDelayed(hide, 5000)
            }
        }

        /** The child-visible notice; skipped when the parent turned it off (rule `showWatching`). Main thread. */
        private fun notice(svc: Acc, title: String) {
            if (!Store.of(svc).rules.showWatching) return
            val nm = svc.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CH, svc.getString(R.string.ch_viewing), NotificationManager.IMPORTANCE_DEFAULT))
            nm.notify(NID, Notification.Builder(svc, CH).setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle(title).setOngoing(true).setOnlyAlertOnce(true).build())
        }

        private fun hideNotice() {
            main.post {
                viewing = false; controlled = false
                running?.getSystemService(NotificationManager::class.java)?.cancel(NID)
            }
        }

        /** Runs one input object (docs/protocol.md "Input"). Returns "ok" or a short error. */
        fun input(o: JSONObject): String {
            val svc = running ?: return "acc off"
            val type = o.optString("type")
            noteViewing(svc)
            val (w, h) = realSize(svc)
            fun fx(k: String) = (o.optDouble(k, Double.NaN)).takeIf { !it.isNaN() }?.coerceIn(0.0, 1.0)
            val res: String = when (type) {
                "tap", "long" -> {
                    val x = fx("x"); val y = fx("y")
                    if (x == null || y == null) "bad input"
                    else gesture(svc, Path().apply { moveTo((x * (w - 1)).toFloat(), (y * (h - 1)).toFloat()) }, if (type == "tap") 50 else 600)
                }
                "swipe" -> {
                    val x1 = fx("x1"); val y1 = fx("y1"); val x2 = fx("x2"); val y2 = fx("y2")
                    if (x1 == null || y1 == null || x2 == null || y2 == null) "bad input"
                    else gesture(svc, Path().apply {
                        moveTo((x1 * (w - 1)).toFloat(), (y1 * (h - 1)).toFloat()); lineTo((x2 * (w - 1)).toFloat(), (y2 * (h - 1)).toFloat())
                    }, o.optLong("ms", 300).coerceIn(50, 3000))
                }
                "key" -> {
                    val a = when (o.optString("key")) {
                        "back" -> AccessibilityService.GLOBAL_ACTION_BACK
                        "home" -> AccessibilityService.GLOBAL_ACTION_HOME
                        "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
                        "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
                        "quick" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
                        "lock" -> if (Build.VERSION.SDK_INT >= 28) AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN else -2   // API 26-27: DPM lockNow
                        "power" -> AccessibilityService.GLOBAL_ACTION_POWER_DIALOG
                        else -> -1
                    }
                    if (a == -2) (if (Policy.lockNow(svc)) "ok" else "failed")
                    else if (a < 0) "bad input" else if (svc.performGlobalAction(a)) "ok" else "failed"
                }
                "text" -> {
                    val f = svc.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    if (f == null || !f.isEditable) "no field"
                    else if (f.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
                            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, o.optString("text")) })) "ok" else "failed"
                }
                else -> "bad input"
            }
            if (res == "ok") main.post {
                if (!controlled) { controlled = true; Store.of(svc).log(R.string.log_control); notice(svc, svc.getString(R.string.notice_control)) }
            }
            return res
        }

        private fun gesture(svc: Acc, p: Path, ms: Long): String =
            if (svc.dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, ms)).build(), null, null)) "ok" else "failed"
    }

    /** [w], [h]: the real screen size in px, so the page can map clicks. */
    class Frame(val jpeg: ByteArray, val w: Int, val h: Int)
}
