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
        }
        return b
    }

    /** Label for a launcher activity, cached by the caller. */
    fun label(ctx: Context, pkg: String): String = runCatching {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    fun isLaunchable(ctx: Context, pkg: String) = ctx.packageManager.getLaunchIntentForPackage(pkg) != null
}
