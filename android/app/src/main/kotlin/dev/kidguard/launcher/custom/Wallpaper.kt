package dev.kidguard

import android.graphics.Bitmap
import android.graphics.BitmapShader
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

    private class Blob(val x: Float, val y: Float, val r: Float, val color: String, val a: Float)

    /** 96 x 96 monochrome noise (white and black specks, mostly transparent), tiled over the mesh wallpapers. */
    private fun grainTile(): Bitmap {
        val n = 96; val rnd = java.util.Random(5)
        val px = IntArray(n * n) { val v = rnd.nextInt(256); Color.argb(if (v > 127) v - 127 else 128 - v, if (v > 127) 255 else 0, if (v > 127) 255 else 0, if (v > 127) 255 else 0) }
        return Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888)
    }

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
        // Mesh gradient (iOS 17 / Pixel style): a flat base plus soft radial blobs (x, y, radius, colour, alpha) that fade to
        // transparent, then a faint tiled grain so big smooth areas do not band. Pure shaders, no blur filter: cheap.
        fun mesh(base: String, vararg blobs: Blob) {
            p.color = col(base); c.drawRect(0f, 0f, W, H, p)
            for (b in blobs) {
                val k = col(b.color)
                p.shader = RadialGradient(b.x, b.y, b.r, intArrayOf(k, (k and 0xFFFFFF) or (0x8C shl 24), k and 0xFFFFFF), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
                p.alpha = (b.a * 255).toInt(); c.drawRect(0f, 0f, W, H, p)
            }
            p.shader = null; p.alpha = 255
        }
        fun grain(strength: Int) {
            val g = grainTile()
            p.shader = BitmapShader(g, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            p.alpha = strength
            c.save(); c.scale(1f / s, 1f / s)   // grain in screen pixels, not design units
            c.drawRect(-w.toFloat(), -h.toFloat(), 2f * w, 2f * h, p)
            c.restore(); p.shader = null; p.alpha = 255
        }
        when (id) {
            "sky" -> {   // light, bé: sky blue, lilac and mint clouds with a soft sun glow
                mesh("#e0f2fe", Blob(330f, 90f, 360f, "#7dd3fc", 0.95f), Blob(60f, 300f, 300f, "#ffffff", 0.9f), Blob(380f, 560f, 340f, "#c7d2fe", 0.9f),
                    Blob(70f, 820f, 380f, "#a7f3d0", 0.9f), Blob(360f, 130f, 140f, "#fde68a", 0.8f))
                grain(14)
            }
            "night" -> {   // dark, bé: indigo / blue / purple glows, a few stars and a moon halo
                mesh("#0a0f26", Blob(70f, 140f, 360f, "#4338ca", 0.6f), Blob(390f, 520f, 350f, "#1d4ed8", 0.45f), Blob(110f, 820f, 380f, "#7e22ce", 0.42f),
                    Blob(335f, 125f, 110f, "#fef9c3", 0.35f))
                stars(45, 7)
                p.color = col("#fef9c3"); p.alpha = 235; c.drawCircle(335f, 125f, 20f, p); p.alpha = 255
                grain(12)
            }
            "sunset" -> {   // light default (teen): peach, pink, lavender, rose and apricot blobs on warm cream
                mesh("#fff1e6", Blob(70f, 110f, 390f, "#fdba74", 0.8f), Blob(370f, 250f, 350f, "#f9a8d4", 0.85f), Blob(50f, 520f, 370f, "#c4b5fd", 0.85f),
                    Blob(350f, 770f, 390f, "#fda4af", 0.75f), Blob(110f, 910f, 340f, "#fde68a", 0.8f))
                grain(14)
            }
            "aurora" -> {   // dark default (teen): emerald, violet, cyan and a pink ember glowing out of near-black navy
                mesh("#050816", Blob(50f, 190f, 320f, "#10b981", 0.55f), Blob(375f, 330f, 340f, "#7c3aed", 0.62f), Blob(130f, 690f, 360f, "#0ea5e9", 0.45f),
                    Blob(390f, 890f, 270f, "#db2777", 0.38f))
                stars(26, 3)
                grain(12)
            }
            "bloom" -> {   // light: lilac and peach with a mint corner, pastel but a touch deeper than sunset
                mesh("#f5f3ff", Blob(340f, 100f, 360f, "#ddd6fe", 0.95f), Blob(40f, 330f, 330f, "#fbcfe8", 0.9f), Blob(380f, 600f, 330f, "#fed7aa", 0.85f),
                    Blob(60f, 840f, 400f, "#99f6e4", 0.8f))
                grain(14)
            }
            "lagoon" -> {   // light: teal / sky / sea-green, calm and cool
                mesh("#ecfeff", Blob(60f, 120f, 370f, "#67e8f9", 0.7f), Blob(380f, 380f, 340f, "#a5b4fc", 0.7f), Blob(40f, 650f, 340f, "#86efac", 0.65f),
                    Blob(360f, 880f, 380f, "#7dd3fc", 0.75f))
                grain(14)
            }
            "nebula" -> {   // dark: teal, magenta and royal blue clouds, deeper than aurora
                mesh("#070612", Blob(380f, 150f, 340f, "#0d9488", 0.5f), Blob(30f, 420f, 360f, "#c026d3", 0.42f), Blob(360f, 700f, 340f, "#2563eb", 0.5f),
                    Blob(80f, 930f, 330f, "#7c3aed", 0.45f))
                stars(34, 17)
                grain(12)
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
    /** Bumped when Tuỳ chỉnh may have replaced the photo file (same style, new picture): the home screen then re-checks the file once. */
    @Volatile var rev = 0

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

    /** Darkens [bmp] by [pct] % (Tuỳ chỉnh "Làm tối"), in place, so the ink sampling sees the same pixels as the kid. */
    fun dim(bmp: Bitmap, pct: Int) { if (pct > 0) Canvas(bmp).drawColor(Color.argb((pct.coerceIn(0, 60) * 255 / 100), 0, 0, 0)) }

    /** Dark wallpapers want light status bar icons. */
    fun isDark(id: String) = id in setOf("night", "aurora", "graphite", "space", "dusk", "berry", "ember", "nebula")

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
