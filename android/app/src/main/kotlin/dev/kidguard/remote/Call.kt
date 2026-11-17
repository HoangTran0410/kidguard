package dev.kidguard

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
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
import java.net.HttpURLConnection
import java.net.URL

/**
 * Video call from a parent (docs/flows/call.md). The relay's sync reply carries `call` {id, st, by, sdp, ice}: "ring" opens
 * [CallActivity] over everything (ringing, Answer / Decline), "end" closes it. The child always answers by hand; the WebView
 * page (ui/call.html + ui/assets/rtc.js) does WebRTC, this file posts its answer / end to the relay with the phone's token.
 */
object Call {
    private const val CH = "kg_call"
    private const val NID = 7306
    const val RING_MS = 45_000L
    const val ORIGIN = "https://appassets.androidplatform.net"

    /** The ringing / running call (relay thread writes, UI reads). */
    @Volatile var cur: JSONObject? = null; private set
    @Volatile private var relay: Triple<String, String, String>? = null   // base, id, phone token
    @Volatile var since = 0L; private set
    @Volatile private var done = ""   // ids already handled (answered, declined, ended): a later sync must not ring again
    @Volatile var activity: CallActivity? = null
    private var ring: Ringtone? = null
    private val main = Handler(Looper.getMainLooper())

    fun state(): JSONObject = JSONObject().put("on", since > 0).put("since", since)

    /** From Relay.sync: the relay's view of the call. */
    fun fromRelay(g: Guard, c: JSONObject, base: String, rid: String, token: String) {
        val id = c.optString("id"); if (id.isEmpty()) return
        when (c.optString("st")) {
            "ring" -> {
                if (id == cur?.optString("id") || done.split(' ').contains(id)) return
                relay = Triple(base, rid, token)
                // another call is on (a second parent): busy, never two screens
                if (cur != null) { post(g, "end", id, "", "busy"); return }
                if (Flavor.HOME) return
                cur = c
                Store.of(g).log(R.string.log_call_in, c.optString("by").ifBlank { g.getString(R.string.call_parent) })
                Thread({ grant(g) }, "call-grant").start()   // camera + mic before the child taps Answer
                Wake.now(g)
                open(g)
                ringStart(g)
                main.postDelayed({ if (cur?.optString("id") == id && since == 0L) remoteEnd(g, id, "missed") }, RING_MS)
            }
            "end" -> if (id == cur?.optString("id")) remoteEnd(g, id, c.optString("why"))
        }
    }

    private fun open(g: Context) {
        val i = Intent(g, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        runCatching { g.startActivity(i) }.onFailure { Log.w("KidGuard", "call activity", it) }
        // basic mode / a phone that blocks starts from the background: the full-screen notification opens it
        runCatching {
            val nm = g.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CH, g.getString(R.string.ch_call), NotificationManager.IMPORTANCE_HIGH))
            val pi = PendingIntent.getActivity(g, NID, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            nm.notify(NID, Notification.Builder(g, CH).setSmallIcon(android.R.drawable.sym_call_incoming)
                .setContentTitle(g.getString(R.string.call_from, who(g))).setContentText(g.getString(R.string.call_tap))
                .setCategory(Notification.CATEGORY_CALL).setOngoing(true).setContentIntent(pi).setFullScreenIntent(pi, true).build())
        }
    }

    fun who(ctx: Context) = cur?.optString("by")?.takeIf { it.isNotBlank() } ?: ctx.getString(R.string.call_parent)

    /** The parent hung up / the ring ran out / the relay ended it. */
    fun remoteEnd(ctx: Context, id: String, why: String) {
        if (cur?.optString("id") != id) return
        val a = activity
        if (a != null && since > 0) main.post { a.js("kgEnd(${JSONObject.quote(why)})") }   // the page shows why, then closes
        else finish(ctx, id, if (since > 0) "" else why)
    }

    /** Call over (any side): ringing off, screen closed, notification gone, a log line. */
    fun finish(ctx: Context, id: String, why: String) {
        if (cur?.optString("id") != id) return
        val took = if (since > 0) System.currentTimeMillis() - since else 0L
        cur = null; since = 0L; done = (done.split(' ').takeLast(4) + id).joinToString(" ")
        main.post { ringStop(); activity?.finish() }
        ctx.getSystemService(NotificationManager::class.java)?.cancel(NID)
        val s = Store.of(ctx)
        if (took > 0) s.log(R.string.log_call_done, ((took + 30_000) / 60_000).coerceAtLeast(1).toString())
        else if (why == "missed") s.log(R.string.log_call_missed)
        Guard.running?.kick()
    }

    fun answered() { since = System.currentTimeMillis(); main.post { ringStop() }; activity?.let { ctx -> ctx.getSystemService(NotificationManager::class.java)?.cancel(NID) }; Guard.running?.kick() }

    /** POST /c/<id>/call {op, id, sdp?, why?} with the phone's token. Blocks (bridge thread). Returns the relay's JSON or {"error":…}. */
    fun post(ctx: Context, op: String, id: String, sdp: String, why: String): String {
        val (base, rid, token) = relay ?: return """{"error":"no relay"}"""
        return runCatching {
            val c = (URL("$base/c/$rid/call").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; connectTimeout = 10_000; readTimeout = 15_000; doOutput = true; instanceFollowRedirects = false
                setRequestProperty("X-KG-Token", token); setRequestProperty("Content-Type", "application/json")
            }
            try {
                val body = JSONObject().put("op", op).put("id", id).apply { if (sdp.isNotEmpty()) put("sdp", sdp); if (why.isNotEmpty()) put("why", why) }
                c.outputStream.use { it.write(body.toString().toByteArray()) }
                (if (c.responseCode < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText().take(64_000) } ?: """{"error":"HTTP ${c.responseCode}"}"""
            } finally { c.disconnect() }
        }.getOrElse { Log.w("KidGuard", "call post", it); """{"error":"net"}""" }
    }

    /** Camera + mic for this call: Device Owner grants them itself; basic mode through our own adb (AutoGrant). */
    fun grant(ctx: Context) {
        val need = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).filter { ctx.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isEmpty()) return
        val dpm = ctx.getSystemService(DevicePolicyManager::class.java)
        if (dpm != null && dpm.isDeviceOwnerApp(ctx.packageName)) {
            for (p in need) runCatching { dpm.setPermissionGrantState(Policy.admin(ctx), ctx.packageName, p, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED) }
                .onFailure { Log.w("KidGuard", "call grant $p", it) }
        } else AutoGrant.camNow(ctx)
    }
    fun hasPerms(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
        ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun ringStart(ctx: Context) = main.post {
        runCatching {
            val am = ctx.getSystemService(AudioManager::class.java)
            ring = RingtoneManager.getRingtone(ctx, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))?.apply {
                audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                if (Build.VERSION.SDK_INT >= 28) isLooping = true
                if (am == null || am.ringerMode == AudioManager.RINGER_MODE_NORMAL) play()
            }
            val v = ctx.getSystemService(Vibrator::class.java)
            v?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 1200), 0))
        }.onFailure { Log.w("KidGuard", "call ring", it) }
    }
    fun ringStop() {
        runCatching { ring?.stop() }; ring = null
        runCatching { activity?.getSystemService(Vibrator::class.java)?.cancel() }
        runCatching { Guard.running?.getSystemService(Vibrator::class.java)?.cancel() }
    }
}

/**
 * The call screen: a WebView with ui/call.html served from the APK under https://appassets.androidplatform.net (a secure
 * origin: getUserMedia needs one). Over the lock screen, screen on. Leaving it (Home, another app) ends the call.
 */
class CallActivity : Activity() {
    private lateinit var web: WebView
    private val id get() = Call.cur?.optString("id") ?: ""

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (Call.cur == null) { finish(); return }
        Call.activity = this
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else @Suppress("DEPRECATION") window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        volumeControlStream = AudioManager.STREAM_VOICE_CALL
        web = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            addJavascriptInterface(Bridge(), "KG")
            webViewClient = object : WebViewClient() {
                // the APK's ui/ folder under a fixed https origin (what androidx WebViewAssetLoader does, without the library)
                override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? {
                    val u = r.url
                    if (u.scheme != "https" || u.host != "appassets.androidplatform.net") return WebResourceResponse("text/plain", "utf-8", 404, "no", emptyMap(), null)
                    val path = u.path ?: return null
                    val file = when (path) { "/call.html" -> "call.html"; "/assets/rtc.js" -> "assets/rtc.js"; else -> return WebResourceResponse("text/plain", "utf-8", 404, "no", emptyMap(), null) }
                    val type = if (file.endsWith(".js")) "text/javascript" else "text/html"
                    return runCatching { WebResourceResponse(type, "utf-8", assets.open(file)) }.getOrNull()
                }
                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest) = true   // nothing leaves this page
            }
            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(r: PermissionRequest) {
                    // only our own page, only camera + mic, and only once the child tapped Answer (Call.since)
                    val ok = r.origin.toString().trimEnd('/') == Call.ORIGIN && Call.since > 0 &&
                        r.resources.all { it == PermissionRequest.RESOURCE_VIDEO_CAPTURE || it == PermissionRequest.RESOURCE_AUDIO_CAPTURE }
                    if (ok) r.grant(r.resources) else r.deny()
                }
            }
        }
        setContentView(web)
        web.loadUrl(Call.ORIGIN + "/call.html")
    }

    fun js(code: String) { if (!isFinishing) web.evaluateJavascript(code, null) }

    override fun onStop() {
        super.onStop()
        // the camera stops in the background anyway: a call the child left is over (never a hidden camera).
        // While still ringing (screen went off, shade pulled) it keeps ringing: the notification brings it back.
        if (!isFinishing && !isChangingConfigurations && Call.since > 0) { val i = id; if (i.isNotEmpty()) { Thread { Call.post(this, "end", i, "", "hangup") }.start(); Call.finish(this, i, "") } }
    }
    override fun onDestroy() {
        if (Call.activity === this) Call.activity = null
        runCatching { web.destroy() }
        super.onDestroy()
    }
    @Deprecated("Back = decline / hang up, through the page")
    override fun onBackPressed() { js("kgBack()") }

    inner class Bridge {
        /** {id, by, sdp, ice} of the ringing call, "" when none. */
        @JavascriptInterface fun offer(): String {
            val c = Call.cur ?: return ""
            return JSONObject().put("id", c.optString("id")).put("by", Call.who(this@CallActivity)).put("sdp", c.optString("sdp"))
                .put("ice", c.optJSONArray("ice") ?: org.json.JSONArray()).toString()
        }
        /** The child tapped Answer: ringing off, camera + mic allowed for the page. False = no permission (the page says so). */
        @JavascriptInterface fun answerTap(): Boolean {
            if (!Call.hasPerms(this@CallActivity)) Call.grant(this@CallActivity)
            if (!Call.hasPerms(this@CallActivity)) return false
            Call.answered(); return true
        }
        /** op = answer | end. Blocks: called from the page's JS thread. */
        @JavascriptInterface fun signal(op: String, sdp: String, why: String): String {
            val i = id; if (i.isEmpty()) return """{"error":"no call"}"""
            if (op != "answer" && op != "end") return """{"error":"bad op"}"""
            return Call.post(this@CallActivity, op, i, sdp, why)
        }
        /** The page is done (after showing why for a moment). */
        @JavascriptInterface fun close(why: String) { val i = id; runOnUiThread { if (i.isNotEmpty()) Call.finish(this@CallActivity, i, why) else finish() } }
        /** UI words in the phone's language. */
        @JavascriptInterface fun words(): String {
            val o = JSONObject()
            for ((k, r) in listOf("from" to R.string.call_from, "answer" to R.string.call_answer, "decline" to R.string.call_decline, "end" to R.string.call_end,
                "mute" to R.string.call_mute, "cam" to R.string.call_cam, "conn" to R.string.call_conn, "ended" to R.string.call_ended, "low" to R.string.call_low,
                "camOff" to R.string.call_cam_off, "noperm" to R.string.call_noperm, "fail" to R.string.call_fail, "missed" to R.string.call_missed,
                "weak" to R.string.call_weak, "more" to R.string.call_more_given, "parentEnd" to R.string.call_parent_end))
                o.put(k, if (k == "from") getString(r, Call.who(this@CallActivity)) else getString(r))
            return o.toString()
        }
    }
}
