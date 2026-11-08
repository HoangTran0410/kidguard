package dev.kidguard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import kotlin.math.abs

/**
 * Page dots in a glass pill (iOS / LauncherOS): at most [MAX] dots; with more pages the row slides and its edge dots
 * shrink. Driven by the pager's scroll position every frame ([pos], 0 = "Hôm nay"), so the lit dot glides with the
 * finger instead of jumping after the page settles. Draw-only (no child views, no layout pass while scrolling).
 */
class PageDots(ctx: Context) : View(ctx) {
    companion object { const val MAX = 5 }

    var pages = 1; private set
    var pos = 1f
        set(v) { if (field != v) { field = v; invalidate() } }
    private var ink = Color.WHITE
    private var glyph: Drawable? = null

    private val d = resources.displayMetrics.density
    private val dot = 7 * d
    private val step = 15 * d
    private val glyphSz = (10 * d).toInt()
    private val padH = 9 * d
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    fun set(n: Int, color: Int, calendar: Drawable?) {
        val changed = n != pages
        pages = n.coerceAtLeast(1); ink = color; glyph = calendar
        // light ink (dark wallpaper) on a dark pane, dark ink on a light one; no rim (it read as a border)
        val light = Color.luminance(color) > 0.5f
        bg.color = if (light) 0x38000000 else 0x55FFFFFF
        if (changed) requestLayout()
        invalidate()
    }

    private fun win() = minOf(pages, MAX)

    override fun onMeasure(w: Int, h: Int) {
        val width = padH * 2 + glyphSz + step * 0.6f + win() * step - (step - dot)
        setMeasuredDimension(resolveSize(width.toInt() + 1, w), resolveSize((18 * d).toInt(), h))
    }

    override fun onDraw(c: Canvas) {
        val h = height.toFloat(); val cy = h / 2
        r.set(0.5f, 0.5f, width - 0.5f, h - 0.5f)
        c.drawRoundRect(r, h / 2, h / 2, bg)
        val p = pos.coerceIn(0f, pages.toFloat())
        // the -1 page: a small calendar glyph, lit while that page shows
        glyph?.let { g ->
            val x = padH.toInt(); val y = ((h - glyphSz) / 2).toInt()
            g.setBounds(x, y, x + glyphSz, y + glyphSz)
            g.alpha = (255 * (0.45f + 0.55f * (1 - p.coerceAtMost(1f)))).toInt(); g.draw(c)
        }
        val n = pages; val w = win()
        val x0 = padH + glyphSz + step * 0.6f + dot / 2
        // first page of the visible window, sliding with the scroll; centred on the current page where it can be
        val s = (p.coerceIn(1f, n.toFloat()) - (w - 1) / 2f).coerceIn(1f, (n - w + 1).toFloat())
        val moreL = (s - 1).coerceIn(0f, 1f); val moreR = (n - w + 1 - s).coerceIn(0f, 1f)
        c.save(); c.clipRect(x0 - dot, 0f, x0 + (w - 1) * step + dot, h)
        val i0 = maxOf(1, s.toInt() - 1); val i1 = minOf(n, s.toInt() + w + 1)
        for (i in i0..i1) {
            val u = i - s
            if (u <= -1f || u >= w.toFloat()) continue
            // dots at the window's edge shrink while more pages hide behind them
            var k = 1f
            if (u < 1f) k -= 0.45f * moreL * (1 - u).coerceIn(0f, 1f)
            if (u > w - 2f) k -= 0.45f * moreR * (u - (w - 2)).coerceIn(0f, 1f)
            val vis = when { u < 0 -> 1 + u; u > w - 1 -> w - u; else -> 1f }.coerceIn(0f, 1f)
            val lit = 1 - abs(i - p).coerceAtMost(1f)
            fill.color = ink; fill.alpha = (255 * vis * (0.42f + 0.58f * lit)).toInt()
            c.drawCircle(x0 + u * step, cy, dot / 2 * k, fill)
        }
        c.restore()
    }
}
