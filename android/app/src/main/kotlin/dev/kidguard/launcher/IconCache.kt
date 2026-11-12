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

    fun clear() { if (Perf.on()) android.util.Log.v(Perf.TAG, "icons clear lru=${lru.size() shr 20}MB"); lru.evictAll() }
    private val queued = java.util.concurrent.atomic.AtomicInteger()

    /** Icon edge + face from Tuỳ chỉnh (HStyle.rim / fill); part of every cache key, so a change re-renders. */
    @Volatile var rim = "top"; private set
    @Volatile var fill = "app"; private set
    fun look(r: String, f: String) { rim = r; fill = f }
    /** Perf counters for KG.perf (reset by the reader): icons rendered (cache misses) and the time they took. */
    var misses = 0; var missMs = 0L
    fun stats(): String = "icons=$misses/${missMs}ms lru=${lru.size() shr 20}/${MAX_BYTES shr 20}MB".also { misses = 0; missMs = 0 }

    private fun key(pkg: String, cls: String, px: Int, shape: String) = "$pkg/$cls@$px${shape.take(2)}$rim$fill${AppLook.iconKey(pkg, cls)}"

    /** Only what is already rendered (no load). */
    fun peek(pkg: String, cls: String, px: Int, shape: String): Bitmap? = lru.get(key(pkg, cls, px, shape))

    // a deque, so an icon the kid is looking at now ([soon]: an open folder) jumps the queue. Measured on the Poco
    // (2026-11-12): a style change re-renders ~150 icons (home + drawer) in ~1.3 s, and a folder opened meanwhile waited
    // behind 108 of them (~0.9 s of empty slots)
    private val todo = java.util.concurrent.LinkedBlockingDeque<Runnable>()
    private val worker = java.util.concurrent.ThreadPoolExecutor(1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS, todo) { r ->
        Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "kg-icons")
    }.apply { prestartCoreThread() }
    private val main by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    /**
     * Lazy icon: rendered on a background thread (~7 ms each, cold, on a POCO), [done] on the main thread. In call order,
     * so the cells built first (the ones on screen) come first; [soon] (an open folder's apps) goes ahead of the queue.
     */
    fun async(ctx: Context, pkg: String, cls: String, px: Int, shape: String, soon: Boolean = false, done: (Bitmap?) -> Unit) {
        val app = ctx.applicationContext
        val t0 = android.os.SystemClock.uptimeMillis(); val ahead = if (soon) 0 else queued.get(); queued.incrementAndGet()
        val job = Runnable {
            val t1 = android.os.SystemClock.uptimeMillis()
            val b = runCatching { get(app, pkg, cls, px, shape) }.getOrNull(); queued.decrementAndGet()
            if (Perf.on()) android.util.Log.v(Perf.TAG, "icon async $pkg@$px wait=${t1 - t0}ms render=${android.os.SystemClock.uptimeMillis() - t1}ms ahead=$ahead lru=${lru.size() shr 20}/${MAX_BYTES shr 20}MB")
            main.post { done(b) }
        }
        if (soon) todo.offerFirst(job) else worker.execute(job)   // the one worker thread is always up (prestarted, never times out)
    }

    fun get(ctx: Context, pkg: String, cls: String, px: Int, shape: String): Bitmap? {
        val key = key(pkg, cls, px, shape)
        lru.get(key)?.let { return it }
        val t0 = android.os.SystemClock.uptimeMillis()
        val d = AppLook.drawable(ctx, pkg, cls, px) ?: load(ctx, pkg, cls) ?: return null
        val b = render(d, px, shape)
        lru.put(key, b)
        misses++; missMs += android.os.SystemClock.uptimeMillis() - t0
        return b
    }

    /** [d] in the icon [shape] exactly as the home draws it, not cached (the "Sửa app" preview: a choice not saved yet). */
    fun shaped(d: Drawable, px: Int, shape: String): Bitmap = render(d, px, shape)

    /** The paused look: greyscale at 45 %, drawn by the caller with this paint (no second bitmap in the cache). */
    fun greyPaint(): Paint = grey

    private fun load(ctx: Context, pkg: String, cls: String): Drawable? = if (cls.startsWith(Shortcuts.PREFIX)) Shortcuts.icon(ctx, pkg, cls.removePrefix(Shortcuts.PREFIX)) else runCatching {
        val pm = ctx.packageManager
        if (cls.isNotEmpty()) pm.getActivityIcon(android.content.ComponentName(pkg, cls)) else pm.getApplicationIcon(pkg)
    }.recoverCatching { ctx.packageManager.getApplicationIcon(pkg) }.getOrNull()

    /**
     * The superellipse exponent per icon shape: "round" = circle (2), "soft" = Pixel / One UI's squircle (3: nearly round,
     * flat sides), "square" = the iOS-like squircle (5: softer corners than a rounded rect).
     */
    fun exp(shape: String) = when (shape) { "round" -> 2.0; "soft" -> 3.0; else -> 5.0 }

    private fun shape(px: Int, shape: String): Path = Path().apply {
        val h = px / 2f
        if (shape == "round") { addCircle(h, h, h, Path.Direction.CW); return@apply }
        val e = 2.0 / exp(shape)
        for (k in 0..96) {
            val t = k * 2 * Math.PI / 96
            val ct = Math.cos(t); val st = Math.sin(t)
            val x = h + h * Math.signum(ct) * Math.pow(Math.abs(ct), e); val y = h + h * Math.signum(st) * Math.pow(Math.abs(st), e)
            if (k == 0) moveTo(x.toFloat(), y.toFloat()) else lineTo(x.toFloat(), y.toFloat())
        }
        close()
    }

    private fun inShape(u: Double, v: Double, shape: String) = exp(shape).let { n -> Math.pow(Math.abs(u), n) + Math.pow(Math.abs(v), n) <= 1.0 }

    private fun render(d: Drawable, px: Int, shape: String): Bitmap {
        val b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val clip = shape(px, shape)
        if (Build.VERSION.SDK_INT >= 26 && d is AdaptiveIconDrawable) {
            // the layers are 108 dp with a 72 dp safe zone: draw at 150 % so the visible part fills the shape
            val ex = (px * 0.25f).toInt()
            d.background?.let { it.setBounds(-ex, -ex, px + ex, px + ex); it.draw(c) }
            d.foreground?.let { it.setBounds(-ex, -ex, px + ex, px + ex); it.draw(c) }
        } else legacy(d, px, shape, c)
        // the mask with smooth edges (clipPath on a bitmap canvas is not anti-aliased)
        clip.fillType = Path.FillType.INVERSE_WINDING
        c.drawPath(clip, Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) })
        edge(b, c, clip, px)
        sheen(c, clip, px)
        glass(c, clip, px)
        return b
    }

    /**
     * A plain plate in the app-icon shape with the same glass rim, for things that sit in the grid next to app icons
     * (folder background, the 1×1 lock widget), so they line up and read like an icon. Small: drawn per render.
     */
    fun plate(px: Int, shape: String, color: Int): Bitmap {
        val b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(b); val clip = shape(px, shape)
        c.drawPath(clip, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
        sheen(c, clip, px)
        glass(c, clip, px)
        return b
    }

    /**
     * The icon edge, drawn once into the cached bitmap (free per frame), inside the mask:
     * - "top": a hairline lit from above, fading towards the bottom, the same on left and right (default);
     * - "line": an even faint hairline all round;
     * - "glass": the iOS 26 / LauncherOS diagonal (top-left + bottom-right lit). Owner 2026-11-12: from afar the two
     *   lit corners read as a skewed, squashed logo, hence no longer the default;
     * - "none".
     */
    private fun glass(c: Canvas, clip: Path, px: Int) {
        if (rim == "none") return
        clip.fillType = Path.FillType.WINDING
        val w = (px / 72f).coerceAtLeast(1.2f)
        val p = px.toFloat()
        c.save(); c.clipPath(clip)
        c.drawPath(clip, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = w * 2
            when (rim) {
                "line" -> color = 0x59FFFFFF
                "glass" -> shader = android.graphics.LinearGradient(0f, 0f, p, p,
                    intArrayOf(0xB3FFFFFF.toInt(), 0x00FFFFFF, 0x00FFFFFF, 0x80FFFFFF.toInt()), floatArrayOf(0f, 0.32f, 0.68f, 1f),
                    android.graphics.Shader.TileMode.CLAMP)
                else -> shader = android.graphics.LinearGradient(0f, 0f, 0f, p,
                    intArrayOf(0xA6FFFFFF.toInt(), 0x40FFFFFF, 0x1AFFFFFF), floatArrayOf(0f, 0.5f, 1f), android.graphics.Shader.TileMode.CLAMP)
            }
        })
        c.restore()
    }

    /**
     * "sheen": soft light from above over the whole face (white 20 % at the top, clear by the middle, a light shade at the
     * bottom), the depth iOS icons have, on any app's flat background. "app" leaves the icon as the app draws it.
     */
    private fun sheen(c: Canvas, clip: Path, px: Int) {
        if (fill != "sheen") return
        clip.fillType = Path.FillType.WINDING
        val p = px.toFloat()
        c.save(); c.clipPath(clip)
        c.drawRect(0f, 0f, p, p, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = android.graphics.LinearGradient(0f, 0f, 0f, p, intArrayOf(0x33FFFFFF, 0x00FFFFFF, 0x00000000, 0x1F000000),
                floatArrayOf(0f, 0.5f, 0.6f, 1f), android.graphics.Shader.TileMode.CLAMP)
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
    private fun legacy(d: Drawable, px: Int, shape: String, c: Canvas) {
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
            if (!inShape(u, v, shape)) continue
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
