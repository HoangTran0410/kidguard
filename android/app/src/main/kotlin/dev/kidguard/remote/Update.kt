package dev.kidguard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** Pure parts of the self-update (unit-tested). */
object UpdateLogic {
    const val REPO = "HoangTran0410/kidguard"
    const val GH = "https://github.com"
    const val LATEST = "$GH/$REPO/releases/latest"
    const val DAY_MS = 24 * 3_600_000L

    /** "0.9.0" from a `Location: …/releases/tag/v0.9.0` redirect, or null. */
    fun tagFromLocation(loc: String?): String? =
        loc?.substringAfterLast("/tag/", "")?.takeIf { Regex("v?\\d{1,4}(\\.\\d{1,4}){1,3}").matches(it) }?.removePrefix("v")

    /** True when [a] is a higher dotted version than [b] ("0.10.0" > "0.9.3"); junk compares as 0. */
    fun newer(a: String, b: String): Boolean {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }; val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) { val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }; if (d != 0) return d > 0 }
        return false
    }

    fun apkUrl(tag: String, name: String) = "$GH/$REPO/releases/download/v$tag/$name"
}

/**
 * Self-update from GitHub releases. Once a day (rule `autoUpdate`) or on the parent's `update` command: read the latest
 * tag, download `kidguard.apk` + verify its `.sha256`, then install. Device Owner installs silently (the process is
 * replaced, [Boot] restarts the guard and it re-applies the policy); basic mode can't, so the child gets a notification
 * and a button in [MainActivity] that opens the system installer. Android itself refuses an APK signed with another key.
 */
object Update {
    private const val TAG = "KidGuard"
    private const val ACTION = "dev.kidguard.UPDATE_STATUS"
    private val busy = AtomicBoolean(false)
    @Volatile private var triedAt = 0L
    @Volatile var latest = ""; private set
    @Volatile var status = ""; private set      // "", "checking", "downloading", "installing", "ready" (basic: waits for the child), "failed"
    @Volatile var err = ""; private set
    /** Counts [start] runs, so a UI that polls [status] can tell a fresh result from a stale one. */
    @Volatile var runs = 0; private set
    /** True while the owner install session runs: [Policy.apply] then leaves the install restrictions lifted. */
    @Volatile var installing = false; private set

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("update", Context.MODE_PRIVATE)
    fun apk(ctx: Context) = File(ctx.filesDir, "update.apk")
    fun current() = BuildConfig.VERSION_NAME

    fun stateJson(ctx: Context): JSONObject {
        val p = prefs(ctx)
        if (latest.isEmpty()) latest = p.getString("latest", "") ?: ""
        // left over from an install that replaced this process (or an older download): not needed any more
        if (!busy.get() && apk(ctx).exists() && !UpdateLogic.newer(p.getString("apkTag", "") ?: "", current())) apk(ctx).delete()
        // "ready" = basic mode waiting for the child's tap; an owner phone installs on its own
        if (status.isEmpty() && apk(ctx).exists() && UpdateLogic.newer(latest, current()) && !Policy.isOwner(ctx)) status = "ready"
        return JSONObject().put("latest", latest).put("status", status).put("err", err).put("checkedAt", p.getLong("at", 0))
    }

    /** From the guard tick: at most once a day, on its own thread. */
    fun maybe(ctx: Context, r: Rules, now: Long) {
        if (!r.autoUpdate) return
        if (now - prefs(ctx).getLong("at", 0) < UpdateLogic.DAY_MS) return
        if (now - triedAt < 3_600_000) return   // offline / GitHub down: try again in an hour, not every tick
        triedAt = now; start(ctx, false)
    }

    /** [force] = the parent asked: also install a download that is already waiting (basic: notify again). */
    fun start(ctx: Context, force: Boolean): Boolean {
        if (!busy.compareAndSet(false, true)) return false
        runs++
        val app = ctx.applicationContext
        Thread({
            try { run(app, force) } catch (e: Throwable) { Log.e(TAG, "update", e); status = "failed"; err = errText(app, e) }
            finally { busy.set(false) }
        }, "update").start()
        return true
    }

    private fun run(ctx: Context, force: Boolean) {
        status = "checking"; err = ""
        val tag = check(ctx)
        prefs(ctx).edit().putString("latest", tag).apply()
        latest = tag
        // "at" (the once-a-day mark) only after a full success: a failed download/install retries in an hour (triedAt)
        val done = { prefs(ctx).edit().putLong("at", System.currentTimeMillis()).apply() }
        if (!UpdateLogic.newer(tag, current())) { status = ""; apk(ctx).delete(); done(); return }
        // a release whose APK we already rejected (wrong package / versionCode): don't download it again every day
        if (prefs(ctx).getString("badTag", "") == tag) { status = "failed"; err = ctx.getString(R.string.upd_err_bad_tag, tag); done(); return }
        val f = apk(ctx)
        if (!f.exists() || prefs(ctx).getString("apkTag", "") != tag) {
            status = "downloading"
            download(ctx, tag, f); prefs(ctx).edit().putString("apkTag", tag).apply()
        }
        try { verifyPackage(ctx, f) } catch (e: IllegalStateException) { prefs(ctx).edit().putString("badTag", tag).apply(); done(); throw e }
        if (Policy.isOwner(ctx)) { status = "installing"; installSilently(ctx, f) }
        else { status = "ready"; if (force || prefs(ctx).getString("notified", "") != tag) { notifyChild(ctx, tag); prefs(ctx).edit().putString("notified", tag).apply() } }
        done()
        if (force) Log.i(TAG, "update to $tag requested by parent")
    }

    /** The latest tag, read from the redirect of /releases/latest (no API key, no rate limit). */
    private fun check(ctx: Context): String {
        val c = URL(UpdateLogic.LATEST).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false; c.connectTimeout = 15_000; c.readTimeout = 15_000; c.requestMethod = "HEAD"
        try {
            if (c.responseCode !in 300..399) throw IllegalStateException(ctx.getString(R.string.upd_err_github, c.responseCode))
            return UpdateLogic.tagFromLocation(c.getHeaderField("Location")) ?: throw IllegalStateException(ctx.getString(R.string.upd_err_version))
        } finally { c.disconnect() }
    }

    private fun get(ctx: Context, url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 60_000; c.instanceFollowRedirects = true
        if (c.responseCode != 200) { val code = c.responseCode; c.disconnect(); throw IllegalStateException(ctx.getString(R.string.err_http, code)) }
        return c
    }

    private fun download(ctx: Context, tag: String, f: File) {
        val want = TsLogic.sha256Of(get(ctx, UpdateLogic.apkUrl(tag, "kidguard.apk.sha256")).inputStream.use { String(it.readBytes()) })
            ?: throw IllegalStateException(ctx.getString(R.string.err_sha_read))
        val tmp = File(f.parentFile, "update.part"); val md = MessageDigest.getInstance("SHA-256")
        try {
            get(ctx, UpdateLogic.apkUrl(tag, "kidguard.apk")).inputStream.use { i -> tmp.outputStream().use { o ->
                val buf = ByteArray(64 * 1024)
                while (true) { val n = i.read(buf); if (n < 0) break; md.update(buf, 0, n); o.write(buf, 0, n) }
            } }
        } catch (e: Throwable) { tmp.delete(); throw e }
        if (md.digest().joinToString("") { "%02x".format(it) } != want) { tmp.delete(); throw IllegalStateException(ctx.getString(R.string.upd_err_sha)) }
        if (!tmp.renameTo(f)) throw IllegalStateException(ctx.getString(R.string.upd_err_save))
    }

    /** Same package, higher versionCode; the signature is checked by Android at install. */
    @Suppress("DEPRECATION")
    private fun verifyPackage(ctx: Context, f: File) {
        val pi = ctx.packageManager.getPackageArchiveInfo(f.path, 0)
        val code = if (Build.VERSION.SDK_INT >= 28) pi?.longVersionCode ?: 0 else (pi?.versionCode ?: 0).toLong()
        if (pi?.packageName != ctx.packageName || code <= BuildConfig.VERSION_CODE) {
            f.delete(); throw IllegalStateException(ctx.getString(R.string.upd_err_invalid))
        }
    }

    private fun session(ctx: Context, f: File): Pair<PackageInstaller, Int> {
        val pi = ctx.packageManager.packageInstaller
        val p = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply { setAppPackageName(ctx.packageName) }
        val id = pi.createSession(p)
        try { pi.openSession(id).use { s -> f.inputStream().use { i -> s.openWrite("kg", 0, f.length()).use { o -> i.copyTo(o); s.fsync(o) } } } }
        catch (e: Throwable) { runCatching { pi.abandonSession(id) }; throw e }
        return pi to id
    }

    private fun installSilently(ctx: Context, f: File) {
        val d = Policy.dpm(ctx); val a = Policy.admin(ctx)
        // as for Tailscale: the owner's own session is refused while "no installs" is set; the policy comes back in
        // finally, or (the usual case: this process is replaced) when the new Guard starts
        installing = true
        listOf(UserManager.DISALLOW_INSTALL_APPS, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES).forEach { runCatching { d.clearUserRestriction(a, it) } }
        try {
            val (pi, id) = session(ctx, f)
            pi.openSession(id).use { s ->
                val res = Ts.commitAndWait(ctx, s, "dev.kidguard.SELF_UPDATE")
                if (res != null) throw IllegalStateException(ctx.getString(R.string.err_install, res))
            }
            status = ""
        } finally { installing = false; runCatching { Policy.apply(ctx, Store.of(ctx).rules) } }
    }

    /** Basic mode, from the child's tap: needs "install unknown apps" for KidGuard, then the system asks to confirm. */
    fun installInteractive(ctx: Context): Boolean {
        val f = apk(ctx); if (!f.exists()) return false
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            runCatching { ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            return false
        }
        val app = ctx.applicationContext   // the thread must not hold the Activity
        Thread({
            runCatching {
                val (pi, id) = session(app, f)
                pi.openSession(id).use { s ->
                    val pend = PendingIntent.getBroadcast(app, 7, Intent(ACTION).setClass(app, UpdateReceiver::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                    s.commit(pend.intentSender)
                }
            }.onFailure { Log.e(TAG, "update install", it); status = "failed"; err = errText(app, it) }
        }, "update-ui").start()
        return true
    }

    private fun notifyChild(ctx: Context, tag: String) = runCatching {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("update", ctx.getString(R.string.ch_update), NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(ctx, 3, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(8, Notification.Builder(ctx, "update").setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(ctx.getString(R.string.notif_update_title, tag)).setContentText(ctx.getString(R.string.notif_update_text)).setAutoCancel(true).setContentIntent(open).build())
    }

    fun failed(msg: String) { status = "failed"; err = msg.take(120) }

    /**
     * What the parent page shows for a failed run. Our own messages pass through; a raw platform exception
     * ("java.security.cert.CertPathValidatorException: Trust anchor…", Poco 2026-11-11) becomes a sentence a parent can act on.
     */
    private fun errText(ctx: Context, e: Throwable): String {
        val chain = generateSequence(e) { it.cause }.take(6).toList()
        return when {
            e is IllegalStateException && !e.message.isNullOrBlank() -> e.message!!.take(160)
            chain.any { it is javax.net.ssl.SSLException || it is java.security.GeneralSecurityException } -> ctx.getString(R.string.upd_err_tls)
            chain.any { it is java.io.IOException } -> ctx.getString(R.string.upd_err_net)
            else -> (e.javaClass.simpleName + ": " + (e.message ?: ctx.getString(R.string.err_generic))).take(160)
        }
    }

    /** Release / unpair: drop a waiting download and its notification. */
    fun forget(ctx: Context) {
        apk(ctx).delete(); File(ctx.filesDir, "update.part").delete()
        runCatching { ctx.getSystemService(NotificationManager::class.java).cancel(8) }
    }
}

/** Status of the child-confirmed install: opens the system "Update this app?" screen. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION") val confirm = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                runCatching { ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            PackageInstaller.STATUS_SUCCESS -> {}
            PackageInstaller.STATUS_FAILURE_ABORTED -> {}   // the child closed the confirm screen: still "ready", not an error
            else -> Update.failed(i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ctx.applicationContext.getString(R.string.upd_cancelled))
        }
    }
}
