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
class Ui(val ctx: Context, pal: Palette? = null) {
    // [pal]: the kid launcher's palette (its accent and its own light / dark), so its modals and sheets match it
    val dark = pal?.dark ?: isDark(ctx)
    val bg = pal?.bg ?: Color.parseColor(if (dark) "#0f172a" else "#f3f6fb")
    val card = pal?.sheet ?: Color.parseColor(if (dark) "#1e293b" else "#ffffff")
    val stroke = pal?.stroke ?: Color.parseColor(if (dark) "#334155" else "#e2e8f0")
    val text = pal?.text ?: Color.parseColor(if (dark) "#f1f5f9" else "#0f172a")
    val sub = pal?.sub ?: Color.parseColor(if (dark) "#94a3b8" else "#64748b")
    val primary = pal?.solid ?: Color.parseColor(if (dark) "#3b82f6" else "#2563eb")
    val accent = pal?.accentText ?: Color.parseColor(if (dark) "#93c5fd" else "#2563eb")
    val tint = pal?.tint ?: Color.parseColor(if (dark) "#1e3a8a" else "#dbeafe")
    val onPrimary = pal?.onSolid ?: Color.WHITE

    companion object {
        /** Light / dark for every KidGuard screen: the launcher's "Giao diện" choice (Tuỳ chỉnh) when the kid made one,
         *  else the system setting. One choice, so the app and the launcher never disagree (Android 8–9 have no system dark). */
        fun isDark(ctx: Context): Boolean {
            val sys = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            return when (runCatching { HomeFile.load(ctx)?.style?.theme }.getOrNull()) { "dark" -> true; "light" -> false; else -> sys }
        }
    }

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
        setTextColor(if (primaryStyle) onPrimary else this@Ui.text)
        val base = if (primaryStyle) shape(primary, 16) else shape(card, 16, stroke, 1)
        background = RippleDrawable(ColorStateList.valueOf(Color.argb(40, 128, 128, 128)), base, null)
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(8), dp(16), dp(8))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
        if (icon != null) Ic.lead(this, icon, if (primaryStyle) onPrimary else accent)
        setOnClickListener { onClick() }
    }

    val danger = Color.parseColor(if (dark) "#f87171" else "#dc2626")

    /** [a] blended toward [b] by [t] (0..1), opaque: tinted fills that look the same on any background. */
    fun mix(a: Int, b: Int, t: Float): Int = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt())

    /** An icon centered on a rounded square, as one drawable (for compound drawables of tiles / list rows). */
    fun badge(icon: Int, iconColor: Int, fill: Int, sizeDp: Int, iconDp: Int, radius: Int, strokeColor: Int = 0): android.graphics.drawable.Drawable {
        val inset = dp((sizeDp - iconDp) / 2)
        return android.graphics.drawable.LayerDrawable(arrayOf(shape(fill, radius, strokeColor, if (strokeColor != 0) 1 else 0), Ic.get(ctx, icon, iconColor, dp(iconDp)))).apply {
            setLayerInset(1, inset, inset, inset, inset); setBounds(0, 0, dp(sizeDp), dp(sizeDp))
        }
    }

    /**
     * Home quick action: a 60dp rounded square with the icon, the label under it. A Button, so callers can keep
     * setting its text / visibility. [toneColor] tints icon, fill and border (SOS = [danger]).
     */
    fun tile(t: String, icon: Int, toneColor: Int, onClick: () -> Unit): Button = Button(ctx).apply {
        text = t; isAllCaps = false; textSize = 12.5f; setTypeface(typeface, Typeface.BOLD); stateListAnimator = null
        setTextColor(if (toneColor == danger) danger else this@Ui.text)
        val tinted = toneColor == danger
        setCompoundDrawablesRelative(null, badge(icon, toneColor, if (tinted) mix(card, toneColor, if (dark) 0.18f else 0.10f) else card, 60, 26, 20,
            if (tinted) mix(card, toneColor, 0.4f) else stroke), null, null)
        compoundDrawablePadding = dp(6)
        gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP; maxLines = 3; setLineSpacing(0f, 1.05f)
        minHeight = dp(48); minimumHeight = dp(48); minWidth = 0; minimumWidth = 0
        setPadding(dp(2), dp(4), dp(2), dp(4))
        background = RippleDrawable(ColorStateList.valueOf(Color.argb(40, 128, 128, 128)), null, shape(Color.WHITE, 16))
        setOnClickListener { onClick() }
    }

    /** Sheet / list row: tinted icon square, label, chevron. A Button (works with MainActivity.holdToRun). */
    fun listRow(t: String, icon: Int, chevron: Boolean = true, onClick: () -> Unit): Button = Button(ctx).apply {
        text = t; isAllCaps = false; textSize = 16f; stateListAnimator = null; setTextColor(this@Ui.text)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER_VERTICAL or Gravity.START; textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        setCompoundDrawablesRelative(badge(icon, accent, tint, 36, 20, 11), null,
            if (chevron) Ic.get(ctx, R.drawable.li_chevron_right, sub, dp(18)) else null, null)
        compoundDrawablePadding = dp(14)
        minHeight = dp(56); minimumHeight = dp(56)
        setPadding(dp(12), dp(6), dp(12), dp(6))
        background = RippleDrawable(ColorStateList.valueOf(Color.argb(40, 128, 128, 128)), null, shape(Color.WHITE, 16))
        layoutParams = LinearLayout.LayoutParams(-1, -2)
        setOnClickListener { onClick() }
    }

    /** Small caps section title ("CỦA CON"). */
    fun section(t: String) = label(t.uppercase(), 12f, sub, true).apply { letterSpacing = 0.1f; setPadding(dp(12), dp(18), dp(12), dp(4)) }

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
