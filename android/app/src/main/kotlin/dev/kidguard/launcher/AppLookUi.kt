package dev.kidguard

import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Long-press menu extras for one app: rename, custom icon, restore, read the name (see [AppLook], [ReadAids]). */
internal fun HomeActivity.lookRows(a: HomeActivity.AppE): List<HomeActivity.Row> {
    val m = model ?: return emptyList()
    val has = AppLook.get(a.pkg, a.cls) != null
    return listOfNotNull(
        HomeActivity.Row(R.drawable.li_pencil, getString(R.string.hm2_rename), true) { renameApp(a) },
        HomeActivity.Row(R.drawable.li_image_plus, getString(R.string.hm2_icon), true) { iconSheet(a) },
        HomeActivity.Row(R.drawable.li_rotate_ccw, getString(R.string.hm2_restore), true) { setLook(a.pkg, AppCustom()) }.takeIf { has },
        HomeActivity.Row(R.drawable.li_volume_2, getString(R.string.hm2_read_name), true) { ReadAids.say(this, a.label) }.takeIf { m.style.speak },
    )
}

/** Saves one app's override (an empty [c] = back to the original) and rebuilds the home. */
internal fun HomeActivity.setLook(pkg: String, c: AppCustom) {
    val m = model ?: return
    apps = null   // labels (and the search / sort keys) are read again with the new override
    update(m.copy(custom = AppLook.with(m.custom, pkg, c)))
    if (drawer != null) main.post { closeDrawer() }
}

internal fun HomeActivity.renameApp(a: HomeActivity.AppE) {
    val md = Modal(this, u)
    val field = md.field(getString(R.string.hm2_name_hint, a.real), AppLook.MAX_LABEL).apply { setText(a.label); setSelection(text.length) }
    md.show(R.drawable.li_pencil, Modal.Tone.INFO, getString(R.string.hm2_rename), content = field, ok = getString(R.string.hm_t_save), cancel = getString(R.string.cancel)) {
        val cur = AppLook.get(a.pkg, a.cls) ?: AppCustom()
        val t = field.text.toString().trim()
        setLook(a.pkg, cur.copy(label = if (t == a.real) "" else t))
        true
    }
    field.requestFocus()
}

private fun HomeActivity.circle(spec: String, px: Int): ImageView = ImageView(this).apply {
    AppLook.parseSpec(spec)?.let { setImageDrawable(AppLook.draw(this@circle, it, px)) }
    outlineProvider = object : ViewOutlineProvider() { override fun getOutline(v: View, o: Outline) = o.setOval(0, 0, v.width, v.height) }
    clipToOutline = true
}

/** "Đổi icon": the original, a glyph on a coloured plate, or an emoji. The colour row picks the plate for the next tap. */
internal fun HomeActivity.iconSheet(a: HomeActivity.AppE) {
    val sh = Sheet(this, u, getString(R.string.hm2_icon_title, a.label))
    val cur = AppLook.get(a.pkg, a.cls)
    var color = cur?.icon?.let { AppLook.parseSpec(it)?.color } ?: AppLook.COLORS[5]
    val sz = dp(48)
    fun pick(spec: String) { sh.dismiss(); setLook(a.pkg, (AppLook.get(a.pkg, a.cls) ?: AppCustom()).copy(icon = spec)) }

    val orig = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8))
        addView(ImageView(this@iconSheet).apply { IconCache.get(this@iconSheet, a.pkg, "", dp(40), model?.style?.shape != "square")?.let { setImageBitmap(it) } }, LinearLayout.LayoutParams(dp(40), dp(40)))
        addView(text(getString(R.string.hm2_icon_orig), 16f, pal.text).apply { setPadding(dp(14), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
        kit.tap(this, 12f) { sh.dismiss(); setLook(a.pkg, (AppLook.get(a.pkg, a.cls) ?: AppCustom()).copy(icon = "")) }
        contentDescription = getString(R.string.hm2_icon_orig)
    }
    sh.body.addView(orig)

    sh.body.addView(text(getString(R.string.hm2_icon_color), 13f, pal.sub, true).apply { setPadding(dp(12), dp(10), dp(12), dp(4)) })
    val glyphs = GridLayout(this).apply { columnCount = 6 }
    val emojis = GridLayout(this).apply { columnCount = 6 }
    fun fillGlyphs() {
        glyphs.removeAllViews()
        for (g in AppLook.GLYPHS) {
            val spec = AppLook.glyphSpec(g, color)
            glyphs.addView(circle(spec, sz).apply { contentDescription = g.replace('_', ' '); kit.tap(this, 24f) { pick(spec) } }, GridLayout.LayoutParams().apply { width = sz; height = sz; setMargins(dp(6), dp(6), dp(6), dp(6)) })
        }
    }
    fun fillEmojis() {
        emojis.removeAllViews()
        for (e in AppLook.EMOJIS) {
            val spec = AppLook.emojiSpec(e, color)
            emojis.addView(circle(spec, sz).apply { contentDescription = e; kit.tap(this, 24f) { pick(spec) } }, GridLayout.LayoutParams().apply { width = sz; height = sz; setMargins(dp(6), dp(6), dp(6), dp(6)) })
        }
    }
    val swatches = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), 0, dp(8), 0) }
    fun paintSwatches() {
        swatches.removeAllViews()
        for (c in AppLook.COLORS) {
            swatches.addView(View(this).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c); if (c == color) setStroke(dp(3), pal.text) }
                contentDescription = AppLook.hex(c)
                setOnClickListener { color = c; paintSwatches(); fillGlyphs(); fillEmojis() }
            }, LinearLayout.LayoutParams(0, dp(28), 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
        }
    }
    paintSwatches(); fillGlyphs(); fillEmojis()
    sh.body.addView(swatches, LinearLayout.LayoutParams(-1, -2))
    sh.body.addView(text(getString(R.string.hm2_icon_glyphs), 13f, pal.sub, true).apply { setPadding(dp(12), dp(12), dp(12), dp(0)) })
    sh.body.addView(glyphs)
    sh.body.addView(text(getString(R.string.hm2_icon_emoji), 13f, pal.sub, true).apply { setPadding(dp(12), dp(8), dp(12), dp(0)) })
    sh.body.addView(emojis)
    sh.show()
}
