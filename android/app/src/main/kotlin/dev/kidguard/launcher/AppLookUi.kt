package dev.kidguard

import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.GridLayout
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Long-press menu entry for one app: a single "Sửa app" that opens [appEditSheet] (name + icon + restore in one place). */
internal fun HomeActivity.lookRow(a: HomeActivity.AppE): HomeActivity.Row =
    HomeActivity.Row(R.drawable.li_pencil, getString(R.string.hm2_edit), true) { appEditSheet(a) }

/** Saves one app's override (an empty [c] = back to the original) and rebuilds the home. */
internal fun HomeActivity.setLook(pkg: String, c: AppCustom) {
    val m = model ?: return
    apps = null   // labels (and the search / sort keys) are read again with the new override
    update(m.copy(custom = AppLook.with(m.custom, pkg, c)))
    if (drawer != null) main.post { closeDrawer() }
}

/** One choice in the icon grid: a coloured circle (its own [bg], recoloured in place) with a white glyph or an emoji. */
private class Tile(val view: View, val bg: GradientDrawable, val glyph: Boolean, val value: String)

/**
 * Plain views, no bitmap per tile: drawing 50 bitmaps (vector + emoji text) on open cost ~70 ms of main thread on
 * Oppo A5s, and a colour tap redrew them all. Now a colour tap only calls setColor on each circle.
 */
private fun HomeActivity.tile(glyph: Boolean, value: String, color: Int, sz: Int): Tile {
    val bg = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
    val v: View = if (glyph) ImageView(this).apply {
        AppLook.GLYPH_RES[value]?.let { id -> setImageDrawable(getDrawable(id)?.mutate()?.apply { setTint(android.graphics.Color.WHITE) }) }
        scaleType = ImageView.ScaleType.FIT_CENTER; val p = (sz * 0.22f).toInt(); setPadding(p, p, p, p)
    } else TextView(this).apply {
        text = value; gravity = Gravity.CENTER; includeFontPadding = false
        setTextColor(android.graphics.Color.WHITE)   // the default text colour is translucent: emoji came out faded
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, sz * 0.5f)
    }
    v.background = bg
    return Tile(v, bg, glyph, value)
}

/**
 * "Sửa app": one sheet for everything about how an app looks (iOS / Pixel "Edit" style): a live preview, the name field,
 * then the icon choices (original, a glyph or an emoji on a coloured plate). Nothing is saved until "Lưu"; "Khôi phục"
 * puts the original name + icon back at once. Replaces three separate menu rows (rename / icon / restore).
 */
internal fun HomeActivity.appEditSheet(a: HomeActivity.AppE) {
    val t0 = Perf.now()
    val sh = Sheet(this, u, getString(R.string.hm2_edit_title))
    val cur = AppLook.get(a.pkg, a.cls) ?: AppCustom()
    var icon = cur.icon                       // "" = the original icon
    var color = AppLook.parseSpec(icon)?.color ?: AppLook.COLORS[5]
    val round = model?.style?.shape != "square"
    val big = dp(64)
    // 6 per row, sized to the sheet (12 dp sheet padding each side, 3 dp gap per side): fixed 48 dp + margins overflowed 360 dp screens
    val cell = (minOf(root.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels, resources.displayMetrics.widthPixels) - dp(24)) / 6
    val gap = dp(3); val sz = (cell - 2 * gap).coerceAtMost(dp(52))
    val tiles = ArrayList<Tile>()

    // preview + name
    val preview = ImageView(this)
    fun paintPreview() {
        val d = AppLook.parseSpec(icon)?.let { AppLook.draw(this, it, big) }
        if (d != null) { preview.setImageDrawable(d); preview.clipToOutline = true }
        else { preview.clipToOutline = false; IconCache.get(this, a.pkg, "", big, round)?.let { preview.setImageBitmap(it) } }
        val sp = AppLook.parseSpec(icon)
        // the ring is the circle's own stroke: foreground holds kit.tap's ripple
        for (t in tiles) t.bg.setStroke(if (sp != null && sp.glyph == t.glyph && sp.value == t.value) dp(3) else 0, pal.text)
    }
    preview.outlineProvider = object : ViewOutlineProvider() { override fun getOutline(v: View, o: Outline) = o.setOval(0, 0, v.width, v.height) }
    val name = EditText(this).apply {
        hint = a.real; setText(a.label); setSelection(text.length)
        filters = arrayOf(android.text.InputFilter.LengthFilter(AppLook.MAX_LABEL))
        setTextColor(u.text); setHintTextColor(u.sub); textSize = 17f; setSingleLine()
        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        background = u.shape(u.bg, 14, u.stroke, 1); setPadding(dp(14), dp(12), dp(14), dp(12))
        contentDescription = getString(R.string.hm2_name_hint, a.real)
    }
    val top = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(6), dp(12), dp(4))
        addView(preview, LinearLayout.LayoutParams(big, big))
        addView(LinearLayout(this@appEditSheet).apply {
            orientation = LinearLayout.VERTICAL
            addView(name, LinearLayout.LayoutParams(-1, -2))
            addView(text(getString(R.string.hm2_name_hint, a.real), 12f, pal.sub).apply { setPadding(dp(4), dp(4), 0, 0); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(14) })
    }
    sh.body.addView(top)
    // actions right under the name (no scrolling to find Lưu): Khôi phục gốc (only when customised) + Lưu
    val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), dp(10), dp(8), dp(4)) }
    if (!cur.empty) bar.addView(kit.pill(getString(R.string.hm2_restore), R.drawable.li_rotate_ccw, false) { sh.dismiss(); setLook(a.pkg, AppCustom()) },
        LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(8) })
    bar.addView(kit.pill(getString(R.string.hm_t_save), R.drawable.li_check, true) {
        val t = name.text.toString().trim()
        sh.dismiss()
        setLook(a.pkg, AppCustom(label = if (t == a.real) "" else t, icon = icon))
    }, LinearLayout.LayoutParams(0, -2, 1f))
    sh.body.addView(bar)

    // icon choices: original, colour, glyphs, emoji (a tap only changes the preview)
    fun choose(spec: String) { icon = spec; paintPreview() }
    val orig = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(6))
        addView(ImageView(this@appEditSheet).apply { IconCache.get(this@appEditSheet, a.pkg, "", dp(36), round)?.let { setImageBitmap(it) } }, LinearLayout.LayoutParams(dp(36), dp(36)))
        addView(text(getString(R.string.hm2_icon_orig), 15f, pal.text).apply { setPadding(dp(12), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
        kit.tap(this, 12f) { choose("") }
        contentDescription = getString(R.string.hm2_icon_orig)
    }
    sh.body.addView(text(getString(R.string.hm2_icon), 13f, pal.sub, true).apply { setPadding(dp(12), dp(14), dp(12), 0) })
    sh.body.addView(orig)
    val glyphs = GridLayout(this).apply { columnCount = 6 }
    val emojis = GridLayout(this).apply { columnCount = 6 }
    fun fill(grid: GridLayout, glyph: Boolean, values: List<String>) {
        for (value in values) {
            val t = tile(glyph, value, color, sz)
            t.view.contentDescription = value.replace('_', ' ')
            kit.tap(t.view, -1f) { choose(if (glyph) AppLook.glyphSpec(value, color) else AppLook.emojiSpec(value, color)) }
            tiles += t
            grid.addView(t.view, GridLayout.LayoutParams().apply { width = sz; height = sz; setMargins(gap, gap, gap, gap) })
        }
    }
    val swatches = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), dp(4), dp(8), dp(4)) }
    val dots = ArrayList<Pair<GradientDrawable, Int>>()
    for (c in AppLook.COLORS) swatches.addView(View(this).apply {
        val d = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c); if (c == color) setStroke(dp(3), pal.text) }
        dots += d to c; background = d
        contentDescription = getString(R.string.hm2_icon_color) + " " + AppLook.hex(c)
        setOnClickListener {
            color = c
            for ((dd, cc) in dots) dd.setStroke(if (cc == c) dp(3) else 0, pal.text)
            for (t in tiles) t.bg.setColor(c)
            // the picked glyph / emoji follows the new colour
            AppLook.parseSpec(icon)?.let { sp -> icon = if (sp.glyph) AppLook.glyphSpec(sp.value, c) else AppLook.emojiSpec(sp.value, c) }
            paintPreview()
        }
    }, LinearLayout.LayoutParams(0, dp(28), 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
    sh.body.addView(swatches, LinearLayout.LayoutParams(-1, -2))
    sh.body.addView(text(getString(R.string.hm2_icon_glyphs), 13f, pal.sub, true).apply { setPadding(dp(12), dp(10), dp(12), 0) })
    // grids sit height-reserved (rows known) and are filled after the sheet's first frame: the slide-up starts at once
    // and the sheet does not jump (a View.post before the first frame still runs ahead of it, see CustomActivity)
    fun rowsH(n: Int) = ((n + 5) / 6) * (sz + 2 * gap)
    sh.body.addView(glyphs, LinearLayout.LayoutParams(-2, rowsH(AppLook.GLYPHS.size)))
    sh.body.addView(text(getString(R.string.hm2_icon_emoji), 13f, pal.sub, true).apply { setPadding(dp(12), dp(8), dp(12), 0) })
    sh.body.addView(emojis, LinearLayout.LayoutParams(-2, rowsH(AppLook.EMOJIS.size)))
    paintPreview()
    sh.body.viewTreeObserver.addOnDrawListener(object : android.view.ViewTreeObserver.OnDrawListener {
        var done = false
        override fun onDraw() {
            if (done) return
            done = true
            val me = this
            sh.body.post {
                runCatching { sh.body.viewTreeObserver.removeOnDrawListener(me) }
                // two lumps on two frames (~12 ms each on Oppo A5s) instead of one 25 ms frame
                val tf = Perf.now()
                fill(glyphs, true, AppLook.GLYPHS); paintPreview()
                Perf.log("appEdit glyphs", Perf.now() - tf)
                sh.body.postOnAnimation {
                    val te = Perf.now()
                    fill(emojis, false, AppLook.EMOJIS); paintPreview()
                    Perf.log("appEdit emoji", Perf.now() - te)
                }
            }
        }
    })

    val built = Perf.now()
    sh.show()
    Perf.firstDraw(sh.body, "appEdit", t0, built)
}
