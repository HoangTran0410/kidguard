package dev.kidguard

import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * The on-device parent session ("Bố mẹ quản lý trên máy này"). The token lives only here, in memory, as a hash; the
 * WebView gets it as an HttpOnly cookie for the local server (never in a URL, never visible to the page's JS).
 */
object ParentSession {
    const val COOKIE = "kgs"
    val ORIGIN = "http://127.0.0.1:${Guard.PORT}"
    private val s = AdminSession()

    /** Starts a session (after the PIN / Authenticator check) and returns its token for the cookie. */
    fun open(): String { val t = Store.randomHex(24); s.open(Store.sha256(t), System.currentTimeMillis()); return t }
    fun touch() = s.touch(System.currentTimeMillis())
    fun revoke() = s.revoke()
    fun active() = s.active(System.currentTimeMillis())
    fun valid(token: String?) = !token.isNullOrBlank() && s.valid(Store.sha256(token), System.currentTimeMillis())

    /** The session token from a `Cookie` header, or null. */
    fun fromCookie(h: String?): String? = h?.split(';')?.map { it.trim() }?.firstOrNull { it.startsWith("$COOKIE=") }
        ?.substringAfter('=')?.takeIf { it.matches(Regex("[0-9a-f]{48}")) }
}

class AdminActivity : Activity() {
    override fun attachBaseContext(base: Context) = super.attachBaseContext(Lang.wrap(base))
    private val ui = Handler(Looper.getMainLooper())
    private var web: WebView? = null
    private var ended = false
    private val check = object : Runnable { override fun run() { if (!ParentSession.active()) end() else ui.postDelayed(this, 5_000) } }
    private val screenOff = object : BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) = end() }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        val t0 = android.os.SystemClock.elapsedRealtime()
        super.onCreate(savedInstanceState)
        if (!ParentSession.active()) { finish(); return }
        registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF))
        val w = WebView(this)
        web = w
        w.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true
            allowFileAccess = false; allowContentAccess = false
            @Suppress("DEPRECATION") allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION") allowUniversalAccessFromFileURLs = false
            setGeolocationEnabled(false); javaScriptCanOpenWindowsAutomatically = false; setSupportMultipleWindows(false)
            mediaPlaybackRequiresUserGesture = true
        }
        w.addJavascriptInterface(Bridge(), "KGLocal")
        val u = Ui(this)
        w.setBackgroundColor(u.bg)
        // the first page load on an old phone takes a few seconds (WebView start + scripts): show that it is coming
        val wait = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL; gravity = android.view.Gravity.CENTER; setBackgroundColor(u.bg)
            isClickable = true
            addView(android.widget.ProgressBar(context).apply { isIndeterminate = true; indeterminateTintList = android.content.res.ColorStateList.valueOf(u.primary) },
                android.widget.LinearLayout.LayoutParams(u.dp(48), u.dp(48)))
            addView(android.widget.TextView(context).apply {
                text = getString(R.string.admin_loading); setTextColor(u.sub); textSize = 15f; gravity = android.view.Gravity.CENTER
                setPadding(u.dp(24), u.dp(16), u.dp(24), 0)
            })
        }
        var shown = false
        fun ready(why: String) {
            if (shown) return; shown = true
            android.util.Log.i("KG.perf", "admin page $why ${android.os.SystemClock.elapsedRealtime() - t0}ms (since PIN ok ${android.os.SystemClock.elapsedRealtime() - okAt}ms)")
            wait.animate().alpha(0f).setDuration(150).withEndAction { wait.visibility = android.view.View.GONE }.start()
        }
        w.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onProgressChanged(v: WebView, p: Int) { if (p >= 100) ready("progress") }
        }
        w.webViewClient = object : WebViewClient() {
            override fun onPageCommitVisible(v: WebView, url: String) { if (url.startsWith(ParentSession.ORIGIN)) ui.postDelayed({ ready("visible") }, 120) }
            override fun onPageFinished(v: WebView, url: String) { if (url.startsWith(ParentSession.ORIGIN)) ready("finished") }
            // only our own page: any other navigation (links to GitHub, the relay, …) is dropped
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest) = !ours(r.url)
            override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? =
                if (ours(r.url) || r.url.host == "tile.openstreetmap.org") null
                else WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", emptyMap(), "".byteInputStream())
        }
        setContentView(android.widget.FrameLayout(this).apply { addView(w); addView(wait) })
        android.util.Log.i("KG.perf", "admin create ${android.os.SystemClock.elapsedRealtime() - t0}ms")
        w.loadUrl("${ParentSession.ORIGIN}/")
        ui.postDelayed(check, 5_000)
    }

    private fun ours(u: Uri) = u.scheme == "http" && u.host == "127.0.0.1" && u.port == Guard.PORT

    private inner class Bridge {
        /** The page ends the session itself ("Xong" button, or a 401 once it expired). */
        @JavascriptInterface fun end() { ui.post { this@AdminActivity.end() } }
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean { ParentSession.touch(); return super.dispatchTouchEvent(ev) }
    override fun dispatchKeyEvent(event: KeyEvent?): Boolean { ParentSession.touch(); return super.dispatchKeyEvent(event) }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { val w = web; if (w != null && w.canGoBack()) w.goBack() else end() }

    /**
     * Not visible any more (home, Recents, another app) ends the session for good and removes the whole task, so no
     * Recents card is left (ColorOS force-stops an app whose card is swiped away). Back / a timeout finish first: Back
     * returns to MainActivity as usual. Only onStop, not onPause: on Android 8 pulling down the notification shade or a
     * system dialog (location accuracy prompt) pauses the page, and the parent's session was thrown away mid-task.
     * The screen turning off ends it through [screenOff].
     */
    override fun onStop() {
        super.onStop()
        if (isFinishing || isChangingConfigurations) return
        end(); finishAndRemoveTask()
    }

    override fun onDestroy() {
        end()
        runCatching { unregisterReceiver(screenOff) }
        web?.let { it.removeJavascriptInterface("KGLocal"); it.destroy() }; web = null
        super.onDestroy()
    }

    private fun end() {
        if (ended) return
        ended = true
        ParentSession.revoke()
        ui.removeCallbacks(check)
        runCatching { web?.loadUrl("about:blank") }
        wipe()
        if (!isFinishing) finish()
    }

    companion object {
        private var okAt = 0L

        /** Loads the WebView engine while the parent types the PIN (its first start costs ~1 s on an old phone, on the main thread). */
        fun warm() { runCatching { CookieManager.getInstance() } }

        /** Opens a session and the page. Call only after [Store.checkAdmin] said yes. */
        fun start(a: Activity) {
            okAt = android.os.SystemClock.elapsedRealtime()
            val t = ParentSession.open()
            val cm = CookieManager.getInstance()
            android.util.Log.i("KG.perf", "admin cookie mgr ${android.os.SystemClock.elapsedRealtime() - okAt}ms")
            cm.setAcceptCookie(true)
            cm.setCookie(ParentSession.ORIGIN, "${ParentSession.COOKIE}=$t; Path=/; HttpOnly; SameSite=Strict") {
                a.startActivity(Intent(a, AdminActivity::class.java))
            }
        }

        /** Drops the cookie and whatever the page kept (kids list, language) in this WebView. */
        fun wipe() {
            runCatching { CookieManager.getInstance().removeAllCookies(null); CookieManager.getInstance().flush() }
            runCatching { WebStorage.getInstance().deleteAllData() }
        }
    }
}
