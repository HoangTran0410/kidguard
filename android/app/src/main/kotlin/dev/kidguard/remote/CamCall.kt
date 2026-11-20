package dev.kidguard

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject

/**
 * "Live camera" over the relay (docs/flows/remote.md 2.16): the parent watches one camera of the phone, one way, like "listen around"
 * with a picture. The relay's sync reply carries `call` {kind: "cam", face: front|back, snd, st: "ring", sdp, ice}. No ring, no tap;
 * same consent as the photo on request (rule `watch.cam`), the child is told (ongoing notification for the whole session, a log line,
 * Android's own camera dot). 10 minutes at most; the parent's Stop, rule off, release or the cap end it.
 *
 * Camera from the background: since Android 11 an app only gets the camera while it is on screen or from a foreground service of type
 * `camera` that was started while it was allowed to ("while-in-use"). Device Owner is exempt, so [CamService] starts straight away;
 * otherwise [CamActivity] (translucent, never takes touches, gone after one frame) starts it from a visible moment. The capture itself
 * runs in a headless WebView (ui/cam.html + ui/assets/rtc.js from the APK) like ListenCall: no native WebRTC library.
 */
object CamCall {
    const val MAX_MS = Listen.MAX_MS
    const val CH = "kg_watch"
    const val NID = 14
    private const val VISIBLE_WAIT_MS = 4000L

    @Volatile private var cur = ""        // id of the running session
    @Volatile private var offer: JSONObject? = null
    @Volatile private var done = ""       // ids already handled: a later sync must not start them again
    @Volatile private var app: Context? = null
    @Volatile var snd = false; private set
    @Volatile var back = false; private set
    @Volatile private var since = 0L
    @Volatile private var svc = false     // CamService is in the foreground with the camera type
    private var web: WebView? = null
    private var wl: PowerManager.WakeLock? = null
    private val main = Handler(Looper.getMainLooper())
    private val cap = Runnable { stop("timeout") }

    val active get() = cur.isNotEmpty()

    /** From Call.fromRelay when `call.kind == "cam"`. */
    fun fromRelay(g: Guard, c: JSONObject, base: String, rid: String, token: String) {
        val id = c.optString("id"); if (id.isEmpty()) return
        when (c.optString("st")) {
            "ring" -> {
                if (id == cur || done.split(' ').contains(id)) return
                if (base.isNotEmpty()) Call.relay = Triple(base, rid, token)   // "" = a LAN call (LanCall): its answer / end stay on the phone
                if (Flavor.HOME) return
                if (cur.isNotEmpty() || MediaBusy.busy("cam")) { mark(id); Thread({ Call.post(g, "end", id, "", "busy") }, "cam-busy").start(); return }
                cur = id; offer = c; app = g.applicationContext   // claimed now: a second sync must not start it again
                snd = c.optBoolean("snd"); back = c.optString("face") == "back"
                Thread({
                    val why = check(g)
                    if (why != null) { cur = ""; mark(id); Call.post(g, "end", id, "", why); return@Thread }
                    begin(g)
                }, "cam-call").start()
            }
            "end" -> if (id == cur) stop(c.optString("why"), tell = false)
        }
    }

    private fun mark(id: String) { done = (done.split(' ').takeLast(4) + id).joinToString(" ") }
    private fun owner(c: Context) = c.getSystemService(DevicePolicyManager::class.java)?.isDeviceOwnerApp(c.packageName) == true

    /** null = may start, else the refusal the page shows (`cam.<why>`). */
    private fun check(g: Guard): String? {
        if (!RemoteSnap.on(g)) return "off"
        grant(g)
        if (g.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return "no camera permission"
        if (snd && g.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return "no mic permission"
        if (!Shot.canUse(g, back)) return "no such camera"
        if (!noticeVisible(g)) return "notice blocked"   // the child must be able to see that the camera is on
        // without Device Owner the camera needs a visible moment (CamActivity): a dark screen has none
        if (!owner(g) && g.getSystemService(PowerManager::class.java)?.isInteractive != true) return "screen off"
        return null
    }

    /** CAMERA (+ RECORD_AUDIO): Device Owner grants them itself, basic mode through our own adb (AutoGrant), like the call. */
    private fun grant(c: Context) {
        val need = listOfNotNull(Manifest.permission.CAMERA, if (snd) Manifest.permission.RECORD_AUDIO else null)
            .filter { c.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isEmpty()) return
        val dpm = c.getSystemService(DevicePolicyManager::class.java)
        if (dpm != null && dpm.isDeviceOwnerApp(c.packageName))
            for (p in need) runCatching { dpm.setPermissionGrantState(Policy.admin(c), c.packageName, p, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED) }
        else runCatching { AutoGrant.camNow(c) }
    }

    fun noticeVisible(c: Context): Boolean {
        val nm = c.getSystemService(NotificationManager::class.java) ?: return false
        nm.createNotificationChannel(NotificationChannel(CH, c.getString(R.string.ch_watch), NotificationManager.IMPORTANCE_HIGH))
        if (!nm.areNotificationsEnabled()) return false
        return (nm.getNotificationChannel(CH)?.importance ?: return false) >= NotificationManager.IMPORTANCE_DEFAULT
    }

    private fun begin(g: Guard) {
        since = System.currentTimeMillis()
        wl = runCatching {
            g.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "kidguard:cam").apply { setReferenceCounted(false); acquire(MAX_MS + 30_000) }
        }.getOrNull()
        runCatching { Store.of(g).log(R.string.log_cam_start) }
        main.postDelayed(cap, MAX_MS)
        if (owner(g)) { startService(g); return }   // Device Owner: exempt from the while-in-use rule
        val id = cur
        val shown = runCatching {
            g.startActivity(Intent(g, CamActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION).putExtra("id", id))
        }.isSuccess
        // the window did not come up (start from the background blocked): try anyway, the service says when Android refuses
        if (!shown) startService(g) else main.postDelayed({ if (cur == id && !svc && !started) startService(g) }, VISIBLE_WAIT_MS)
    }

    @Volatile private var started = false
    /** From CamActivity (on screen) or [begin]. */
    fun startService(c: Context) {
        if (cur.isEmpty() || started) return
        started = true
        runCatching { c.applicationContext.startForegroundService(Intent(c.applicationContext, CamService::class.java)) }
            .onFailure { Log.w("KidGuard", "cam service", it); stop("cam blocked") }
    }
    fun wants(id: String) = id.isNotEmpty() && id == cur && !started

    /** CamService is in the foreground with the camera type: the WebView may open the camera now. */
    fun serviceUp() { svc = true; main.post { startWeb() } }

    /** Session over (any side). [tell]: let the relay know (the parent's page then stops waiting). Safe to call twice. */
    fun stop(why: String, tell: Boolean = true) {
        val ctx = app ?: return
        val id = synchronized(this) { val i = cur; cur = ""; i }
        if (id.isEmpty()) return
        mark(id)
        main.removeCallbacks(cap)
        main.post {
            val w = web; web = null
            if (w != null) {
                runCatching { w.evaluateJavascript("kgEnd('')", null) }   // lets the page say bye on its DataChannel
                main.postDelayed({ runCatching { w.destroy() } }, 600)
            }
        }
        if (tell) Thread({ Call.post(ctx, "end", id, "", why.ifEmpty { "hangup" }) }, "cam-end").start()
        runCatching { ctx.stopService(Intent(ctx, CamService::class.java)) }
        ctx.getSystemService(NotificationManager::class.java)?.cancel(NID)
        runCatching { wl?.release() }; wl = null
        val t0 = since; svc = false; started = false; since = 0L
        if (t0 > 0) runCatching {
            val s = Store.of(ctx)
            val min = (System.currentTimeMillis() - t0 + 30_000) / 60_000
            val took = if (min < 1) ctx.getString(R.string.under_1_min) else ctx.resources.getQuantityString(R.plurals.listen_about, min.toInt(), min.toInt())
            if (why.isNotEmpty() && why !in setOf("hangup", "timeout", "off")) s.log(R.string.log_cam_err, why)
            s.log(R.string.log_cam_done, java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(t0)), took)
        }
        Guard.running?.kick()
    }

    /** The child's notice (also the foreground service's notification): which camera, sound or not. */
    fun notice(c: Context): Notification {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(NotificationChannel(CH, c.getString(R.string.ch_watch), NotificationManager.IMPORTANCE_HIGH))
        val text = c.getString(if (back) R.string.cam_notice_back else R.string.cam_notice_front) + (if (snd) " " + c.getString(R.string.cam_notice_snd) else "")
        return Notification.Builder(c, CH).setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(c.getString(R.string.cam_notice_title)).setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(Monitoring.openIntent(c)).build()
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun startWeb() {
        val ctx = app ?: return
        if (cur.isEmpty() || web != null) return
        runCatching {
            val w = WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                addJavascriptInterface(Bridge(ctx), "KG")
                webViewClient = object : WebViewClient() {
                    // the APK's ui/ folder under a fixed https origin (getUserMedia needs a secure origin); only the two files this page needs
                    override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? {
                        val u = r.url
                        if (u.scheme != "https" || u.host != "appassets.androidplatform.net") return WebResourceResponse("text/plain", "utf-8", 404, "no", emptyMap(), null)
                        val file = when (u.path) { "/cam.html" -> "cam.html"; "/assets/rtc.js" -> "assets/rtc.js"; else -> return WebResourceResponse("text/plain", "utf-8", 404, "no", emptyMap(), null) }
                        val type = if (file.endsWith(".js")) "text/javascript" else "text/html"
                        return runCatching { WebResourceResponse(type, "utf-8", ctx.assets.open(file)) }.getOrNull()
                    }
                    override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest) = true
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onPermissionRequest(r: PermissionRequest) {
                        // our own page, the camera (+ the mic only when the parent asked for sound), only while a told session runs
                        val allowed = if (snd) setOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE, PermissionRequest.RESOURCE_AUDIO_CAPTURE) else setOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                        val ok = r.origin.toString().trimEnd('/') == Call.ORIGIN && cur.isNotEmpty() && svc && r.resources.all { it in allowed }
                        if (ok) r.grant(r.resources) else r.deny()
                    }
                }
            }
            web = w
            w.onResume(); w.resumeTimers()   // never attached to a window: keep its timers and the capture running
            w.loadUrl(Call.ORIGIN + "/cam.html")
        }.onFailure { Log.w("KidGuard", "cam webview", it); stop("cam failed") }
    }

    private class Bridge(private val ctx: Context) {
        /** {id, sdp, ice, face, snd} of the offer, "" when none. */
        @JavascriptInterface fun offer(): String {
            val c = CamCall.offer ?: return ""
            return JSONObject().put("id", c.optString("id")).put("sdp", c.optString("sdp")).put("ice", c.optJSONArray("ice") ?: org.json.JSONArray())
                .put("face", if (back) "back" else "front").put("snd", snd).toString()
        }
        /** op = answer | end. Blocks: called from the page's JS thread. */
        @JavascriptInterface fun signal(op: String, sdp: String, why: String): String {
            val i = cur; if (i.isEmpty()) return """{"error":"no session"}"""
            if (op != "answer" && op != "end") return """{"error":"bad op"}"""
            return Call.post(ctx, op, i, sdp, why)
        }
        /** The parent switched cameras: the child's notice says which one is on. */
        @JavascriptInterface fun face(f: String) {
            back = f == "back"
            if (cur.isNotEmpty()) runCatching { ctx.getSystemService(NotificationManager::class.java)?.notify(NID, notice(ctx)) }
        }
        /** The page is done (it already told the relay when it had to). */
        @JavascriptInterface fun close(why: String) { stop(why, tell = false) }
    }
}

/** Holds the camera (+ mic) "while-in-use" for a live camera session or a Telegram clip (extra `clip`, [CamClip]); its notification is the child's notice. */
class CamService : Service() {
    override fun onBind(i: Intent?): IBinder? = null
    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        if (i?.getBooleanExtra("clip", false) == true) return clip()
        val ok = runCatching {
            val n = CamCall.notice(this)
            if (Build.VERSION.SDK_INT >= 29) startForeground(CamCall.NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or (if (CamCall.snd) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0))
            else startForeground(CamCall.NID, n)
        }.onFailure { Log.w("KidGuard", "cam foreground", it) }.isSuccess   // Android 14: not allowed from the background without a visible moment
        if (!ok) {
            // a service started with startForegroundService that stops without ever calling startForeground crashes the WHOLE process
            // (Guard included): go foreground with the type that needs nothing, then stop
            runCatching { if (Build.VERSION.SDK_INT >= 34) startForeground(CamCall.NID, CamCall.notice(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(CamCall.NID, CamCall.notice(this)) }
        }
        if (!ok || !CamCall.active) { if (CamCall.active) CamCall.stop("cam blocked"); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY }
        CamCall.serviceUp()
        return START_NOT_STICKY
    }
    private fun clip(): Int {
        val ok = runCatching {
            val n = CamClip.notice(this)
            if (Build.VERSION.SDK_INT >= 29) startForeground(CamClip.NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or (if (CamClip.snd) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0))
            else startForeground(CamClip.NID, n)
        }.onFailure { Log.w("KidGuard", "clip foreground", it) }.isSuccess
        // same rule as above: never stop a startForegroundService service before startForeground
        if (!ok) runCatching { if (Build.VERSION.SDK_INT >= 34) startForeground(CamClip.NID, CamClip.notice(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(CamClip.NID, CamClip.notice(this)) }
        if (!ok || !CamClip.waiting()) { CamClip.serviceUp(false); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY }
        CamClip.serviceUp(true)
        return START_NOT_STICKY
    }
    override fun onDestroy() { if (CamCall.active) CamCall.stop("hangup"); super.onDestroy() }
}

/**
 * The visible moment a live camera session needs without Device Owner (Android 11+ "while-in-use"): translucent, NEVER takes touches,
 * starts [CamService] once resumed and ends itself at once (2 s hard end, like SnapActivity's stuck-overlay rules).
 */
class CamActivity : Activity() {
    private val h = Handler(Looper.getMainLooper())
    private var clip = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        clip = intent.getBooleanExtra("clip", false)
        if (if (clip) !CamClip.wants() else !CamCall.wants(intent.getStringExtra("id").orEmpty())) { finish(); return }   // a stale intent
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        if (Build.VERSION.SDK_INT >= 27) setShowWhenLocked(true)
        h.postDelayed({ finish() }, 2000)
    }
    override fun onResume() {
        super.onResume()
        if (clip) CamClip.startService(this) else CamCall.startService(this)
        h.post { finish() }
    }
    override fun onStop() { super.onStop(); finish() }
    override fun onDestroy() { h.removeCallbacksAndMessages(null); super.onDestroy() }
}
