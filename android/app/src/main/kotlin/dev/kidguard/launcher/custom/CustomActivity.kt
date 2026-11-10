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
    private var undoPhoto = false                           // the undo also brings the previous wall.jpg back (wall.jpg.prev)
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
        else if (dotsNeed && NotifDots.granted(this, fresh = true)) { NotifDots.setOn(this, true); build() }   // back from the access screen
    }

    private fun isDark(st: HStyle) = st.theme == "dark" || (st.theme == "auto" &&
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES)

    private fun build() {
        if (isDestroyed || isFinishing) return   // the photo thread can finish after the screen is gone
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
        pv.addView(k.text(s(R.string.hm_c_preview, mm.cols, mm.rows, s(when (st.shape) { "square" -> R.string.hm_c_square; "soft" -> R.string.hm_c_soft; else -> R.string.hm_c_round }),
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
        // no rows stepper: rows always follow the screen (cells ~square, HomeLayout.fitRows); picking both made cells oblong
        line(home); home.addView(stepper(s(R.string.hm_c_icon), st.icon, 1, 5) { v -> set { it.copy(icon = v) } })   // 1..5 like "Số cột" (owner: a slider took a whole block)
        line(home); home.addView(switchRow(s(R.string.hm_c_labels), st.labels) { v -> set { it.copy(labels = v) } })
        line(home); home.addView(segRow(s(R.string.hm_c_face), HomeActivity.FACES.zip(listOf(R.string.hm_c_face_sys, R.string.hm_c_face_light, R.string.hm_c_face_medium, R.string.hm_c_face_condensed, R.string.hm_c_face_serif, R.string.hm2_c_face_easy)).map { (f, r) -> s(r) to f }, st.face) { v -> set { it.copy(face = v) } })
        line(home); home.addView(segRow(s(R.string.hm_c_shape), listOf(s(R.string.hm_c_round_opt) to "round", s(R.string.hm_c_soft_opt) to "soft", s(R.string.hm_c_square_opt) to "square"), st.shape) { v -> set { it.copy(shape = v) } })
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
            k.tap(this, 12f) { NotifDots.ask(this@CustomActivity) }
        })
        col.addView(home, lp(8))

        section(col, R.string.hm2_c_read)
        val read = group()
        read.addView(switchRow(s(R.string.hm2_c_speak), st.speak) { v -> set { it.copy(speak = v) } })
        line(read); read.addView(switchRow(s(R.string.hm2_c_calm), st.calm) { v -> set { it.copy(calm = v) } })
        line(read); read.addView(switchRow(s(R.string.hm2_c_solid), st.solid) { v -> set { it.copy(solid = v) } })
        col.addView(read, lp(8))
        undo?.let { u0 ->
            col.addView(k.pill(s(R.string.hm_c_undo), R.drawable.li_rotate_ccw, false) { if (!guard()) return@pill; if (undoPhoto) restorePhoto(); save(u0); undo = null; undoPhoto = false; build() },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }

        // first open: what fills the screen now, the sections below the fold right after the first frame (the whole form
        // was 86-139 ms before it on an Oppo A5s); a rebuild (a setting changed) builds it all at once, so nothing flashes
        val rest = {
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

            gestBackup(col)   // gestures + backup / restore (HomeGestures.kt, HomeBackup.kt)
            if (!locked && !Flavor.HOME) col.addView(note(s(R.string.hm_c_lock_hint), false), lp(14))
            col.addView(k.pill(s(R.string.hm_c_reset), R.drawable.li_rotate_ccw, false) { if (guard()) applyPreset(HomeStyle.preset(st.preset)) },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        }
        if (!first) rest() else {
            // after the first frame is drawn (a View.post before it still ran ahead of that frame: no gain, measured)
            val vto = window.decorView.viewTreeObserver
            vto.addOnDrawListener(object : android.view.ViewTreeObserver.OnDrawListener {
                var done = false
                override fun onDraw() {
                    if (done) return
                    done = true
                    val me = this
                    col.post { window.decorView.viewTreeObserver.removeOnDrawListener(me); if (!isDestroyed && scroll.getChildAt(0) === col) rest() }
                }
            })
        }
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
        undo = null; undoPhoto = false   // "until the next change"
        save(mm.copy(style = ns)); build()
    }

    /** Columns only: the home recomputes the rows for the new cell width (autoRows) once it is shown again. */
    private fun regrid(c: Int, r: Int) {
        if (!guard()) return
        val mm = m ?: return
        val n = HomeLayout.regrid(mm, c, r).copy(autoRows = true)
        if (n.cols == mm.cols && n.rows == mm.rows) return
        // a smaller grid moves things: offer "Hoàn tác" until the next change
        undo = if (c < mm.cols || r < mm.rows) mm else null; undoPhoto = false
        save(n); build()
    }

    private fun applyPreset(p: HomeStyle.Preset) {
        val mm = m ?: return
        val g = HomeLayout.regrid(mm, p.cols, p.rows)
        undoPhoto = false
        undo = mm   // a preset (and Reset) replaces the whole style: always offer "Hoàn tác" until the next change
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

    // label above, the bar full width under it (Android Settings): a 150 dp bar beside the label squeezed
    // "Cỡ biểu tượng" against "Nhỏ" at font 1.3
    private fun slider(label: String, v: Int, max: Int, lo: String, hi: String, bigEnd: Boolean = false, on: (Int) -> Unit): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(0, dp(12), 0, dp(6))
        addView(k.text(label, 16f, pal.text))
        addView(sliderBar(label, v, max, lo, hi, bigEnd, on), LinearLayout.LayoutParams(-1, -2))
    }

    private fun sliderBar(label: String, v: Int, max: Int, lo: String, hi: String, bigEnd: Boolean, on: (Int) -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(44)
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
        }, LinearLayout.LayoutParams(0, -2, 1f))
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
    private fun segRow(label: String, items: List<Pair<String, String>>, cur: String, on: (String) -> Unit) = StackRow(this).apply {
        minimumHeight = dp(60)
        addView(k.text(label, 16f, pal.text), LinearLayout.LayoutParams(0, -2, 1f))
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
                    val rad = when (st.shape) { "square" -> s0 * 0.25f; "soft" -> s0 * 0.4f; else -> s0 / 2 }
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
        if ((req == 8 || req == 9) && res == RESULT_OK && uri != null) { backupResult(req, uri); return }
        if (req != 7 || res != RESULT_OK || uri == null) return
        busy = true; build()
        val dm = resources.displayMetrics
        val sw = minOf(dm.widthPixels, 1080); val sh = (sw.toLong() * dm.heightPixels / dm.widthPixels).toInt()
        Thread {
            val ok = runCatching { savePhoto(uri, sw, sh) }.getOrDefault(false)
            runOnUiThread {
                busy = false
                if (ok) {
                    val before = m
                    set { it.copy(wallKind = "photo", wall = "", dim = if (it.wallKind == "photo") it.dim else 10) }
                    // the old photo (wall.jpg.prev) and style stay one tap away
                    if (before != null && File(filesDir, Wallpaper.PHOTO + ".prev").exists()) { undo = before; undoPhoto = true; build() }
                }
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
        val cur = File(filesDir, Wallpaper.PHOTO); val prev = File(filesDir, Wallpaper.PHOTO + ".prev")
        if (cur.exists()) { if (!cur.renameTo(prev)) prev.delete() } else prev.delete()   // no photo before: nothing to undo to
        if (tmp.renameTo(cur)) return true
        if (prev.exists()) prev.renameTo(cur)   // could not place the new one: put the old one back
        return false
    }

    /** Undo after a new photo: the previous wall.jpg comes back (the style comes back with the saved model). */
    // ---- gestures + backup (logic: GestLogic.kt, HomeGestures.kt, HomeBackup.kt; strings: strings_gest.xml) ----

    private fun gestBackup(col: LinearLayout) {
        section(col, R.string.gest_title)
        val g = group()
        val names = mapOf(GestLogic.DBL to R.string.gest_dbl, GestLogic.UP to R.string.gest_up, GestLogic.DOWN to R.string.gest_down)
        for ((i, gid) in GestLogic.GESTURES.withIndex()) {
            val cur = Gest.action(this, gid)
            val items = ArrayList<Pair<String, String>>()
            if (gid != GestLogic.DBL) items += s(if (gid == GestLogic.UP) R.string.gest_def_up else R.string.gest_def_down) to GestLogic.DEFAULT
            items += s(R.string.gest_none) to GestLogic.NONE
            items += s(R.string.gest_search) to GestLogic.SEARCH
            if (gid != GestLogic.UP) items += s(R.string.gest_drawer) to GestLogic.DRAWER   // swipe up already opens it ("default")
            items += s(R.string.gest_shade) to GestLogic.SHADE
            if (Gest.lockOk(this)) items += s(R.string.gest_lock) to GestLogic.LOCK
            if (m?.style?.today != false) items += s(R.string.gest_today) to GestLogic.TODAY
            val pkg = Gest.app(this, gid)
            items += (if (cur == GestLogic.APP && pkg.isNotEmpty()) s(R.string.gest_app_named, appLabel(pkg)) else s(R.string.gest_app)) to GestLogic.APP
            if (i > 0) line(g)
            g.addView(segRow(s(names.getValue(gid)), items, cur) { v -> if (guard()) { if (v == GestLogic.APP) chooseApp(gid) else { Gest.set(this, gid, v); build() } } else build() })
        }
        col.addView(g, lp(8))
        col.addView(note(s(R.string.gest_hint), false), lp(8))

        section(col, R.string.bk_title)
        col.addView(k.pill(s(R.string.bk_save), R.drawable.li_file_down, false) { backupStart() }, lp(8))
        col.addView(k.pill(s(R.string.bk_restore), R.drawable.li_file_up, false) { if (guard()) restoreStart() }, lp(8))
        if (HomeBackup.hasUndo(this)) col.addView(k.pill(s(R.string.bk_undo), R.drawable.li_rotate_ccw, false) {
            if (guard()) { if (HomeBackup.undo(this)) { reloadAfterBackup(); Toast.makeText(this, R.string.bk_undone, Toast.LENGTH_SHORT).show() } else build() }
        }, lp(8))
        col.addView(note(s(R.string.bk_hint), false), lp(8))
    }

    private fun appLabel(pkg: String) = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)

    private fun chooseApp(gid: String) {
        val pm = packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }.filter { it.first != packageName && !it.first.startsWith("$packageName.") }
            .distinctBy { it.first }.sortedBy { it.second.lowercase() }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var dlg: android.app.Dialog? = null
        val ui = Ui(this)
        for ((pkg, label) in apps) list.addView(TextView(this).apply {
            text = label; textSize = 16f; setTextColor(ui.text); setPadding(dp(8), dp(14), dp(8), dp(14)); minHeight = dp(48)
            setOnClickListener { dlg?.dismiss(); Gest.set(this@CustomActivity, gid, GestLogic.APP, pkg); build() }
        }, LinearLayout.LayoutParams(-1, -2))
        dlg = Modal(this, ui).show(null, Modal.Tone.INFO, s(R.string.gest_pick_app), null, list, s(R.string.gest_cancel))
    }

    private fun backupStart() {
        val name = "kidguard-home-" + java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date()) + ".json"
        runCatching { startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json").putExtra(Intent.EXTRA_TITLE, name), 8) }
            .onFailure { Toast.makeText(this, R.string.bk_no_picker, Toast.LENGTH_LONG).show() }
    }

    private fun restoreStart() {
        runCatching { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), 9) }
            .onFailure { Toast.makeText(this, R.string.bk_no_picker, Toast.LENGTH_LONG).show() }
    }

    private fun reloadAfterBackup() { m = HomeFile.load(this) ?: m; undo = null; undoPhoto = false; build() }

    private fun backupResult(req: Int, uri: android.net.Uri) {
        if (req == 8) {
            Thread {
                val b = HomeBackup.current(this)
                val ok = b != null && HomeBackup.write(this, uri, b.first)
                runOnUiThread { Toast.makeText(this, if (!ok) s(R.string.bk_fail) else if (b!!.second) s(R.string.bk_saved) else s(R.string.bk_saved_nophoto), Toast.LENGTH_LONG).show() }
            }.start()
            return
        }
        if (!guard()) return   // the parent locked Tuỳ chỉnh: no restore
        Thread {
            val p = HomeBackup.parse(HomeBackup.read(this, uri))
            val gone = if (p != null) HomeBackup.missing(this, p.model) else 0
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                if (p == null) { Toast.makeText(this, R.string.bk_bad, Toast.LENGTH_LONG).show(); return@runOnUiThread }
                val body = s(R.string.bk_confirm_body, p.model.pages.size, p.model.onHome().size, gone)
                Modal(this, Ui(this)).show(R.drawable.li_file_up, Modal.Tone.WARN, s(R.string.bk_confirm_title), body, null, s(R.string.bk_restore_ok), s(R.string.gest_cancel)) {
                    if (HomeBackup.apply(this, p)) { reloadAfterBackup(); Toast.makeText(this, R.string.bk_done, Toast.LENGTH_LONG).show() }
                    else Toast.makeText(this, R.string.bk_fail, Toast.LENGTH_LONG).show()
                    true
                }
            }
        }.start()
    }

    private fun restorePhoto() {
        val prev = File(filesDir, Wallpaper.PHOTO + ".prev")
        if (prev.exists()) prev.renameTo(File(filesDir, Wallpaper.PHOTO))
    }
}

/**
 * Label + value row that stacks (label above, value below, start-aligned) when both don't fit on one line. Side by side,
 * a long value ("Mặc định (danh sách ứng dụng)") left the weighted label ~0 px: "Vuốt lên" wrapped one letter per line.
 */
private class StackRow(c: android.content.Context) : LinearLayout(c) {
    init { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    override fun onMeasure(w: Int, h: Int) {
        val label = getChildAt(0) as? TextView; val value = getChildAt(1) as? TextView
        if (label != null && value != null && MeasureSpec.getMode(w) != MeasureSpec.UNSPECIFIED) {
            val avail = MeasureSpec.getSize(w) - paddingLeft - paddingRight
            value.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            val needLabel = kotlin.math.ceil(label.paint.measureText(label.text.toString())).toInt() + label.paddingLeft + label.paddingRight
            val stack = needLabel + value.measuredWidth > avail
            if (stack != (orientation == VERTICAL)) {
                orientation = if (stack) VERTICAL else HORIZONTAL
                gravity = if (stack) Gravity.START else Gravity.CENTER_VERTICAL
                label.layoutParams = if (stack) LayoutParams(-1, -2).apply { topMargin = (10 * resources.displayMetrics.density).toInt() } else LayoutParams(0, -2, 1f)
                value.layoutParams = LayoutParams(-2, -2).apply { if (stack) bottomMargin = (4 * resources.displayMetrics.density).toInt() }
                value.gravity = if (stack) Gravity.CENTER_VERTICAL or Gravity.START else Gravity.CENTER_VERTICAL or Gravity.END
                value.setPadding(if (stack) 0 else value.paddingRight * 3, value.paddingTop, value.paddingRight, value.paddingBottom)
            }
        }
        super.onMeasure(w, h)
    }
}

