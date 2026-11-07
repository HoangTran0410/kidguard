package dev.kidguard

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * App-styled dialogs for the child screen (instead of the system AlertDialog): a rounded card in the [Ui] palette,
 * a tinted icon badge, a title, a message, optional content and one or two big buttons. Follows light / dark.
 */
class Modal(private val act: Activity, private val u: Ui = Ui(act)) {
    enum class Tone { INFO, OK, WARN, DANGER }

    private fun color(t: Tone): Int = when (t) {
        Tone.OK -> u.tone(Ic.OK)
        Tone.WARN -> u.tone(Ic.WARN)
        Tone.DANGER -> Color.parseColor(if (u.dark) "#f87171" else "#dc2626")
        Tone.INFO -> u.accent
    }
    private fun fill(t: Tone): Int = when (t) {
        Tone.DANGER -> Color.parseColor("#dc2626")
        Tone.OK -> Color.parseColor("#15803d")      // white label >= 4.5:1
        Tone.WARN -> Color.parseColor("#b45309")
        Tone.INFO -> u.primary
    }

    /** Error line inside the card (e.g. "Sai PIN"); hidden until [setError]. */
    val error: TextView = u.label("", 14f, color(Tone.DANGER), true).apply { gravity = Gravity.CENTER; visibility = View.GONE }
    fun setError(t: String?) { error.text = t ?: ""; error.visibility = if (t.isNullOrBlank()) View.GONE else View.VISIBLE }

    /**
     * Shows the modal. [onOk] returns true to close it, false to keep it open (e.g. wrong PIN). [cancel] null = one button.
     */
    fun show(icon: Int?, tone: Tone, title: String, message: String? = null, content: View? = null,
             ok: String = "OK", cancel: String? = null, onCancel: () -> Unit = {}, onOk: () -> Boolean = { true }): Dialog {
        val d = Dialog(act)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val c = color(tone)
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            background = u.shape(u.card, 28, u.stroke, 1)
            setPadding(u.dp(24), u.dp(24), u.dp(24), u.dp(20))
        }
        if (icon != null) box.addView(ImageView(act).apply {
            background = u.shape(Color.argb(if (u.dark) 55 else 30, Color.red(c), Color.green(c), Color.blue(c)), 32)
            setImageDrawable(Ic.get(act, icon, c, u.dp(30))); scaleType = ImageView.ScaleType.CENTER
        }, LinearLayout.LayoutParams(u.dp(64), u.dp(64)).apply { bottomMargin = u.dp(14) })
        box.addView(u.label(title, 20f, u.text, true).apply { gravity = Gravity.CENTER })
        if (!message.isNullOrBlank()) box.addView(u.label(message, 15f, u.sub).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.15f) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = u.dp(8) })
        if (content != null) box.addView(content, LinearLayout.LayoutParams(-1, -2).apply { topMargin = u.dp(16) })
        box.addView(error, LinearLayout.LayoutParams(-1, -2).apply { topMargin = u.dp(10) })

        val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        fun place(b: Button, first: Boolean) = row.addView(b, LinearLayout.LayoutParams(0, -2, 1f).apply { if (!first) marginStart = u.dp(10) })
        if (cancel != null) place(u.button(cancel, null, false) { d.dismiss(); onCancel() }, true)
        val okBtn = u.button(ok, null, true) { if (onOk()) d.dismiss() }.apply {
            background = RippleDrawable(ColorStateList.valueOf(Color.argb(60, 255, 255, 255)), u.shape(fill(tone), 16), null)
        }
        place(okBtn, cancel == null)
        box.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = u.dp(20) })

        d.setContentView(box)
        d.setCanceledOnTouchOutside(cancel != null)
        d.setOnCancelListener { onCancel() }
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val w = act.resources.displayMetrics.widthPixels
            setLayout(minOf(w - u.dp(32), u.dp(420)), WindowManager.LayoutParams.WRAP_CONTENT)
            setDimAmount(0.55f); addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            if (content is EditText || (content as? LinearLayout)?.let { l -> (0 until l.childCount).any { l.getChildAt(it) is EditText } } == true)
                setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        d.show()
        box.alpha = 0f; box.scaleX = 0.92f; box.scaleY = 0.92f
        box.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(160).start()
        return d
    }

    /** Rounded text field in the palette. */
    fun field(hint: String, max: Int, pin: Boolean = false): EditText = EditText(act).apply {
        this.hint = if (pin) "• • • •" else hint; filters = arrayOf(InputFilter.LengthFilter(max))
        setTextColor(u.text); setHintTextColor(u.sub); textSize = if (pin) 24f else 16f
        if (pin) { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD; gravity = Gravity.CENTER; letterSpacing = 0.3f }
        else inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        imeOptions = EditorInfo.IME_ACTION_DONE
        background = u.shape(u.bg, 14, u.stroke, 1)
        setPadding(u.dp(14), u.dp(12), u.dp(14), u.dp(12))
    }

    /** Segmented choice (e.g. 15 / 30 / 60 phút). [selected] reads the current index. */
    class Choice(val view: LinearLayout, private val get: () -> Int) { val selected get() = get() }

    fun choice(items: List<String>, start: Int = 0, vertical: Boolean = false): Choice {
        var sel = start
        val row = LinearLayout(act).apply { orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL }
        val views = ArrayList<TextView>()
        fun paint() = views.forEachIndexed { i, v ->
            val on = i == sel
            v.background = u.shape(if (on) u.tint else u.bg, 14, if (on) u.primary else u.stroke, if (on) 2 else 1)
            v.setTextColor(if (on) u.accent else u.text)
        }
        items.forEachIndexed { i, t ->
            val v = TextView(act).apply {
                text = t; textSize = 16f; gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD)
                minHeight = u.dp(50); isClickable = true; isFocusable = true
                setOnClickListener { sel = i; paint() }
            }
            views += v
            if (vertical) row.addView(v, LinearLayout.LayoutParams(-1, -2).apply { if (i > 0) topMargin = u.dp(8) })
            else row.addView(v, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) marginStart = u.dp(8) })
        }
        paint()
        return Choice(row) { sel }
    }
}
