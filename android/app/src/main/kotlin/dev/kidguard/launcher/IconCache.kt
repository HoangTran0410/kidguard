package dev.kidguard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.LruCache

/**
 * Real app icons for the kid launcher, rendered once to a bitmap of the wanted size and kept in an LRU of 1/8 of the heap
 * (>= 8 MB). 4 MB was too small on a 420 dpi phone (~90 KB an icon, 50+ on the pages + dock + drawer): every rebuild walked
 * the LRU in the same order, so it missed every icon and re-rendered them all (~700 ms after each drop on a Galaxy A51).
 * Adaptive icons get the chosen mask (circle / rounded square); legacy icons are drawn as they are, full size, no plate (Pixel / Nova).
 */
object IconCache {
    private val MAX_BYTES = (Runtime.getRuntime().maxMemory() / 8).coerceIn(8L shl 20, 64L shl 20).toInt()
    private val lru = object : LruCache<String, Bitmap>(MAX_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val grey = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) }) }

    fun clear() = lru.evictAll()

    fun get(ctx: Context, pkg: String, cls: String, px: Int, round: Boolean): Bitmap? {
        val key = "$pkg/$cls@$px${if (round) "r" else "s"}"
        lru.get(key)?.let { return it }
        val d = load(ctx, pkg, cls) ?: return null
        val b = render(d, px, round)
        lru.put(key, b)
        return b
    }

    /** The paused look: greyscale at 45 %, drawn by the caller with this paint (no second bitmap in the cache). */
    fun greyPaint(): Paint = grey

    private fun load(ctx: Context, pkg: String, cls: String): Drawable? = runCatching {
        val pm = ctx.packageManager
        if (cls.isNotEmpty()) pm.getActivityIcon(android.content.ComponentName(pkg, cls)) else pm.getApplicationIcon(pkg)
    }.recoverCatching { ctx.packageManager.getApplicationIcon(pkg) }.getOrNull()

    private fun shape(px: Int, round: Boolean): Path = Path().apply {
        if (round) addCircle(px / 2f, px / 2f, px / 2f, Path.Direction.CW)
        else addRoundRect(RectF(0f, 0f, px.toFloat(), px.toFloat()), px * 0.24f, px * 0.24f, Path.Direction.CW)
    }

    private fun render(d: Drawable, px: Int, round: Boolean): Bitmap {
        val b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val clip = shape(px, round)
        if (Build.VERSION.SDK_INT >= 26 && d is AdaptiveIconDrawable) {
            // the layers are 108 dp with a 72 dp safe zone: draw at 150 % so the visible part fills the shape
            val ex = (px * 0.25f).toInt()
            d.background?.let { it.setBounds(-ex, -ex, px + ex, px + ex); it.draw(c) }
            d.foreground?.let { it.setBounds(-ex, -ex, px + ex, px + ex); it.draw(c) }
            // then the mask with smooth edges (clipPath on a bitmap canvas is not anti-aliased)
            clip.fillType = Path.FillType.INVERSE_WINDING
            c.drawPath(clip, Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) })
        } else {
            // keep the icon's own aspect, centred (a few legacy icons are not square)
            val iw = d.intrinsicWidth.takeIf { it > 0 } ?: px; val ih = d.intrinsicHeight.takeIf { it > 0 } ?: px
            val sc = px.toFloat() / maxOf(iw, ih)
            val w = (iw * sc).toInt(); val h = (ih * sc).toInt()
            d.setBounds((px - w) / 2, (px - h) / 2, (px - w) / 2 + w, (px - h) / 2 + h); d.draw(c)
            normalize(b, c, d)
        }
        edge(b, c, clip, px)
        return b
    }

    /**
     * A white icon (Chrome, Google, Play) on a white dock or drawer loses its outline and reads smaller than the rest
     * (same disc, only the logo shows). Like One UI's icon shadow: a faint hairline when the rim is mostly light.
     */
    private fun edge(b: Bitmap, c: Canvas, clip: Path, px: Int) {
        val r = px / 2f - 2f; var light = 0; var n = 0
        for (k in 0 until 32) {
            val a = k * Math.PI / 16
            val x = (px / 2f + r * Math.cos(a)).toInt().coerceIn(0, px - 1); val y = (px / 2f + r * Math.sin(a)).toInt().coerceIn(0, px - 1)
            val p = b.getPixel(x, y); if (android.graphics.Color.alpha(p) < 200) continue
            n++; if (android.graphics.Color.red(p) > 225 && android.graphics.Color.green(p) > 225 && android.graphics.Color.blue(p) > 225) light++
        }
        if (n < 16 || light * 10 < n * 7) return
        clip.fillType = Path.FillType.WINDING
        val w = (px / 60f).coerceAtLeast(1.5f)
        c.save(); c.clipPath(clip)
        c.drawPath(clip, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = w * 2; color = 0x24000000 })
        c.restore()
    }

    /**
     * Legacy icons often carry their own transparent margin (Samsung hands Chrome / Phone out pre-shaped and padded), so
     * they looked smaller than their neighbours in the dock. Like Launcher3's IconNormalizer: crop to the visible pixels,
     * then scale so the visible area matches a full circle of [b]'s size (a square gets ~89 %, a circle 100 %).
     */
    private fun normalize(b: Bitmap, c: Canvas, d: Drawable) {
        val px = b.width
        val a = IntArray(px * px); b.getPixels(a, 0, px, 0, 0, px, px)
        var l = px; var t = px; var r = -1; var bt = -1; var n = 0
        for (y in 0 until px) for (x in 0 until px) if ((a[y * px + x] ushr 24) > 40) { n++; if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > bt) bt = y }
        if (r < 0) return
        val bw = r - l + 1; val bh = bt - t + 1
        val fill = n.toFloat() / (bw * bh)                      // circle ~0.785, squircle ~0.9, square 1
        val target = px * minOf(1f, kotlin.math.sqrt(0.785f / fill.coerceAtLeast(0.3f)))
        val s = target / maxOf(bw, bh)
        if (s < 1.04f && s > 0.96f) return                      // already right: keep the pixels as drawn
        val ob = d.bounds
        val ox = (px - bw * s) / 2 - l * s; val oy = (px - bh * s) / 2 - t * s
        b.eraseColor(0)
        d.setBounds((ob.left * s + ox).toInt(), (ob.top * s + oy).toInt(), (ob.right * s + ox).toInt(), (ob.bottom * s + oy).toInt())
        d.draw(c)
    }

    /** Label for a launcher activity, cached by the caller. */
    fun label(ctx: Context, pkg: String): String = runCatching {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    fun isLaunchable(ctx: Context, pkg: String) = ctx.packageManager.getLaunchIntentForPackage(pkg) != null
}
