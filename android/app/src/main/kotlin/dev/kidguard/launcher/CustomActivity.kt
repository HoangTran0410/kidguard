package dev.kidguard

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Kid launcher "Tuỳ chỉnh" (long-press the home screen): style preset, grid, icons, labels, wallpaper (built-in or the
 * kid's photo), accent, light / dark, dock background, text size. Writes home.json `style` / `grid` (HomeFile); the
 * home screen reloads on resume. No PIN: the parent can lock it with rule `launcher.lockCustom` (then read-only + a note).
 */
class CustomActivity : Activity() {
    private var m: HomeModel? = null
    private lateinit var k: HomeKit
    private lateinit var pal: Palette
    private lateinit var scroll: ScrollView
    private var wallX = 0                                  // the wallpaper row's own scroll, kept across rebuilds
    private val thumbs = HashMap<String, Bitmap>()         // built-in wallpaper tiles, drawn once
    private var undo: HomeModel? = null
    private var busy = false
    private val locked get() = Store.of(this).rules.launcher.lockCustom
    private fun dp(v: Int) = k.dp(v)
    private fun s(id: Int, vararg a: Any) = getString(id, *a)

    override fun getResources(): android.content.res.Resources = Lang.res(baseContext) ?: super.getResources()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        m = HomeFile.load(this)
        if (m == null) { finish(); return }
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        build()
    }

    private var dotsNeed = false
    override fun onResume() {
        super.onResume()
        if (m != null && HomeFile.load(this) !== m) { m = HomeFile.load(this) ?: m; build() }
        else if (dotsNeed && NotifDots.granted(this)) { NotifDots.setOn(this, true); build() }   // back from the access screen
    }

    private fun isDark(st: HStyle) = st.theme == "dark" || (st.theme == "auto" &&
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES)

    private fun build() {
        val mm = m ?: return
        val st = mm.style
        val preset = HomeStyle.preset(st.preset)
        val dark = isDark(st)
        pal = HomeStyle.palette(st.accent.ifEmpty { if (dark) preset.accentDark else preset.accentLight }, dark)
        k = HomeKit(this, pal)
        window.statusBarColor = pal.bg; window.navigationBarColor = pal.bg
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (dark) 0 else (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or if (Build.VERSION.SDK_INT >= 26) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0)
        // One ScrollView for the activity's life: a rebuild swaps only its content, so the page never jumps back to the
        // top for a frame (the old setContentView + post(scrollTo) flashed on every tap, e.g. picking a wallpaper).
        val first = !::scroll.isInitialized
        if (first) scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        scroll.setBackgroundColor(pal.bg)
        scroll.removeAllViews()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(28)) }
        scroll.addView(col)
        // header: back + title
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(ImageView(this).apply {
            setImageDrawable(Ic.get(this@CustomActivity, R.drawable.li_arrow_left, pal.text, dp(24))); setPadding(dp(10), dp(10), dp(10), dp(10))
            contentDescription = s(R.string.hm_c_back); k.tap(this, -1f) { finish() }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        head.addView(k.text(s(R.string.hm_customize), 24f, pal.text, true).apply { setPadding(dp(8), 0, 0, 0) })
        col.addView(head, LinearLayout.LayoutParams(-1, -2))
        if (locked) col.addView(note(s(R.string.hm_c_locked), true), lp(10))
        // live preview
        val pv = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        pv.addView(Preview(mm), LinearLayout.LayoutParams(dp(96), dp(176)))
        pv.addView(k.text(s(R.string.hm_c_preview, mm.cols, mm.rows, s(if (st.shape == "square") R.string.hm_c_square else R.string.hm_c_round),
            s(if (st.labels) R.string.hm_c_with_labels else R.string.hm_c_no_labels)), 14f, pal.sub).apply { setPadding(dp(16), 0, 0, 0); includeFontPadding = true; setLineSpacing(0f, 1.2f) },
            LinearLayout.LayoutParams(0, -2, 1f))
        col.addView(pv, lp(10))

        section(col, R.string.hm_c_style)
        val pr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((i, p) in HomeStyle.PRESETS.withIndex()) pr.addView(presetCard(p, p.id == st.preset), LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) leftMargin = dp(8) })
        col.addView(pr, lp(8))

        section(col, R.string.hm_c_home)
        val home = group()
        home.addView(stepper(s(R.string.hm_c_cols), mm.cols, 3, 5) { regrid(it, mm.rows) })
        line(home); home.addView(stepper(s(R.string.hm_c_rows), mm.rows, 4, HomeLayout.MAX_ROWS) { regrid(mm.cols, it, manual = true) })
        line(home); home.addView(slider(s(R.string.hm_c_icon), st.icon - 1, 4, s(R.string.hm_c_small), s(R.string.hm_c_big)) { v -> set { it.copy(icon = v + 1) } })
        line(home); home.addView(switchRow(s(R.string.hm_c_labels), st.labels) { v -> set { it.copy(labels = v) } })
        line(home); home.addView(segRow(s(R.string.hm_c_face), HomeActivity.FACES.zip(listOf(R.string.hm_c_face_sys, R.string.hm_c_face_light, R.string.hm_c_face_medium, R.string.hm_c_face_condensed, R.string.hm_c_face_serif)).map { (f, r) -> s(r) to f }, st.face) { v -> set { it.copy(face = v) } })
        line(home); home.addView(segRow(s(R.string.hm_c_shape), listOf(s(R.string.hm_c_round) to "round", s(R.string.hm_c_square) to "square"), st.shape) { v -> set { it.copy(shape = v) } })
        line(home); home.addView(switchRow(s(R.string.hm_c_dock), st.dockBg) { v -> set { it.copy(dockBg = v) } })
        line(home); home.addView(switchRow(s(R.string.hm_c_dock_labels), st.dockLabels) { v -> set { it.copy(dockLabels = v) } })
        line(home); home.addView(switchRow(s(R.string.hm_c_today), st.today) { v -> set { it.copy(today = v) } })
        line(home); home.addView(switchRow(s(R.string.hm_c_library), st.lib) { v -> set { it.copy(lib = v) } })
        line(home); home.addView(segRow(s(R.string.hm_c_fx), listOf(s(R.string.hm_c_fx_full) to "full", s(R.string.hm_c_fx_lite) to "lite", s(R.string.hm_c_auto) to "auto"), st.fx) { v -> set { it.copy(fx = v) } })
        line(home); home.addView(segRow(s(R.string.hm_c_dots), listOf(s(R.string.hm_c_badge_off) to "off", s(R.string.hm_c_badge_dot) to "dot", s(R.string.hm_c_badge_num) to "num"), st.badge) { v ->
            set { it.copy(badge = v) }; NotifDots.setOn(this, v != "off")
        })
        // no access (a phone set up before 0.9.8): a tap opens the system screen; setup grants it on new phones
        dotsNeed = st.badge != "off" && !NotifDots.granted(this)
        if (dotsNeed) home.addView(note(s(R.string.hm_c_dots_need), false).apply {
            setPadding(dp(4), 0, dp(4), dp(12))
            k.tap(this, 12f) { runCatching { startActivity(android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) } }
        })
        col.addView(home, lp(8))
        undo?.let { u0 ->
            col.addView(k.pill(s(R.string.hm_c_undo), R.drawable.li_rotate_ccw, false) { if (!guard()) return@pill; save(u0); undo = null; build() },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }

        section(col, R.string.hm_c_wall)
        val ws = object : HorizontalScrollView(this) {
            private var placed = false
            override fun onScrollChanged(l: Int, t: Int, ol: Int, ot: Int) { super.onScrollChanged(l, t, ol, ot); if (placed) wallX = l }
            override fun onLayout(ch: Boolean, l: Int, t: Int, r: Int, b: Int) { super.onLayout(ch, l, t, r, b); if (!placed) { placed = true; scrollTo(wallX, 0) } }   // before the first draw
        }.apply { isHorizontalScrollBarEnabled = false; clipToPadding = false }
        val wr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        wr.addView(pickTile(st.wallKind == "photo"), LinearLayout.LayoutParams(dp(64), dp(112)))
        val cur = if (st.wallKind == "photo") "" else st.wall.ifEmpty { if (dark) preset.wallDark else preset.wallLight }
        for (w in HomeStyle.WALLS) wr.addView(wallTile(w, w == cur), LinearLayout.LayoutParams(dp(64), dp(112)).apply { leftMargin = dp(8) })
        ws.addView(wr)
        col.addView(ws, lp(8))
        // POCO-style: blur and darken any wallpaper, so the icons and labels stand out
        val fx = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = k.sheetCard(18f); setPadding(dp(16), dp(4), dp(16), dp(4)) }
        fx.addView(slider(s(R.string.hm_c_blur), st.blur / 20, 5, "0", "100%") { v -> set { it.copy(blur = v * 20) } })
        fx.addView(slider(s(R.string.hm_c_dim), st.dim / 10, 6, "0", "60%") { v -> set { it.copy(dim = v * 10) } })
        col.addView(fx, lp(8))

        section(col, R.string.hm_c_accent)
        val ar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val curAccent = st.accent.ifEmpty { if (dark) preset.accentDark else preset.accentLight }
        for (a in HomeStyle.ACCENTS) ar.addView(swatch(a, a.id == curAccent, dark), LinearLayout.LayoutParams(0, dp(44), 1f))
        col.addView(ar, lp(8))

        section(col, R.string.hm_c_theme)
        col.addView(seg(listOf(s(R.string.hm_c_light) to "light", s(R.string.hm_c_dark) to "dark", s(R.string.hm_c_auto) to "auto"), st.theme,
            listOf(R.drawable.li_sun, R.drawable.li_moon, R.drawable.li_smartphone_auto)) { v -> set { it.copy(theme = v) } }, lp(8))

        section(col, R.string.hm_c_text)
        val fonts = listOf(0.85, 1.0, 1.15, 1.3)
        val fi = fonts.indexOfFirst { kotlin.math.abs(it - st.font) < 0.01 }.let { if (it < 0) 1 else it }
        col.addView(group().apply { addView(slider(s(R.string.hm_c_font), fi, 3, "A", "A", bigEnd = true) { v -> set { it.copy(font = fonts[v]) } }) }, lp(8))

        if (!locked) col.addView(note(s(R.string.hm_c_lock_hint), false), lp(14))
        col.addView(k.pill(s(R.string.hm_c_reset), R.drawable.li_rotate_ccw, false) { if (guard()) applyPreset(HomeStyle.preset(st.preset)) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        if (first) setContentView(scroll)   // else: scrollY is untouched by removeAllViews and clamped on the next layout
    }

    // ---- model edits ----

    /** false (and a toast) when the parent locked Tuỳ chỉnh. */
    private fun guard(): Boolean {
        if (!locked) return true
        Toast.makeText(this, R.string.hm_c_locked_short, Toast.LENGTH_SHORT).show()
        return false
    }

    private fun save(n: HomeModel) { HomeFile.save(this, n); m = n; IconCache.clear() }

    private fun set(f: (HStyle) -> HStyle) {
        if (!guard()) { build(); return }
        val mm = m ?: return
        val ns = f(mm.style)
        if (ns == mm.style) return
        save(mm.copy(style = ns)); build()
    }

    /** [manual] = the kid set the rows: "auto" (rows follow the screen) is off until a style preset is picked again. */
    private fun regrid(c: Int, r: Int, manual: Boolean = false) {
        if (!guard()) return
        val mm = m ?: return
        val n = HomeLayout.regrid(mm, c, r).copy(autoRows = mm.autoRows && !manual)
        if (n.cols == mm.cols && n.rows == mm.rows) return
        // a smaller grid moves things: offer "Hoàn tác" until the next change
        undo = if (c < mm.cols || r < mm.rows) mm else null
        save(n); build()
    }

    private fun applyPreset(p: HomeStyle.Preset) {
        val mm = m ?: return
        val g = HomeLayout.regrid(mm, p.cols, p.rows)
        undo = if (p.cols < mm.cols || p.rows < mm.rows) mm else null
        save(g.copy(autoRows = true, style = HStyle(preset = p.id, labels = p.labels, dockBg = p.dockBg, theme = mm.style.theme)))
        build()
    }

    // ---- controls (colours: HomeStyleTest "part 2 controls") ----

    private fun lp(top: Int) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }

    private fun section(col: LinearLayout, id: Int) =
        col.addView(k.text(s(id).uppercase(), 12f, pal.sub, true).apply { letterSpacing = 0.08f; setPadding(dp(4), 0, 0, 0); if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true }, lp(22))

    private fun group() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = k.sheetCard(20f); setPadding(dp(16), dp(2), dp(16), dp(2)) }
    private fun line(g: LinearLayout) = g.addView(View(this).apply { setBackgroundColor(pal.stroke) }, LinearLayout.LayoutParams(-1, dp(1)))

    private fun row(label: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(60)
        addView(k.text(label, 16f, pal.text), LinearLayout.LayoutParams(0, -2, 1f))
    }

    private fun note(t: String, strong: Boolean) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        if (strong) { background = k.fill(pal.warnTint, 16f); setPadding(dp(14), dp(12), dp(14), dp(12)) } else setPadding(dp(4), 0, dp(4), 0)
        addView(ImageView(context).apply { setImageDrawable(Ic.get(context, R.drawable.li_lock, if (strong) pal.warnText else pal.sub, dp(16))); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, LinearLayout.LayoutParams(dp(16), dp(16)))
        addView(k.text(t, 13f, if (strong) pal.warnText else pal.sub).apply { setPadding(dp(10), 0, 0, 0); includeFontPadding = true }, LinearLayout.LayoutParams(0, -2, 1f))
    }

    private fun stepper(label: String, v: Int, min: Int, max: Int, on: (Int) -> Unit) = row(label).apply {
        fun btn(icon: Int, enabled: Boolean, desc: String, d: Int) = ImageView(context).apply {
            setImageDrawable(Ic.get(context, icon, if (enabled) pal.onTint else pal.sub, dp(20)))
            background = k.fill(if (enabled) pal.tint else pal.bg, 12f); setPadding(dp(10), dp(10), dp(10), dp(10)); contentDescription = desc
            k.tap(this, 12f) { if (enabled) on(v + d) }
        }
        addView(btn(R.drawable.li_minus, v > min, s(R.string.hm_c_less, label), -1), LinearLayout.LayoutParams(dp(40), dp(40)))
        addView(k.text(v.toString(), 18f, pal.text, true).apply { gravity = Gravity.CENTER; minWidth = dp(40) }, LinearLayout.LayoutParams(-2, -2))
        addView(btn(R.drawable.li_plus, v < max, s(R.string.hm_c_more, label), 1), LinearLayout.LayoutParams(dp(40), dp(40)))
    }

    private fun slider(label: String, v: Int, max: Int, lo: String, hi: String, bigEnd: Boolean = false, on: (Int) -> Unit) = row(label).apply {
        addView(k.text(lo, 12f, pal.sub))
        addView(SeekBar(context).apply {
            this.max = max; progress = v; contentDescription = label
            progressTintList = ColorStateList.valueOf(pal.accentText); thumbTintList = ColorStateList.valueOf(pal.accentText)
            progressBackgroundTintList = ColorStateList.valueOf(pal.sub)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, user: Boolean) {}
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) { if (sb.progress != v) on(sb.progress) }
            })
        }, LinearLayout.LayoutParams(dp(150), -2))
        addView(k.text(hi, if (bigEnd) 20f else 12f, if (bigEnd) pal.text else pal.sub, bigEnd))
    }

    private fun switchRow(label: String, v: Boolean, on: (Boolean) -> Unit) = row(label).apply {
        val sw = Switch(context).apply {
            isChecked = v; contentDescription = label
            thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(pal.accentText, pal.sheet))
            trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(pal.accentText, pal.sub))
            setOnCheckedChangeListener { _, c -> if (c != v) on(c) }
        }
        addView(sw)
        k.tap(this, 12f) { sw.toggle() }
    }

    private fun seg(items: List<Pair<String, String>>, cur: String, icons: List<Int>? = null, on: (String) -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; background = k.sheetCard(16f); setPadding(dp(4), dp(4), dp(4), dp(4))
        for ((i, it) in items.withIndex()) {
            val sel = it.second == cur
            addView(k.text(it.first, 14f, if (sel) pal.onSolid else pal.text, sel).apply {
                gravity = Gravity.CENTER; setPadding(dp(8), dp(11), dp(8), dp(11))
                if (sel) background = k.fill(pal.solid, 12f)
                icons?.getOrNull(i)?.let { ic -> Ic.lead(this, ic, if (sel) pal.onSolid else pal.sub); compoundDrawablePadding = dp(6) }
                k.tap(this, 12f, sel || pal.dark) { if (!sel) on(it.second) }
                contentDescription = it.first + if (sel) ", " + s(R.string.hm_c_selected) else ""
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
    }

    /**
     * A select row (Android's list preference / Material's exposed dropdown): label, the current value and a chevron;
     * a tap drops the choices below it. Chips beside the label did not fit at a 1.5 font ("Chấm" was cut).
     */
    private fun segRow(label: String, items: List<Pair<String, String>>, cur: String, on: (String) -> Unit) = row(label).apply {
        val curLabel = items.firstOrNull { it.second == cur }?.first ?: items.first().first
        val value = k.text(curLabel, 15f, pal.accentText, true).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.END; maxLines = 1
            setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, Ic.get(context, R.drawable.li_chevron_up, pal.sub, dp(18)).let { d ->
                android.graphics.drawable.RotateDrawable().apply { drawable = d; fromDegrees = 180f; toDegrees = 180f; level = 10000 } }, null)
            compoundDrawablePadding = dp(6); setPadding(dp(12), dp(8), dp(4), dp(8))
        }
        addView(value, LinearLayout.LayoutParams(-2, -2))
        contentDescription = "$label, $curLabel"
        k.tap(this, 12f) { dropdown(this, items, cur, on) }
    }

    private fun dropdown(anchor: View, items: List<Pair<String, String>>, cur: String, on: (String) -> Unit) {
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = k.sheetCard(16f); setPadding(0, dp(6), 0, dp(6)); elevation = k.dpf(8f) }
        val pop = android.widget.PopupWindow(list, (anchor.width * 0.6f).toInt().coerceAtLeast(dp(200)), -2, true).apply {
            elevation = k.dpf(8f); setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)); isOutsideTouchable = true
        }
        for ((t, id) in items) {
            val sel = id == cur
            list.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(52); setPadding(dp(16), 0, dp(14), 0)
                addView(k.text(t, 16f, if (sel) pal.accentText else pal.text, sel), LinearLayout.LayoutParams(0, -2, 1f))
                if (sel) addView(ImageView(context).apply { setImageDrawable(Ic.get(context, R.drawable.li_check, pal.accentText, dp(20))) }, LinearLayout.LayoutParams(dp(20), dp(20)))
                contentDescription = t + if (sel) ", " + s(R.string.hm_c_selected) else ""
                k.tap(this, 0f) { pop.dismiss(); if (!sel) on(id) }
            }, LinearLayout.LayoutParams(-1, -2))
        }
        pop.showAsDropDown(anchor, anchor.width - pop.width, -dp(6))
    }

    private fun presetCard(p: HomeStyle.Preset, sel: Boolean) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(8), dp(10), dp(8), dp(10))
        background = GradientDrawable().apply { setColor(pal.sheet); cornerRadius = k.dpf(18f); setStroke(dp(if (sel) 2 else 1), if (sel) pal.accentText else pal.stroke) }
        addView(object : View(context) {
            // decorative cells: the accent at half strength on its tint (mock), the label below carries the meaning
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.accentText; alpha = 120 }
            val bg = Paint().apply { color = pal.tint }
            override fun onDraw(c: Canvas) {
                c.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), k.dpf(10f), k.dpf(10f), bg)
                val pad = k.dpf(6f); val gap = k.dpf(3f)
                val cw = (width - 2 * pad - (p.cols - 1) * gap) / p.cols; val ch = (height - 2 * pad - (p.rows - 1) * gap) / p.rows
                for (y in 0 until p.rows) for (x in 0 until p.cols) {
                    val l = pad + x * (cw + gap); val t = pad + y * (ch + gap)
                    c.drawRoundRect(l, t, l + cw, t + ch, k.dpf(4f), k.dpf(4f), paint)
                }
            }
        }, LinearLayout.LayoutParams(-1, dp(76)))
        val name = s(when (p.id) { "be" -> R.string.hm_c_be; "gon" -> R.string.hm_c_gon; else -> R.string.hm_c_teen })
        addView(k.text(name, 15f, pal.text, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0) })
        addView(k.text(s(R.string.hm_c_grid, p.cols, p.rows), 12f, pal.sub).apply { gravity = Gravity.CENTER; setPadding(0, dp(2), 0, 0) })
        contentDescription = name + if (sel) ", " + s(R.string.hm_c_selected) else ""
        k.tap(this, 18f) { if (guard()) applyPreset(p) }
    }

    private fun wallTile(id: String, sel: Boolean) = FrameLayout(this).apply {
        val bmp = thumbs.getOrPut(id) { Wallpaper.draw(id, dp(64), dp(112)) }
        addView(object : View(context) {
            override fun onDraw(c: Canvas) {
                val path = android.graphics.Path().apply { addRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), k.dpf(14f), k.dpf(14f), android.graphics.Path.Direction.CW) }
                c.save(); c.clipPath(path); c.drawBitmap(bmp, 0f, 0f, null); c.restore()
                if (sel) c.drawRoundRect(k.dpf(1.5f), k.dpf(1.5f), width - k.dpf(1.5f), height - k.dpf(1.5f), k.dpf(13f), k.dpf(13f),
                    Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = k.dpf(3f); color = pal.accentText })
            }
        }, FrameLayout.LayoutParams(-1, -1))
        if (sel) addView(k.badge(R.drawable.li_check, pal.onSolid, pal.solid, 24, 14), FrameLayout.LayoutParams(dp(24), dp(24), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(6), dp(6), 0) })
        contentDescription = s(R.string.hm_c_wall_n, HomeStyle.WALLS.indexOf(id) + 1) + if (sel) ", " + s(R.string.hm_c_selected) else ""
        k.tap(this, 14f) { set { it.copy(wallKind = "builtin", wall = id) } }
    }

    private fun pickTile(sel: Boolean) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
        background = GradientDrawable().apply { setColor(pal.sheet); cornerRadius = k.dpf(14f); setStroke(dp(if (sel) 3 else 1), if (sel) pal.accentText else pal.stroke) }
        addView(ImageView(context).apply { setImageDrawable(Ic.get(context, R.drawable.li_image_plus, pal.accentText, dp(22))); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, LinearLayout.LayoutParams(dp(22), dp(22)))
        addView(k.text(s(if (busy) R.string.hm_c_picking else R.string.hm_c_pick), 12f, pal.accentText, true).apply { gravity = Gravity.CENTER; setPadding(dp(2), dp(6), dp(2), 0) })
        contentDescription = s(R.string.hm_c_pick) + if (sel) ", " + s(R.string.hm_c_selected) else ""
        k.tap(this, 14f) { if (guard() && !busy) pick() }
    }

    private fun swatch(a: Accent, sel: Boolean, dark: Boolean) = FrameLayout(this).apply {
        val sp = HomeStyle.palette(a.id, dark)
        addView(View(context).apply {
            // dark: the swatch carries a ring of its own text colour so it stands out from the page (HomeStyleTest "swatch")
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(sp.solid); if (dark) setStroke(dp(2), sp.accentText) }
        }, FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER))
        if (sel) {
            addView(View(context).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke(dp(2), pal.text) } }, FrameLayout.LayoutParams(dp(42), dp(42), Gravity.CENTER))
            addView(ImageView(context).apply { setImageDrawable(Ic.get(context, R.drawable.li_check, sp.onSolid, dp(18))) }, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
        }
        contentDescription = s(R.string.hm_c_accent_n, HomeStyle.ACCENTS.indexOf(a) + 1) + if (sel) ", " + s(R.string.hm_c_selected) else ""
        k.tap(this, -1f) { set { it.copy(accent = a.id) } }
    }

    /** The home screen in small: wallpaper, page 1's widgets as cards, apps as icon shapes, the dock. */
    private inner class Preview(val mm: HomeModel) : View(this@CustomActivity) {
        private val st = mm.style
        private var bmp: Bitmap? = null
        private val pFill = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            bmp?.recycle()
            val dark = isDark(st); val pre = HomeStyle.preset(st.preset)
            bmp = Wallpaper.blur((if (st.wallKind == "photo") Wallpaper.photo(context, w, h) else null) ?: Wallpaper.draw(st.wall.ifEmpty { if (dark) pre.wallDark else pre.wallLight }, w, h), st.blur)
            bmp?.let { Wallpaper.dim(it, st.dim) }
        }
        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat(); val r = k.dpf(14f)
            val clip = android.graphics.Path().apply { addRoundRect(RectF(0f, 0f, w, h), r, r, android.graphics.Path.Direction.CW) }
            c.save(); c.clipPath(clip)
            bmp?.let { c.drawBitmap(it, 0f, 0f, null) }
            val side = w * 0.06f; val top = h * 0.08f; val dockH = h * 0.12f
            val gw = (w - 2 * side) / mm.cols; val gh = (h - top - dockH - h * 0.06f) / mm.rows
            for (it in mm.pages.firstOrNull() ?: emptyList()) {
                val l = side + it.x * gw + gw * 0.08f; val t = top + it.y * gh + gh * 0.1f
                val rr = l + it.w * gw - gw * 0.16f; val b = t + it.h * gh - gh * 0.2f
                if (it.kind == "widget") { pFill.color = pal.card; c.drawRoundRect(l, t, rr, b, k.dpf(6f), k.dpf(6f), pFill) }
                else {
                    pFill.color = if (it.kind == "folder") pal.card else pal.solid
                    val s0 = minOf(rr - l, b - t) * (0.6f + 0.1f * st.icon); val cx = (l + rr) / 2; val cy = (t + b) / 2
                    val rad = if (st.shape == "square") s0 * 0.25f else s0 / 2
                    c.drawRoundRect(cx - s0 / 2, cy - s0 / 2, cx + s0 / 2, cy + s0 / 2, rad, rad, pFill)
                }
            }
            if (st.dockBg) { pFill.color = pal.card; c.drawRoundRect(side, h - dockH - h * 0.03f, w - side, h - h * 0.03f, k.dpf(10f), k.dpf(10f), pFill) }
            c.restore()
        }
    }

    // ---- "Chọn ảnh": the system photo picker (no storage permission), downscaled once to the screen, ~300 KB ----

    private fun pick() {
        val i = if (Build.VERSION.SDK_INT >= 33) Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*")
        else Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")
        runCatching { startActivityForResult(i, 7) }.recoverCatching { startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*"), 7) }
            .onFailure { Toast.makeText(this, R.string.hm_c_pick_fail, Toast.LENGTH_LONG).show() }
    }

    @Deprecated("fine on API 26-34 for one system picker")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        val uri = data?.data
        if (req != 7 || res != RESULT_OK || uri == null) return
        busy = true; build()
        val dm = resources.displayMetrics
        val sw = minOf(dm.widthPixels, 1080); val sh = (sw.toLong() * dm.heightPixels / dm.widthPixels).toInt()
        Thread {
            val ok = runCatching { savePhoto(uri, sw, sh) }.getOrDefault(false)
            runOnUiThread {
                busy = false
                if (ok) set { it.copy(wallKind = "photo", wall = "", dim = if (it.wallKind == "photo") it.dim else 10) }
                else { Toast.makeText(this, R.string.hm_c_pick_fail, Toast.LENGTH_LONG).show(); build() }
            }
        }.start()
    }

    private fun savePhoto(uri: android.net.Uri, w: Int, h: Int): Boolean {
        val cr = contentResolver
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) } ?: return false
        if (o.outWidth <= 0 || o.outHeight <= 0) return false
        val rot = runCatching { cr.openInputStream(uri)?.use { android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1) } }.getOrNull() ?: 1
        val deg = when (rot) { 6 -> 90; 3 -> 180; 8 -> 270; else -> 0 }
        val (iw, ih) = if (deg % 180 == 0) o.outWidth to o.outHeight else o.outHeight to o.outWidth
        var sample = 1
        while (iw / (sample * 2) >= w && ih / (sample * 2) >= h) sample *= 2
        val src = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return false
        val rotated = if (deg == 0) src else Bitmap.createBitmap(src, 0, 0, src.width, src.height, Matrix().apply { postRotate(deg.toFloat()) }, true).also { src.recycle() }
        // centre-crop to the screen's shape, then scale to the screen
        val s = maxOf(w.toFloat() / rotated.width, h.toFloat() / rotated.height)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val dw = rotated.width * s; val dh = rotated.height * s
        Canvas(out).apply { drawColor(Color.BLACK); drawBitmap(rotated, null, RectF((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2), Paint(Paint.FILTER_BITMAP_FLAG)) }
        rotated.recycle()
        var q = 85; var bytes: ByteArray
        do { bytes = ByteArrayOutputStream().also { out.compress(Bitmap.CompressFormat.JPEG, q, it) }.toByteArray(); q -= 10 } while (bytes.size > 300 * 1024 && q >= 45)
        out.recycle()
        val tmp = File(filesDir, Wallpaper.PHOTO + ".tmp")
        tmp.writeBytes(bytes)
        return tmp.renameTo(File(filesDir, Wallpaper.PHOTO))
    }
}
