package dev.kidguard

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * View helpers shared by the kid launcher's screens (HomeActivity, its "Hôm nay" page, the widget picker, Tuỳ chỉnh).
 * Every colour comes from [pal] (HomeStyle.palette, contrast-tested in HomeStyleTest); [font] is the kid's text size.
 */
class HomeKit(val act: Activity, val pal: Palette, val font: Float = 1f) {
    private val d = act.resources.displayMetrics.density
    fun dp(v: Int) = (v * d).toInt()
    fun dpf(v: Float) = v * d

    fun fill(color: Int, radius: Float = 999f) = GradientDrawable().apply { setColor(color); cornerRadius = dpf(radius) }
    fun card(radius: Float = 22f) = GlassCard(pal.card, dpf(radius), dpf(1f), pal.dark)
    fun sheetCard(radius: Float = 22f) = GradientDrawable().apply { setColor(pal.sheet); cornerRadius = dpf(radius); setStroke(dp(1), pal.stroke) }
    fun circle(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }

    fun text(t: CharSequence, sp: Float, color: Int, bold: Boolean = false) = TextView(act).apply {
        text = t; setTextSize(TypedValue.COMPLEX_UNIT_SP, sp * font); setTextColor(color)
        typeface = HomeActivity.tf(bold)
        includeFontPadding = false
    }

    /** Ripple + no grey focus box (API 26+ draws one on the focused view otherwise). [radius] < 0 = oval. */
    fun tap(v: View, radius: Float = 16f, dark: Boolean = pal.dark, onClick: (() -> Unit)? = null) {
        val mask = if (radius < 0) GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) } else fill(Color.WHITE, radius)
        v.foreground = RippleDrawable(ColorStateList.valueOf(if (dark) 0x33ffffff else 0x22000000), null, mask)
        if (Build.VERSION.SDK_INT >= 26) v.defaultFocusHighlightEnabled = false
        v.isClickable = true
        if (onClick != null) v.setOnClickListener { onClick() }
    }

    /** A glyph in a filled circle: the glyph colour is the one tested for that fill (onSolid / onTint / onDanger …). */
    fun badge(icon: Int, glyph: Int, fillColor: Int, size: Int, iconSize: Int, square: Boolean = false) = ImageView(act).apply {
        setImageDrawable(Ic.get(act, icon, glyph, dp(iconSize)))
        background = if (square) fill(fillColor, 12f) else circle(fillColor)
        val p = dp((size - iconSize) / 2); setPadding(p, p, p, p)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
    }

    /** Full-width button. primary: the accent with its tested label colour; else text on the sheet with a stroke. */
    fun pill(t: String, icon: Int?, primary: Boolean, onClick: () -> Unit) = object : TextView(act) {
        // icon + text centred as one group (Material button): the side padding is set here, before the first frame. It was
        // a post() after layout, so every Tuỳ chỉnh rebuild drew the icon at the far left for one frame (owner saw it blink).
        override fun onMeasure(w: Int, h: Int) {
            val d = compoundDrawablesRelative[0]
            if (d != null && MeasureSpec.getMode(w) == MeasureSpec.EXACTLY) {
                val tw = paint.measureText(text.toString()).toInt()
                val side = ((MeasureSpec.getSize(w) - tw - d.bounds.width() - compoundDrawablePadding) / 2).coerceAtLeast(dp(16))
                if (paddingLeft != side) setPadding(side, paddingTop, side, paddingBottom)
            }
            super.onMeasure(w, h)
        }
    }.apply {
        text = t; gravity = Gravity.CENTER; setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f * font)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        val fg = if (primary) pal.onSolid else pal.text
        setTextColor(fg)
        background = if (primary) fill(pal.solid, 16f) else GradientDrawable().apply { setColor(pal.sheet); cornerRadius = dpf(16f); setStroke(dp(1), pal.stroke) }
        setPadding(dp(16), dp(12), dp(16), dp(12)); minHeight = dp(52)   // grows with a large font
        if (icon != null) {
            Ic.lead(this, icon, fg)   // centred with the text in onMeasure above
        }
        tap(this, 16f, primary || pal.dark) { onClick() }
        contentDescription = t
    }

    /** Small rounded chip: [bg] fill and [fg] text (a tested pair, e.g. tint / onTint, warnTint / warnText). */
    fun chip(t: String, fg: Int, bg: Int, icon: Int? = null) = text(t, 12f, fg, true).apply {
        background = fill(bg); setPadding(dp(10), dp(5), dp(10), dp(5)); gravity = Gravity.CENTER_VERTICAL
        if (icon != null) { Ic.lead(this, icon, fg); compoundDrawablePadding = dp(4) }
    }


    fun drawRound(c: Canvas, l: Float, t: Float, r: Float, b: Float, p: Paint, rad: Float = dpf(16f)) = c.drawRoundRect(l, t, r, b, rad, rad, p)
}

/**
 * The launcher's one card surface (widgets, "Hôm nay" cards): the palette fill plus the glass rim the icons have, a
 * hairline lit on the top-left and bottom-right corners that fades along the sides (iOS 26 / LauncherOS). A plain
 * GradientDrawable underneath (setColor / cornerRadius keep working); the rim shader is rebuilt only on a size change.
 */
class GlassCard(color: Int, radius: Float, private val w: Float, dark: Boolean) : GradientDrawable() {
    private val rim = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.STROKE; strokeWidth = w }
    private val r = android.graphics.RectF()
    private val rad = radius
    // on a light card the white rim would vanish: a faint dark edge on the shaded corner keeps the pane readable
    private val a0 = if (dark) 0x66FFFFFF else 0xCCFFFFFF.toInt()
    private val a1 = if (dark) 0x38FFFFFF else 0x1F000000
    init { setColor(color); cornerRadius = radius }

    override fun onBoundsChange(b: android.graphics.Rect) {
        super.onBoundsChange(b)
        rim.shader = android.graphics.LinearGradient(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(),
            intArrayOf(a0, a0 and 0x00FFFFFF, a1 and 0x00FFFFFF, a1), floatArrayOf(0f, 0.35f, 0.65f, 1f), android.graphics.Shader.TileMode.CLAMP)
    }

    override fun draw(c: android.graphics.Canvas) {
        super.draw(c)
        val b = bounds; val h = w / 2
        r.set(b.left + h, b.top + h, b.right - h, b.bottom - h)
        rim.alpha = alpha   // the rim fades with the pane (folder card morphing into its icon)
        c.drawRoundRect(r, rad - h, rad - h, rim)
    }
}
