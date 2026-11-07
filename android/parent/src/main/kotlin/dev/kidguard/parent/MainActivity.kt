package dev.kidguard.parent

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast

/** JS bridge `window.KGApp`. Only the page served from our own 127.0.0.1 origin is ever loaded in the WebView. */
class Bridge(private val a: Activity) {
    @JavascriptInterface fun version() = "1"
    /** The kid list survives a proxy port change (localStorage is per origin). */
    @JavascriptInterface fun getKids(): String = Store.kids(a)
    @JavascriptInterface fun setKids(json: String) {
        if (json.length > 100_000) return
        val old = Logic.parseKids(Store.kids(a))
        Store.setKids(a, org.json.JSONArray(json).toString())   // throws (ignored by WebView) on invalid JSON
        val now = Logic.parseKids(Store.kids(a)).map { it.key }.toSet()
        Push.sync(a.applicationContext, old.filter { it.key !in now })
    }
    @JavascriptInterface fun setNotify(v: String) {
        Store.setNotify(a, v == "true")
        Push.sync(a.applicationContext)   // notify off = unregister from the relay too
        if (v == "true" && Build.VERSION.SDK_INT >= 33 && a.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            a.runOnUiThread { a.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1) }
    }
    @JavascriptInterface fun getHistoryDays() = Store.historyDays(a).toString()
    @JavascriptInterface fun setHistoryDays(n: String) { Store.setHistoryDays(a, n.toIntOrNull() ?: 0) }
    /** Rule templates (page "Mẫu luật"); same reason as getKids. */
    @JavascriptInterface fun getTpl(): String = Store.tpl(a)
    @JavascriptInterface fun setTpl(json: String) = Store.setTpl(a, json)
    @JavascriptInterface fun getNotify() = Store.notify(a).toString()
    /** "true" when the OS lets us show notifications (permission + not disabled in settings). */
    @JavascriptInterface fun canNotify() = CheckJob.canNotify(a.applicationContext).toString()
    /** App language for native text (notifications): "" = phone setting, else a [Langs.ALL] code. The page translates itself. */
    @JavascriptInterface fun getLang(): String = Lang.get(a)
    /** Unknown codes are ignored. Same code = no-op. The activity declares configChanges=locale, so the WebView is never recreated. */
    @JavascriptInterface fun setLang(code: String) {
        if (Langs.valid(code)) Lang.set(a.applicationContext, code)
    }
}

class MainActivity : Activity() {
    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(Lang.wrap(base))   // in-app language (Android 8-12)
    private var web: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val proxy = proxy ?: Proxy(applicationContext, Store.pickPort(applicationContext)).also {
            if (!it.start()) { Toast.makeText(this, getString(R.string.err_port), Toast.LENGTH_LONG).show(); finish(); return }
            proxy = it
        }
        CheckJob.channel(this)
        if (Build.VERSION.SDK_INT >= 33 && Store.notify(this) && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        CheckJob.schedule(this)
        Push.sync(applicationContext)

        val origin = "http://127.0.0.1:${proxy.port}"
        val w = WebView(this)
        web = w
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        w.addJavascriptInterface(Bridge(this), "KGApp")
        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                if (r.url.toString().startsWith("$origin/")) return false
                // https links the page shows (Google Maps, OSM copyright) open outside: the Maps app, or the browser
                if (r.url.scheme == "https" && r.hasGesture()) runCatching {
                    startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, r.url).addCategory(android.content.Intent.CATEGORY_BROWSABLE))
                }
                return true
            }
            // nothing but our own proxy (and the OSM map tiles the page uses) may be reached from the WebView
            override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? {
                val u = r.url.toString()
                return if (u.startsWith("$origin/") || u.startsWith("https://tile.openstreetmap.org/") || u.startsWith("data:") || u.startsWith("blob:")) null
                else WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), java.io.ByteArrayInputStream(ByteArray(0)))
            }
            // Android killed the page renderer (low memory, cheap phones): without this the whole app dies with it
            override fun onRenderProcessGone(v: WebView, d: android.webkit.RenderProcessGoneDetail): Boolean {
                if (web === v) web = null
                runCatching { (v.parent as? android.view.ViewGroup)?.removeView(v); v.destroy() }
                recreate()
                return true
            }
        }
        setContentView(w)
        // never restoreState: the cookie secret changes with the process, page state lives in JS/localStorage
        w.loadUrl("$origin/s/${proxy.secret}")
        Log.i("KGParent", "page $origin")
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val w = web
        if (w != null && w.canGoBack()) w.goBack() else super.onBackPressed()
    }

    // in the background the page is hidden: its poll timers skip their fetches (relay quota, battery); notifications come from CheckJob / FCM
    override fun onPause() { web?.onPause(); super.onPause() }
    override fun onResume() { super.onResume(); web?.onResume() }
    override fun onDestroy() { web?.destroy(); web = null; super.onDestroy() }

    companion object { @Volatile var proxy: Proxy? = null }
}
