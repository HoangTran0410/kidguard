package dev.kidguard

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader

/**
 * The built-in wallpapers (no image files: drawn in a 412 x 915 design space, scaled to the screen, cropped to fill).
 * [sample] gives the downsampled pixels of a screen region for HomeStyle.ink (adaptive text colour).
 */
object Wallpaper {
    private const val W = 412f
    private const val H = 915f

    fun draw(id: String, w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val s = maxOf(w / W, h / H)
        c.translate((w - W * s) / 2, (h - H * s) / 2); c.scale(s, s)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        fun col(x: String) = Color.parseColor(x)
        fun lin(vararg stops: Pair<Float, String>) { p.shader = LinearGradient(0f, 0f, 0f, H, stops.map { col(it.second) }.toIntArray(), stops.map { it.first }.toFloatArray(), Shader.TileMode.CLAMP); c.drawRect(0f, 0f, W, H, p); p.shader = null }
        fun oval(cx: Float, cy: Float, rx: Float, ry: Float, color: String, a: Float = 1f) { p.color = col(color); p.alpha = (a * 255).toInt(); c.drawOval(RectF(cx - rx, cy - ry, cx + rx, cy + ry), p); p.alpha = 255 }
        fun stars(n: Int, seed: Int) {
            val rnd = java.util.Random(seed.toLong())
            p.color = Color.WHITE
            repeat(n) { p.alpha = (90 + rnd.nextInt(150)); c.drawCircle(rnd.nextFloat() * W, rnd.nextFloat() * H * 0.7f, 0.6f + rnd.nextFloat() * 1.3f, p) }
            p.alpha = 255
        }
        fun wave(y0: Float, c1x: Float, c1y: Float, c2x: Float, c2y: Float, y1: Float, color: String, a: Float) {
            val path = Path().apply { moveTo(0f, y0); cubicTo(c1x, c1y, c2x, c2y, W, y1); lineTo(W, H); lineTo(0f, H); close() }
            p.color = col(color); p.alpha = (a * 255).toInt(); c.drawPath(path, p); p.alpha = 255
        }
        when (id) {
            "sky" -> {
                lin(0f to "#7dd3fc", 0.75f to "#e0f2fe")
                p.color = col("#fde047"); c.drawCircle(340f, 120f, 38f, p)
                for ((x, y, rx, ry) in listOf(listOf(90f, 215f, 62f, 22f), listOf(128f, 198f, 40f, 26f), listOf(320f, 430f, 72f, 24f), listOf(292f, 414f, 38f, 24f), listOf(80f, 610f, 56f, 18f)))
                    oval(x, y, rx, ry, "#ffffff", 0.95f)
                oval(90f, 960f, 330f, 210f, "#86efac"); oval(390f, 990f, 300f, 230f, "#4ade80")
            }
            "night" -> {
                lin(0f to "#0b1026", 1f to "#312e81")
                stars(70, 7)
                p.color = col("#fef9c3"); c.drawCircle(330f, 130f, 34f, p)
                p.color = col("#0e1430"); c.drawCircle(345f, 120f, 30f, p)
                oval(80f, 980f, 320f, 200f, "#1e1b4b"); oval(380f, 1000f, 300f, 220f, "#172554")
            }
            "sunset" -> {
                lin(0f to "#fbcfe8", 0.5f to "#fed7aa", 1f to "#fef3c7")
                wave(520f, 120f, 470f, 260f, 590f, 520f, "#fda4af", 0.55f)
                wave(640f, 140f, 590f, 250f, 700f, 630f, "#f9a8d4", 0.55f)
                wave(760f, 120f, 720f, 280f, 800f, 750f, "#c4b5fd", 0.6f)
            }
            "aurora" -> {
                p.color = col("#020617"); c.drawRect(0f, 0f, W, H, p)
                p.maskFilter = BlurMaskFilter(45f, BlurMaskFilter.Blur.NORMAL)
                oval(90f, 230f, 160f, 90f, "#10b981", 0.6f); oval(320f, 360f, 170f, 100f, "#8b5cf6", 0.65f); oval(160f, 660f, 190f, 110f, "#0ea5e9", 0.45f)
                p.maskFilter = null
                stars(30, 3)
            }
            "ocean" -> {
                lin(0f to "#bae6fd", 0.45f to "#e0f2fe", 0.46f to "#38bdf8", 1f to "#0369a1")
                p.color = col("#fef08a"); c.drawCircle(80f, 140f, 30f, p)
                oval(300f, 190f, 60f, 18f, "#ffffff", 0.9f); oval(330f, 176f, 34f, 20f, "#ffffff", 0.9f)
                wave(520f, 120f, 490f, 280f, 560f, 515f, "#7dd3fc", 0.6f)
                wave(650f, 140f, 610f, 270f, 700f, 640f, "#0ea5e9", 0.55f)
                wave(790f, 110f, 750f, 300f, 830f, 780f, "#075985", 0.6f)
            }
            "forest" -> {
                lin(0f to "#d9f99d", 0.7f to "#f7fee7")
                p.color = col("#fde68a"); c.drawCircle(320f, 150f, 44f, p)
                oval(60f, 1000f, 330f, 330f, "#86efac"); oval(380f, 1010f, 300f, 340f, "#4ade80")
                val rnd = java.util.Random(11)
                for (k in 0 until 9) {
                    val x = 20f + k * 46f + rnd.nextFloat() * 14f; val base = 760f + rnd.nextFloat() * 70f; val th = 120f + rnd.nextFloat() * 70f
                    val tri = Path().apply { moveTo(x, base - th); lineTo(x - 28f, base); lineTo(x + 28f, base); close() }
                    p.color = col(if (k % 2 == 0) "#15803d" else "#166534"); c.drawPath(tri, p)
                }
            }
            "candy" -> {
                p.color = col("#fdf2f8"); c.drawRect(0f, 0f, W, H, p)
                p.maskFilter = BlurMaskFilter(60f, BlurMaskFilter.Blur.NORMAL)
                oval(80f, 180f, 170f, 150f, "#f9a8d4", 0.75f); oval(340f, 420f, 170f, 150f, "#a5b4fc", 0.7f)
                oval(110f, 700f, 190f, 150f, "#fde68a", 0.75f); oval(330f, 860f, 150f, 120f, "#99f6e4", 0.7f)
                p.maskFilter = null
            }
            "mint" -> {
                lin(0f to "#ccfbf1", 1f to "#ecfeff")
                for ((x, y, r, cl) in listOf(listOf(330f, 160f, 90f, "#99f6e4"), listOf(60f, 420f, 70f, "#a7f3d0"), listOf(360f, 640f, 120f, "#bae6fd"), listOf(90f, 850f, 100f, "#99f6e4")))
                    oval(x as Float, y as Float, r as Float, r, cl as String, 0.7f)
            }
            "space" -> {
                lin(0f to "#030712", 1f to "#1e1b4b")
                stars(90, 21)
                p.color = col("#f97316"); c.drawCircle(290f, 300f, 70f, p)
                p.color = col("#fdba74"); p.alpha = 120; c.drawCircle(270f, 280f, 50f, p); p.alpha = 255
                p.style = Paint.Style.STROKE; p.strokeWidth = 7f; p.color = col("#fcd34d")
                c.save(); c.rotate(-18f, 290f, 300f); c.drawOval(RectF(185f, 280f, 395f, 320f), p); c.restore()
                p.style = Paint.Style.FILL
                p.color = col("#93c5fd"); c.drawCircle(90f, 620f, 18f, p)
            }
            "dusk" -> {
                lin(0f to "#1e1b4b", 0.55f to "#7c3aed", 0.8f to "#f472b6", 1f to "#fdba74")
                stars(40, 5)
                wave(690f, 110f, 600f, 250f, 720f, 660f, "#4c1d95", 0.9f)
                wave(780f, 140f, 720f, 300f, 820f, 760f, "#2e1065", 1f)
            }
            "berry" -> {
                p.color = col("#0f0a1e"); c.drawRect(0f, 0f, W, H, p)
                p.maskFilter = BlurMaskFilter(55f, BlurMaskFilter.Blur.NORMAL)
                oval(330f, 200f, 160f, 120f, "#db2777", 0.55f); oval(80f, 480f, 170f, 130f, "#7c3aed", 0.55f); oval(300f, 800f, 190f, 130f, "#2563eb", 0.5f)
                p.maskFilter = null
            }
            "ember" -> {
                p.shader = RadialGradient(W * 0.2f, H * 0.95f, H, col("#7c2d12"), col("#0c0a09"), Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, W, H, p); p.shader = null
                val rnd = java.util.Random(9)
                repeat(40) { p.color = col("#fb923c"); p.alpha = 60 + rnd.nextInt(140); c.drawCircle(rnd.nextFloat() * W, H * 0.35f + rnd.nextFloat() * H * 0.65f, 1f + rnd.nextFloat() * 2.2f, p) }
                p.alpha = 255
            }
            else -> {   // graphite
                p.shader = RadialGradient(W * 0.8f, H * 0.15f, H * 0.9f, col("#334155"), col("#0b0f17"), Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, W, H, p); p.shader = null
                p.color = Color.WHITE; p.alpha = 9; p.strokeWidth = 6f
                c.save(); c.rotate(35f, W / 2, H / 2)
                var x = -H; while (x < W + H) { c.drawLine(x, -H, x, 2 * H, p); x += 14f }
                c.restore(); p.alpha = 255
            }
        }
        return bmp
    }

    /** The kid's own photo (filesDir/wall.jpg, already screen-sized by Tuỳ chỉnh), cropped to fill [w] x [h]; null if missing. */
    fun photo(ctx: android.content.Context, w: Int, h: Int): Bitmap? = runCatching {
        val f = java.io.File(ctx.filesDir, PHOTO)
        if (!f.exists()) return null
        val src = android.graphics.BitmapFactory.decodeFile(f.path) ?: return null
        val out = Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val s = maxOf(w.toFloat() / src.width, h.toFloat() / src.height)
        val dw = src.width * s; val dh = src.height * s
        Canvas(out).drawBitmap(src, null, RectF((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2), Paint(Paint.FILTER_BITMAP_FLAG))
        src.recycle()
        out
    }.getOrNull()

    const val PHOTO = "wall.jpg"

    /** Darkens [bmp] by [pct] % (Tuỳ chỉnh "Làm tối"), in place, so the ink sampling sees the same pixels as the kid. */
    /**
     * Frosted wallpaper (POCO's "làm mờ hình nền"): [pct] 0-100. Done once per wallpaper change, never per frame: shrink to
     * 1/4..1/14, three box-blur passes on the small copy (≈ a Gaussian), scale back up with filtering. Returns [bmp] at 0.
     */
    fun blur(bmp: Bitmap, pct: Int): Bitmap {
        if (pct <= 0) return bmp
        val f = 4 + pct / 10
        val sw = (bmp.width / f).coerceAtLeast(8); val sh = (bmp.height / f).coerceAtLeast(8)
        val small = Bitmap.createScaledBitmap(bmp, sw, sh, true)
        val a = IntArray(sw * sh); small.getPixels(a, 0, sw, 0, 0, sw, sh)
        val t = IntArray(a.size); val r = 2
        repeat(3) { box(a, t, sw, sh, r, true); box(t, a, sw, sh, r, false) }
        small.setPixels(a, 0, sw, 0, 0, sw, sh)
        val out = Bitmap.createScaledBitmap(small, bmp.width, bmp.height, true)
        small.recycle(); if (out !== bmp) bmp.recycle()
        return if (out.isMutable) out else out.copy(Bitmap.Config.ARGB_8888, true).also { out.recycle() }
    }

    /** One box-blur pass of radius [r], rows ([horiz]) or columns, edges clamped; opaque wallpapers, so alpha is kept at 255. */
    private fun box(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horiz: Boolean) {
        val n = if (horiz) w else h; val lines = if (horiz) h else w; val d = 2 * r + 1
        for (l in 0 until lines) {
            fun at(i: Int) = src[if (horiz) l * w + i.coerceIn(0, n - 1) else i.coerceIn(0, n - 1) * w + l]
            var rs = 0; var gs = 0; var bs = 0
            for (i in -r..r) { val p = at(i); rs += p shr 16 and 255; gs += p shr 8 and 255; bs += p and 255 }
            for (i in 0 until n) {
                dst[if (horiz) l * w + i else i * w + l] = (0xFF shl 24) or (rs / d shl 16) or (gs / d shl 8) or (bs / d)
                val o = at(i - r); val p = at(i + r + 1)
                rs += (p shr 16 and 255) - (o shr 16 and 255); gs += (p shr 8 and 255) - (o shr 8 and 255); bs += (p and 255) - (o and 255)
            }
        }
    }

    fun dim(bmp: Bitmap, pct: Int) { if (pct > 0) Canvas(bmp).drawColor(Color.argb((pct.coerceIn(0, 60) * 255 / 100), 0, 0, 0)) }

    /** Dark wallpapers want light status bar icons. */
    fun isDark(id: String) = id in setOf("night", "aurora", "graphite", "space", "dusk", "berry", "ember")

    /** Pixels of [r] (screen coords, clipped) scaled down to at most [n] x [n], for HomeStyle.ink. */
    fun sample(bmp: Bitmap, r: Rect, n: Int = 24): IntArray {
        val x = r.left.coerceIn(0, bmp.width - 1); val y = r.top.coerceIn(0, bmp.height - 1)
        val w = (r.right.coerceAtMost(bmp.width) - x).coerceAtLeast(1); val h = (r.bottom.coerceAtMost(bmp.height) - y).coerceAtLeast(1)
        val sub = Bitmap.createBitmap(bmp, x, y, w, h)
        val sw = minOf(n, w); val sh = minOf(n, h)
        val small = Bitmap.createScaledBitmap(sub, sw, sh, true)
        val out = IntArray(sw * sh); small.getPixels(out, 0, sw, 0, 0, sw, sh)
        if (small !== sub) small.recycle()
        if (sub !== bmp) sub.recycle()
        return out
    }
}
