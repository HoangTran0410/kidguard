package dev.kidguard

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.UserManager
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Pure parts of the remote-access feature (unit tested). */
object TsLogic {
    const val PKG = "com.tailscale.ipn"
    const val BASE = "https://pkgs.tail" + "scale.com/stable/"

    /** The universal APK file name from `?mode=json`, or null if it isn't a plain file name. */
    fun apkName(json: String): String? = runCatching {
        JSONObject(json).optJSONObject("APKs")?.optString("universal", "")
    }.getOrNull()?.takeIf { Regex("[A-Za-z0-9._-]{4,100}\\.apk").matches(it) }

    /** First token of a `.sha256` file, lower case, or null if it isn't 64 hex digits. */
    fun sha256Of(text: String): String? =
        text.trim().split(Regex("\\s+")).firstOrNull()?.lowercase()?.takeIf { Regex("[0-9a-f]{64}").matches(it) }

    // Keep-alive ([Ts.keep]): Tailscale killed (HyperOS "clean all", Recents swipe, battery saver) leaves no tunnel.
    const val RECEIVER = "com.tailscale.ipn.IPNReceiver"
    const val CONNECT = "com.tailscale.ipn.CONNECT_VPN"
    const val DOWN_MS = 20_000L            // a short gap (network change, app update) is not a drop
    const val ASK_EVERY_MS = 60_000L       // the CONNECT_VPN broadcast, silent
    const val OPEN_AFTER_MS = 90_000L      // still down after the broadcasts: open its screen once
    const val OPEN_EVERY_MS = 10 * 60_000L

    /** What to do while the tunnel is down: 0 wait, 1 send CONNECT_VPN, 2 open the app once (only on our home screen). */
    fun reviveStep(downMs: Long, sinceAsk: Long, sinceOpen: Long, onHome: Boolean, canOpen: Boolean): Int = when {
        downMs < DOWN_MS -> 0
        downMs >= OPEN_AFTER_MS && onHome && canOpen && sinceOpen >= OPEN_EVERY_MS -> 2
        sinceAsk >= ASK_EVERY_MS -> 1
        else -> 0
    }

    /** 100.64.0.0/10 (the tailnet range). */
    fun isTailnet(a: ByteArray): Boolean =
        a.size == 4 && (a[0].toInt() and 0xff) == 100 && (a[1].toInt() and 0xc0) == 0x40

    fun validKey(k: String): Boolean = Regex("[A-Za-z0-9_-]{10,300}").matches(k)

    /** An API access token (Settings → Keys → Generate access token) instead of an auth key: the phone then makes its own
     *  one-off auth key and turns off its node key expiry (else Tailscale signs it out after 180 days). */
    fun isApiToken(k: String) = k.startsWith("tskey-api-")

    const val API = "https://api.tail" + "scale.com/api/v2"

    /** Body for POST /tailnet/-/keys: one device, pre-approved, not ephemeral, key valid 1 hour. */
    fun keyBody(): String = JSONObject().put("capabilities", JSONObject().put("devices", JSONObject().put("create",
        JSONObject().put("reusable", false).put("ephemeral", false).put("preauthorized", true)))).put("expirySeconds", 3600).toString()

    /** The new auth key from the create-key answer, or null. */
    fun keyFrom(json: String): String? = runCatching { JSONObject(json).optString("key") }.getOrNull()?.takeIf { it.startsWith("tskey-") && validKey(it) }

    /** Device id (GET /tailnet/-/devices) of the device that has tailnet address [ip], or null. */
    fun deviceId(json: String, ip: String): String? = runCatching {
        val a = JSONObject(json).getJSONArray("devices")
        (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { d ->
            val ad = d.optJSONArray("addresses"); ad != null && (0 until ad.length()).any { ad.optString(it) == ip }
        }?.let { d -> d.optString("nodeId").ifEmpty { d.optString("id") } }
    }.getOrNull()?.takeIf { Regex("[A-Za-z0-9_-]{1,100}").matches(it) }

    /** Device name on the tailnet: a-z 0-9 and "-", at most 40 chars; default "kid-" + first 4 chars of the phone id. */
    fun hostName(raw: String, id: String): String {
        val s = raw.lowercase().replace(Regex("[^a-z0-9-]+"), "-").trim('-').take(40).trim('-')
        return s.ifEmpty { "kid-" + id.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }.take(4).ifEmpty { "x" } }
    }

    /** Managed configuration of the app (keys from the app's app_restrictions.xml; values per the vendor's MDM keys page). */
    fun restrictions(key: String, host: String): Map<String, Any> = linkedMapOf(
        "AuthKey" to key,
        "Hostname" to host,
        "ForceEnabled" to true,
        "OnboardingFlow" to "hide",
        "UseTailscaleDNSSettings" to "never",      // keep KidGuard's Private DNS filter
        "AllowIncomingConnections" to "always",   // the parent's browser reaches port 8770 over the tailnet
        "ManagedByOrganizationName" to "KidGuard",
    )

    fun toBundle(m: Map<String, Any>): Bundle = Bundle().also { b ->
        m.forEach { (k, v) -> when (v) { is Boolean -> b.putBoolean(k, v); else -> b.putString(k, v.toString()) } }
    }
}

/** Remote access for the parent over the tailnet, set up by the Device Owner without touching the child's phone. */
object Ts {
    private const val TAG = "KidGuard"
    private const val GRACE_MS = 3 * 60_000L

    @Volatile var state = "off"; private set        // off | installing | on | error
    @Volatile var ip = ""; private set
    @Volatile var err = ""; private set
    @Volatile private var busy = false
    @Volatile private var since = 0L
    @Volatile private var asserted = false

    /** Parent's API token: memory only, dropped once key expiry is off (or after a few tries). Never stored or sent. */
    @Volatile private var apiToken = ""
    @Volatile private var expTries = 0

    /** Never includes the auth key or token. noExpiry: the node key won't expire (else the page asks to turn it off by hand). */
    fun stateJson(s: Store): JSONObject = JSONObject().put("state", state).put("ip", ip).put("host", s.tsHost).put("err", err).put("noExpiry", s.tsNoExpiry)

    fun installed(ctx: Context) = runCatching { ctx.packageManager.getPackageInfo(TsLogic.PKG, 0); true }.getOrDefault(false)

    fun findIp(): String = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && TsLogic.isTailnet(it.address) }?.hostAddress ?: ""
    }.getOrDefault("")

    /** Command `tailscale`. Returns at once; the work runs on its own thread. */
    fun enable(ctx: Context, s: Store, key: String, hostRaw: String): String {
        if (!Policy.isOwner(ctx)) return "not owner"
        val k = key.trim()
        if (!TsLogic.validKey(k)) return "bad key"
        if (busy) return "busy"
        if (TsLogic.isApiToken(k)) { apiToken = k; expTries = 0; s.tsKey = "" } else { apiToken = ""; s.tsKey = k }
        s.tsNoExpiry = false; s.tsHost = TsLogic.hostName(hostRaw, s.id); s.tsWanted = true; s.tsHide = true
        startSetup(ctx, s)
        return "ok"
    }

    @Volatile private var lastTry = 0L

    private fun startSetup(ctx: Context, s: Store) {
        state = "installing"; err = ""; ip = ""; since = System.currentTimeMillis(); asserted = false; lastTry = since
        busy = true
        Thread({
            try { setup(ctx, s) } catch (e: Exception) {
                Log.w(TAG, "remote access setup", e); state = "error"; err = (e.message ?: ctx.getString(R.string.err_generic)).take(120)
            } finally { busy = false }
        }, "ts-setup").start()
    }

    /** Command `tailscaleOff`. */
    fun disable(ctx: Context, s: Store): String {
        if (!Policy.isOwner(ctx)) return "not owner"
        s.tsWanted = false; state = "off"; ip = ""; err = ""
        Thread({ runCatching { undo(ctx, s) } }, "ts-off").start()
        return "ok"
    }

    @Volatile private var downAt = 0L
    @Volatile private var askAt = 0L
    @Volatile private var openAt = 0L

    /**
     * Every Guard tick, Device Owner AND basic mode: the parents reach the phone through Tailscale, so it must stay up.
     * Applies once a tunnel was seen on this phone (Store.tsSeen, set here) or KidGuard manages it (tsWanted).
     * Down for [TsLogic.DOWN_MS]: send Tailscale's own CONNECT_VPN broadcast (silent, starts a killed process);
     * still down after [TsLogic.OPEN_AFTER_MS]: open its screen once and go back home, like the bank flow
     * (AccPause.pauseLate): only while our launcher is in front, and only with a background-start right
     * ([AccPause.canReopen]). A Tailscale that was force-stopped gets no broadcasts until it is opened.
     */
    fun keep(ctx: Context, s: Store, fg: String?, now: Long) {
        if (Flavor.HOME || s.released || !installed(ctx)) return
        if (findIp().isNotEmpty()) {
            if (downAt != 0L && askAt >= downAt) s.log(R.string.ts_log_back)
            downAt = 0L
            if (!s.tsSeen) s.tsSeen = true
            return
        }
        if (!s.tsSeen && !s.tsWanted) return
        if (downAt == 0L) downAt = now
        val screenOn = runCatching { ctx.getSystemService(android.os.PowerManager::class.java).isInteractive }.getOrDefault(false)
        val onHome = screenOn && fg == ctx.packageName
        val canOpen = AccPause.canReopen(ctx) && !(s.tsWanted && s.tsHide)   // a suspended (hidden) app can't open
        when (TsLogic.reviveStep(now - downAt, now - askAt, now - openAt, onHome, canOpen)) {
            1 -> {
                if (askAt < downAt) s.log(R.string.ts_log_down)
                askAt = now
                runCatching { ctx.sendBroadcast(Intent(TsLogic.CONNECT).setClassName(TsLogic.PKG, TsLogic.RECEIVER)) }
                    .onFailure { Log.w(TAG, "ts connect broadcast", it) }
            }
            2 -> {
                openAt = now; askAt = now
                Thread({
                    runCatching {
                        ctx.packageManager.getLaunchIntentForPackage(TsLogic.PKG)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { ctx.startActivity(it) }
                        Thread.sleep(3_000)
                        ctx.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }.onFailure { Log.w(TAG, "ts open", it) }
                }, "ts-open").start()
            }
        }
    }

    /** Every Guard tick: follow the tunnel address, and re-assert the policy once after a restart. */
    fun poll(ctx: Context, s: Store) {
        if (!s.tsWanted) { if (state != "off") { state = "off"; ip = ""; err = "" }; return }
        if (busy) return
        if (since == 0L) since = System.currentTimeMillis()
        // not installed yet (the download failed, or the phone restarted halfway): try again, at most every 5 minutes
        if (!installed(ctx) && Policy.isOwner(ctx) && s.tsKey.isNotEmpty() && System.currentTimeMillis() - lastTry > 5 * 60_000L) { startSetup(ctx, s); return }
        if (!asserted && Policy.isOwner(ctx) && installed(ctx)) {
            asserted = true
            Thread({ runCatching { applyPolicy(ctx, s, suspend = null) } }, "ts-assert").start()
            if (state == "off") state = "installing"
        }
        val now = findIp()
        if (now.isNotEmpty()) {
            ip = now
            if (state != "on") { state = "on"; err = ""; s.log(R.string.ts_log_connected, now); hide(ctx, s) }
            if (apiToken.isNotEmpty() && !s.tsNoExpiry && !busy) noExpiry(ctx, s, now)
        } else {
            ip = ""
            if (state == "on") { state = "installing"; since = System.currentTimeMillis() }
            if (System.currentTimeMillis() - since > GRACE_MS && state != "error") {
                state = "error"; err = ctx.getString(R.string.ts_err_no_net)
            }
        }
    }

    /** With the parent's API token: make a one-off pre-approved auth key for this phone. */
    private fun makeKey(ctx: Context, s: Store) {
        val c = api(ctx, "/tailnet/-/keys", TsLogic.keyBody())
        s.tsKey = TsLogic.keyFrom(c) ?: throw IllegalStateException(ctx.getString(R.string.ts_err_token))
    }

    /** With the parent's API token: turn off this phone's node key expiry, then forget the token. */
    private fun noExpiry(ctx: Context, s: Store, myIp: String) {
        busy = true
        Thread({
            try {
                val id = TsLogic.deviceId(api(ctx, "/tailnet/-/devices", null), myIp) ?: throw IllegalStateException("device not listed yet")
                api(ctx, "/device/$id/key", JSONObject().put("keyExpiryDisabled", true).toString())
                s.tsNoExpiry = true; apiToken = ""; s.log(R.string.ts_log_noexpiry)
            } catch (e: Exception) {
                Log.w(TAG, "ts key expiry", e)
                if (++expTries >= 5) apiToken = ""   // the page then shows the manual step
            } finally { busy = false }
        }, "ts-expiry").start()
    }

    private fun api(ctx: Context, path: String, body: String?): String {
        val c = URL(TsLogic.API + path).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 20_000
        c.setRequestProperty("Authorization", "Bearer $apiToken")
        if (body != null) {
            c.requestMethod = "POST"; c.doOutput = true; c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = c.responseCode
        if (code == 401 || code == 403) { apiToken = ""; throw IllegalStateException(ctx.getString(R.string.ts_err_token)) }
        if (code !in 200..299) throw IllegalStateException(ctx.getString(R.string.err_http, code))
        return c.inputStream.use { String(it.readBytes()) }.take(1_000_000)
    }

    private fun setup(ctx: Context, s: Store) {
        if (s.tsKey.isEmpty() && apiToken.isNotEmpty()) makeKey(ctx, s)
        if (!installed(ctx)) {
            val apk = download(ctx)
            try { install(ctx, s, apk) } finally { apk.delete() }
            if (!installed(ctx)) throw IllegalStateException(ctx.getString(R.string.ts_err_install))
        }
        applyPolicy(ctx, s, suspend = false)
        asserted = true
        since = System.currentTimeMillis()
        s.log(R.string.ts_log_installed)
        // the always-on VPN binds the app's VPN service, which reads the managed config and logs in without any UI.
        // Only if no tailnet address shows up: open the app once (so the child sees it), then go back home.
        if (waitIp(30_000)) { Log.i(TAG, "remote access: up without opening the app"); return }
        Log.i(TAG, "remote access: no address after 30 s, opening the app once")
        runCatching {
            ctx.packageManager.getLaunchIntentForPackage(TsLogic.PKG)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { ctx.startActivity(it) }
            Thread.sleep(3_000)
            ctx.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun waitIp(ms: Long): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (findIp().isNotEmpty()) return true; Thread.sleep(1_000) }
        return false
    }

    private fun httpGet(ctx: Context, url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 30_000; c.instanceFollowRedirects = true
        if (c.responseCode != 200) throw IllegalStateException(ctx.getString(R.string.err_http, c.responseCode))
        return c
    }

    private fun download(ctx: Context): File {
        val name = TsLogic.apkName(httpGet(ctx, TsLogic.BASE + "?mode=json").inputStream.use { String(it.readBytes()) })
            ?: throw IllegalStateException(ctx.getString(R.string.ts_err_apk_name))
        val want = TsLogic.sha256Of(httpGet(ctx, TsLogic.BASE + name + ".sha256").inputStream.use { String(it.readBytes()) })
            ?: throw IllegalStateException(ctx.getString(R.string.err_sha_read))
        val f = File(ctx.cacheDir, "ts.apk")
        val md = MessageDigest.getInstance("SHA-256")
        httpGet(ctx, TsLogic.BASE + name).inputStream.use { i -> f.outputStream().use { o ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = i.read(buf); if (n < 0) break; md.update(buf, 0, n); o.write(buf, 0, n) }
        } }
        val got = md.digest().joinToString("") { "%02x".format(it) }
        if (got != want) { f.delete(); throw IllegalStateException(ctx.getString(R.string.ts_err_sha)) }
        return f
    }

    private fun install(ctx: Context, s: Store, apk: File) {
        // a Device Owner may install even under "no installs", but only when the restriction is not set for the session: lift it for a moment
        val d = Policy.dpm(ctx); val a = Policy.admin(ctx)
        val lifted = listOf(UserManager.DISALLOW_INSTALL_APPS, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
        lifted.forEach { runCatching { d.clearUserRestriction(a, it) } }
        try {
            val pi = ctx.packageManager.packageInstaller
            val p = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply { setAppPackageName(TsLogic.PKG) }
            val id = pi.createSession(p)
            pi.openSession(id).use { ses ->
                apk.inputStream().use { i -> ses.openWrite("ts", 0, apk.length()).use { o -> i.copyTo(o); ses.fsync(o) } }
                val res = commitAndWait(ctx, ses, "dev.kidguard.TS_INSTALL")
                if (res != null) throw IllegalStateException(ctx.getString(R.string.err_install, res))
            }
        } finally { runCatching { Policy.apply(ctx, s.rules) } }
    }

    /** Commits and waits for the status broadcast. Null = success, else a short reason. */
    internal fun commitAndWait(ctx: Context, ses: PackageInstaller.Session, action: String): String? {
        val done = CountDownLatch(1)
        var out: String? = ctx.getString(R.string.err_timeout)
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
                    PackageInstaller.STATUS_SUCCESS -> out = null
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> out = ctx.getString(R.string.err_user_action)
                    else -> out = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ctx.getString(R.string.err_generic)
                }
                done.countDown()
            }
        }
        val f = IntentFilter(action)
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED) else @Suppress("UnspecifiedRegisterReceiverFlag") ctx.registerReceiver(r, f)
        try {
            val pending = PendingIntent.getBroadcast(ctx, 0, Intent(action).setPackage(ctx.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            ses.commit(pending.intentSender)
            done.await(90, TimeUnit.SECONDS)
        } finally { runCatching { ctx.unregisterReceiver(r) } }
        return out
    }

    /** Managed config, always-on VPN, no uninstall, no VPN settings for the child. Idempotent. */
    private fun applyPolicy(ctx: Context, s: Store, suspend: Boolean?) {
        val d = Policy.dpm(ctx); val a = Policy.admin(ctx)
        val host = s.tsHost.ifEmpty { TsLogic.hostName("", s.id) }
        runCatching { d.setApplicationRestrictions(a, TsLogic.PKG, TsLogic.toBundle(TsLogic.restrictions(s.tsKey, host))) }
            .onFailure { Log.w(TAG, "ts restrictions", it) }
        if (suspend != null) runCatching { d.setPackagesSuspended(a, arrayOf(TsLogic.PKG), suspend) }
        runCatching { d.setUninstallBlocked(a, TsLogic.PKG, true) }.onFailure { Log.w(TAG, "ts uninstall block", it) }
        Policy.applyVpnLock(ctx, d, a, s.rules.noVpn)   // sets it (tsWanted is true); never clears what the rule noVpn needs
        // not lockdown: without the VPN the child's internet still works
        // right after an install the system may not know the VPN service yet: check it took, else try again a few times
        for (i in 0 until 6) {
            runCatching { d.setAlwaysOnVpnPackage(a, TsLogic.PKG, false) }.onFailure { Log.w(TAG, "remote access always-on", it) }
            if (runCatching { d.getAlwaysOnVpnPackage(a) }.getOrNull() == TsLogic.PKG) return
            runCatching { Thread.sleep(2_000) }
        }
        Log.w(TAG, "remote access: always-on VPN did not take")
    }

    /** Hides the app from the child once the tunnel is up, and checks the tunnel survives; if not, shows the app again. */
    private fun hide(ctx: Context, s: Store) {
        if (!s.tsHide || busy) return
        busy = true
        Thread({
            try {
                val d = Policy.dpm(ctx); val a = Policy.admin(ctx)
                runCatching { d.setPackagesSuspended(a, arrayOf(TsLogic.PKG), true) }
                Thread.sleep(8_000)
                if (findIp().isEmpty()) {
                    runCatching { d.setPackagesSuspended(a, arrayOf(TsLogic.PKG), false) }
                    s.tsHide = false
                    s.log(R.string.ts_log_unhide)
                    runCatching { ctx.packageManager.getLaunchIntentForPackage(TsLogic.PKG)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { ctx.startActivity(it) } }
                }
            } catch (e: Exception) { Log.w(TAG, "ts hide", e) } finally { busy = false }
        }, "ts-hide").start()
    }

    /** Takes everything back (also called from release). Synchronous, at most ~15 s. */
    fun undo(ctx: Context, s: Store) {
        s.tsWanted = false; s.tsKey = ""; s.tsNoExpiry = false; s.tsSeen = false; apiToken = ""
        if (!Policy.isOwner(ctx)) return
        val d = Policy.dpm(ctx); val a = Policy.admin(ctx)
        runCatching { d.setApplicationRestrictions(a, TsLogic.PKG, Bundle()) }
        runCatching { d.setAlwaysOnVpnPackage(a, null, false) }
        runCatching { d.setUninstallBlocked(a, TsLogic.PKG, false) }
        Policy.applyVpnLock(ctx, d, a, !s.released && s.rules.noVpn)   // tsWanted is already false: only the rule noVpn can keep it
        runCatching { d.setPackagesSuspended(a, arrayOf(TsLogic.PKG), false) }
        if (installed(ctx)) runCatching {
            val done = CountDownLatch(1)
            val r = object : BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) { done.countDown() } }
            val act = "dev.kidguard.TS_UNINSTALL"
            if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(r, IntentFilter(act), Context.RECEIVER_NOT_EXPORTED) else @Suppress("UnspecifiedRegisterReceiverFlag") ctx.registerReceiver(r, IntentFilter(act))
            try {
                val pending = PendingIntent.getBroadcast(ctx, 1, Intent(act).setPackage(ctx.packageName), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                ctx.packageManager.packageInstaller.uninstall(TsLogic.PKG, pending.intentSender)
                done.await(12, TimeUnit.SECONDS)
            } finally { runCatching { ctx.unregisterReceiver(r) } }
        }.onFailure { Log.w(TAG, "ts uninstall", it) }
        state = "off"; ip = ""; err = ""
    }
}
