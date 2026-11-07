package dev.kidguard

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import java.io.ByteArrayOutputStream

/**
 * Live screen on Android 8-10 (API 26-29), where AccessibilityService.takeScreenshot (API 30) does not exist:
 * MediaProjection into a small ImageReader. The child always sees the system consent dialog (we never auto-click it;
 * on Android 8-9 it has "Don't show again"), and a notification while it runs. Stops [ProjLogic.IDLE_MS] after the
 * last frame was asked for, on release, and when the system ends the projection. See docs/protocol.md.
 */
object Proj {
    /** off | asking | on | denied */
    @Volatile var state = "off"; private set
    @Volatile var err = ""; private set
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var app: Context? = null
    private var mp: MediaProjection? = null
    private var vd: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var vw = 0; private var vh = 0
    private var last: Acc.Frame? = null
    private var lastShot = 0L
    @Volatile private var lastUse = 0L
    private var askedAt = 0L; private var deniedAt = 0L
    // consent result handed from ProjActivity to ProjService (a projection may only be made once the service is foreground on API 29)
    @Volatile private var grantCode = 0
    @Volatile private var grantData: Intent? = null

    private val idle = object : Runnable {
        override fun run() {
            if (System.currentTimeMillis() - lastUse > ProjLogic.IDLE_MS) app?.let { stop(it) } else main.postDelayed(this, 5_000)
        }
    }

    /** A JPEG of the screen, longest side <= [maxSide]; null + [err] ("need consent" | "denied" | "timeout" | "capture failed"). Not on the main thread. */
    fun capture(ctx: Context, maxSide: Int, quality: Int): Acc.Frame? {
        app = ctx.applicationContext
        lastUse = System.currentTimeMillis()
        val p = mp
        if (p == null) {
            val now = System.currentTimeMillis()
            synchronized(lock) {
                if (mp == null && ProjLogic.next(state, now, askedAt, deniedAt) == "ask") {
                    state = "asking"; askedAt = now
                    runCatching {
                        ctx.startActivity(Intent(ctx, ProjActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS))
                    }.onFailure { Log.w("KidGuard", "proj ask", it) }
                }
            }
            err = ProjLogic.waitError(state); return null
        }
        synchronized(lock) {
            val (w, h) = realSize(ctx)
            val (tw, th) = ProjLogic.fit(w, h, maxSide)
            if (vd == null || tw != vw || th != vh) runCatching { setup(ctx, p, tw, th) }.onFailure {
                Log.w("KidGuard", "proj setup", it); err = "capture failed"; return null
            }
            val now = System.currentTimeMillis()
            last?.let { if (now - lastShot < ProjLogic.GAP_MS) return it }
            // nothing new from the reader = the screen did not change: the last frame is still right
            var tries = if (last == null) 15 else 1
            while (tries-- > 0) {
                val f = grab(w, h, quality)
                if (f != null) { last = f; lastShot = System.currentTimeMillis(); err = ""; return f }
                if (tries > 0) Thread.sleep(100)
            }
            return last ?: run { err = "timeout"; null }
        }
    }

    private fun grab(w: Int, h: Int, quality: Int): Acc.Frame? {
        val img = runCatching { reader?.acquireLatestImage() }.getOrNull() ?: return null
        try {
            val pl = img.planes[0]
            val full = Bitmap.createBitmap(ProjLogic.rowPx(pl.rowStride, pl.pixelStride, img.width), img.height, Bitmap.Config.ARGB_8888)
            try {
                full.copyPixelsFromBuffer(pl.buffer)
                val b = if (full.width != img.width) Bitmap.createBitmap(full, 0, 0, img.width, img.height) else full
                try {
                    val out = ByteArrayOutputStream()
                    b.compress(Bitmap.CompressFormat.JPEG, quality, out)
                    return Acc.Frame(out.toByteArray(), w, h)
                } finally { if (b !== full) b.recycle() }
            } finally { full.recycle() }
        } catch (e: Throwable) { Log.w("KidGuard", "proj grab", e); return null } finally { img.close() }
    }

    private fun setup(ctx: Context, p: MediaProjection, tw: Int, th: Int) {
        val r = ImageReader.newInstance(tw, th, PixelFormat.RGBA_8888, 2)
        val dpi = ctx.resources.displayMetrics.densityDpi
        val old = reader
        val v = vd
        if (v == null) vd = p.createVirtualDisplay("kidguard", tw, th, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null)
        else { v.resize(tw, th, dpi); v.surface = r.surface }
        reader = r; vw = tw; vh = th; last = null
        runCatching { old?.close() }
    }

    @Suppress("DEPRECATION")
    private fun realSize(ctx: Context): Pair<Int, Int> {
        val m = DisplayMetrics()
        (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(m)
        return m.widthPixels to m.heightPixels
    }

    /** ProjActivity: the child answered the system dialog. */
    internal fun answered(ctx: Context, code: Int, data: Intent?) {
        if (code == Activity.RESULT_OK && data != null) {
            grantCode = code; grantData = data
            runCatching { ctx.startForegroundService(Intent(ctx, ProjService::class.java)) }.onFailure {
                Log.w("KidGuard", "proj service", it); state = "off"
            }
        } else {
            state = "denied"; deniedAt = System.currentTimeMillis()
            runCatching { Store.of(ctx).log(R.string.log_proj_denied) }
        }
    }

    /** ProjService is foreground: make the projection. */
    internal fun begin(svc: Service) {
        val data = grantData; grantData = null
        val p = data?.let { runCatching { svc.getSystemService(MediaProjectionManager::class.java).getMediaProjection(grantCode, it) }.onFailure { e -> Log.w("KidGuard", "proj get", e) }.getOrNull() }
        if (p == null) { state = "off"; svc.stopSelf(); return }
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { drop(svc.applicationContext, false) }
        }, main)
        synchronized(lock) { mp = p; state = "on"; err = "" }
        app = svc.applicationContext
        lastUse = System.currentTimeMillis()
        main.removeCallbacks(idle); main.postDelayed(idle, 5_000)
    }

    /** Ends the projection (idle, release, service gone). Safe to call any time, from any thread. */
    fun stop(ctx: Context) = drop(ctx.applicationContext, true)

    private fun drop(ctx: Context, stopProjection: Boolean) {
        main.removeCallbacks(idle)
        val p: MediaProjection?
        synchronized(lock) {
            p = mp; mp = null
            runCatching { vd?.release() }; vd = null
            runCatching { reader?.close() }; reader = null
            vw = 0; vh = 0; last = null
            if (state == "on" || state == "asking") state = "off"
        }
        if (stopProjection) runCatching { p?.stop() }
        runCatching { ctx.stopService(Intent(ctx, ProjService::class.java)) }
    }
}

/** Shows the system "start recording / casting?" dialog. Never auto-confirmed: the child has to see it and press it. */
class ProjActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (b != null) return   // recreated (rotation): the dialog is already up
        val m = getSystemService(MediaProjectionManager::class.java)
        runCatching { startActivityForResult(m.createScreenCaptureIntent(), 1) }.onFailure { Proj.answered(this, RESULT_CANCELED, null); finish() }
    }

    @Deprecated("plain Activity API, fine here")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        Proj.answered(applicationContext, resultCode, data)
        finish()
    }
}

/** Foreground service holding the projection (type mediaProjection, required on API 29). Its notice is always shown. */
class ProjService : Service() {
    override fun getResources(): android.content.res.Resources = Lang.res(baseContext) ?: super.getResources()
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, getString(R.string.ch_proj), NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, CH).setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle(getString(R.string.notice_proj)).setOngoing(true).build()
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) else startForeground(NID, n)
        }.onFailure { Log.w("KidGuard", "proj fg", it); stopSelf(); return START_NOT_STICKY }
        Proj.begin(this)
        return START_NOT_STICKY
    }

    override fun onDestroy() { Proj.stop(this); super.onDestroy() }

    private companion object { const val CH = "kg_proj"; const val NID = 7302 }
}
