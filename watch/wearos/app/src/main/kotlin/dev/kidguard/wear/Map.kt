package dev.kidguard.wear

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.tan

/** Web Mercator helpers, same maths as watch/zepp/utils/map.js. OSM slippy tiles are 256 px. */
object MapMath {
    const val TILE = 256
    const val ZMIN = 13
    const val ZMAX = 18

    fun tileF(lat: Double, lng: Double, z: Int): Pair<Double, Double> {
        val n = 2.0.pow(z)
        val la = Math.toRadians(lat.coerceIn(-85.0511, 85.0511))
        return Pair((lng + 180) / 360 * n, (1 - ln(tan(la) + 1 / cos(la)) / PI) / 2 * n)
    }

    /** metres per tile pixel at this latitude / zoom */
    fun mpp(lat: Double, z: Int) = 156543.03392 * cos(Math.toRadians(lat)) / 2.0.pow(z)
}

/** A 2x2 tile mosaic (512 px) with the kid inside its centre half; px/py = kid in mosaic pixels. */
class Mosaic(val bmp: Bitmap, val px: Float, val py: Float, val missing: Int)

object Tiles {
    private const val TTL = 7 * 24 * 3600_000L
    private val mem = object : LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Bitmap>) = size > 24
    }

    /** Blocking (IO). Memory + disk cache (OSM tile policy: cache, few requests). */
    fun mosaic(c: Context, k: Kid, lat: Double, lng: Double, z: Int): Mosaic {
        val n = 1 shl z
        val (fx, fy) = MapMath.tileF(lat, lng, z)
        val x0 = floor(fx - 0.5).toInt()
        val y0 = floor(fy - 0.5).toInt()
        val out = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        val cv = Canvas(out)
        cv.drawColor(Color.rgb(42, 42, 42))
        var missing = 0
        for (j in 0..1) for (i in 0..1) {
            val y = y0 + j
            if (y < 0 || y >= n) continue
            val x = ((x0 + i) % n + n) % n
            val b = tile(c, k, z, x, y)
            if (b == null) missing++ else cv.drawBitmap(b, (i * 256).toFloat(), (j * 256).toFloat(), null)
        }
        return Mosaic(out, ((fx - x0) * 256).toFloat(), ((fy - y0) * 256).toFloat(), missing)
    }

    private fun tile(c: Context, k: Kid, z: Int, x: Int, y: Int): Bitmap? {
        val key = "$z/$x/$y"
        synchronized(mem) { mem[key]?.let { return it } }
        val f = File(c.cacheDir, "t_${z}_${x}_$y.png")
        var data: ByteArray? = null
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < TTL) data = try { f.readBytes() } catch (e: Exception) { null }
        if (data == null) {
            data = Net.tile(k, z, x, y)
            if (data != null) try { f.writeBytes(data) } catch (e: Exception) {}
        }
        val bmp = data?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } ?: return null
        synchronized(mem) { mem[key] = bmp }
        return bmp
    }
}
