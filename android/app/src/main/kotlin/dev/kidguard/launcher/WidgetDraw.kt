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
internal class MonthGrid(ctx: Context, private val text: Int, private val sub: Int, private val mark: Int, private val onMark: Int, private val scale: Float) : View(ctx) {
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
        val title = SimpleDateFormat("LLLL yyyy", loc).format(cal.time).replaceFirstChar { it.titlecase(loc) }
        val padX = width * 0.04f
        val colW = (width - 2 * padX) / 7f
        val lineH = height / (weeks + 2.3f)
        val ts = minOf(lineH * 0.5f, colW * 0.42f) * scale.coerceAtMost(1.15f)
        // title
        p.typeface = bold; p.textSize = ts * 1.12f; p.color = text; p.textAlign = Paint.Align.LEFT
        c.drawText(title, padX + colW * 0.15f, lineH * 0.78f, p)
        p.textAlign = Paint.Align.CENTER
        // weekday initials
        p.typeface = Typeface.DEFAULT; p.textSize = ts * 0.85f; p.color = sub
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
            } else { p.color = text; p.typeface = Typeface.DEFAULT }
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
