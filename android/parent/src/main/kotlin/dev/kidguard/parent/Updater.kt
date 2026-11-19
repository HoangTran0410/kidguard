package dev.kidguard.parent

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** In-app update (page "Cài đặt app"): check GitHub, download the APK ourselves (never the browser: the GitHub app hangs it), hand it to the system installer. State goes to the page as `kgUpd({st,...})`. */
object Updater {
    @Volatile private var busy = false

    private fun head(url: String): HttpURLConnection {
        val u = URL(url).openConnection() as HttpURLConnection
        u.instanceFollowRedirects = false; u.requestMethod = "HEAD"; u.connectTimeout = 15_000; u.readTimeout = 15_000
        return u
    }

    fun check(a: MainActivity) {
        if (busy) return
        busy = true
        a.push(JSONObject().put("st", "checking"))
        Thread({
            val o = JSONObject()
            try {
                val u = head(Logic.RELEASES)
                val tag = try { Logic.tagFromLocation(u.getHeaderField("Location")) } finally { u.disconnect() }
                if (tag == null) o.put("st", "fail")
                else if (Logic.newer(tag, BuildConfig.VERSION_NAME)) o.put("st", "new").put("tag", tag) else o.put("st", "ok")
            } catch (e: Exception) { o.put("st", "fail") }
            busy = false
            a.push(o)
        }, "kg-upd-check").start()
    }

    fun install(a: MainActivity, tag: String) {
        if (busy || !Regex("\\d{1,4}(\\.\\d{1,4}){1,3}").matches(tag)) return
        if (!a.packageManager.canRequestPackageInstalls()) {
            // user must allow installs from this app first; the page asks again after they come back
            a.runOnUiThread { runCatching { a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.packageName))) } }
            a.push(JSONObject().put("st", "perm").put("tag", tag))
            return
        }
        busy = true
        a.push(JSONObject().put("st", "dl").put("tag", tag).put("pct", 0))
        Thread({
            val o = JSONObject().put("tag", tag)
            try {
                val dir = File(a.cacheDir, "upd").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
                val f = File(dir, "kidguard-parent-$tag.apk")
                val want = fetchSha(Logic.manifest(tag))   // like the child updater: no checksum, no install
                download(Logic.parentApk(tag), f, want) { a.push(JSONObject().put("st", "dl").put("tag", tag).put("pct", it)) }
                // PackageInstaller session: on Android 12+ with us as the installer of record the update goes in without a tap
                // (USER_ACTION_NOT_REQUIRED); otherwise the system asks (InstallResult opens its prompt). The bridge keeps the old "inst" state.
                SelfInstall.start(a, f)
                o.put("st", "inst")
            } catch (e: Exception) { android.util.Log.w("KGParent", "update download", e); o.put("st", "dlfail") }
            busy = false
            a.push(o)
        }, "kg-upd-dl").start()
    }

    /** GET following up to 6 https redirects by hand; progress in percent (0..100) when the size is known. */
    /** Our APK's SHA-256 from the release's version.json (GitHub redirects to its CDN). */
    private fun fetchSha(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 15_000; c.instanceFollowRedirects = true
        try {
            if (c.responseCode != 200) throw java.io.IOException("sha HTTP ${c.responseCode}")
            return dev.kidguard.Release.shaIn(String(c.inputStream.use { it.readBytes() }.take(16384).toByteArray()), "kidguard-parent.apk") ?: throw java.io.IOException("sha unreadable")
        } finally { c.disconnect() }
    }

    private fun download(url: String, out: File, want: String, progress: (Int) -> Unit) {
        var cur = url
        for (hop in 0..6) {
            val c = URL(cur).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false; c.connectTimeout = 15_000; c.readTimeout = 30_000
            try {
                val code = c.responseCode
                if (code in 301..308 && code != 304) {
                    val loc = c.getHeaderField("Location") ?: throw java.io.IOException("redirect")
                    cur = URL(URL(cur), loc).toString()
                    if (!cur.startsWith("https://")) throw java.io.IOException("not https")
                    continue
                }
                if (code != 200) throw java.io.IOException("HTTP $code")
                val total = c.contentLengthLong
                var got = 0L; var last = -1
                val md = java.security.MessageDigest.getInstance("SHA-256")
                c.inputStream.use { i -> out.outputStream().use { w ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = i.read(buf); if (n < 0) break
                        w.write(buf, 0, n); md.update(buf, 0, n); got += n
                        val p = if (total > 0) (got * 100 / total).toInt() else 0
                        if (p != last) { last = p; progress(p) }
                    }
                } }
                if (got < 100_000 || (total > 0 && got != total)) throw java.io.IOException("short")
                if (md.digest().joinToString("") { "%02x".format(it) } != want) { out.delete(); throw java.io.IOException("sha256 mismatch") }
                return
            } finally { c.disconnect() }
        }
        throw java.io.IOException("too many redirects")
    }
}

/** Install the downloaded APK through a PackageInstaller session instead of ACTION_VIEW. */
object SelfInstall {
    const val ACTION = "dev.kidguard.parent.INSTALL_RESULT"

    fun start(ctx: android.content.Context, apk: File) {
        val pi = ctx.packageManager.packageInstaller
        val p = android.content.pm.PackageInstaller.SessionParams(android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        p.setAppPackageName(ctx.packageName)
        if (android.os.Build.VERSION.SDK_INT >= 31) p.setRequireUserAction(android.content.pm.PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        val id = pi.createSession(p)
        pi.openSession(id).use { s ->
            apk.inputStream().use { i -> s.openWrite("kidguard-parent.apk", 0, apk.length()).use { o -> i.copyTo(o); s.fsync(o) } }
            val intent = Intent(ctx, InstallResult::class.java).setAction(ACTION).setPackage(ctx.packageName)
            val flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT or (if (android.os.Build.VERSION.SDK_INT >= 31) android.app.PendingIntent.FLAG_MUTABLE else 0)
            s.commit(android.app.PendingIntent.getBroadcast(ctx, id, intent, flags).intentSender)
        }
    }
}

/** Result of [SelfInstall]: a needed confirmation is opened (the old behaviour); a failure is logged (the page keeps its "inst" line, the parent can retry). */
class InstallResult : android.content.BroadcastReceiver() {
    override fun onReceive(c: android.content.Context, i: Intent) {
        val st = i.getIntExtra(android.content.pm.PackageInstaller.EXTRA_STATUS, -1)
        if (st == android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION") val ask = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            if (ask != null) runCatching { c.startActivity(ask.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        } else if (st != android.content.pm.PackageInstaller.STATUS_SUCCESS) android.util.Log.w("KGParent", "self-update install status $st: ${i.getStringExtra(android.content.pm.PackageInstaller.EXTRA_STATUS_MESSAGE)}")
    }
}
