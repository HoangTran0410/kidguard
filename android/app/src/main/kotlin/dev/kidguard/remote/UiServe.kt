package dev.kidguard

import android.content.Context
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPOutputStream

/**
 * The parent page's files (index.html + ui/assets/) over LAN: gzip when the browser accepts it (~816 KB -> ~266 KB a load)
 * and an ETag tied to this install, so a revisit asks "changed?" and gets an empty 304 (assets only change with an app update).
 */
object UiServe {
    private val raw = ConcurrentHashMap<String, ByteArray>()
    private val gz = ConcurrentHashMap<String, ByteArray>()
    @Volatile private var tag: String? = null

    private fun tagOf(ctx: Context): String = tag ?: runCatching {
        val p = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        "\"kg-${p.lastUpdateTime.toString(36)}\""
    }.getOrDefault("\"kg-0\"").also { tag = it }

    fun res(ctx: Context, r: Req, file: String, type: String): Res {
        val t = tagOf(ctx)
        val h = mapOf("Cache-Control" to "no-cache", "ETag" to t)
        if (r.header("if-none-match") == t) return Res(304, type, ByteArray(0), h)
        val b = raw.getOrPut(file) { ctx.assets.open(file).use { it.readBytes() } }
        if (b.size < 1024 || !(r.header("accept-encoding") ?: "").contains("gzip")) return Res(200, type, b, h)
        val z = gz.getOrPut(file) { ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(b) } }.toByteArray() }
        return Res(200, type, z, h + ("Content-Encoding" to "gzip"))
    }
}
