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

/** What the school day is doing at [minute]: (main line, small line), or null outside school hours. */
private fun HomeActivity.ttStatus(tt: org.json.JSONObject, dow: Int, minute: Int): Pair<String, String>? {
    val s = TimetableLogic.slots(tt, dow)
    if (s.isEmpty() || TimetableLogic.eveningFirst(minute)) return null
    val (cur, next) = TimetableLogic.at(s, minute)
    fun per(x: TimetableLogic.Slot) = getString(R.string.hm_wk_period, x.period) + " · " + x.subject
    val then = next?.let { " · " + getString(R.string.hm_wk_then, it.subject) } ?: ""
    return when {
        cur?.kind == TimetableLogic.Kind.CLASS -> per(cur) to getString(R.string.hm_wk_until, TimetableLogic.hm(cur.end)) + then
        cur?.kind == TimetableLogic.Kind.RECESS -> getString(R.string.hm_wk_recess) to getString(R.string.hm_wk_until, TimetableLogic.hm(cur.end)) + then
        cur?.kind == TimetableLogic.Kind.LUNCH -> getString(R.string.hm_wk_lunch) to getString(R.string.hm_wk_pm_starts, TimetableLogic.hm(cur.end)) + then
        next != null && minute < s.first().start -> getString(R.string.hm_wk_starts, TimetableLogic.hm(next.start)) to per(next)
        next != null -> per(next) to getString(R.string.hm_wk_at, TimetableLogic.hm(next.start))
        else -> null
    }
}

/** The class now (session, 0-based period) for the week grid's highlight, or null. */
private fun nowCell(tt: org.json.JSONObject, dow: Int, minute: Int): Pair<Boolean, Int>? =
    TimetableLogic.at(TimetableLogic.slots(tt, dow), minute).first?.takeIf { it.kind == TimetableLogic.Kind.CLASS }?.let { it.pm to it.period - 1 }

private fun HomeActivity.weekGrid(tt: org.json.JSONObject, dow: Int, minute: Int): WeekGrid {
    val days = TimetableLogic.weekDays(tt)
    return WeekGrid(this, pal.text, pal.sub, pal.tint, pal.solid, pal.onSolid, pal.stroke).apply {
        // a used session shows all its bell periods (a steady grid, free periods visible), an unused one no rows
        fun n(pm: Boolean) = TimetableLogic.rows(tt, days, pm).let { if (it == 0) 0 else maxOf(it, TimetableLogic.bells(tt, pm).size) }
        set(days, days.map { dayName(it) }, n(false), n(true),
            { d, pm, i -> TimetableLogic.session(tt, d, pm).getOrElse(i) { "" } }, getString(R.string.hm_wk_pm))
        today = dow; now = nowCell(tt, dow, minute)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
}

/**
 * 2×2 / 4×2: what school is doing now (period · subject, ra chơi, nghỉ trưa, vào học), today's subjects and tomorrow's
 * bag; after 17:00 tomorrow leads. 4×4: the whole week as a grid (today's column and the period now lit). A tap opens the editor.
 */
internal fun HomeActivity.classWidget(m: HomeModel, dv: View0, w: Int, h: Int): View {
    val card = wCard()
    val tt = m.timetable
    card.addView(wHead(R.drawable.li_calendar, getString(R.string.hm_w_class), dayName(dv.dow)))
    if (TimetableLogic.isEmpty(tt)) {
        card.addView(wEmpty(getString(R.string.hm_wk_class_empty)))
        kit.tap(card, 22f) { openTimetable(dv.dow) }
        return centred(card)
    }
    if (w >= 4 && h >= 3) {
        val g = weekGrid(tt, dv.dow, dv.minute).apply { minimumHeight = dp(150) }
        card.addView(g, LinearLayout.LayoutParams(-1, 0, 1f))
        tickers += { v -> g.today = v.dow; g.now = nowCell(model?.timetable ?: tt, v.dow, v.minute) }
        card.contentDescription = getString(R.string.hm_w_class) + ". " + TimetableLogic.day(tt, dv.dow).joinToString(", ")
        kit.tap(card, 22f) { openTimetable(dv.dow, tab = 1) }
        return centred(card)
    }
    val today = TimetableLogic.day(tt, dv.dow)
    val next = TimetableLogic.distinct(TimetableLogic.day(tt, TimetableLogic.tomorrow(dv.dow)))
    val evening = TimetableLogic.eveningFirst(dv.minute)
    val big = h >= 2 && w >= 4
    val st = ttStatus(tt, dv.dow, dv.minute)
    val main = wMain(st?.first ?: "").apply { setTextColor(pal.accentText) }
    val sub = wSub(st?.second ?: "").apply { setPadding(0, 0, 0, dp(4)) }
    for (v in listOf(main, sub)) { v.visibility = if (st == null) View.GONE else View.VISIBLE; card.addView(v) }
    tickers += { v ->
        val s = ttStatus(model?.timetable ?: tt, v.dow, v.minute)
        for (x in listOf(main, sub)) x.visibility = if (s == null) View.GONE else View.VISIBLE
        if (s != null) { if (main.text.toString() != s.first) main.text = s.first; if (sub.text.toString() != s.second) sub.text = s.second }
    }
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
    if (evening) {
        block(getString(R.string.hm_wk_pack), next, R.string.hm_wk_off_tomorrow)
        if (big && today.isNotEmpty()) { card.addView(View(this), LinearLayout.LayoutParams(-1, dp(8))); block(getString(R.string.hm_wk_today), today, R.string.hm_wk_off) }
    } else {
        // during school the status line already says what is on: the 2×2 keeps today's chips only when it has room
        if (st == null || h >= 2) block(getString(R.string.hm_wk_today), today, R.string.hm_wk_off)
        if (big && st == null) { card.addView(View(this), LinearLayout.LayoutParams(-1, dp(8))); block(getString(R.string.hm_wk_tomorrow), next, R.string.hm_wk_off_tomorrow) }
    }
    card.contentDescription = getString(R.string.hm_w_class) + ". " + listOfNotNull(st?.first, st?.second).joinToString(". ") + ". " + (if (evening) next else today).joinToString(", ")
    kit.tap(card, 22f) { openTimetable(dv.dow) }
    return centred(card)
}

/** "−  value  +" row; [fmt] shows the value. Returns the row and a getter. */
private fun HomeActivity.stepper(label: String, init: Int, min: Int, max: Int, step: Int, fmt: (Int) -> String): Pair<View, () -> Int> {
    var v = init.coerceIn(min, max)
    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(4), 0, dp(4)) }
    row.addView(text(label, 14f, pal.text).apply { maxLines = 2 }, LinearLayout.LayoutParams(0, -2, 1f))
    val value = text(fmt(v), 15f, pal.text, true).apply { gravity = Gravity.CENTER }
    fun btn(icon: Int, d: Int) = android.widget.ImageView(this).apply {
        setImageDrawable(Ic.get(this@stepper, icon, pal.onTint, dp(18))); background = fill(pal.tint, 999f); setPadding(dp(9), dp(9), dp(9), dp(9))
        kit.tap(this, 999f) { v = (v + d).coerceIn(min, max); value.text = fmt(v) }
    }
    row.addView(btn(R.drawable.li_minus, -step), LinearLayout.LayoutParams(dp(36), dp(36)))
    row.addView(value, LinearLayout.LayoutParams(dp(84), -2))
    row.addView(btn(R.drawable.li_plus, step), LinearLayout.LayoutParams(dp(36), dp(36)))
    return row to { v }
}

/**
 * Full-screen sheet with three tabs:
 * - "Từng ngày": weekday tabs, then the morning and afternoon periods with their bell times (tap = subject, hold = free),
 *   recess and lunch shown between them, copy from the day before;
 * - "Cả tuần": the week grid, tap a cell to edit it;
 * - "Giờ học": the bell times (shared by every day): quick setup per session, tap a period to move / resize it, hold to remove.
 */
internal fun HomeActivity.openTimetable(startDow: Int, tab: Int = 0) {
    popup?.dismiss()
    var cur = startDow.coerceIn(1, 7)
    var mode = tab
    val scrim = FrameLayout(this).apply { setBackgroundColor(Color.argb(if (pal.dark) 140 else 80, 0, 0, 0)); isClickable = true; setOnClickListener { closeOverlay() }; tag = "timetable" }
    val sheet = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; isClickable = true; elevation = shadow(12f)
        background = GradientDrawable().apply { setColor(pal.sheet); cornerRadii = floatArrayOf(dpf(28f), dpf(28f), dpf(28f), dpf(28f), 0f, 0f, 0f, 0f) }
        setPadding(dp(20) + insL, dp(10), dp(20) + insR, dp(12) + imeB)
    }
    sheet.addView(View(this).apply { background = fill(pal.stroke) }, LinearLayout.LayoutParams(dp(36), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(12) })
    val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    head.addView(text(getString(R.string.hm_wk_edit_title), 22f, pal.text, true), LinearLayout.LayoutParams(0, -2, 1f))
    head.addView(kit.chip(getString(R.string.hm_wk_done), pal.onSolid, pal.solid).apply { kit.tap(this, 999f, true) { closeOverlay() } })
    sheet.addView(head)
    val modes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; background = fill(pal.tint, 999f); setPadding(dp(3), dp(3), dp(3), dp(3)) }
    sheet.addView(modes, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
    val hint = text("", 13f, pal.sub).apply { setPadding(0, dp(8), 0, dp(8)) }
    sheet.addView(hint)
    val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, dp(8)) }
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    sheet.addView(tabs, LinearLayout.LayoutParams(-1, -2))
    val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(list) }
    sheet.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

    fun tt() = model?.timetable ?: org.json.JSONObject()
    fun saveTt(n: org.json.JSONObject) { model?.let { mm -> update(mm.copy(timetable = n)) } }
    lateinit var rebuild: () -> Unit

    fun editSubject(d: Int, pm: Boolean, i: Int) {
        val old = TimetableLogic.session(tt(), d, pm).getOrElse(i) { "" }
        val md = Modal(this, u)
        val f = md.field(getString(R.string.hm_wk_subject_hint), TimetableLogic.MAX_LEN)
        f.setText(old); f.setSelection(f.text.length)
        val title = dayName(d) + " · " + getString(if (pm) R.string.hm_wk_pm else R.string.hm_wk_am) + " · " + getString(R.string.hm_wk_period, i + 1)
        md.show(R.drawable.li_calendar, Modal.Tone.INFO, title, content = f, ok = getString(R.string.hm_wk_done), cancel = getString(R.string.cancel)) {
            val l = TimetableLogic.session(tt(), d, pm).toMutableList()
            while (l.size <= i) l += ""
            l[i] = f.text.toString().trim()
            saveTt(TimetableLogic.withSession(tt(), d, pm, l)); rebuild(); true
        }
        f.requestFocus()
    }
    fun clearSubject(d: Int, pm: Boolean, i: Int) {
        val l = TimetableLogic.session(tt(), d, pm).toMutableList()
        if (i < l.size) { l[i] = ""; saveTt(TimetableLogic.withSession(tt(), d, pm, l)); rebuild() }
    }
    fun editBell(pm: Boolean, i: Int?) {
        val b = TimetableLogic.bells(tt(), pm).toMutableList()
        val base = i?.let { b[it] } ?: b.lastOrNull()?.let { intArrayOf(it[1] + 5, it[1] + 5 + (it[1] - it[0])) } ?: intArrayOf(if (pm) 810 else 420, if (pm) 855 else 465)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val (s, gs) = stepper(getString(R.string.hm_wk_start), base[0], 5 * 60, 22 * 60, 5) { TimetableLogic.hm(it) }
        val (l, gl) = stepper(getString(R.string.hm_wk_len), base[1] - base[0], 10, 120, 5) { getString(R.string.hm_wk_min, it) }
        box.addView(s); box.addView(l)
        val title = getString(if (pm) R.string.hm_wk_pm else R.string.hm_wk_am) + " · " + getString(R.string.hm_wk_period, (i ?: b.size) + 1)
        Modal(this, u).show(R.drawable.li_clock, Modal.Tone.INFO, title, content = box, ok = getString(R.string.hm_wk_done), cancel = getString(R.string.cancel)) {
            val p = intArrayOf(gs(), gs() + gl())
            if (i != null) b[i] = p else b += p
            saveTt(TimetableLogic.withBells(tt(), pm, b)); rebuild(); true
        }
    }
    fun quick(pm: Boolean) {
        val b = TimetableLogic.bells(tt(), pm)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val st = stepper(getString(R.string.hm_wk_start), b.firstOrNull()?.get(0) ?: if (pm) 810 else 420, 5 * 60, 22 * 60, 5) { TimetableLogic.hm(it) }
        val ln = stepper(getString(R.string.hm_wk_len), b.firstOrNull()?.let { it[1] - it[0] } ?: 45, 10, 120, 5) { getString(R.string.hm_wk_min, it) }
        val n = stepper(getString(R.string.hm_wk_count), b.size.coerceAtLeast(1), 1, TimetableLogic.MAX_SESSION, 1) { "$it" }
        val gp = stepper(getString(R.string.hm_wk_gap), 5, 0, 30, 5) { getString(R.string.hm_wk_min, it) }
        val ra = stepper(getString(R.string.hm_wk_recess_after), 2, 0, TimetableLogic.MAX_SESSION - 1, 1) { if (it == 0) getString(R.string.hm_wk_none) else getString(R.string.hm_wk_period, it) }
        val rl = stepper(getString(R.string.hm_wk_recess_len), 20, 5, 60, 5) { getString(R.string.hm_wk_min, it) }
        for (x in listOf(st, ln, n, gp, ra, rl)) box.addView(x.first)
        val title = getString(R.string.hm_wk_quick) + " · " + getString(if (pm) R.string.hm_wk_pm else R.string.hm_wk_am)
        Modal(this, u).show(R.drawable.li_clock, Modal.Tone.INFO, title, content = box, ok = getString(R.string.hm_wk_done), cancel = getString(R.string.cancel)) {
            saveTt(TimetableLogic.withBells(tt(), pm, TimetableLogic.gen(st.second(), ln.second(), gp.second(), n.second(), ra.second(), rl.second()))); rebuild(); true
        }
    }

    fun row(lead: String, leadSub: String?, main: String, mainColor: Int, tap: (() -> Unit)?, hold: (() -> Unit)?) = list.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(9), dp(4), dp(9))
        val l = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        l.addView(text(lead, 13f, pal.text, true))
        if (leadSub != null) l.addView(text(leadSub, 11f, pal.sub))
        addView(l, LinearLayout.LayoutParams(dp(92), -2))
        addView(text(main, if (mainColor == pal.sub) 14f else 16f, mainColor).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(dp(8), 0, dp(8), 0) }, LinearLayout.LayoutParams(0, -2, 1f))
        if (tap != null) kit.tap(this, 12f) { tap() }
        if (hold != null) { isLongClickable = true; setOnLongClickListener { hold(); true } }
    }, LinearLayout.LayoutParams(-1, -2))
    // a break between two periods: tap to make it longer / shorter, the periods after it move with it
    fun editGap(pm: Boolean, i: Int, lunch: Boolean) {
        val b = TimetableLogic.bells(tt(), pm)
        val prevEnd = if (lunch) TimetableLogic.bells(tt(), false).lastOrNull()?.get(1) ?: return else b[i - 1][1]
        val gap = b[i][0] - prevEnd
        val (row, get) = stepper(getString(if (lunch) R.string.hm_wk_lunch else R.string.hm_wk_recess), gap, 0, if (lunch) 300 else 90, 5) { getString(R.string.hm_wk_min, it) }
        Modal(this, u).show(R.drawable.li_clock, Modal.Tone.INFO, getString(if (lunch) R.string.hm_wk_lunch else R.string.hm_wk_recess), getString(R.string.hm_wk_gap_hint), content = row,
            ok = getString(R.string.hm_wk_done), cancel = getString(R.string.cancel)) {
            saveTt(TimetableLogic.withBells(tt(), pm, TimetableLogic.shift(b, i, get() - gap))); rebuild(); true
        }
    }
    fun gapRow(t: String, tap: (() -> Unit)? = null) = list.addView(text(t, 12f, pal.accentText).apply {
        gravity = Gravity.CENTER; background = fill(pal.tint, 999f); setPadding(dp(10), dp(6), dp(10), dp(6))
        if (tap != null) { Ic.lead(this, R.drawable.li_clock, pal.accentText); compoundDrawablePadding = dp(6); kit.tap(this, 999f) { tap() } }
    }, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(24), dp(3), dp(24), dp(3)) })
    fun section(t: String) = list.addView(text(t.uppercase(), 12f, pal.accentText, true).apply { letterSpacing = 0.08f; setPadding(dp(4), dp(12), 0, dp(2)) })
    fun action(icon: Int, label: String, go: () -> Unit) = list.addView(text(label, 14f, pal.accentText, true).apply {
        setPadding(dp(4), dp(12), dp(4), dp(12)); Ic.lead(this, icon, pal.accentText); compoundDrawablePadding = dp(8); gravity = Gravity.CENTER_VERTICAL
        kit.tap(this, 10f) { go() }
    }, LinearLayout.LayoutParams(-2, -2))
    fun mins(a: Int, b: Int) = getString(R.string.hm_wk_min, b - a)

    rebuild = {
        modes.removeAllViews(); tabs.removeAllViews(); list.removeAllViews()
        for ((k, lab) in listOf(R.string.hm_wk_tab_day, R.string.hm_wk_tab_week, R.string.hm_wk_tab_bells).withIndex()) {
            val sel = k == mode
            modes.addView(text(getString(lab), 14f, if (sel) pal.onSolid else pal.onTint, true).apply {
                gravity = Gravity.CENTER; background = if (sel) fill(pal.solid, 999f) else null
                kit.tap(this, 999f, sel) { mode = k; scroll.scrollTo(0, 0); rebuild() }
            }, LinearLayout.LayoutParams(0, dp(36), 1f))
        }
        val t = tt()
        when (mode) {
            0 -> {
                hint.text = getString(R.string.hm_wk_edit_hint); tabs.visibility = View.VISIBLE
                for (d in 1..7) {
                    val sel = d == cur
                    tabs.addView(text(dayName(d), 14f, if (sel) pal.onSolid else pal.onTint, true).apply {
                        gravity = Gravity.CENTER; background = fill(if (sel) pal.solid else pal.tint, 999f)
                        kit.tap(this, 999f, sel) { cur = d; rebuild() }
                    }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { rightMargin = if (d < 7) dp(4) else 0 })
                }
                var lastEnd: Int? = null
                for (pm in listOf(false, true)) {
                    val b = TimetableLogic.bells(t, pm); val l = TimetableLogic.session(t, cur, pm)
                    if (pm && lastEnd != null && b.isNotEmpty() && b[0][0] > lastEnd) gapRow(getString(R.string.hm_wk_lunch) + " · " + TimetableLogic.hm(lastEnd) + " – " + TimetableLogic.hm(b[0][0])) { editGap(true, 0, true) }
                    section(getString(if (pm) R.string.hm_wk_pm else R.string.hm_wk_am))
                    for (i in 0 until maxOf(b.size, l.size)) {
                        if (i > 0 && i < b.size && b[i][0] - b[i - 1][1] >= TimetableLogic.RECESS) gapRow(getString(R.string.hm_wk_recess) + " · " + mins(b[i - 1][1], b[i][0])) { editGap(pm, i, false) }
                        val s = l.getOrElse(i) { "" }
                        row(getString(R.string.hm_wk_period, i + 1), b.getOrNull(i)?.let { TimetableLogic.hm(it[0]) + " – " + TimetableLogic.hm(it[1]) },
                            s.ifEmpty { getString(R.string.hm_wk_free) }, if (s.isEmpty()) pal.sub else pal.text,
                            { editSubject(cur, pm, i) }, { clearSubject(cur, pm, i) })
                    }
                    if (b.isNotEmpty()) lastEnd = b.last()[1]
                }
                action(R.drawable.li_clock, getString(R.string.hm_wk_change_bells)) { mode = 2; scroll.scrollTo(0, 0); rebuild() }
                val prev = if (cur == 1) 7 else cur - 1
                if (TimetableLogic.day(t, cur).isEmpty() && TimetableLogic.day(t, prev).isNotEmpty())
                    action(R.drawable.li_calendar, getString(R.string.hm_wk_copy)) { saveTt(TimetableLogic.copyDay(tt(), prev, cur)); rebuild() }
            }
            1 -> {
                hint.text = getString(R.string.hm_wk_week_hint); tabs.visibility = View.GONE
                val days = TimetableLogic.weekDays(t)
                // in the editor every bell period gets a row, so an empty week can be filled from here too
                val am = maxOf(TimetableLogic.rows(t, days, false), TimetableLogic.bells(t, false).size)
                val pmN = maxOf(TimetableLogic.rows(t, days, true), TimetableLogic.bells(t, true).size)
                val cal = java.util.Calendar.getInstance()
                val g = WeekGrid(this, pal.text, pal.sub, pal.tint, pal.solid, pal.onSolid, pal.stroke).apply {
                    set(days, days.map { dayName(it) }, am, pmN, { d, pm, i -> TimetableLogic.session(t, d, pm).getOrElse(i) { "" } }, getString(R.string.hm_wk_pm))
                    today = startDow; now = nowCell(t, startDow, cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE))
                    onCell = { d, pm, i -> editSubject(d, pm, i) }
                }
                list.addView(g, LinearLayout.LayoutParams(-1, dp(34) * (am + pmN + 1) + dp(24)))
            }
            else -> {
                hint.text = getString(R.string.hm_wk_bells_hint); tabs.visibility = View.GONE
                var lastEnd: Int? = null
                for (pm in listOf(false, true)) {
                    val b = TimetableLogic.bells(t, pm)
                    if (pm && lastEnd != null && b.isNotEmpty() && b[0][0] > lastEnd) gapRow(getString(R.string.hm_wk_lunch) + " · " + mins(lastEnd, b[0][0])) { editGap(true, 0, true) }
                    section(getString(if (pm) R.string.hm_wk_pm else R.string.hm_wk_am))
                    for ((i, p) in b.withIndex()) {
                        if (i > 0) gapRow(getString(if (p[0] - b[i - 1][1] >= TimetableLogic.RECESS) R.string.hm_wk_recess else R.string.hm_wk_change) + " · " + mins(b[i - 1][1], p[0])) { editGap(pm, i, false) }
                        row(getString(R.string.hm_wk_period, i + 1), null, TimetableLogic.hm(p[0]) + " – " + TimetableLogic.hm(p[1]) + "  (" + mins(p[0], p[1]) + ")", pal.text,
                            { editBell(pm, i) }, { saveTt(TimetableLogic.withBells(tt(), pm, b.filterIndexed { j, _ -> j != i })); rebuild() })
                    }
                    val acts = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                    list.addView(acts)
                    if (b.size < TimetableLogic.MAX_SESSION) acts.addView(text(getString(R.string.hm_wk_add_period), 14f, pal.accentText, true).apply {
                        setPadding(dp(4), dp(10), dp(16), dp(10)); Ic.lead(this, R.drawable.li_plus, pal.accentText); compoundDrawablePadding = dp(6)
                        kit.tap(this, 10f) { editBell(pm, null) }
                    })
                    acts.addView(text(getString(R.string.hm_wk_quick), 14f, pal.accentText, true).apply {
                        setPadding(dp(4), dp(10), dp(4), dp(10)); Ic.lead(this, R.drawable.li_clock, pal.accentText); compoundDrawablePadding = dp(6)
                        kit.tap(this, 10f) { quick(pm) }
                    })
                    if (b.isNotEmpty()) lastEnd = b.last()[1]
                }
            }
        }
    }
    rebuild()
    scrim.addView(sheet, FrameLayout.LayoutParams(-1, (root.height * 0.88f).toInt(), Gravity.BOTTOM))
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
