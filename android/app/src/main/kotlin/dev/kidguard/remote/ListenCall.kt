package dev.kidguard

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject

/**
 * "Listen around" over the relay (docs/flows/remote.md 2.6): the relay's sync reply carries `call` {kind: "listen", st: "ring", sdp, ice}.
 * No screen and no tap: [Listen.openRtc] runs the same checks as the LAN stream (mic permission, notice visible, microphone
 * foreground service) and tells the child (beep + ongoing notification + log), then a headless WebView (ui/listen.html +
 * ui/assets/rtc.js, served from the APK) answers the offer with the microphone only. Opus mono 24 kbps, 10 minutes at most.
 * The relay, the parent's Stop, the watchdog or the cap end it through [stop].
 */
object ListenCall {
    @Volatile private var cur = ""        // id of the running session
    @Volatile private var offer: JSONObject? = null
    @Volatile private var done = ""       // ids already handled: a later sync must not start them again
    @Volatile private var app: Context? = null
    private var web: WebView? = null
    private val main = Handler(Looper.getMainLooper())
    private val cap = Runnable { stop("timeout") }
    private val ERR = setOf("no mic permission", "notice blocked", "mic blocked", "mic failed", "mic silenced", "mic stalled")

    val active get() = cur.isNotEmpty()

    /** From Call.fromRelay when `call.kind == "listen"`. */
    fun fromRelay(g: Guard, c: JSONObject, base: String, rid: String, token: String) {
        val id = c.optString("id"); if (id.isEmpty()) return
        when (c.optString("st")) {
            "ring" -> {
                if (id == cur || done.split(' ').contains(id)) return
                Call.relay = Triple(base, rid, token)
                if (Flavor.HOME) return
                if (cur.isNotEmpty() || Call.cur != null || CamCall.active) { Call.post(g, "end", id, "", "busy"); return }
                cur = id; offer = c; app = g.applicationContext   // claimed now: a second sync must not start it again
                Thread({
                    // same refusals as the LAN stream; the parent's page shows the reason ("ear.<reason>")
                    if (!Listen.openRtc(g)) { val why = Listen.lastError.ifEmpty { "mic failed" }; cur = ""; mark(id); Call.post(g, "end", id, "", why); return@Thread }
                    main.post { startWeb(g.applicationContext) }
                    main.postDelayed(cap, Listen.MAX_MS)
                }, "listen-call").start()
            }
            "end" -> if (id == cur) stop(c.optString("why"), tell = false)
        }
    }

    private fun mark(id: String) { done = (done.split(' ').takeLast(4) + id).joinToString(" ") }

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
        if (tell) Thread({ Call.post(ctx, "end", id, "", why.ifEmpty { "hangup" }) }, "listen-end").start()
        Listen.closeRtc(ctx, if (why in ERR) why else "")
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun startWeb(ctx: Context) {
        if (cur.isEmpty()) return
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
                        val file = when (u.path) { "/listen.html" -> "listen.html"; "/assets/rtc.js" -> "assets/rtc.js"; else -> return WebResourceResponse("text/plain", "utf-8", 404, "no", emptyMap(), null) }
                        val type = if (file.endsWith(".js")) "text/javascript" else "text/html"
                        return runCatching { WebResourceResponse(type, "utf-8", ctx.assets.open(file)) }.getOrNull()
                    }
                    override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest) = true
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onPermissionRequest(r: PermissionRequest) {
                        // our own page, the microphone only, and only while a session the child was told about is running
                        val ok = r.origin.toString().trimEnd('/') == Call.ORIGIN && cur.isNotEmpty() && Listen.active &&
                            r.resources.all { it == PermissionRequest.RESOURCE_AUDIO_CAPTURE }
                        if (ok) r.grant(r.resources) else r.deny()
                    }
                }
            }
            web = w
            w.onResume(); w.resumeTimers()   // never attached to a window: keep its timers and audio capture running
            w.loadUrl(Call.ORIGIN + "/listen.html")
        }.onFailure { Log.w("KidGuard", "listen webview", it); stop("mic failed") }
    }

    private class Bridge(private val ctx: Context) {
        /** {id, sdp, ice} of the offer, "" when none. */
        @JavascriptInterface fun offer(): String {
            val c = ListenCall.offer ?: return ""
            return JSONObject().put("id", c.optString("id")).put("sdp", c.optString("sdp")).put("ice", c.optJSONArray("ice") ?: org.json.JSONArray()).toString()
        }
        /** op = answer | end. Blocks: called from the page's JS thread. */
        @JavascriptInterface fun signal(op: String, sdp: String, why: String): String {
            val i = ListenCall.cur; if (i.isEmpty()) return """{"error":"no session"}"""
            if (op != "answer" && op != "end") return """{"error":"bad op"}"""
            return Call.post(ctx, op, i, sdp, why)
        }
        /** The page is done (it already told the relay when it had to). */
        @JavascriptInterface fun close(why: String) { stop(why, tell = false) }
    }
}
