package dev.kidguard

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import org.json.JSONObject
import java.io.File

/**
 * App labels kept across process starts. loadLabel is a binder call per app: 185 apps took ~290 ms on a POCO right
 * after a cold start (launcher) and again in Policy.launchable. Keyed by apk path (changes on every update) + activity +
 * locale, so a stale label can't survive. Saved off the main thread, at most once per 2 s.
 */
object Labels {
    private const val FILE = "labels.json"
    private var map: HashMap<String, String>? = null
    private var dirty = false
    private var saving = false

    @Synchronized private fun m(ctx: Context): HashMap<String, String> = map ?: HashMap<String, String>().also { h ->
        runCatching { JSONObject(File(ctx.filesDir, FILE).readText()).let { o -> if (o.length() < 3000) o.keys().forEach { k -> h[k] = o.getString(k) } } }   // old apk paths pile up: start over now and then
        map = h
    }

    private fun locale(ctx: Context) = ctx.resources.configuration.locales[0].toString()

    fun of(ctx: Context, ri: ResolveInfo, pm: PackageManager): String =
        get(ctx, ri.activityInfo?.applicationInfo?.sourceDir + "|" + ri.activityInfo?.name) { ri.loadLabel(pm).toString() }

    fun of(ctx: Context, ai: ApplicationInfo, pm: PackageManager): String =
        get(ctx, ai.sourceDir + "|") { ai.loadLabel(pm).toString() }

    /** Any other per-apk fact (the apk path in [k0] changes on every update). */
    fun memo(ctx: Context, k0: String, load: () -> String): String = get(ctx, k0, load)

    private fun get(ctx: Context, k0: String, load: () -> String): String {
        val k = k0 + "|" + locale(ctx)
        synchronized(this) { m(ctx)[k]?.let { return it } }
        val v = load()
        if (v.isEmpty()) return v   // a failed look-up is not kept
        synchronized(this) { m(ctx)[k] = v; dirty = true }
        save(ctx.applicationContext)
        return v
    }

    @Synchronized private fun save(ctx: Context) {
        if (saving) return
        saving = true
        Thread {
            Thread.sleep(2000)   // one write for a whole list
            val o = synchronized(this) { dirty = false; saving = false; JSONObject((map ?: return@Thread) as Map<*, *>).toString() }
            runCatching { val f = File(ctx.filesDir, "$FILE.tmp"); f.writeText(o); f.renameTo(File(ctx.filesDir, FILE)) }
        }.start()
    }
}
