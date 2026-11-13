package dev.kidguard

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.SeekBar

/**
 * Colour picker (iOS / Material "custom colour" pattern): a big preview, 24 common colours as 44 dp circles for a quick
 * tap, then hue / saturation / brightness sliders and a hex field for any colour. Replaces a row of ten 28 dp dots
 * (owner: too small to hit, too few colours). [done] gets the chosen colour, opaque.
 */
internal fun HomeActivity.colorPick(start: Int, done: (Int) -> Unit) {
    val hsv = FloatArray(3).also { Color.colorToHSV(start, it) }
    var cur = start or 0xFF000000.toInt()
    val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    val swatch = GradientDrawable().apply { shape = GradientDrawable.OVAL }
    val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, dp(10)) }
    top.addView(View(this).apply { background = swatch; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, LinearLayout.LayoutParams(dp(48), dp(48)))
    val hexIn = EditText(this).apply {
        setTextColor(u.text); textSize = 18f; setSingleLine(); typeface = android.graphics.Typeface.MONOSPACE
        background = u.shape(u.bg, 12, u.stroke, 1); setPadding(dp(12), dp(8), dp(12), dp(8))
        filters = arrayOf(android.text.InputFilter.LengthFilter(7))
        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        contentDescription = getString(R.string.hm_hex_label)
    }
    top.addView(hexIn, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(12) })
    box.addView(top)

    val rings = ArrayList<Pair<GradientDrawable, Int>>()
    val grid = GridLayout(this).apply { columnCount = 6 }
    lateinit var sync: (from: String) -> Unit
    for (c in AppLook.PRESETS) grid.addView(View(this).apply {
        val d = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c) }
        rings += d to c; background = d
        contentDescription = getString(R.string.hm2_icon_color) + " " + AppLook.hex(c)
        kit.tap(this, -1f) { cur = c; Color.colorToHSV(c, hsv); sync("preset") }
    }, GridLayout.LayoutParams().apply { width = dp(44); height = dp(44); setMargins(dp(3), dp(3), dp(3), dp(3)) })
    box.addView(grid, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL })

    fun bar(label: Int, max: Int): Pair<SeekBar, GradientDrawable> {
        val track = GradientDrawable().apply { cornerRadius = dpf(8f); orientation = GradientDrawable.Orientation.LEFT_RIGHT }
        val sb = SeekBar(this).apply {
            this.max = max; progressDrawable = track; splitTrack = false
            thumb = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE); setStroke(dp(2), 0x55000000); setSize(dp(26), dp(26)) }
            minimumHeight = dp(16); setPadding(dp(14), dp(10), dp(14), dp(10)); contentDescription = getString(label)
        }
        box.addView(text(getString(label), 13f, u.sub, true).apply { setPadding(dp(2), dp(10), 0, 0) })
        box.addView(sb, LinearLayout.LayoutParams(-1, dp(40)))
        return sb to track
    }
    val (hue, hueT) = bar(R.string.cp_hue, 360)
    val (sat, satT) = bar(R.string.cp_sat, 100)
    val (vb, valT) = bar(R.string.cp_val, 100)
    hueT.colors = IntArray(7) { Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f)) }

    var busy = false
    sync = { from ->
        busy = true
        swatch.setColor(cur)
        for ((d, c) in rings) d.setStroke(if (c == cur) dp(3) else 0, u.text)
        if (from != "hue") hue.progress = Math.round(hsv[0])
        if (from != "sat") sat.progress = Math.round(hsv[1] * 100)
        if (from != "val") vb.progress = Math.round(hsv[2] * 100)
        satT.colors = intArrayOf(Color.HSVToColor(floatArrayOf(hsv[0], 0f, hsv[2])), Color.HSVToColor(floatArrayOf(hsv[0], 1f, hsv[2])))
        valT.colors = intArrayOf(Color.BLACK, Color.HSVToColor(floatArrayOf(hsv[0], hsv[1], 1f)))
        if (from != "hex") { hexIn.setText(AppLook.hex(cur)); hexIn.setSelection(hexIn.text.length) }
        busy = false
    }
    val onBar = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(s: SeekBar, p: Int, user: Boolean) {
            if (busy || !user) return
            when (s) { hue -> hsv[0] = p.toFloat(); sat -> hsv[1] = p / 100f; else -> hsv[2] = p / 100f }
            cur = Color.HSVToColor(hsv); sync(if (s === hue) "hue" else if (s === sat) "sat" else "val")
        }
        override fun onStartTrackingTouch(s: SeekBar) {}
        override fun onStopTrackingTouch(s: SeekBar) {}
    }
    for (s in listOf(hue, sat, vb)) s.setOnSeekBarChangeListener(onBar)
    hexIn.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) {
            if (busy) return
            val t = s.toString().trim().removePrefix("#")
            if (t.length == 6 && t.all { it.isDigit() || it.uppercaseChar() in 'A'..'F' }) {
                cur = (0xFF000000.toInt()) or t.toInt(16); Color.colorToHSV(cur, hsv); sync("hex")
            }
        }
    })
    sync("init")
    Modal(this, u).show(null, Modal.Tone.INFO, getString(R.string.hm2_icon_color), null, box, getString(R.string.hm_t_save), getString(R.string.cancel)) {
        done(cur); true
    }
}
