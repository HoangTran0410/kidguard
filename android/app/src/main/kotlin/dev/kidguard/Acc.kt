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
    private var lastPkg: String? = null
    private var lastPoke = 0L

    override fun onServiceConnected() { running = this; lastError = "" }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString() ?: return
        if (pkg == lastPkg) return
        lastPkg = pkg
        val wait = 1000 - (System.currentTimeMillis() - lastPoke)
        if (wait > 0) {
            // inside the throttle window: poke once more when it ends so the last app is never missed
            main.removeCallbacks(trail)
            main.postDelayed(trail, wait + 20)
        } else pokeNow()
    }

    private val trail = Runnable { pokeNow() }
    private fun pokeNow() { lastPoke = System.currentTimeMillis(); runCatching { Guard.poke(this) } }

    override fun onInterrupt() {}
    override fun onUnbind(intent: android.content.Intent?): Boolean { drop(); return super.onUnbind(intent) }
    override fun onDestroy() { drop(); super.onDestroy() }
    private fun drop() { main.removeCallbacks(trail); if (running === this) running = null; lastError = "acc off"; hideNotice() }

    companion object {
        @Volatile var running: Acc? = null
        @Volatile var lastError: String = "acc off"

        private const val CH = "kg_watch"
        private const val NID = 7301
        private val main = Handler(Looper.getMainLooper())
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

        /**
         * Turns our service back on if the child turned it off (needs WRITE_SECURE_SETTINGS from setup).
         * True only when the service is connected now. If our entry is present but never connects (crashed service),
         * after 3 misses the entry is removed; the next call re-adds it, which forces a rebind.
         */
        fun ensureEnabled(ctx: Context): Boolean {
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
                        Store.of(ctx).log("Trợ năng không kết nối, thử bind lại")
                    }
                } else {
                    Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, if (cur.isEmpty()) me else "$cur:$me")
                    Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
                    if (rebind) rebind = false
                    else {
                        val now = System.currentTimeMillis()
                        if (now - lastReEnableLog > 3_600_000) { lastReEnableLog = now; Store.of(ctx).log("Trợ năng bị tắt, đã bật lại") }
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

        /** One JPEG of the screen, longest side <= [maxSide]. Blocks up to 3 s. Null + [lastError] when it cannot. */
        fun capture(maxSide: Int = 720, quality: Int = 50): Frame? {
            val svc = running ?: run { lastError = "acc off"; return null }
            if (Build.VERSION.SDK_INT < 30) { lastError = "unsupported"; return null }
            if (Looper.myLooper() == Looper.getMainLooper()) { lastError = "busy"; return null }
            if (!(svc.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive) { lastError = "screen off"; return null }
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
                    Store.of(svc).log("Bố mẹ bắt đầu xem màn hình")
                    notice(svc, "Bố mẹ đang xem màn hình")
                }
                main.removeCallbacks(hide)
                main.postDelayed(hide, 5000)
            }
        }

        /** The child-visible notice; skipped when the parent turned it off (rule `showWatching`). Main thread. */
        private fun notice(svc: Acc, title: String) {
            if (!Store.of(svc).rules.showWatching) return
            val nm = svc.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CH, "Đang xem màn hình", NotificationManager.IMPORTANCE_DEFAULT))
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
                        "lock" -> if (Build.VERSION.SDK_INT >= 28) AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN else -1
                        "power" -> AccessibilityService.GLOBAL_ACTION_POWER_DIALOG
                        else -> -1
                    }
                    if (a < 0) "bad input" else if (svc.performGlobalAction(a)) "ok" else "failed"
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
                if (!controlled) { controlled = true; Store.of(svc).log("Bố mẹ điều khiển từ xa"); notice(svc, "Bố mẹ đang điều khiển máy") }
            }
            return res
        }

        private fun gesture(svc: Acc, p: Path, ms: Long): String =
            if (svc.dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, ms)).build(), null, null)) "ok" else "failed"
    }

    /** [w], [h]: the real screen size in px, so the page can map clicks. */
    class Frame(val jpeg: ByteArray, val w: Int, val h: Int)
}
