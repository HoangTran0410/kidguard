package dev.kidguard

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

// Edit mode, POCO / One UI style (split out of HomeActivity; extension functions on it; state lives in the activity):
// the pages shrink, every grid item gets a pick circle, a tap picks it, and the bottom bar acts on all picked items
// (group into a folder, move to a page, remove) or, with nothing picked, on the page (select all, delete page).

internal fun HomeActivity.enterEdit(pre: String? = null) {
    popup?.dismiss()
    if (editing) { pre?.let { togglePick(it) }; return }
    libraryOut()
    if (pager.page == 0) pager.snapTo(1)
    editing = true
    picked.clear(); pre?.let { picked += it }
    pager.pivotX = pager.width / 2f; pager.pivotY = pager.height * 0.48f
    // withLayer: the pages (icons, widgets) scale as one GPU texture, not redrawn each frame (62 % janky frames on an Oppo A5s)
    pager.animate().scaleX(0.86f).scaleY(0.86f).setDuration(240).setInterpolator(DecelerateInterpolator(1.6f)).withLayer().start()
    for (v in listOf(hint, dock)) v.animate().alpha(0f).setDuration(150).withEndAction { if (editing) v.visibility = View.INVISIBLE }.start()
    styleCells(); chromeFade()
    editBars()
}

internal fun HomeActivity.exitEdit() {
    if (!editing) return
    editing = false; picked.clear(); chromeFade(); libraryBack()
    pager.animate().scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(DecelerateInterpolator(1.6f)).withLayer().start()
    for (v in listOf(hint, dock)) { v.visibility = View.VISIBLE; v.animate().alpha(1f).setDuration(180).start() }
    for (v in listOfNotNull(editTop, editBottom)) v.animate().alpha(0f).setDuration(150).withEndAction { floatBox.removeView(v) }.start()
    editTop = null; editBottom = null
    styleCells()
}

internal fun HomeActivity.togglePick(id: String) {
    if (!picked.remove(id)) picked += id
    pager.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
    for (i in 1 until pager.childCount) pager.getChildAt(i).invalidate()
    editBars()
}

/**
 * iOS minus badge: asks first (what goes and that the apps stay in the library), then the item shrinks + fades out
 * and leaves the home; the other picks are kept.
 */
internal fun HomeActivity.removeOne(id: String) {
    val m = model ?: return
    val item = m.pages.flatten().firstOrNull { it.id == id } ?: return
    pager.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
    val (title, body) = when (item.kind) {
        "app" -> getString(R.string.hm_e_rm_q, IconCache.label(this, item.pkg)) to getString(R.string.hm_e_rm_app)
        "folder" -> getString(R.string.hm_e_rm_q, folderName(item)) to getString(R.string.hm_e_rm_folder, item.apps.size)
        else -> getString(R.string.hm_e_rm_widget_q) to getString(R.string.hm_e_rm_widget)
    }
    Modal(this, u).show(R.drawable.li_circle_minus, Modal.Tone.WARN, title, body, ok = getString(R.string.hm_e_rm_ok), cancel = getString(R.string.cancel)) {
        val v = cellOf(id)
        val done = {
            val cur = model
            if (cur != null && editing) {
                val keep = picked.filter { it != id }
                applyEdit(HomeLayout.removeIds(cur, setOf(id)))
                picked += keep; editBars()
                toast(getString(R.string.hm_e_removed1))
            }
        }
        if (v == null || !android.animation.ValueAnimator.areAnimatorsEnabled()) done()
        else {
            v.pivotX = v.width / 2f; v.pivotY = v.height / 2f
            v.alpha = 0.99f; (v.parent as? View)?.invalidate()
            v.animate().scaleX(0.2f).scaleY(0.2f).alpha(0f).setDuration(200)
                .setInterpolator(android.view.animation.AccelerateInterpolator(1.4f)).withLayer().withEndAction { done() }.start()
        }
        true
    }
}

/** The grid view of item [id] on any page, if built. */
private fun HomeActivity.cellOf(id: String): View? {
    for (i in 0 until pager.childCount) {
        val p = pager.getChildAt(i) as? android.view.ViewGroup ?: continue
        for (k in 0 until p.childCount) { val c = p.getChildAt(k); if ((c.layoutParams as? HomeActivity.CellLP)?.id == id) return c }
    }
    return null
}

/** After a bulk action: the new layout, nothing picked, still in edit mode. */
private fun HomeActivity.applyEdit(m: HomeModel) {
    picked.clear()
    update(m)
    hint.visibility = View.INVISIBLE; dock.visibility = View.INVISIBLE
    styleCells()
    editBars()
}

/** The top row ("Đã chọn N" + "Xong") and the action bar at the bottom; rebuilt on every pick (a few views). */
private fun HomeActivity.editBars() {
    val first = editTop == null
    editTop?.let { floatBox.removeView(it) }; editBottom?.let { floatBox.removeView(it) }
    val m = model ?: return

    val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8)) }
    top.addView(text(if (picked.isEmpty()) getString(R.string.hm_e_tap) else getString(R.string.hm_e_n, picked.size), 14f, pal.text, true).apply {
        background = fill(pal.sheet); setPadding(dp(14), dp(8), dp(14), dp(8))
        if (picked.isNotEmpty()) setOnClickListener { picked.clear(); for (i in 1 until pager.childCount) pager.getChildAt(i).invalidate(); editBars() }
    }, LinearLayout.LayoutParams(-2, -2))
    top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
    top.addView(TextView(this).apply {
        text = getString(R.string.hm_e_done); setTextColor(pal.onSolid); background = fill(pal.solid); setPadding(dp(18), dp(8), dp(18), dp(8))
        typeface = HomeActivity.tf(true); setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
        kit.tap(this, 999f, true) { exitEdit() }
    }, LinearLayout.LayoutParams(-2, -2))
    floatBox.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
    editTop = top

    val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; background = fill(pal.sheet, 24f); elevation = dpf(6f); setPadding(dp(6), dp(6), dp(6), dp(6)) }
    fun btn(icon: Int, label: String, enabled: Boolean = true, run: (View) -> Unit) = bar.addView(LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(4), dp(8), dp(4), dp(8))
        addView(ImageView(context).apply { setImageDrawable(Ic.get(context, icon, if (enabled) pal.text else pal.sub, dp(22))) }, LinearLayout.LayoutParams(dp(22), dp(22)))
        addView(text(label, 12f, if (enabled) pal.text else pal.sub).apply { gravity = Gravity.CENTER; maxLines = 2; setPadding(0, dp(4), 0, 0) }, LinearLayout.LayoutParams(-2, -2))
        contentDescription = label; alpha = if (enabled) 1f else 0.6f
        kit.tap(this, 18f); setOnClickListener { v -> if (enabled) run(v) else if (icon == R.drawable.li_folder_plus) toast(getString(R.string.hm_e_group_need)) }
    }, LinearLayout.LayoutParams(0, -2, 1f))

    val page = pager.page - 1   // pager child 0 is "Hôm nay"
    if (picked.isEmpty()) {
        btn(R.drawable.li_check, getString(R.string.hm_e_all), page in m.pages.indices && m.pages[page].isNotEmpty()) {
            picked += m.pages[page].map { it.id }; for (i in 1 until pager.childCount) pager.getChildAt(i).invalidate(); editBars()
        }
        btn(R.drawable.li_trash_2, getString(R.string.hm_e_delpage), page in m.pages.indices) { deletePage(page) }
        btn(R.drawable.li_layout_dashboard, getString(R.string.hm_w_title)) { exitEdit(); openPicker() }
    } else {
        val items = m.pages.flatten().filter { it.id in picked }
        val apps = items.sumOf { when (it.kind) { "app" -> 1; "folder" -> it.apps.size; else -> 0 } }
        btn(R.drawable.li_x, getString(R.string.hm_e_none)) { picked.clear(); for (i in 1 until pager.childCount) pager.getChildAt(i).invalidate(); editBars() }
        btn(R.drawable.li_folder_plus, getString(R.string.hm_e_group), apps >= 2) { group() }
        btn(R.drawable.li_move, getString(R.string.hm_e_move)) { v -> moveMenu(v) }
        btn(R.drawable.li_circle_minus, getString(R.string.hm_unpin)) {
            val n = picked.size
            applyEdit(HomeLayout.removeIds(m, picked.toSet())); toast(getString(R.string.hm_e_removed, n))
        }
    }
    floatBox.addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(dp(16), 0, dp(16), dp(12)) })
    editBottom = bar
    if (first) for (v in listOf(top, bar)) {
        v.alpha = 0f; v.translationY = dpf(if (v === top) -12f else 16f)
        v.animate().alpha(1f).translationY(0f).setDuration(220).setInterpolator(DecelerateInterpolator(1.6f)).start()
    }
}

private fun HomeActivity.group() {
    val m = model ?: return
    val items = m.pages.flatten().filter { it.id in picked && (it.kind == "app" || it.kind == "folder") }
    val pk = items.flatMap { if (it.kind == "folder") it.apps else listOf(it.pkg) }
    val name = if (pk.size >= 2) autoName(pk[0], pk[1]) else ""
    val out = HomeLayout.group(m, picked.toList(), name) ?: run { toast(getString(R.string.hm_f_full)); return }
    applyEdit(out)
}

private fun HomeActivity.moveMenu(anchor: View) {
    val m = model ?: return
    val cur = pager.page - 1
    val rows = m.pages.indices.filter { it != cur }.map { i -> HomeActivity.Row(R.drawable.li_layout_grid, getString(R.string.hm_e_page, i + 1), true) { moveTo(i) } } +
        HomeActivity.Row(R.drawable.li_plus, getString(R.string.hm_e_newpage), true) { moveTo(m.pages.size) }
    menu(rows, anchor)
}

private fun HomeActivity.moveTo(page: Int) {
    val m = model ?: return
    val total = picked.size
    val (out, n) = HomeLayout.moveTo(m, picked.toList(), page)
    if (n < total) toast(getString(R.string.hm_e_nofit, total - n))
    if (n == 0) return
    // where the target page ends up once empty pages are dropped: count the pages before it that still have items
    val target = (0 until minOf(page, m.pages.size)).count { i -> out.pages.size > 0 && m.pages[i].any { it.id !in picked } } .coerceAtMost(out.pages.size - 1)
    applyEdit(out)
    pager.post { pager.snapTo(target + 1) }
}

private fun HomeActivity.deletePage(page: Int) {
    val m = model ?: return
    val n = m.pages.getOrNull(page)?.size ?: return
    if (n == 0) { applyEdit(HomeLayout.removePage(m, page)); return }
    Modal(this, u).show(R.drawable.li_trash_2, Modal.Tone.WARN, getString(R.string.hm_e_del_title), getString(R.string.hm_e_del_body, n),
        ok = getString(R.string.hm_e_delpage), cancel = getString(R.string.cancel)) {
        applyEdit(HomeLayout.removePage(model ?: return@show true, page)); true
    }
}
