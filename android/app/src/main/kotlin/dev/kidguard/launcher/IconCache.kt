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
 * Real app icons for the kid launcher, rendered once to a bitmap of the wanted size and kept in an LRU of 1/5 of the heap
 * (8-96 MB; the drawer reuses the home-size bitmap, so ~1 bitmap per app). 4 MB was too small on a 420 dpi phone (~90 KB an icon, 50+ on the pages + dock + drawer): every rebuild walked
 * the LRU in the same order, so it missed every icon and re-rendered them all (~700 ms after each drop on a Galaxy A51).
 * Every icon gets the chosen mask (circle / squircle); legacy icons are fitted or put on a plate first ([legacy]).
 */
object IconCache {
    private val MAX_BYTES = (Runtime.getRuntime().maxMemory() / 5).coerceIn(8L shl 20, 96L shl 20).toInt()
    private val lru = object : LruCache<String, Bitmap>(MAX_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val grey = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) }) }

    fun clear() = lru.evictAll()
    /** Perf counters for KG.perf (reset by the reader): icons rendered (cache misses) and the time they took. */
    var misses = 0; var missMs = 0L
    fun stats(): String = "icons=$misses/${missMs}ms lru=${lru.size() shr 20}/${MAX_BYTES shr 20}MB".also { misses = 0; missMs = 0 }

    private fun key(pkg: String, cls: String, px: Int, round: Boolean) = "$pkg/$cls@$px${if (round) "r" else "s"}"

    /** Only what is already rendered (no load). */
    fun peek(pkg: String, cls: String, px: Int, round: Boolean): Bitmap? = lru.get(key(pkg, cls, px, round))

    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "kg-icons")
    }
    private val main by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    /**
     * Lazy icon: rendered on a background thread (~7 ms each, cold, on a POCO), [done] on the main thread. In call order,
     * so the cells built first (the ones on screen) come first.
     */
    fun async(ctx: Context, pkg: String, cls: String, px: Int, round: Boolean, done: (Bitmap?) -> Unit) {
        val app = ctx.applicationContext
        worker.execute { val b = runCatching { get(app, pkg, cls, px, round) }.getOrNull(); main.post { done(b) } }
    }

    fun get(ctx: Context, pkg: String, cls: String, px: Int, round: Boolean): Bitmap? {
        val key = key(pkg, cls, px, round)
        lru.get(key)?.let { return it }
        val t0 = android.os.SystemClock.uptimeMillis()
        val d = load(ctx, pkg, cls) ?: return null
        val b = render(d, px, round)
        lru.put(key, b)
        misses++; missMs += android.os.SystemClock.uptimeMillis() - t0
        return b
    }

    /** The paused look: greyscale at 45 %, drawn by the caller with this paint (no second bitmap in the cache). */
    fun greyPaint(): Paint = grey

    private fun load(ctx: Context, pkg: String, cls: String): Drawable? = if (cls.startsWith(Shortcuts.PREFIX)) Shortcuts.icon(ctx, pkg, cls.removePrefix(Shortcuts.PREFIX)) else runCatching {
        val pm = ctx.packageManager
        if (cls.isNotEmpty()) pm.getActivityIcon(android.content.ComponentName(pkg, cls)) else pm.getApplicationIcon(pkg)
    }.recoverCatching { ctx.packageManager.getApplicationIcon(pkg) }.getOrNull()

    /** Circle, or One UI's squircle (superellipse, n = 5): softer corners than a rounded rect. */
    private fun shape(px: Int, round: Boolean): Path = Path().apply {
        val h = px / 2f
        if (round) { addCircle(h, h, h, Path.Direction.CW); return@apply }
        for (k in 0..96) {
            val t = k * 2 * Math.PI / 96
            val ct = Math.cos(t); val st = Math.sin(t)
            val x = h + h * Math.signum(ct) * Math.pow(Math.abs(ct), 0.4); val y = h + h * Math.signum(st) * Math.pow(Math.abs(st), 0.4)
            if (k == 0) moveTo(x.toFloat(), y.toFloat()) else lineTo(x.toFloat(), y.toFloat())
        }
        close()
    }

    private fun inShape(u: Double, v: Double, round: Boolean) =
        if (round) u * u + v * v <= 1.0 else Math.pow(Math.abs(u), 5.0) + Math.pow(Math.abs(v), 5.0) <= 1.0

    private fun render(d: Drawable, px: Int, round: Boolean): Bitmap {
        val b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val clip = shape(px, round)
        if (Build.VERSION.SDK_INT >= 26 && d is AdaptiveIconDrawable) {
            // the layers are 108 dp with a 72 dp safe zone: draw at 150 % so the visible part fills the shape
            val ex = (px * 0.25f).toInt()
            d.background?.let { it.setBounds(-ex, -ex, px + ex, px + ex); it.draw(c) }
            d.foreground?.let { it.setBounds(-ex, -ex, px + ex, px + ex); it.draw(c) }
        } else legacy(d, px, round, c)
        // the mask with smooth edges (clipPath on a bitmap canvas is not anti-aliased)
        clip.fillType = Path.FillType.INVERSE_WINDING
        c.drawPath(clip, Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) })
        edge(b, c, clip, px)
        glass(c, clip, px)
        return b
    }

    /**
     * A plain plate in the app-icon shape with the same glass rim, for things that sit in the grid next to app icons
     * (folder background, the 1×1 lock widget), so they line up and read like an icon. Small: drawn per render.
     */
    fun plate(px: Int, round: Boolean, color: Int): Bitmap {
        val b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(b); val clip = shape(px, round)
        c.drawPath(clip, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
        glass(c, clip, px)
        return b
    }

    /**
     * The glass rim (iOS 26 / LauncherOS): a thin light line on the top-left and bottom-right corners, fading out along
     * the sides, so the icon reads as a lit pane and its logo stands out. Drawn once into the cached bitmap: free per frame.
     */
    private fun glass(c: Canvas, clip: Path, px: Int) {
        clip.fillType = Path.FillType.WINDING
        val w = (px / 72f).coerceAtLeast(1.2f)
        val p = px.toFloat()
        c.save(); c.clipPath(clip)
        c.drawPath(clip, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = w * 2
            shader = android.graphics.LinearGradient(0f, 0f, p, p,
                intArrayOf(0xB3FFFFFF.toInt(), 0x00FFFFFF, 0x00FFFFFF, 0x80FFFFFF.toInt()), floatArrayOf(0f, 0.32f, 0.68f, 1f),
                android.graphics.Shader.TileMode.CLAMP)
        })
        c.restore()
    }

    /**
     * Legacy (non-adaptive) icons, the One UI way, so every icon ends up in the same shape:
     * - the visible part already fills the shape (a square logo in square mode, Samsung's round Chrome in round mode):
     *   scaled to the full size, then masked;
     * - otherwise (eTax's disc in square mode, Vietnam Airlines' bird): shrunk to 74 % on a white plate that goes
     *   slightly grey towards the bottom, the light-from-above look of One UI's plates.
     * Measured on the visible pixels (Samsung hands some icons out pre-shaped with a transparent margin).
     */
    private fun legacy(d: Drawable, px: Int, round: Boolean, c: Canvas) {
        val raw = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val iw = d.intrinsicWidth.takeIf { it > 0 } ?: px; val ih = d.intrinsicHeight.takeIf { it > 0 } ?: px
        val sc = px.toFloat() / maxOf(iw, ih)
        val w = (iw * sc).toInt(); val h = (ih * sc).toInt()
        d.setBounds((px - w) / 2, (px - h) / 2, (px - w) / 2 + w, (px - h) / 2 + h); d.draw(Canvas(raw))
        val a = IntArray(px * px); raw.getPixels(a, 0, px, 0, 0, px, px)
        var l = px; var t = px; var r = -1; var bt = -1
        for (y in 0 until px) for (x in 0 until px) if ((a[y * px + x] ushr 24) > 40) { if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > bt) bt = y }
        if (r < 0) { raw.recycle(); return }
        val bw = r - l + 1; val bh = bt - t + 1
        // share of the shape (stretched over the visible box) that is opaque
        var inN = 0; var opN = 0
        for (y in t..bt step 2) for (x in l..r step 2) {
            val u = (x - l + 0.5) / bw * 2 - 1; val v = (y - t + 0.5) / bh * 2 - 1
            if (!inShape(u, v, round)) continue
            inN++; if ((a[y * px + x] ushr 24) > 200) opN++
        }
        val full = inN > 0 && opN >= inN * 0.96 && minOf(bw, bh) >= maxOf(bw, bh) * 0.92
        val src = android.graphics.Rect(l, t, r + 1, bt + 1)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        if (full) {
            val o = px * 0.01f   // a hair past the mask: no light rim from the icon's own anti-aliased edge
            c.drawBitmap(raw, src, RectF(-o, -o, px + o, px + o), paint)
        } else {
            c.drawRect(0f, 0f, px.toFloat(), px.toFloat(), Paint().apply {
                shader = android.graphics.LinearGradient(0f, 0f, 0f, px.toFloat(), 0xFFFFFFFF.toInt(), 0xFFE6E9EE.toInt(), android.graphics.Shader.TileMode.CLAMP)
            })
            val s = px * 0.74f / maxOf(bw, bh)
            val dw = bw * s; val dh = bh * s
            c.drawBitmap(raw, src, RectF((px - dw) / 2, (px - dh) / 2, (px + dw) / 2, (px + dh) / 2), paint)
        }
        raw.recycle()
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

    /** Label for a launcher activity, cached by the caller. */
    fun label(ctx: Context, pkg: String): String = runCatching {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    fun isLaunchable(ctx: Context, pkg: String) = ctx.packageManager.getLaunchIntentForPackage(pkg) != null
}
