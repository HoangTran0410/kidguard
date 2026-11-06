package dev.kidguard

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Small view helpers + palette (follows the system light/dark setting) for the child screen. */
class Ui(val ctx: Context) {
    val dark = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    val bg = Color.parseColor(if (dark) "#0f172a" else "#f3f6fb")
    val card = Color.parseColor(if (dark) "#1e293b" else "#ffffff")
    val stroke = Color.parseColor(if (dark) "#334155" else "#e2e8f0")
    val text = Color.parseColor(if (dark) "#f1f5f9" else "#0f172a")
    val sub = Color.parseColor(if (dark) "#94a3b8" else "#64748b")
    val primary = Color.parseColor(if (dark) "#3b82f6" else "#2563eb")
    val accent = Color.parseColor(if (dark) "#93c5fd" else "#2563eb")
    val tint = Color.parseColor(if (dark) "#1e3a8a" else "#dbeafe")

    fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()
    fun dpf(v: Int) = v * ctx.resources.displayMetrics.density

    /** Status colors, a bit lighter on dark. */
    fun tone(hex: String): Int = when (hex) {
        Ic.OK -> Color.parseColor(if (dark) "#4ade80" else "#16a34a")
        Ic.WARN -> Color.parseColor(if (dark) "#fbbf24" else "#d97706")
        else -> accent
    }

    fun shape(fill: Int, radius: Int, strokeColor: Int = 0, strokeDp: Int = 0) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dpf(radius)
        if (strokeDp > 0) setStroke(dp(strokeDp), strokeColor)
    }

    fun cardBox(pad: Int = 16): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = shape(card, 20, stroke, 1)
        setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) }
    }

    fun label(t: String, size: Float = 13f, color: Int = sub, bold: Boolean = false) = TextView(ctx).apply {
        text = t; textSize = size; setTextColor(color); if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    /** Rounded button; primary = filled, otherwise outlined. Icon (if any) takes the text color. */
    fun button(t: String, icon: Int?, primaryStyle: Boolean, onClick: () -> Unit): Button = Button(ctx).apply {
        text = t; isAllCaps = false; textSize = 16f; setTypeface(typeface, Typeface.BOLD)
        stateListAnimator = null; minHeight = dp(54); minimumHeight = dp(54)
        setTextColor(if (primaryStyle) Color.WHITE else this@Ui.text)
        val base = if (primaryStyle) shape(primary, 16) else shape(card, 16, stroke, 1)
        background = RippleDrawable(ColorStateList.valueOf(Color.argb(40, 128, 128, 128)), base, null)
        gravity = Gravity.CENTER
        setPadding(dp(16), 0, dp(16), 0)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
        if (icon != null) Ic.lead(this, icon, if (primaryStyle) Color.WHITE else accent)
        setOnClickListener { onClick() }
    }

    /** Icon row for the status list: tinted icon + text. */
    fun row(icon: Int, toneHex: String, t: String, last: Boolean = false): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(8), 0, dp(8))
        val c = tone(toneHex)
        addView(android.widget.ImageView(ctx).apply {
            background = shape(Color.argb(if (dark) 50 else 28, Color.red(c), Color.green(c), Color.blue(c)), 12)
            setImageDrawable(Ic.get(ctx, icon, c, dp(20))); scaleType = android.widget.ImageView.ScaleType.CENTER
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        addView(TextView(ctx).apply { text = t; textSize = 16f; setTextColor(this@Ui.text) },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
    }
}
