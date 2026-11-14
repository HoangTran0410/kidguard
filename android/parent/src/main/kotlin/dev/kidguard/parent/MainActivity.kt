package dev.kidguard.parent

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
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
class Bridge(private val a: MainActivity) {
    @JavascriptInterface fun version() = "1"
    /** "true": this app picks files (file chooser) and saves downloads (blob / data / same-origin) to Downloads. */
    @JavascriptInterface fun files() = "true"
    // blob download: the page's JS (see MainActivity.onDownloadStart) hands the bytes over in chunks
    @JavascriptInterface fun dlBegin(name: String, mime: String): String = Downloads.begin(a, name, mime)
    @JavascriptInterface fun dlChunk(id: String, b64: String): Boolean = Downloads.chunk(id, b64)
    @JavascriptInterface fun dlEnd(id: String, ok: String) { Downloads.end(a, id, ok == "true") }
    /** The kid list survives a proxy port change (localStorage is per origin). */
    @JavascriptInterface fun getKids(): String = Store.kids(a)
    @JavascriptInterface fun setKids(json: String) {
        if (json.length > 100_000) return
        val old = Logic.parseKids(Store.kids(a))
        Store.setKids(a, org.json.JSONArray(json).toString())   // throws (ignored by WebView) on invalid JSON
        val now = Logic.parseKids(Store.kids(a)).map { it.key }.toSet()
        Push.sync(a.applicationContext, old.filter { it.key !in now })
        // the permission is asked when the first kid is added (not cold at launch: the parent sees why)
        if (old.isEmpty() && now.isNotEmpty() && Store.notify(a)) askNotif()
    }
    /**
     * Asks for the notification permission once; when it was already asked (Android 13 shows no second dialog) or it is
     * granted but the app's notifications are off, opens the system page. Returns "ok" | "asked" | "settings".
     */
    @JavascriptInterface fun askNotif(): String {
        if (CheckJob.canNotify(a.applicationContext)) return "ok"
        val needPerm = Build.VERSION.SDK_INT >= 33 && a.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needPerm && !Store.notifAsked(a)) {
            Store.setNotifAsked(a)
            a.runOnUiThread { a.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1) }
            return "asked"
        }
        openNotifSettings()
        return "settings"
    }
    /**
     * "Do alerts arrive?" card. JSON: {enabled (app switch), perm (OS allows notifications), lastCheck (ms of the last background
     * check, 0 = never), push ("off" no FCM in this build | "none" no relay kid | "wait" | "ok" | "fail"), pushAt (ms), battery
     * (true = this app is exempt from battery optimisation), sdk}.
     */
    @JavascriptInterface fun notifHealth(): String {
        val c = a.applicationContext
        val pm = c.getSystemService(android.os.PowerManager::class.java)
        return org.json.JSONObject().put("enabled", Store.notify(a)).put("perm", CheckJob.canNotify(c)).put("lastCheck", Store.checkAt(a))
            .put("push", Push.status(c)).put("pushAt", Store.pushAt(a)).put("battery", pm?.isIgnoringBatteryOptimizations(c.packageName) == true)
            .put("sdk", Build.VERSION.SDK_INT).toString()
    }
    /** Posts a test notification: "ok" | "blocked" (permission or the app's notifications are off). */
    @JavascriptInterface fun sendTest(): String = if (Notif.test(a.applicationContext)) "ok" else "blocked"
    /** The system's one-tap "let KidGuard run in the background" dialog; the full battery page when that is refused. */
    @JavascriptInterface fun openBatterySettings() {
        a.runOnUiThread {
            val ok = runCatching { a.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:" + a.packageName))) }.isSuccess
            if (!ok) runCatching { a.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
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
    /** "true" when notifications are on in the app and allowed by Android. */
    @JavascriptInterface fun notifOn() = (Store.notify(a) && CheckJob.canNotify(a.applicationContext)).toString()
    /** Android's own notification settings page for this app. */
    @JavascriptInterface fun openNotifSettings() {
        a.runOnUiThread { runCatching { a.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, a.packageName)) } }
    }
    /**
     * Opens Google's code scanner (Play services; no camera permission). The text arrives as `kgScan({text})`; `kgScan({st:"cancel"})`
     * when closed, `kgScan({st:"fail"})` when the scanner is unavailable (the app also shows a toast). Page validates the text.
     */
    @JavascriptInterface fun scanQr() { a.runOnUiThread { a.scanQr() } }
    /** Picks a photo (system picker, no storage permission) and reads a QR code in it; answers like [scanQr]. */
    @JavascriptInterface fun scanImg() { a.runOnUiThread { a.scanImg() } }
    @JavascriptInterface fun appVersion(): String = BuildConfig.VERSION_NAME
    /** Checks GitHub now; progress and result arrive as `kgUpd({st: checking|ok|new|fail, tag})` on the page. */
    @JavascriptInterface fun checkUpdate() = Updater.check(a)
    /** Downloads release [tag] and opens the system installer: `kgUpd({st: perm|dl(pct)|inst|dlfail, tag})`. */
    @JavascriptInterface fun installUpdate(tag: String) = Updater.install(a, tag)
    /** "true" once after the update notification was tapped: the page opens "App settings". */
    @JavascriptInterface fun takeSettings(): String = if (MainActivity.openSettings) { MainActivity.openSettings = false; "true" } else "false"
    /** A notification tap asked for one kid: `{"target","id"}` once (then ""), so the page opens that kid instead of the list. */
    @JavascriptInterface fun takeOpen(): String {
        val h = MainActivity.openKid ?: return ""
        MainActivity.openKid = null
        val k = Notif.kidOf(a, h) ?: return ""
        return org.json.JSONObject().put("target", k.target).put("id", k.id).toString()
    }
}

class MainActivity : Activity() {
    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(Lang.wrap(base))   // in-app language (Android 8-12)
    private var web: WebView? = null
    private var chooser: android.webkit.ValueCallback<Array<android.net.Uri>>? = null

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: android.content.Intent?) {
        if (req == REQ_IMG) { val u = data?.data; if (res == RESULT_OK && u != null) readQr(u) else scanSend(org.json.JSONObject().put("st", "cancel")); return }
        if (req != REQ_PICK) return super.onActivityResult(req, res, data)
        val cb = chooser ?: return
        chooser = null
        val uris: Array<android.net.Uri>? = if (res != RESULT_OK || data == null) null else {
            val c = data.clipData
            if (c != null) Array(c.itemCount) { c.getItemAt(it).uri } else data.data?.let { arrayOf(it) }
        }
        cb.onReceiveValue(uris)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        takeOpen(intent); takeSettings(intent)
        val proxy = proxy ?: Proxy(applicationContext, Store.pickPort(applicationContext)).also {
            if (!it.start()) { Toast.makeText(this, getString(R.string.err_port), Toast.LENGTH_LONG).show(); finish(); return }
            proxy = it
        }
        CheckJob.channel(this)
        // no cold permission prompt on a fresh install: the bridge asks when the first kid is added (setKids). Upgrades with kids ask here.
        if (Build.VERSION.SDK_INT >= 33 && Store.notify(this) && Logic.parseKids(Store.kids(this)).isNotEmpty() && !Store.notifAsked(this) &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) { Store.setNotifAsked(this); requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1) }
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
        w.setDownloadListener { url, _, cd, mime, _ ->
            if (url.startsWith("blob:") || url.startsWith("data:") || url.startsWith("$origin/"))
                w.evaluateJavascript(Downloads.script(url, cd, mime), null)
        }
        w.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onShowFileChooser(v: WebView, cb: android.webkit.ValueCallback<Array<android.net.Uri>>, p: FileChooserParams): Boolean {
                chooser?.onReceiveValue(null)
                chooser = cb
                val i = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).addCategory(android.content.Intent.CATEGORY_OPENABLE).setType("*/*")
                    .putExtra(android.content.Intent.EXTRA_ALLOW_MULTIPLE, p.mode == FileChooserParams.MODE_OPEN_MULTIPLE)
                try { startActivityForResult(i, REQ_PICK) } catch (e: Exception) { chooser = null; cb.onReceiveValue(null); Toast.makeText(this@MainActivity, R.string.files_nopicker, Toast.LENGTH_LONG).show() }
                return true
            }
        }
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
                return if (u.startsWith("$origin/") || u.startsWith("https://tile.openstreetmap.de/") || u.startsWith("https://tile.openstreetmap.org/") || u.startsWith("data:") || u.startsWith("blob:")) null
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
        // debug build only: lets scripts/shots/shoot.mjs --cdp drive the real Android WebView (layout bugs desktop Chrome misses)
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) WebView.setWebContentsDebuggingEnabled(true)
        // never restoreState: the cookie secret changes with the process, page state lives in JS/localStorage
        w.loadUrl("$origin/s/${proxy.secret}")
        Log.i("KGParent", "page $origin")
    }

    /** Notification tap ([Notif.openKid]): remember the kid; a running page is told at once, a loading one asks on boot. */
    private fun takeOpen(i: android.content.Intent?): Boolean {
        val h = i?.getStringExtra(Notif.EXTRA_KID)?.takeIf { it.length == 64 } ?: return false
        openKid = h
        return true
    }
    private fun takeSettings(i: android.content.Intent?): Boolean {
        if (i?.getBooleanExtra(EXTRA_SETTINGS, false) != true) return false
        openSettings = true
        return true
    }
    fun scanQr() {
        fun send(o: org.json.JSONObject) { web?.evaluateJavascript("window.kgScan && kgScan(" + o + ")", null) }
        val fail = { Toast.makeText(this, R.string.scan_fail, Toast.LENGTH_LONG).show(); send(org.json.JSONObject().put("st", "fail")); Unit }
        try {
            val opt = com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE).build()
            com.google.mlkit.vision.codescanner.GmsBarcodeScanning.getClient(this, opt).startScan()
                .addOnSuccessListener { send(org.json.JSONObject().put("text", (it.rawValue ?: "").take(2000))) }
                .addOnCanceledListener { send(org.json.JSONObject().put("st", "cancel")) }
                .addOnFailureListener { fail() }
        } catch (e: Throwable) { fail() }
    }
    private fun scanSend(o: org.json.JSONObject) { web?.evaluateJavascript("window.kgScan && kgScan(" + o + ")", null) }
    fun scanImg() {
        val i = if (android.os.Build.VERSION.SDK_INT >= 33) android.content.Intent(android.provider.MediaStore.ACTION_PICK_IMAGES)
            else android.content.Intent(android.content.Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(android.content.Intent.CATEGORY_OPENABLE)
        try { startActivityForResult(i, REQ_IMG) } catch (e: Exception) { Toast.makeText(this, R.string.files_nopicker, Toast.LENGTH_LONG).show(); scanSend(org.json.JSONObject().put("st", "fail")) }
    }
    /** ML Kit barcode reader (Play services model, QR only) on the picked photo; no QR -> kgScan({st:"none"}). */
    private fun readQr(u: android.net.Uri) {
        val fail = { Toast.makeText(this, R.string.scan_fail, Toast.LENGTH_LONG).show(); scanSend(org.json.JSONObject().put("st", "fail")); Unit }
        try {
            val img = com.google.mlkit.vision.common.InputImage.fromFilePath(this, u)
            val opt = com.google.mlkit.vision.barcode.BarcodeScannerOptions.Builder()
                .setBarcodeFormats(com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE).build()
            com.google.mlkit.vision.barcode.BarcodeScanning.getClient(opt).process(img)
                .addOnSuccessListener { l ->
                    val txt = l.firstOrNull { !it.rawValue.isNullOrEmpty() }?.rawValue
                    scanSend(if (txt == null) org.json.JSONObject().put("st", "none") else org.json.JSONObject().put("text", txt.take(2000)))
                }
                .addOnFailureListener { fail() }
        } catch (e: Throwable) { fail() }
    }
    /** Updater -> page: one JSON state, on the main thread. */
    fun push(o: org.json.JSONObject) { runOnUiThread { web?.evaluateJavascript("window.kgUpd && kgUpd(" + o + ")", null) } }
    override fun onNewIntent(i: android.content.Intent) {
        super.onNewIntent(i)
        if (takeOpen(i) or takeSettings(i)) web?.evaluateJavascript("window.kgAppOpen && kgAppOpen()", null)
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

    companion object { const val REQ_PICK = 71; const val REQ_IMG = 72; @Volatile var proxy: Proxy? = null; @Volatile var openKid: String? = null; @Volatile var openSettings = false; const val EXTRA_SETTINGS = "kg.settings" }
}
