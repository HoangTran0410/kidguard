package dev.kidguard

import android.app.TimePickerDialog
import android.view.Gravity
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Calendar

// Study mode ("Chế độ Học bài") UI on the kid launcher: home filter, countdown chip, sheet, pickers. Logic: FocusLogic, storage: FocusStore.

private var chipText: TextView? = null
private var chipSession: FocusLogic.Session? = null

/** Render hook: the show map with every non-study app hidden while study mode (session or schedule) is on. Parent blocks stay as they are. */
internal fun HomeActivity.focusShow(show: Map<String, LauncherLogic.Show>, dv: HomeActivity.View0): Map<String, LauncherLogic.Show> {
    val s = FocusStore.expire(this, dv.now)
    val mode = FocusLogic.mode(s, FocusStore.slots(this), dv.now, dv.dow, dv.minute)
    FocusStore.mode = mode; chipSession = s
    if (mode == FocusLogic.Mode.OFF) return show
    val allowed = FocusLogic.allowed(FocusStore.study(this), listOf(dialer(), packageName))
    return show.mapValues { (pkg, v) -> if (FocusLogic.visible(mode, pkg, v != LauncherLogic.Show.HIDE, allowed)) v else LauncherLogic.Show.HIDE }
}

/** Part of the render key: a mode or session change must rebuild even when the visible app set happens to be the same. */
internal fun focusKey(ctx: android.content.Context) = FocusStore.mode.name + (chipSession?.end ?: 0L)

/** After buildBanner: the "Đang học bài · còn N phút" chip under the banner (tap = the study sheet). */
internal fun HomeActivity.focusChip() {
    chipText = null
    if (FocusStore.mode == FocusLogic.Mode.OFF) return
    val old = (0 until bannerBox.childCount).map { bannerBox.getChildAt(it) }
    bannerBox.removeAllViews()
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    for (v in old) col.addView(v, LinearLayout.LayoutParams(v.layoutParams as ViewGroup.MarginLayoutParams))
    val t = text("", 14f, pal.onSolid, true).apply {
        gravity = Gravity.CENTER; background = fill(pal.solid, 18f); setPadding(dp(14), dp(8), dp(14), dp(8)); elevation = dpf(2f)
        Ic.lead(this, R.drawable.li_timer, pal.onSolid); compoundDrawablePadding = dp(8)
        kit.tap(this, 18f, true) { focusSheet() }
    }
    col.addView(t, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(14), dp(6), dp(14), dp(2)) })
    bannerBox.addView(col, android.widget.FrameLayout.LayoutParams(-1, -2))
    chipText = t
    focusTick()
}

/** Every minute (also when the page did not rebuild): the chip's countdown. */
internal fun HomeActivity.focusTick() {
    val t = chipText ?: return
    val s = chipSession
    val now = System.currentTimeMillis()
    t.text = if (s != null) getString(R.string.focus_chip, FocusLogic.leftMin(s, now))
    else FocusStore.slots(this).let { l -> val c = Calendar.getInstance(); val dow = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7 + 1; val m = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        getString(R.string.focus_chip_sched, FocusLogic.clock(l.filter { it.has(dow) && m >= it.from && m < it.to }.maxOfOrNull { it.to } ?: m)) }
}

// ---- gate (parent PIN) ----

/** Runs [then] at once (Home flavor / no PIN), else after the parent PIN is typed right. */
private fun HomeActivity.gate(why: Int, then: () -> Unit) {
    val s = Store.of(this)
    if (!FocusLogic.needsPin(Flavor.HOME, s.rules.pinHash.isNotBlank())) { then(); return }
    fun lockText(left: Long) = ((left + 59_999) / 60_000).toInt().let { resources.getQuantityString(R.plurals.pin_locked, it, it) }
    s.pinLockLeft().takeIf { it > 0 }?.let { Modal(this, u).show(R.drawable.li_triangle_alert, Modal.Tone.WARN, getString(R.string.locked_title), lockText(it)); return }
    val m = Modal(this, u)
    val input = m.field(getString(R.string.pin_field), 12, pin = true)
    m.show(R.drawable.li_shield, Modal.Tone.INFO, getString(R.string.focus_end_t), getString(why), input, ok = getString(R.string.focus_end_ok), cancel = getString(R.string.cancel)) {
        if (s.checkParentPin(input.text.toString().filter { it.isDigit() })) { then(); true }
        else { input.setText(""); m.setError(s.pinLockLeft().takeIf { it > 0 }?.let { lockText(it) } ?: getString(R.string.pin_wrong)); false }
    }
}

private fun HomeActivity.endFocus() {
    val ok = { FocusStore.end(this); key = ""; render(true) }
    if (FocusLogic.needsPin(Flavor.HOME, Store.of(this).rules.pinHash.isNotBlank())) gate(R.string.focus_end_pin, ok)
    else Modal(this, u).show(R.drawable.li_timer, Modal.Tone.INFO, getString(R.string.focus_end_t), getString(R.string.focus_end_b), ok = getString(R.string.focus_end_ok), cancel = getString(R.string.cancel)) { ok(); true }
}

// ---- sheet ----

internal fun HomeActivity.focusSheet() {
    val sh = Sheet(this, u, getString(R.string.focus_title))
    val b = sh.body
    val lp = { LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(12), dp(8), dp(12), 0) } }
    val live = FocusStore.expire(this)
    val study = FocusStore.study(this)
    if (live != null) {
        b.addView(text(getString(R.string.focus_chip, FocusLogic.leftMin(live, System.currentTimeMillis())), 18f, pal.text, true), lp())
        if (live.dndSet) b.addView(text(getString(R.string.focus_dnd_on), 13f, pal.sub), lp())
        b.addView(kit.pill(getString(R.string.focus_end), R.drawable.li_x, false) { sh.dismiss(); main.post { endFocus() } }, lp())
    } else if (FocusStore.mode == FocusLogic.Mode.SCHEDULE) {
        b.addView(text(chipText?.text?.toString() ?: getString(R.string.focus_title), 16f, pal.text, true), lp())
    } else {
        b.addView(text(getString(R.string.focus_sub), 14f, pal.sub), lp())
        if (!FocusStore.dndAccess(this)) b.addView(text(getString(R.string.focus_dnd_off), 13f, pal.sub), lp())
        if (study.isEmpty()) b.addView(text(getString(R.string.focus_apps_empty), 13f, pal.warnText, true), lp())
        for (m in FocusLogic.DURATIONS) b.addView(kit.pill(getString(R.string.focus_start_n, m), R.drawable.li_timer, m == FocusLogic.DURATIONS[1]) {
            if (FocusStore.study(this).isEmpty()) { sh.dismiss(); main.post { pickStudy() }; return@pill }
            sh.dismiss(); FocusStore.start(this, m); key = ""; render(true)
        }, lp())
    }
    b.addView(kit.pill(getString(R.string.focus_apps, study.size), null, false) { sh.dismiss(); main.post { pickStudy() } }, lp())
    val sl = FocusStore.slots(this)
    b.addView(kit.pill(getString(R.string.focus_sched) + " · " + (if (sl.isEmpty()) getString(R.string.focus_sched_none) else sl.size.toString()), R.drawable.li_clock, false) {
        sh.dismiss(); main.post { gate(R.string.focus_sched_pin) { editSlots() } }
    }, lp())
    sh.show()
}

private fun HomeActivity.pickStudy() {
    val chosen = FocusStore.study(this).toMutableSet()
    val list = launcherApps().values.filter { lastShow[it.pkg] != LauncherLogic.Show.HIDE || it.pkg in chosen }.sortedBy { it.sortKey }
    appPickSheet(getString(R.string.focus_apps_t), list, chosen) { picked ->
        val keep = chosen - list.map { it.pkg }.toSet()   // apps not listed (parent-hidden) keep their mark
        FocusStore.setStudy(this, keep + picked.filter { p -> list.any { it.pkg == p } })
        key = ""; render(true)
    }
}

// ---- schedule editor ----

private fun daysText(d: Int): String {
    val loc = java.util.Locale.getDefault()
    return if (d == FocusLogic.ALL_DAYS) "" else (1..7).filter { d and (1 shl (it - 1)) != 0 }.joinToString(" ") { DayOfWeek.of(it).getDisplayName(TextStyle.SHORT, loc) }
}

private fun HomeActivity.slotLine(s: FocusLogic.Slot) = (daysText(s.days) + " " + FocusLogic.clock(s.from) + "-" + FocusLogic.clock(s.to)).trim()

private fun HomeActivity.editSlots() {
    val list = FocusStore.slots(this).toMutableList()
    val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    fun fillBox() {
        box.removeAllViews()
        for (s in list) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(text(slotLine(s), 15f, pal.text), LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(text(getString(R.string.focus_remove), 14f, pal.dangerText, true).apply { setPadding(dp(8), dp(10), dp(4), dp(10)); setOnClickListener { list.remove(s); fillBox() } })
            box.addView(row)
        }
        if (list.size < FocusLogic.MAX_SLOTS) box.addView(kit.pill(getString(R.string.focus_add), R.drawable.li_plus, false) { addSlot { list += it; fillBox() } },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    }
    fillBox()
    Modal(this, u).show(R.drawable.li_clock, Modal.Tone.INFO, getString(R.string.focus_sched_t), content = ScrollView(this).apply { addView(box) },
        ok = getString(R.string.hm_t_save), cancel = getString(R.string.cancel)) { FocusStore.setSlots(this, list); key = ""; render(true); true }
}

private fun HomeActivity.addSlot(done: (FocusLogic.Slot) -> Unit) {
    var from = 19 * 60; var to = 21 * 60
    val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val days = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    val loc = java.util.Locale.getDefault()
    val cbs = (1..7).map { d -> CheckBox(this).apply { isChecked = d <= 5; text = DayOfWeek.of(d).getDisplayName(TextStyle.NARROW, loc); setTextColor(u.text); textSize = 13f; setPadding(0, 0, dp(2), 0) }.also { days.addView(it, LinearLayout.LayoutParams(0, -2, 1f)) } }
    box.addView(days)
    fun timeBtn(label: Int, get: () -> Int, set: (Int) -> Unit): TextView = TextView(this).also { t ->
        fun paint() { t.text = getString(label) + "  " + FocusLogic.clock(get()) }
        paint(); t.setTextColor(u.text); t.textSize = 17f; t.setPadding(dp(4), dp(12), dp(4), dp(12))
        t.setOnClickListener { TimePickerDialog(this, { _, h, m -> set(h * 60 + m); paint() }, get() / 60, get() % 60, true).show() }
    }
    box.addView(timeBtn(R.string.focus_from, { from }) { from = it }); box.addView(timeBtn(R.string.focus_to, { to }) { to = it })
    val m = Modal(this, u)
    m.show(R.drawable.li_clock, Modal.Tone.INFO, getString(R.string.focus_add), content = box, ok = getString(R.string.hm_t_save), cancel = getString(R.string.cancel)) {
        val mask = cbs.withIndex().sumOf { (i, c) -> if (c.isChecked) 1 shl i else 0 }
        when {
            mask == 0 -> { m.setError(getString(R.string.focus_no_day)); false }
            to <= from -> { m.setError(getString(R.string.focus_bad_range)); false }
            else -> { done(FocusLogic.Slot(mask, from, to)); true }
        }
    }
}

// ---- Today card ----

/** Today page hook: a small "Học bài" card (tap = the sheet). */
internal fun focusCard(act: android.app.Activity, col: LinearLayout) {
    val h = act as? HomeActivity ?: return
    val on = FocusStore.mode != FocusLogic.Mode.OFF
    val t = h.text(if (on) h.getString(R.string.focus_chip, chipSession?.let { FocusLogic.leftMin(it, System.currentTimeMillis()) } ?: 0).takeIf { chipSession != null }
        ?: h.getString(R.string.focus_title) else h.getString(R.string.focus_card_idle), 16f, h.pal.text, true).apply {
        setPadding(h.dp(16), h.dp(14), h.dp(16), h.dp(14)); background = h.kit.card(22f); Ic.lead(this, R.drawable.li_timer, h.pal.accentText); compoundDrawablePadding = h.dp(10)
        h.kit.tap(this, 22f) { h.focusSheet() }
    }
    col.addView(t, LinearLayout.LayoutParams(-1, -2).apply { topMargin = h.dp(12) })
}

/** Long-press menu entry helper. */
internal fun HomeActivity.focusRow() = HomeActivity.Row(R.drawable.li_timer, getString(R.string.focus_title), true) { focusSheet() }
