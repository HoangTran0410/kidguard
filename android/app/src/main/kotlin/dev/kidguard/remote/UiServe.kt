package dev.kidguard

import android.content.Context
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPOutputStream

/**
 * The parent page's files (index.html + ui/assets/) over LAN: gzip when the browser accepts it (~816 KB -> ~266 KB a load)
 * and an ETag, so a revisit asks "changed?" and gets an empty 304. The files come from the newest verified UI pack
 * ([UiPackHub], android/shared/UiPack.kt) or, without one, from the APK (ETag tied to this install).
 */
object UiServe {
    private val gz = ConcurrentHashMap<String, ByteArray>()
    @Volatile private var hubV: UiPackHub? = null
    @Volatile private var lastTag = ""

    /** Per-process pack hub. Stored device-protected (the LAN server may run before the first unlock). */
    fun hub(ctx: Context): UiPackHub = hubV ?: synchronized(this) {
        hubV ?: run {
            val app = ctx.applicationContext
            val dp = if (android.os.Build.VERSION.SDK_INT >= 24) app.createDeviceProtectedStorageContext() else app
            val tag = runCatching { "\"kg-${app.packageManager.getPackageInfo(app.packageName, 0).lastUpdateTime.toString(36)}\"" }.getOrDefault("\"kg-0\"")
            UiPackHub(UiPackStore(java.io.File(dp.filesDir, "uipack"), UiRole.KID, BuildConfig.VERSION_CODE), { n -> app.assets.open(n).use { it.readBytes() } }, tag)
        }.also { hubV = it }
    }

    /** Background checks (page open, daily tick) follow the rule autoUpdate; the parent's button / command always checks. */
    fun autoOk(ctx: Context) = (ctx as? Guard)?.autoUpdateOn() != false

    fun res(ctx: Context, r: Req, file: String, type: String): Res {
        val h = hub(ctx)
        // a page that never booted 3 times is reverted by the store; the tag read below is of what is served now
        if (file == "index.html") { h.store.indexServed(); if (autoOk(ctx)) h.checkAsync(60_000) }
        val t = h.tag()
        val hd = mapOf("Cache-Control" to "no-cache", "ETag" to t)
        if (r.header("if-none-match") == t) return Res(304, type, ByteArray(0), hd)
        val b = h.file(file)
        if (t != lastTag) { gz.clear(); lastTag = t }
        if (b.size < 1024 || !(r.header("accept-encoding") ?: "").contains("gzip")) return Res(200, type, b, hd)
        val z = gz.getOrPut("$t/$file") { ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(b) } }.toByteArray() }
        return Res(200, type, z, hd + ("Content-Encoding" to "gzip"))
    }
}
