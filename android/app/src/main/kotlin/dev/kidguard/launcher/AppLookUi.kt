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

private fun HomeActivity.circle(spec: String, px: Int): ImageView = ImageView(this).apply {
    AppLook.parseSpec(spec)?.let { setImageDrawable(AppLook.draw(this@circle, it, px)) }
    outlineProvider = object : ViewOutlineProvider() { override fun getOutline(v: View, o: Outline) = o.setOval(0, 0, v.width, v.height) }
    clipToOutline = true
}

/**
 * "Sửa app": one sheet for everything about how an app looks (iOS / Pixel "Edit" style): a live preview, the name field,
 * then the icon choices (original, a glyph or an emoji on a coloured plate). Nothing is saved until "Lưu"; "Khôi phục"
 * puts the original name + icon back at once. Replaces three separate menu rows (rename / icon / restore).
 */
internal fun HomeActivity.appEditSheet(a: HomeActivity.AppE) {
    val sh = Sheet(this, u, getString(R.string.hm2_edit_title))
    val cur = AppLook.get(a.pkg, a.cls) ?: AppCustom()
    var icon = cur.icon                       // "" = the original icon
    var color = AppLook.parseSpec(icon)?.color ?: AppLook.COLORS[5]
    val round = model?.style?.shape != "square"
    val big = dp(64)
    // 6 per row, sized to the sheet (12 dp sheet padding each side, 3 dp gap per side): fixed 48 dp + margins overflowed 360 dp screens
    val cell = (minOf(root.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels, resources.displayMetrics.widthPixels) - dp(24)) / 6
    val gap = dp(3); val sz = (cell - 2 * gap).coerceAtMost(dp(52))
    val selected = ArrayList<Pair<View, String>>()

    // preview + name
    val preview = ImageView(this)
    fun paintPreview() {
        val d = AppLook.parseSpec(icon)?.let { AppLook.draw(this, it, big) }
        if (d != null) { preview.setImageDrawable(d); preview.clipToOutline = true }
        else { preview.clipToOutline = false; IconCache.get(this, a.pkg, "", big, round)?.let { preview.setImageBitmap(it) } }
        for ((v, spec) in selected) v.foreground = if (spec == icon) GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke(dp(3), pal.text) } else null
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
    fun fill(grid: GridLayout, specs: List<Pair<String, String>>) {
        grid.removeAllViews()
        for ((spec, desc) in specs) {
            val v = circle(spec, sz).apply { contentDescription = desc; kit.tap(this, 24f) { choose(spec) } }
            selected += v to spec
            grid.addView(v, GridLayout.LayoutParams().apply { width = sz; height = sz; setMargins(gap, gap, gap, gap) })
        }
    }
    fun fillAll() {
        selected.clear()
        fill(glyphs, AppLook.GLYPHS.map { AppLook.glyphSpec(it, color) to it.replace('_', ' ') })
        fill(emojis, AppLook.EMOJIS.map { AppLook.emojiSpec(it, color) to it })
    }
    val swatches = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), dp(4), dp(8), dp(4)) }
    fun paintSwatches() {
        swatches.removeAllViews()
        for (c in AppLook.COLORS) swatches.addView(View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c); if (c == color) setStroke(dp(3), pal.text) }
            contentDescription = getString(R.string.hm2_icon_color) + " " + AppLook.hex(c)
            setOnClickListener {
                color = c; paintSwatches(); fillAll()
                // the picked glyph / emoji follows the new colour
                AppLook.parseSpec(icon)?.let { sp -> icon = if (sp.glyph) AppLook.glyphSpec(sp.value, c) else AppLook.emojiSpec(sp.value, c) }
                paintPreview()
            }
        }, LinearLayout.LayoutParams(0, dp(28), 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
    }
    paintSwatches(); fillAll()
    sh.body.addView(swatches, LinearLayout.LayoutParams(-1, -2))
    sh.body.addView(text(getString(R.string.hm2_icon_glyphs), 13f, pal.sub, true).apply { setPadding(dp(12), dp(10), dp(12), 0) })
    sh.body.addView(glyphs)
    sh.body.addView(text(getString(R.string.hm2_icon_emoji), 13f, pal.sub, true).apply { setPadding(dp(12), dp(8), dp(12), 0) })
    sh.body.addView(emojis)
    paintPreview()

    sh.show()
}
