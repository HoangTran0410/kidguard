package dev.kidguard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

// Home widgets drawn on one canvas each: a month grid as 40+ TextViews costs ~10 ms to build and lay out, this is one view.

/** Clock face with hands; [minute] = minutes since midnight (set by the minute tick, redraws only). */
internal class AnalogFace(ctx: Context, private val face: Int, private val tick: Int, private val hour: Int, private val hand: Int) : View(ctx) {
    var minute = 0
        set(v) { if (field != v) { field = v; invalidate() } }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }

    override fun onDraw(c: Canvas) {
        val r = minOf(width, height) / 2f - resources.displayMetrics.density * 2
        if (r <= 0) return
        val cx = width / 2f; val cy = height / 2f
        p.style = Paint.Style.FILL; p.color = face
        c.drawCircle(cx, cy, r, p)
        p.color = tick
        for (i in 0 until 12) {
            val a = Math.toRadians(i * 30.0)
            val big = i % 3 == 0
            p.strokeWidth = r * (if (big) 0.05f else 0.03f)
            val r0 = r * (if (big) 0.78f else 0.83f); val r1 = r * 0.9f
            c.drawLine(cx + (r0 * Math.sin(a)).toFloat(), cy - (r0 * Math.cos(a)).toFloat(), cx + (r1 * Math.sin(a)).toFloat(), cy - (r1 * Math.cos(a)).toFloat(), p)
        }
        fun arm(deg: Double, len: Float, w: Float, col: Int) {
            val a = Math.toRadians(deg); p.color = col; p.strokeWidth = w
            c.drawLine(cx, cy, cx + (len * Math.sin(a)).toFloat(), cy - (len * Math.cos(a)).toFloat(), p)
        }
        val h = (minute / 60) % 12; val m = minute % 60
        arm((h + m / 60.0) * 30, r * 0.5f, r * 0.09f, hour)
        arm(m * 6.0, r * 0.72f, r * 0.06f, hand)
        p.color = hand; c.drawCircle(cx, cy, r * 0.07f, p)
    }
}

/** This month: title, weekday initials, the days; today in a filled circle. [day] = any time today. */
internal class MonthGrid(ctx: Context, private val text: Int, private val sub: Int, private val mark: Int, private val onMark: Int, private val scale: Float, private val head: Int = mark) : View(ctx) {
    var day = System.currentTimeMillis()
        set(v) { if (Calendar.getInstance().apply { timeInMillis = v }.get(Calendar.DAY_OF_YEAR) != Calendar.getInstance().apply { timeInMillis = field }.get(Calendar.DAY_OF_YEAR)) { field = v; invalidate() } else field = v }
    private val loc: Locale = ctx.resources.configuration.locales[0]
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val bold = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    /** Day labels from the locale's first day of the week (vi: T2 … CN). */
    private fun heads(first: Int): List<String> = (0 until 7).map { i ->
        val d = (first - 1 + i) % 7 + 1
        if (loc.language == "vi") (if (d == Calendar.SUNDAY) "CN" else "T$d")
        else java.text.DateFormatSymbols(loc).shortWeekdays[d].take(2)
    }

    override fun onDraw(c: Canvas) {
        val cal = Calendar.getInstance(loc).apply { timeInMillis = day }
        val today = cal.get(Calendar.DAY_OF_MONTH)
        val first = cal.firstDayOfWeek
        val days = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        cal.set(Calendar.DAY_OF_MONTH, 1)
        val lead = (cal.get(Calendar.DAY_OF_WEEK) - first + 7) % 7
        val weeks = (lead + days + 6) / 7
        // iOS calendar widget: "THÁNG 11" in the accent colour, no year (it is this month), bold digits
        val title = SimpleDateFormat("LLLL", loc).format(cal.time).uppercase(loc)
        val padX = width * 0.04f
        val colW = (width - 2 * padX) / 7f
        val lineH = height / (weeks + 2.3f)
        val sd = resources.displayMetrics.scaledDensity * scale.coerceAtMost(1.15f)
        // type scale (WType): never above the shared sizes, smaller when the cell is
        // the WType sizes, only smaller when a line is too short for them (digits ~0.75 of a line, like iOS / HyperOS)
        val ts = WType.cap(minOf(lineH * 0.75f, colW * 0.45f), WType.BODY, sd)
        val tsTitle = WType.cap(lineH * 0.8f, WType.TITLE, sd); val tsHead = WType.cap(lineH * 0.62f, WType.CAPTION, sd)
        // title
        p.typeface = bold; p.textSize = tsTitle; p.color = head; p.textAlign = Paint.Align.LEFT
        p.letterSpacing = 0.08f
        c.drawText(title, padX + colW * 0.15f, lineH * 0.78f, p)
        p.letterSpacing = 0f
        p.textAlign = Paint.Align.CENTER
        // weekday initials
        p.typeface = bold; p.textSize = tsHead; p.color = sub
        for ((i, s) in heads(first).withIndex()) c.drawText(s, padX + colW * (i + 0.5f), lineH * 1.9f, p)
        // days
        p.textSize = ts
        val fm = p.fontMetrics; val mid = -(fm.ascent + fm.descent) / 2
        for (d in 1..days) {
            val i = lead + d - 1
            val x = padX + colW * (i % 7 + 0.5f); val y = lineH * (2.3f + i / 7 + 0.5f)
            if (d == today) {
                p.color = mark; c.drawCircle(x, y, minOf(lineH, colW) * 0.46f, p)
                p.color = onMark; p.typeface = bold
            } else { p.color = text; p.typeface = bold }
            c.drawText(d.toString(), x, y + mid, p)
        }
    }
}

/** A battery outline filled to [level] (0-100); [low] colour under 20 %. */
internal class BatteryGlyph(ctx: Context, private val line: Int, private val ok: Int, private val low: Int) : View(ctx) {
    var level = 0
        set(v) { if (field != v) { field = v; invalidate() } }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    override fun onDraw(c: Canvas) {
        val d = resources.displayMetrics.density
        val cap = width * 0.08f; val sw = 2 * d
        p.style = Paint.Style.STROKE; p.strokeWidth = sw; p.color = line
        r.set(sw / 2, sw / 2, width - cap - sw / 2, height - sw / 2)
        c.drawRoundRect(r, 4 * d, 4 * d, p)
        p.style = Paint.Style.FILL
        c.drawRoundRect(width - cap, height * 0.3f, width.toFloat(), height * 0.7f, 2 * d, 2 * d, p)
        val inset = sw + 2 * d
        val full = (width - cap - sw) - inset
        p.color = if (level < 20) low else ok
        r.set(inset, inset, inset + full * level.coerceIn(3, 100) / 100f, height - inset)
        c.drawRoundRect(r, 2 * d, 2 * d, p)
    }
}

/**
 * The timetable's week: a column per school day, a row per period (morning, then a thin "Chiều" band, then afternoon),
 * drawn on one canvas. Today's column has a tinted plate; the period now is filled. [onCell] (editor only) gets taps.
 */
internal class WeekGrid(ctx: Context, private val text: Int, private val sub: Int, private val tint: Int, private val solid: Int,
                        private val onSolid: Int, private val line: Int) : View(ctx) {
    private var days: List<Int> = emptyList(); private var heads: List<String> = emptyList()
    private var am = 0; private var pm = 0; private var pmLabel = ""
    private var cell: (Int, Boolean, Int) -> String = { _, _, _ -> "" }
    var today = 0
        set(v) { if (field != v) { field = v; invalidate() } }
    var now: Pair<Boolean, Int>? = null
        set(v) { if (field != v) { field = v; invalidate() } }
    var onCell: ((Int, Boolean, Int) -> Unit)? = null
    private val p = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val r = RectF()
    private val d = ctx.resources.displayMetrics.density

    fun set(days: List<Int>, heads: List<String>, am: Int, pm: Int, cell: (Int, Boolean, Int) -> String, pmLabel: String) {
        this.days = days; this.heads = heads; this.am = am; this.pm = pm; this.cell = cell; this.pmLabel = pmLabel; invalidate()
    }

    // geometry: left gutter (period numbers), header row, morning rows, band, afternoon rows
    private fun gutter() = width * 0.07f
    private fun colW() = (width - gutter()) / days.size.coerceAtLeast(1)
    private fun band() = if (am > 0 && pm > 0) rowH() * 0.55f else 0f
    private fun rowH(): Float { val n = am + pm; return if (n == 0) 0f else minOf(height / (n + 0.9f + if (am > 0 && pm > 0) 0.55f else 0f), 46 * d) }
    /** Space left over when the rows hit their cap: split above and below, so the grid sits in the middle of its card. */
    private fun off() = ((height - (headH() + (am + pm) * rowH() + band())) / 2).coerceAtLeast(0f)
    private fun headH() = rowH() * 0.9f
    private fun rowTop(isPm: Boolean, i: Int) = off() + headH() + (if (isPm) am * rowH() + band() else 0f) + i * rowH()

    override fun onDraw(c: Canvas) {
        if (days.isEmpty() || am + pm == 0) return
        val g = gutter(); val cw = colW(); val rh = rowH(); val hh = headH()
        val ts = minOf(rh * 0.40f, cw * 0.21f, 15 * d)
        // today's plate
        days.indexOf(today).takeIf { it >= 0 }?.let { i ->
            p.color = tint; r.set(g + cw * i + d, 0f, g + cw * (i + 1) - d, height.toFloat()); c.drawRoundRect(r, 10 * d, 10 * d, p)
        }
        // heads
        p.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); p.textSize = ts * 0.95f
        for ((i, h) in heads.withIndex()) { p.color = if (days[i] == today) text else sub; c.drawText(h, g + cw * (i + 0.5f), off() + hh * 0.68f, p) }
        // the "Chiều" band
        if (am > 0 && pm > 0) {
            val y = off() + hh + am * rh + band() / 2
            p.textSize = ts * 0.7f; p.color = sub; p.typeface = Typeface.DEFAULT; p.textAlign = Paint.Align.LEFT
            c.drawText(pmLabel, g, y + ts * 0.25f, p)
            p.textAlign = Paint.Align.CENTER
            p.color = line; c.drawRect(g + p.measureText(pmLabel) + 8 * d, y - d * 0.5f, width.toFloat(), y + d * 0.5f, p)
        }
        // cells
        p.typeface = Typeface.DEFAULT
        val fm = Paint.FontMetrics()
        for (isPm in listOf(false, true)) for (i in 0 until if (isPm) pm else am) {
            val top = rowTop(isPm, i)
            p.textSize = ts * 0.75f; p.color = sub; p.getFontMetrics(fm)
            c.drawText("${i + 1}", g / 2, top + rh / 2 - (fm.ascent + fm.descent) / 2, p)
            if (i > 0) { p.color = line; p.alpha = 90; c.drawRect(g + 4 * d, top, width - 4 * d, top + d * 0.6f, p); p.alpha = 255 }
            for ((k, day) in days.withIndex()) {
                val s = cell(day, isPm, i); val x0 = g + cw * k
                val lit = day == today && now == (isPm to i)
                if (lit) { p.color = solid; r.set(x0 + 2 * d, top + 2 * d, x0 + cw - 2 * d, top + rh - 2 * d); c.drawRoundRect(r, 7 * d, 7 * d, p) }
                if (s.isEmpty()) continue
                p.textSize = ts; p.color = if (lit) onSolid else text
                val avail = cw - 6 * d
                if (p.measureText(s) > avail) p.textSize = (ts * avail / p.measureText(s)).coerceAtLeast(ts * 0.78f)
                val t = android.text.TextUtils.ellipsize(s, p, avail, android.text.TextUtils.TruncateAt.END).toString()
                p.getFontMetrics(fm)
                c.drawText(t, x0 + cw / 2, top + rh / 2 - (fm.ascent + fm.descent) / 2, p)
            }
        }
    }

    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        val go = onCell ?: return super.onTouchEvent(e)
        if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) return true
        if (e.actionMasked != android.view.MotionEvent.ACTION_UP || days.isEmpty()) return true
        val k = ((e.x - gutter()) / colW()).toInt(); if (e.x < gutter() || k !in days.indices) return true
        val rh = rowH(); if (rh <= 0f) return true
        val y = e.y - off() - headH()
        val (isPm, i) = if (y < am * rh) false to (y / rh).toInt() else true to ((y - am * rh - band()) / rh).toInt()
        if (y >= 0 && i >= 0 && i < (if (isPm) pm else am)) { playSoundEffect(android.view.SoundEffectConstants.CLICK); go(days[k], isPm, i) }
        return true
    }
}
