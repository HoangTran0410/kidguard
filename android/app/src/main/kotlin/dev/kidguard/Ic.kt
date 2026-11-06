package dev.kidguard

import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.DynamicDrawableSpan
import android.text.style.ImageSpan
import android.widget.TextView

/** Vector icons (Lucide, res/drawable/li_*.xml) instead of emoji, which look different on every phone. */
object Ic {
    const val OK = "#16a34a"
    const val WARN = "#d97706"
    const val INFO = "#2563eb"

    fun get(ctx: Context, res: Int, color: Int, sizePx: Int): Drawable =
        ctx.getDrawable(res)!!.mutate().apply { setTint(color); setBounds(0, 0, sizePx, sizePx) }

    /** Icon at the start of a TextView / Button, tinted with the text color. */
    fun lead(v: TextView, res: Int, color: Int = v.currentTextColor) {
        val size = (v.textSize * 1.25f).toInt()
        v.setCompoundDrawablesRelative(get(v.context, res, color, size), null, null, null)
        v.compoundDrawablePadding = (v.textSize * 0.5f).toInt()
    }

    /** Appends "<icon> text\n" to a status text. */
    fun line(sb: SpannableStringBuilder, v: TextView, res: Int, color: String, text: String) {
        val size = (v.textSize * 1.15f).toInt()
        val align = if (Build.VERSION.SDK_INT >= 29) DynamicDrawableSpan.ALIGN_CENTER else DynamicDrawableSpan.ALIGN_BOTTOM
        val start = sb.length
        sb.append(" ")
        sb.setSpan(ImageSpan(get(v.context, res, android.graphics.Color.parseColor(color), size), align), start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.append("  ").append(text).append("\n")
    }
}
