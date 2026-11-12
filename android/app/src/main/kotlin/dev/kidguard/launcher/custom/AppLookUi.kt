package dev.kidguard

import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
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
        AppLook.GLYPH_RES[value]?.let { id -> setImageDrawable(getDrawable(id)?.mutate()?.apply { setTint(AppLook.ink(color)) }) }
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
    val shp = model?.style?.shape ?: "soft"
    val big = dp(64)
    // 6 per row, sized to the sheet (12 dp sheet padding each side, 3 dp gap per side): fixed 48 dp + margins overflowed 360 dp screens
    val cell = (minOf(root.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels, resources.displayMetrics.widthPixels) - dp(24)) / 6
    val gap = dp(3); val sz = (cell - 2 * gap).coerceAtMost(dp(52))
    val tiles = ArrayList<Tile>()

    // preview + name
    val preview = ImageView(this)
    fun paintPreview() {
        val d = AppLook.parseSpec(icon)?.let { AppLook.draw(this, it, big) }
        // the home's own mask (round / soft / square), not a fixed circle: the preview shows what the icon will look like
        if (d != null) preview.setImageBitmap(IconCache.shaped(d, big, shp))
        else IconCache.get(this, a.pkg, "", big, shp)?.let { preview.setImageBitmap(it) }
        val sp = AppLook.parseSpec(icon)
        // the ring is the circle's own stroke: foreground holds kit.tap's ripple
        for (t in tiles) t.bg.setStroke(if (sp != null && sp.glyph == t.glyph && sp.value == t.value) dp(3) else 0, pal.text)
    }
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
    // actions right under the name, compact (a full-width primary button took a third of the screen): "Khôi phục gốc" as
    // a text button on the left (only when customised), "Lưu" a wrap-content pill on the right (iOS / Material dialogs)
    val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(8), dp(12), dp(4)) }
    if (!cur.empty) bar.addView(text(getString(R.string.hm2_restore), 15f, pal.accentText, true).apply {
        setPadding(dp(10), dp(10), dp(10), dp(10)); Ic.lead(this, R.drawable.li_rotate_ccw, pal.accentText); compoundDrawablePadding = dp(6)
        kit.tap(this, 12f) { sh.dismiss(); setLook(a.pkg, AppCustom()) }
        contentDescription = getString(R.string.hm2_restore)
    })
    bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
    bar.addView(kit.pill(getString(R.string.hm_t_save), R.drawable.li_check, true) {
        val t = name.text.toString().trim()
        sh.dismiss()
        setLook(a.pkg, AppCustom(label = if (t == a.real) "" else t, icon = icon))
    }.apply { minHeight = dp(44); setPadding(dp(18), dp(8), dp(20), dp(8)) }, LinearLayout.LayoutParams(-2, -2))
    sh.body.addView(bar)

    // icon choices: original, colour, glyphs, emoji (a tap only changes the preview)
    fun choose(spec: String) { icon = spec; paintPreview() }
    val orig = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(6))
        addView(ImageView(this@appEditSheet).apply { IconCache.get(this@appEditSheet, a.pkg, "", dp(36), shp)?.let { setImageBitmap(it) } }, LinearLayout.LayoutParams(dp(36), dp(36)))
        addView(text(getString(R.string.hm2_icon_orig), 15f, pal.text).apply { setPadding(dp(12), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
        kit.tap(this, 12f) { choose("") }
        contentDescription = getString(R.string.hm2_icon_orig)
    }
    sh.body.addView(text(getString(R.string.hm2_icon), 13f, pal.sub, true).apply { setPadding(dp(12), dp(14), dp(12), 0) })
    sh.body.addView(orig)
    fun fill(grid: GridLayout, glyph: Boolean, values: List<String>) {
        for (value in values) {
            val t = tile(glyph, value, color, sz)
            t.view.contentDescription = value.replace('_', ' ')
            kit.tap(t.view, -1f) { choose(if (glyph) AppLook.glyphSpec(value, color) else AppLook.emojiSpec(value, color)) }
            tiles += t
            grid.addView(t.view, GridLayout.LayoutParams().apply { width = sz; height = sz; setMargins(gap, gap, gap, gap) })
        }
    }
    // one "Màu" row (a 36 dp swatch + hex) opening the colour picker: ten 28 dp dots were hard to hit and too few
    val sw = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
    val hexT = text(AppLook.hex(color), 14f, pal.sub)
    sh.body.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(52); setPadding(dp(12), dp(6), dp(12), dp(6))
        addView(View(this@appEditSheet).apply { background = sw }, LinearLayout.LayoutParams(dp(36), dp(36)))
        addView(text(getString(R.string.hm2_icon_color), 15f, pal.text).apply { setPadding(dp(12), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(hexT)
        addView(ImageView(this@appEditSheet).apply { setImageDrawable(Ic.get(this@appEditSheet, R.drawable.li_chevron_right, pal.sub, dp(20))) }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { leftMargin = dp(6) })
        contentDescription = getString(R.string.hm2_icon_color)
        kit.tap(this, 12f) {
            colorPick(color) { c ->
                color = c; sw.setColor(c); hexT.text = AppLook.hex(c)
                for (t in tiles) { t.bg.setColor(c); if (t.glyph) (t.view as ImageView).drawable?.setTint(AppLook.ink(c)) }
                // the picked glyph / emoji follows the new colour
                AppLook.parseSpec(icon)?.let { sp -> icon = if (sp.glyph) AppLook.glyphSpec(sp.value, c) else AppLook.emojiSpec(sp.value, c) }
                paintPreview()
            }
        }
    }, LinearLayout.LayoutParams(-1, -2))
    // ~100 glyphs + ~100 emoji: horizontal pages of 6x3 (iOS / Gboard emoji panels), a page is built only when it comes
    // into view (owner: "don't render every icon at once"); the pages' height is fixed so nothing jumps
    val pagers = ArrayList<PagedGrid>()
    sh.body.addView(text(getString(R.string.hm2_icon_glyphs), 13f, pal.sub, true).apply { setPadding(dp(12), dp(10), dp(12), 0) })
    pagers += PagedGrid(this, AppLook.GLYPHS, cell, sz + 2 * gap, pal.sub, pal.text) { g, vals -> fill(g, true, vals); paintPreview() }.also { sh.body.addView(it.root) }
    sh.body.addView(text(getString(R.string.hm2_icon_emoji), 13f, pal.sub, true).apply { setPadding(dp(12), dp(8), dp(12), 0) })
    pagers += PagedGrid(this, AppLook.EMOJIS, cell, sz + 2 * gap, pal.sub, pal.text) { g, vals -> fill(g, false, vals); paintPreview() }.also { sh.body.addView(it.root) }
    // any emoji the phone can draw, picked with the keyboard's own emoji panel + search (Gboard, Samsung, ...): a bundled
    // full picker would need ~3,600 emoji with vi + en names; the keyboard already has them, up to date
    sh.body.addView(text(getString(R.string.hm2_emoji_more), 15f, pal.accentText, true).apply {
        setPadding(dp(14), dp(12), dp(14), dp(12)); Ic.lead(this, R.drawable.li_plus, pal.accentText); compoundDrawablePadding = dp(8)
        kit.tap(this, 12f) { emojiInput { e -> choose(AppLook.emojiSpec(e, color)); sh.body.post { sh.body.parent?.let { (it as? android.widget.ScrollView)?.smoothScrollTo(0, 0) } } } }
    })
    paintPreview()
    sh.body.viewTreeObserver.addOnDrawListener(object : android.view.ViewTreeObserver.OnDrawListener {
        var done = false
        override fun onDraw() {
            if (done) return
            done = true
            val me = this
            sh.body.post {
                runCatching { sh.body.viewTreeObserver.removeOnDrawListener(me) }
                // first page of each on two frames (~6 ms each on Oppo A5s); the rest when swiped to
                val tf = Perf.now()
                pagers[0].ensure(0)
                Perf.log("appEdit glyphs p0", Perf.now() - tf)
                sh.body.postOnAnimation {
                    val te = Perf.now()
                    pagers[1].ensure(0)
                    Perf.log("appEdit emoji p0", Perf.now() - te)
                }
            }
        }
    })

    val built = Perf.now()
    sh.show()
    Perf.firstDraw(sh.body, "appEdit", t0, built)
}

/** The first emoji typed (keyboard emoji panel), checked to be one the phone draws; anything else = a short hint. */
private fun HomeActivity.emojiInput(done: (String) -> Unit) {
    val f = EditText(this).apply {
        hint = getString(R.string.hm2_emoji_hint); setTextColor(u.text); setHintTextColor(u.sub); textSize = 28f; gravity = Gravity.CENTER; setSingleLine()
        background = u.shape(u.bg, 14, u.stroke, 1); setPadding(dp(14), dp(10), dp(14), dp(10))
    }
    Modal(this, u).show(null, Modal.Tone.INFO, getString(R.string.hm2_emoji_more), getString(R.string.hm2_emoji_body), f, getString(R.string.hm_t_save), getString(R.string.cancel)) {
        val e = AppLook.firstEmoji(f.text.toString())
        if (e == null || !android.graphics.Paint().hasGlyph(e)) { f.error = getString(R.string.hm2_emoji_bad); false } else { done(e); true }
    }
}

/**
 * Horizontal pages of [cols] x [rows] cells over [values], snapping page by page, with dots under them. Page i's views
 * are built by [fill] only once page i (or its neighbour) is shown.
 */
private class PagedGrid(act: HomeActivity, val values: List<String>, cell: Int, rowH: Int, dotOff: Int, dotOn: Int,
                        val fill: (GridLayout, List<String>) -> Unit) {
    val per = 18
    val count = (values.size + per - 1) / per
    private val grids = ArrayList<GridLayout>()
    private val built = HashSet<Int>()
    private val dots = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
    private val dotOff = dotOff; private val dotOn = dotOn
    private var cur = 0
    private val pageW = cell * 6
    val scroll = object : android.widget.HorizontalScrollView(act) {
        override fun fling(velocityX: Int) { isFlinging = true; go(if (velocityX > 0) cur + 1 else if (velocityX < 0) cur - 1 else cur) }
        override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
            val r = super.onTouchEvent(e)
            if (e.actionMasked == android.view.MotionEvent.ACTION_UP || e.actionMasked == android.view.MotionEvent.ACTION_CANCEL)
                post { if (!isFlinging) go(Math.round(scrollX.toFloat() / pageW)); isFlinging = false }   // a fling already picked its page
            return r
        }
        var isFlinging = false
        override fun onScrollChanged(l: Int, t: Int, ol: Int, ot: Int) {
            super.onScrollChanged(l, t, ol, ot)
            // the page coming into view (and the one after it) are built while the finger is still moving
            val p = l / pageW; ensure(p); if (l % pageW != 0) ensure(p + 1)
        }
    }
    val root = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }

    init {
        scroll.isHorizontalScrollBarEnabled = false; scroll.overScrollMode = View.OVER_SCROLL_NEVER
        val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        for (i in 0 until count) {
            val g = GridLayout(act).apply { columnCount = 6 }
            grids += g
            row.addView(g, LinearLayout.LayoutParams(pageW, rowH * 3))
        }
        scroll.addView(row)
        root.addView(scroll, LinearLayout.LayoutParams(pageW, -2).apply { gravity = Gravity.CENTER_HORIZONTAL })
        if (count > 1) {
            val d = act.resources.displayMetrics.density
            for (i in 0 until count) dots.addView(View(act).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (i == 0) dotOn else dotOff) }
                alpha = if (i == 0) 1f else 0.5f
            }, LinearLayout.LayoutParams((6 * d).toInt(), (6 * d).toInt()).apply { setMargins((4 * d).toInt(), (6 * d).toInt(), (4 * d).toInt(), (2 * d).toInt()) })
            root.addView(dots, LinearLayout.LayoutParams(-1, -2))
        }
    }

    fun ensure(p: Int) {
        if (p !in 0 until count || !built.add(p)) return
        fill(grids[p], values.subList(p * per, minOf(values.size, (p + 1) * per)))
    }

    fun go(p: Int) {
        cur = p.coerceIn(0, count - 1)
        ensure(cur)
        scroll.smoothScrollTo(cur * pageW, 0)
        for (i in 0 until dots.childCount) dots.getChildAt(i).apply {
            (background as GradientDrawable).setColor(if (i == cur) dotOn else dotOff); alpha = if (i == cur) 1f else 0.5f
        }
    }
}
