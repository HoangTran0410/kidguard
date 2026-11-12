package dev.kidguard

import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Multi-select app picker (iOS Focus "Choose apps" / Pixel "Apps" pickers): a bottom sheet with the drawer's search
 * field pinned on top, one row per app (its real icon + name) and a round check on the right; "Lưu (n)" sits under the
 * search so it never scrolls away. Replaces a plain CheckBox list with no icons and no search ("Chọn app học").
 *
 * Perf (docs/launcher-perf.md): rows are plain views; the first ~14 are built before the sheet shows, the rest after
 * its first frame in lumps; icons come from IconCache (peek, else async). Search only toggles row visibility.
 */
internal fun HomeActivity.appPickSheet(title: String, apps: List<HomeActivity.AppE>, chosen0: Set<String>, back: (() -> Unit)? = null, onSave: (Set<String>) -> Unit) {
    val t0 = Perf.now()
    // opened from another sheet ([back] set): stacked on it (no second dim), Save / Back return to it instead of closing both
    val sh = Sheet(this, u, title, stacked = back != null)
    val chosen = chosen0.toMutableSet()
    val shp = model?.style?.shape ?: "soft"
    val ipx = dp(40)

    // pinned: search (same look as the drawer's) + save
    val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = fill(pickSurf(), 24f); setPadding(dp(14), 0, dp(6), 0) }
    bar.addView(ImageView(this).apply { setImageDrawable(Ic.get(this@appPickSheet, R.drawable.li_search, pal.sub, dp(20))); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, LinearLayout.LayoutParams(dp(20), dp(20)))
    val search = EditText(this).apply {
        hint = getString(R.string.hm_search); setHintTextColor(pal.sub); setTextColor(pal.text); background = null; setSingleLine()
        textSize = 16f; setPadding(dp(10), dp(12), dp(10), dp(12))
        imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
    }
    bar.addView(search, LinearLayout.LayoutParams(0, -2, 1f))
    val clear = ImageView(this).apply {
        setImageDrawable(Ic.get(this@appPickSheet, R.drawable.li_x, pal.sub, dp(20))); setPadding(dp(10), dp(10), dp(10), dp(10))
        contentDescription = getString(R.string.close); visibility = View.INVISIBLE
        kit.tap(this, -1f) { search.setText("") }
    }
    bar.addView(clear, LinearLayout.LayoutParams(dp(44), dp(44)))
    sh.pinned.addView(bar, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(4), dp(8), dp(4), dp(8)) })
    lateinit var save: View
    fun saveBtn(): View = kit.pill(getString(R.string.app_pick_save, chosen.size), R.drawable.li_check, true) { sh.dismiss(); onSave(chosen.toSet()) }
    val saveBox = android.widget.FrameLayout(this)
    fun refreshSave() { saveBox.removeAllViews(); save = saveBtn(); saveBox.addView(save) }
    refreshSave()
    sh.pinned.addView(saveBox, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(4), 0, dp(4), dp(6)) })

    // rows
    class Row(val view: View, val a: HomeActivity.AppE, val check: ImageView, val ring: GradientDrawable)
    val rows = ArrayList<Row>()
    fun paint(r: Row) {
        val on = r.a.pkg in chosen
        r.ring.setColor(if (on) pal.solid else 0); r.ring.setStroke(if (on) 0 else dp(2), pal.sub)
        r.check.setImageDrawable(if (on) Ic.get(this, R.drawable.li_check, pal.onSolid, dp(16)) else null)
        r.view.contentDescription = r.a.label; r.view.isSelected = on
    }
    fun row(a: HomeActivity.AppE): Row {
        val icon = ImageView(this).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        IconCache.peek(a.pkg, a.cls, ipx, shp)?.let { icon.setImageBitmap(it) }
            ?: IconCache.async(this, a.pkg, a.cls, ipx, shp) { b -> if (b != null && sh.showing) icon.setImageBitmap(b) }
        val ring = GradientDrawable().apply { shape = GradientDrawable.OVAL }
        val check = ImageView(this).apply { background = ring; scaleType = ImageView.ScaleType.CENTER; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        val v = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(56); setPadding(dp(12), dp(6), dp(12), dp(6))
            addView(icon, LinearLayout.LayoutParams(ipx, ipx))
            addView(text(a.label, 16f, pal.text).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(dp(14), 0, dp(10), 0) }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(check, LinearLayout.LayoutParams(dp(24), dp(24)))
        }
        val r = Row(v, a, check, ring)
        kit.tap(v, 14f) { if (!chosen.add(a.pkg)) chosen.remove(a.pkg); paint(r); refreshSave() }
        paint(r)
        return r
    }
    val empty = text(getString(R.string.app_pick_none), 14f, pal.sub).apply { gravity = Gravity.CENTER; setPadding(0, dp(24), 0, dp(24)); visibility = View.GONE }
    // a steady height while filtering: the sheet does not jump as rows hide
    sh.body.minimumHeight = (resources.displayMetrics.heightPixels * 0.55f).toInt()
    sh.body.addView(empty, LinearLayout.LayoutParams(-1, -2))
    fun filter() {
        val q = LauncherLogic.fold(search.text.toString()).trim()
        clear.visibility = if (q.isEmpty()) View.INVISIBLE else View.VISIBLE
        var n = 0
        for (r in rows) { val hit = q.isEmpty() || LauncherLogic.matchesFolded(r.a.folded, q); r.view.visibility = if (hit) View.VISIBLE else View.GONE; if (hit) n++ }
        empty.visibility = if (n == 0 && rows.size == apps.size) View.VISIBLE else View.GONE
    }
    search.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) { filter() }
    })
    val first = 14
    for (a in apps.take(first)) { val r = row(a); rows += r; sh.body.addView(r.view) }
    fun more(from: Int) {
        if (from >= apps.size || !sh.showing) return
        val tl = Perf.now()
        val end = minOf(apps.size, from + 24)
        for (i in from until end) { val r = row(apps[i]); rows += r; sh.body.addView(r.view) }
        if (search.text.isNotEmpty()) filter()
        Perf.log("appPick rows $from-$end", Perf.now() - tl)
        sh.body.postOnAnimation { more(end) }
    }
    sh.body.viewTreeObserver.addOnDrawListener(object : android.view.ViewTreeObserver.OnDrawListener {
        var done = false
        override fun onDraw() {
            if (done) return
            done = true; val me = this
            sh.body.post { runCatching { sh.body.viewTreeObserver.removeOnDrawListener(me) }; more(first) }
        }
    })
    sh.onBack = back
    val built = Perf.now()
    sh.show()
    Perf.firstDraw(sh.body, "appPick", t0, built)
}
