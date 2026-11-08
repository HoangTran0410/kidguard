package dev.kidguard

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.kidguard.HomeActivity.View0

// Kid action widgets: "Kiếm thêm giờ" (earn), "Thời khoá biểu" (class). Design system: HomeWidgets.kt (wCard, wHead...).

/** Same flow as the "Hôm nay" page: a request tagged with the chore id; not paired -> the pair modal. */
private fun HomeActivity.earnDone(ch: Chore, st: Store) {
    if (!st.everPaired) {
        Modal(this, u).show(R.drawable.li_link, Modal.Tone.INFO, getString(R.string.hm_pair_title), getString(R.string.hm_pair_body), ok = getString(R.string.hm_open_kg), cancel = getString(R.string.cancel)) { openMain(); true }
        return
    }
    val ok = st.addRequest(ch.min, "", ch.id)
    if (ok) { st.log(R.string.log_more); Guard.poke(this) }
    Modal(this, u).show(if (ok) R.drawable.li_send else R.drawable.li_triangle_alert, if (ok) Modal.Tone.OK else Modal.Tone.WARN,
        getString(if (ok) R.string.more_sent else R.string.more_fail), getString(if (ok) R.string.hm_t_chore_sent else R.string.more_fail_body))
    key = ""; render(true)
}

/** Chores from the parent, each with "Xong · +N phút" (2x2: 2 rows, 4x2: 3). */
internal fun HomeActivity.earnWidget(r: Rules, s: Store, w: Int, h: Int): View {
    val card = wCard()
    val chores = r.chores
    card.addView(wHead(R.drawable.li_timer, getString(R.string.hm_w_earn), if (chores.isEmpty()) null else getString(R.string.hm_wk_total, chores.sumOf { it.min })))
    if (chores.isEmpty()) {
        card.addView(wEmpty(getString(R.string.hm_wk_earn_empty)))
        card.contentDescription = getString(R.string.hm_w_earn)
        wOpen(card)
        return centred(card)
    }
    val reqs = s.requests()
    val waiting = HashSet<String>()
    for (i in 0 until reqs.length()) reqs.optJSONObject(i)?.optString("chore")?.takeIf { it.isNotEmpty() }?.let { waiting += it }
    val cap = if (w >= 4) 3 else 2
    for (ch in chores.take(cap)) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(4), 0, dp(4)) }
        row.addView(wMain(ch.name), LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(8) })
        if (ch.id in waiting) row.addView(kit.chip(getString(R.string.hm_t_wait), pal.warnText, pal.warnTint, R.drawable.li_clock))
        else row.addView(kit.chip(getString(R.string.hm_wk_done_btn, ch.min), pal.onSolid, pal.solid, R.drawable.li_check).apply {
            kit.tap(this, 999f, true) { earnDone(ch, s) }
            contentDescription = getString(R.string.hm_t_done_desc, ch.name)
        })
        card.addView(row, LinearLayout.LayoutParams(-1, -2))
    }
    if (chores.size > cap) card.addView(wSub(getString(R.string.hm_w_more, chores.size - cap)).apply { setPadding(0, dp(2), 0, 0) })
    return centred(card)
}

private fun HomeActivity.dayName(dow: Int) = getString(when (dow) {
    1 -> R.string.hm_wk_d1; 2 -> R.string.hm_wk_d2; 3 -> R.string.hm_wk_d3; 4 -> R.string.hm_wk_d4
    5 -> R.string.hm_wk_d5; 6 -> R.string.hm_wk_d6; else -> R.string.hm_wk_d7
})

/** Today's subjects + tomorrow's bag; after 17:00 tomorrow leads. A tap opens the editor. */
internal fun HomeActivity.classWidget(m: HomeModel, dv: View0, w: Int, h: Int): View {
    val card = wCard()
    val tt = m.timetable
    val today = TimetableLogic.day(tt, dv.dow)
    val next = TimetableLogic.distinct(TimetableLogic.day(tt, TimetableLogic.tomorrow(dv.dow)))
    val evening = TimetableLogic.eveningFirst(dv.minute)
    val big = h >= 2
    card.addView(wHead(R.drawable.li_calendar, getString(R.string.hm_w_class), dayName(dv.dow)))
    fun chips(l: List<String>, max: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        for (x in l.take(max)) addView(kit.chip(x, pal.onTint, pal.tint).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(4) })
        if (l.size > max) addView(wSub("+${l.size - max}"), LinearLayout.LayoutParams(-2, -2))
    }
    val per = if (w >= 4) 4 else 2
    fun block(label: String, l: List<String>, off: Int) {
        card.addView(wSub(label).apply { setPadding(0, dp(2), 0, dp(3)) })
        if (l.isEmpty()) card.addView(wMain(getString(off))) else card.addView(chips(l, per))
    }
    if (TimetableLogic.isEmpty(tt)) card.addView(wEmpty(getString(R.string.hm_wk_class_empty)))
    else if (evening) {
        block(getString(R.string.hm_wk_pack), next, R.string.hm_wk_off_tomorrow)
        if (big && today.isNotEmpty()) { card.addView(View(this), LinearLayout.LayoutParams(-1, dp(8))); block(getString(R.string.hm_wk_today), today, R.string.hm_wk_off) }
    } else {
        block(getString(R.string.hm_wk_today), today, R.string.hm_wk_off)
        if (big) { card.addView(View(this), LinearLayout.LayoutParams(-1, dp(8))); block(getString(R.string.hm_wk_tomorrow), next, R.string.hm_wk_off_tomorrow) }
    }
    card.contentDescription = getString(R.string.hm_w_class) + ". " + (if (evening) next else today).joinToString(", ")
    kit.tap(card, 22f) { openTimetable(dv.dow) }
    return centred(card)
}

/** Full-screen sheet: weekday tabs, the day's subjects (tap = edit, hold = remove), add, copy from the previous day. */
internal fun HomeActivity.openTimetable(startDow: Int) {
    popup?.dismiss()
    var cur = startDow.coerceIn(1, 7)
    val scrim = FrameLayout(this).apply { setBackgroundColor(Color.argb(if (pal.dark) 140 else 80, 0, 0, 0)); isClickable = true; setOnClickListener { closeOverlay() }; tag = "timetable" }
    val sheet = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; isClickable = true; elevation = dpf(12f)
        background = GradientDrawable().apply { setColor(pal.sheet); cornerRadii = floatArrayOf(dpf(28f), dpf(28f), dpf(28f), dpf(28f), 0f, 0f, 0f, 0f) }
        setPadding(dp(20) + insL, dp(10), dp(20) + insR, dp(12) + imeB)
    }
    sheet.addView(View(this).apply { background = fill(pal.stroke) }, LinearLayout.LayoutParams(dp(36), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(12) })
    val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    head.addView(text(getString(R.string.hm_wk_edit_title), 22f, pal.text, true), LinearLayout.LayoutParams(0, -2, 1f))
    head.addView(kit.chip(getString(R.string.hm_wk_done), pal.onSolid, pal.solid).apply { kit.tap(this, 999f, true) { closeOverlay() } })
    sheet.addView(head)
    sheet.addView(text(getString(R.string.hm_wk_edit_hint), 13f, pal.sub).apply { setPadding(0, dp(4), 0, dp(8)) })
    val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, dp(8)) }
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    sheet.addView(tabs, LinearLayout.LayoutParams(-1, -2))
    sheet.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))

    fun daily(d: Int) = TimetableLogic.day(model?.timetable ?: org.json.JSONObject(), d)
    fun save(d: Int, l: List<String>) { model?.let { mm -> update(mm.copy(timetable = TimetableLogic.with(mm.timetable, d, l))) } }
    lateinit var rebuild: () -> Unit
    fun prompt(old: String?) {
        val md = Modal(this, u)
        val f = md.field(getString(R.string.hm_wk_subject_hint), TimetableLogic.MAX_LEN)
        if (old != null) f.setText(old)
        md.show(R.drawable.li_calendar, Modal.Tone.INFO, getString(if (old == null) R.string.hm_wk_add else R.string.hm_wk_edit_subject), content = f, ok = getString(if (old == null) R.string.hm_t_add else R.string.hm_wk_done), cancel = getString(R.string.cancel)) {
            val l = daily(cur).toMutableList()
            val t = f.text.toString().trim()
            if (t.isNotEmpty()) {
                if (old == null) l += t else { val i = l.indexOf(old); if (i >= 0) l[i] = t else l += t }
                save(cur, l)
                rebuild()
            }
            true
        }
        f.requestFocus()
    }
    rebuild = {
        tabs.removeAllViews(); list.removeAllViews()
        for (d in 1..7) {
            val sel = d == cur
            tabs.addView(text(dayName(d), 14f, if (sel) pal.onSolid else pal.onTint, true).apply {
                gravity = Gravity.CENTER; background = fill(if (sel) pal.solid else pal.tint, 999f)
                kit.tap(this, 999f, sel) { cur = d; rebuild() }
            }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { rightMargin = if (d < 7) dp(4) else 0 })
        }
        val l = daily(cur)
        if (l.isEmpty()) list.addView(text(getString(R.string.hm_wk_day_empty), 14f, pal.sub).apply { setPadding(0, dp(12), 0, dp(12)) })
        l.forEachIndexed { i, sub ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(10), dp(4), dp(10)) }
            row.addView(text("${i + 1}", 13f, pal.sub, true).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(24), -2))
            row.addView(text(sub, 16f, pal.text).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(dp(8), 0, dp(8), 0) }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(android.widget.ImageView(this).apply {
                setImageDrawable(Ic.get(this@openTimetable, R.drawable.li_x, pal.sub, dp(18))); setPadding(dp(8), dp(8), dp(8), dp(8))
                contentDescription = getString(R.string.hm_wk_remove_desc, sub)
                kit.tap(this, 999f) { save(cur, l.filterIndexed { j, _ -> j != i }); rebuild() }
            }, LinearLayout.LayoutParams(dp(36), dp(36)))
            kit.tap(row, 12f) { prompt(sub) }
            row.isLongClickable = true
            row.setOnLongClickListener { save(cur, l.filterIndexed { j, _ -> j != i }); rebuild(); true }
            list.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        fun action(icon: Int, label: String, go: () -> Unit) = list.addView(text(label, 14f, pal.accentText, true).apply {
            setPadding(dp(4), dp(12), dp(4), dp(12)); Ic.lead(this, icon, pal.accentText); compoundDrawablePadding = dp(8); gravity = Gravity.CENTER_VERTICAL
            kit.tap(this, 10f) { go() }
        }, LinearLayout.LayoutParams(-2, -2))
        action(R.drawable.li_plus, getString(R.string.hm_wk_add)) {
            if (daily(cur).size >= TimetableLogic.MAX_DAY) toast(getString(R.string.hm_wk_full, TimetableLogic.MAX_DAY)) else prompt(null)
        }
        val prev = if (cur == 1) 7 else cur - 1
        if (l.isEmpty() && daily(prev).isNotEmpty()) action(R.drawable.li_calendar, getString(R.string.hm_wk_copy)) { save(cur, daily(prev)); rebuild() }
    }
    rebuild()
    scrim.addView(sheet, FrameLayout.LayoutParams(-1, (root.height * 0.85f).toInt(), Gravity.BOTTOM))
    showOverlay(scrim)
    sheet.translationY = dpf(80f); sheet.alpha = 0f
    sheet.animate().translationY(0f).alpha(1f).setDuration(180).start()
}

internal fun HomeActivity.earnPreview(c: LinearLayout) {
    c.addView(text(getString(R.string.hm_wk_total, 45), 13f, pal.accentText, true))
    c.addView(kit.chip(getString(R.string.hm_wk_done_btn, 15), pal.onSolid, pal.solid).apply { textSize = 9f }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
}

internal fun HomeActivity.classPreview(c: LinearLayout) {
    val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
    for (n in listOf("Toán", "Văn")) r.addView(kit.chip(n, pal.onTint, pal.tint).apply { textSize = 9f; setPadding(dp(6), dp(3), dp(6), dp(3)) }, LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(2), 0, dp(2), 0) })
    c.addView(text(getString(R.string.hm_w_class), 10f, pal.sub))
    c.addView(r, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(3) })
}
