package dev.kidguard

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date

/**
 * The launcher's "-1" page ("Hôm nay", swipe right from page 1; protocol "v0.9.9: kid launcher"): an SOS row that is
 * always there, today's schedule (LauncherLogic.today), chores (they go to the parent as a time request, like Xin giờ)
 * and the kid's own homework, notes ("Bố mẹ xem được" per note, KidNotes) and the shared Download/KidGuard folder.
 * Opaque pal.bg surface: every text is a tested pair (HomeStyleTest).
 */
class TodayPage(
    private val act: Activity, private val k: HomeKit, private val u: Ui, private val acts: KidActions,
    private val model: () -> HomeModel?, private val save: (HomeModel) -> Unit, private val openMain: () -> Unit,
) {
    private val pal = k.pal
    private fun dp(v: Int) = k.dp(v)
    private fun s(id: Int, vararg a: Any) = act.getString(id, *a)
    val view = ScrollView(act).apply { isVerticalScrollBarEnabled = false; isFillViewport = true; setBackgroundColor(pal.bg); overScrollMode = View.OVER_SCROLL_NEVER }
    private val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    private var notesOpen = false
    var padTop = 0; var padBottom = 0

    init { view.addView(col, FrameLayout.LayoutParams(-1, -2)); view.clipToPadding = true }

    // the status bar strip is the scroll view's padding (clipped): scrolled cards no longer run under the clock
    fun pad(top: Int, bottom: Int) { padTop = top; padBottom = bottom; view.setPadding(0, top, 0, 0); col.setPadding(dp(16), dp(12), dp(16), bottom + dp(20)) }

    /** Rebuilds everything (cheap: a few dozen views). Cards the kid turned off (HomeModel.todayHide) are left out. */
    internal fun fill(r: Rules, dv: HomeActivity.View0, block: String?, all: Map<String, HomeActivity.AppE>, icon: (HomeActivity.AppE, Int) -> View) {
        val st = Store.of(act)
        val hide = model()?.todayHide ?: emptySet()
        col.removeAllViews(); until = null
        pad(padTop, padBottom)
        header(r, st, dv.now)
        if ("now" !in hide) hero(r, st, dv, block)
        if ("time" !in hide) screen(st, r, dv, block, all, icon)
        if ("sched" !in hide) schedule(r, dv.minute, dv.dow)
        if ("chores" !in hide) chores(r, st)
        if ("tomorrow" !in hide) tomorrow(dv)
        if ("notes" !in hide) notes()
        if ("files" !in hide) files()
        col.addView(k.text(s(R.string.hm_t_cards), 14f, pal.accentText, true).apply {
            setPadding(dp(12), dp(10), dp(12), dp(10)); gravity = Gravity.CENTER_VERTICAL
            Ic.lead(this, R.drawable.li_sliders_horizontal, pal.accentText); compoundDrawablePadding = dp(8)
            k.tap(this, 12f) { pickCards() }
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(14); gravity = Gravity.CENTER_HORIZONTAL })
    }

    /** Every minute (the launcher's clock tick): the countdown line of "Bây giờ", without a rebuild. */
    fun tick(minute: Int) { until?.let { (t, at) -> untilText(t, at.first, at.second, minute) } }
    private var until: Pair<TextView, Pair<Int, String>>? = null
    private fun untilText(t: TextView, at: Int, name: String, minute: Int) {
        val left = at - minute
        t.visibility = if (left in 1..180) View.VISIBLE else View.GONE
        if (left > 0) t.text = s(R.string.hm_t_until, durText(left * 60_000L), name.ifBlank { s(R.string.hm_t_bed_word) }, HomeLogic.clock(at))
    }

    private fun durText(ms: Long) = HomeLogic.hm(ms).let { (h, m) -> if (h == 0) s(R.string.home_m, m) else if (m == 0) s(R.string.home_h, h) else s(R.string.home_hm, h, m) }

    // ---- choose cards ----

    private val cardIds = listOf("now" to R.string.hm_t_now_card, "time" to R.string.hm_t_screen, "sched" to R.string.hm_t_schedule,
        "chores" to R.string.hm_t_chores, "tomorrow" to R.string.hm_t_tomorrow_card, "notes" to R.string.hm_t_notes, "files" to R.string.hm_t_files)

    private fun pickCards() {
        val m0 = model() ?: return
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        box.addView(k.text(s(R.string.hm_t_cards_sub), 13f, u.sub).apply { setPadding(dp(2), 0, 0, dp(6)) })
        val sws = cardIds.map { (id, name) ->
            Switch(act).apply {
                text = s(name); setTextColor(u.text); textSize = 16f; isChecked = id !in m0.todayHide
                setPadding(dp(2), dp(7), 0, dp(7))
                thumbTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(pal.accentText, pal.sheet))
                trackTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(pal.accentText, pal.sub))
            }.also { box.addView(it, LinearLayout.LayoutParams(-1, -2)) }
        }
        // scrolls: 7 switches don't fit a small phone at a big font
        val scroll = ScrollView(act).apply { isVerticalScrollBarEnabled = false; addView(box) }
        Modal(act, u).show(R.drawable.li_sliders_horizontal, Modal.Tone.INFO, s(R.string.hm_t_cards), content = scroll, ok = s(R.string.hm_t_save), cancel = s(R.string.cancel)) {
            val hide = cardIds.indices.filter { !sws[it].isChecked }.map { cardIds[it].first }.toSet()
            model()?.let { mm -> if (mm.todayHide != hide) save(mm.copy(todayHide = hide)) }; true
        }
    }

    private fun lp(top: Int = 12) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }

    private fun header(r: Rules, st: Store, now: Long) {
        val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val t = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        t.addView(k.text(s(R.string.hm_t_title), 30f, pal.text, true).apply { maxLines = 1; if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true })
        val date = SimpleDateFormat("EEEE d/M", act.resources.configuration.locales[0]).format(Date(now)).replaceFirstChar { it.titlecase() }
        t.addView(k.text(date, 14f, pal.sub).apply { setPadding(0, dp(2), 0, 0) })
        if (st.sos().length() > 0) t.addView(k.text(s(R.string.home_sos) + " · " + s(R.string.hm_q_sent), 13f, pal.dangerText, true).apply { setPadding(0, dp(2), 0, 0) })
        row.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
        // call: a number in the lock-screen contact ("Gọi mẹ 0912…"), else just the phone app
        val num = Regex("\\+?\\d[\\d .-]{6,}\\d").find(r.lockContact)?.value?.replace(Regex("[ .-]"), "")
        row.addView(ImageView(act).apply {
            setImageDrawable(Ic.get(act, R.drawable.li_phone, pal.text, dp(22))); scaleType = ImageView.ScaleType.CENTER
            background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(pal.sheet); setStroke(dp(1), pal.stroke) }
            contentDescription = s(R.string.hm_t_call); k.tap(this, 26f) { dial(num) }
        }, LinearLayout.LayoutParams(dp(52), dp(52)).apply { marginEnd = dp(10) })
        // SOS: always here, can't be hidden
        row.addView(ImageView(act).apply {
            setImageDrawable(Ic.get(act, R.drawable.li_siren, pal.onDanger, dp(24))); scaleType = ImageView.ScaleType.CENTER
            background = k.circle(pal.danger); contentDescription = s(R.string.main_sos)
            k.tap(this, 26f) { if (st.everPaired) acts.askSos() else dial("112") }
        }, LinearLayout.LayoutParams(dp(52), dp(52)))
        col.addView(row, lp(0))
    }

    // ---- Bây giờ: greeting, the school period now / next, countdown to the next lock or bedtime ----

    private fun hero(r: Rules, st: Store, dv: HomeActivity.View0, block: String?) {
        val m = model() ?: return
        val c = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; background = k.fill(pal.tint, 22f); setPadding(dp(18), dp(16), dp(18), dp(16)) }
        val h = dv.minute / 60
        val hi = when { h < 5 || h >= 22 -> R.string.hm_t_hi_night; h < 11 -> R.string.hm_t_hi_morning; h < 13 -> R.string.hm_t_hi_noon; h < 18 -> R.string.hm_t_hi_afternoon; else -> R.string.hm_t_hi_evening }
        val top = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(ImageView(act).apply { setImageDrawable(Ic.get(act, if (h in 6..17) R.drawable.li_sun else R.drawable.li_moon, pal.onTint, dp(22))); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
            LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(10) })
        top.addView(k.text(s(hi), 20f, pal.onTint, true).apply { maxLines = 2 }, LinearLayout.LayoutParams(0, -2, 1f))
        c.addView(top)
        val lines = ArrayList<String>()
        if (block != null) lines += s(R.string.hm_t_locked, BasicLogic.reasonText(block, st.tr))
        val (cur, next) = TimetableLogic.at(TimetableLogic.slots(m.timetable, dv.dow), dv.minute)
        when (cur?.kind) {
            TimetableLogic.Kind.CLASS -> lines += s(R.string.hm_t_class_now, cur.subject, cur.period, HomeLogic.clock(cur.end))
            TimetableLogic.Kind.RECESS -> lines += s(R.string.hm_t_recess, HomeLogic.clock(cur.end))
            TimetableLogic.Kind.LUNCH -> lines += s(R.string.hm_t_lunch, HomeLogic.clock(cur.end))
            null -> {}
        }
        if (next != null && cur?.kind != TimetableLogic.Kind.CLASS) lines += s(R.string.hm_t_class_next, next.subject, HomeLogic.clock(next.start))
        for (l in lines) c.addView(k.text(l, 15f, pal.onTint).apply { setPadding(0, dp(6), 0, 0); maxLines = 3 })
        val ev = LauncherLogic.nextEvent(r, dv.minute, dv.dow)
        if (ev != null) {
            val t = k.text("", 15f, pal.onTint, true).apply { setPadding(0, dp(6), 0, 0); maxLines = 2 }
            untilText(t, ev.first, ev.second, dv.minute); until = t to ev
            c.addView(t)
        }
        if (lines.isEmpty() && h in 5..21 && (ev == null || ev.first - dv.minute > 180)) c.addView(k.text(s(R.string.hm_t_nice_day), 15f, pal.onTint).apply { setPadding(0, dp(6), 0, 0) })
        col.addView(c, lp(16))
    }

    // ---- Thời gian dùng máy: ring + left, top 3 apps, ask for more ----

    private fun screen(st: Store, r: Rules, dv: HomeActivity.View0, block: String?, all: Map<String, HomeActivity.AppE>, icon: (HomeActivity.AppE, Int) -> View) {
        val top = dv.usage.entries.filter { it.value >= 60_000 && it.key != act.packageName && it.key in all && it.key in dv.apps && it.key !in dv.prot && it.key !in r.allow }
            .sortedByDescending { it.value }
        val used = top.sumOf { it.value }
        val limitMs = (baseLimitMin(r, dv.today).takeIf { it > 0 }?.let { it + if (r.bonusDay == dv.today) r.bonusMin else 0 } ?: 0) * 60_000L
        if (limitMs <= 0 && used == 0L) return   // no limit, nothing used yet: nothing worth a card
        val c = card()
        title(c, R.drawable.li_timer, s(R.string.hm_t_screen))
        val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(10), 0, 0) }
        val low = limitMs > 0 && dv.leftMs in 0 until 10 * 60_000L
        if (limitMs > 0) row.addView(UseRing(act, (1f - dv.leftMs.coerceAtLeast(0).toFloat() / limitMs).coerceIn(0f, 1f), if (low) pal.warn else pal.solid, pal.stroke, k.dpf(7f)),
            LinearLayout.LayoutParams(dp(60), dp(60)).apply { marginEnd = dp(14) })
        val t = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        t.addView(k.text(if (limitMs > 0) s(R.string.hm_t_left, durText(dv.leftMs.coerceAtLeast(0))) else s(R.string.hm_t_no_limit), 20f, if (low) pal.warnText else pal.text, true).apply { maxLines = 2 })
        t.addView(k.text(s(R.string.hm_t_used, durText(if (limitMs > 0) (limitMs - dv.leftMs.coerceAtLeast(0)).coerceAtLeast(0) else used)), 13f, pal.sub).apply { setPadding(0, dp(2), 0, 0) })
        row.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
        c.addView(row)
        if (top.isNotEmpty()) {
            val apps = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(12), 0, 0) }
            for ((pkg, ms) in top.take(3)) {
                val a = all[pkg] ?: continue
                val one = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; contentDescription = a.label + " " + durText(ms) }
                one.addView(icon(a, 26), LinearLayout.LayoutParams(dp(26), dp(26)))
                one.addView(k.text(durText(ms), 13f, pal.sub).apply { setPadding(dp(6), 0, 0, 0); maxLines = 1 })
                apps.addView(one, LinearLayout.LayoutParams(0, -2, 1f))
            }
            c.addView(apps)
        }
        if (HomeLogic.canAsk(st.everPaired, dv.leftMs, block) && limitMs > 0)
            c.addView(k.pill(s(R.string.main_more), R.drawable.li_plus, false) { acts.askMore() }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })
        col.addView(c, lp())
    }

    // ---- Ngày mai: tomorrow's subjects, from 17:00 ----

    private fun tomorrow(dv: HomeActivity.View0) {
        val m = model() ?: return
        if (!TimetableLogic.eveningFirst(dv.minute)) return
        val subj = TimetableLogic.distinct(TimetableLogic.day(m.timetable, TimetableLogic.tomorrow(dv.dow)))
        if (subj.isEmpty()) return
        val c = card()
        val day = SimpleDateFormat("EEEE", act.resources.configuration.locales[0]).format(Date(dv.now + 86_400_000L)).replaceFirstChar { it.titlecase() }
        title(c, R.drawable.li_calendar, s(R.string.hm_t_tomorrow, day))
        c.addView(k.text(subj.joinToString(" · "), 15f, pal.text, true).apply { setPadding(0, dp(10), 0, 0) })
        c.addView(k.text(s(R.string.hm_t_pack), 13f, pal.sub).apply { setPadding(0, dp(4), 0, 0) })
        col.addView(c, lp())
    }

    private fun card(): LinearLayout = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL; background = k.sheetCard(20f); setPadding(dp(16), dp(14), dp(16), dp(14))
    }

    private fun title(c: LinearLayout, icon: Int, t: String, right: String? = null, onRight: (() -> Unit)? = null) {
        val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(ImageView(act).apply { setImageDrawable(Ic.get(act, icon, pal.accentText, dp(18))); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, LinearLayout.LayoutParams(dp(18), dp(18)))
        row.addView(k.text(t, 16f, pal.text, true).apply { setPadding(dp(8), 0, 0, 0); if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true }, LinearLayout.LayoutParams(0, -2, 1f))
        if (right != null) row.addView(k.text(right, 13f, pal.sub, true).apply {
            setPadding(dp(8), dp(6), dp(4), dp(6)); if (onRight != null) k.tap(this, 10f) { onRight() }
        })
        c.addView(row, LinearLayout.LayoutParams(-1, -2))
    }

    private fun divider(c: LinearLayout) = c.addView(View(act).apply { setBackgroundColor(pal.stroke) }, LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(10); bottomMargin = dp(2) })

    private fun dial(n: String?) {
        val i = if (n == null) Intent(Intent.ACTION_DIAL) else Intent(Intent.ACTION_DIAL, Uri.parse("tel:$n"))
        runCatching { act.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { android.widget.Toast.makeText(act, R.string.hm_no_dialer, android.widget.Toast.LENGTH_SHORT).show() }
    }

    // ---- Lịch ----

    private fun schedule(r: Rules, minute: Int, dow: Int) {
        val evs = LauncherLogic.today(r, minute, dow)
        if (evs.isEmpty()) return   // an empty card is noise: "Hôm nay" shows what is there
        val c = card()
        title(c, R.drawable.li_calendar, s(R.string.hm_t_schedule))
        for ((i, e) in evs.withIndex()) {
            if (i > 0) divider(c)
            val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, dp(4)) }
            val past = e.state == "past"
            val hot = e.state == "now" || e.state == "next"
            row.addView(k.text(HomeLogic.clock(e.start), 14f, if (hot) pal.accentText else pal.sub, true).apply { minWidth = dp(58); maxLines = 1 }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
            val t = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            val name = when (e.kind) { "bed" -> s(R.string.hm_t_bed); "group" -> s(R.string.hm_t_group, e.name); else -> e.name.ifBlank { s(R.string.hm_t_lock) } }
            val sub = when (e.kind) {
                "bed" -> s(R.string.hm_t_bed_sub, HomeLogic.clock(e.end))
                "group" -> s(R.string.hm_t_group_sub, HomeLogic.clock(e.start), HomeLogic.clock(e.end))
                else -> s(R.string.hm_t_lock_sub, HomeLogic.clock(e.end))
            }
            t.addView(k.text(name, 15f, if (past) pal.sub else pal.text, true).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
            t.addView(k.text(sub, 12f, pal.sub).apply { setPadding(0, dp(2), 0, 0); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
            row.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
            when (e.state) {
                "now" -> row.addView(k.chip(s(R.string.hm_t_now), pal.onSolid, pal.solid))
                "next" -> row.addView(k.chip(s(R.string.hm_t_next), pal.onTint, pal.tint))
            }
            c.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        col.addView(c, lp())
    }

    // ---- Việc nhà và bài tập ----

    private fun chores(r: Rules, st: Store) {
        val m = model() ?: return
        val c = card()
        title(c, R.drawable.li_list_checks, s(R.string.hm_t_chores))
        val reqs = st.requests()
        val waiting = (0 until reqs.length()).mapNotNull { reqs.optJSONObject(it)?.optString("chore")?.takeIf { s -> s.isNotEmpty() } }.toSet()
        var n = 0
        for (ch in r.chores) {
            if (n++ > 0) divider(c)
            val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, dp(4)) }
            val wait = ch.id in waiting
            row.addView(k.badge(if (wait) R.drawable.li_check else R.drawable.li_list_checks, pal.onTint, pal.tint, 40, 20, square = true))
            val t = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(8), 0) }
            t.addView(k.text(ch.name, 15f, pal.text, true).apply { maxLines = 2 })
            t.addView(k.text(s(R.string.hm_t_plus_min, ch.min), 12f, pal.sub).apply { setPadding(0, dp(2), 0, 0) })
            row.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
            if (wait) row.addView(k.chip(s(R.string.hm_t_wait), pal.warnText, pal.warnTint, R.drawable.li_clock))
            else row.addView(k.text(s(R.string.hm_t_done), 14f, pal.onSolid, true).apply {
                background = k.fill(pal.solid, 14f); setPadding(dp(14), dp(9), dp(16), dp(9)); gravity = Gravity.CENTER_VERTICAL
                Ic.lead(this, R.drawable.li_check, pal.onSolid); compoundDrawablePadding = dp(6)
                k.tap(this, 14f, true) { choreDone(ch, st) }
                contentDescription = s(R.string.hm_t_done_desc, ch.name)
            })
            c.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        // the kid's own homework list: never leaves the phone
        val todos = KidNotes.todos(m.todo)
        for (td in todos) {
            if (n++ > 0) divider(c)
            val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, dp(4)) }
            row.addView(ImageView(act).apply {
                // done: accent-filled box with its tested glyph; open: a box outlined in sub (3:1 on the sheet)
                if (td.done) { setImageDrawable(Ic.get(act, R.drawable.li_check, pal.onSolid, dp(18))); background = k.fill(pal.solid, 8f) }
                else background = android.graphics.drawable.GradientDrawable().apply { setColor(pal.sheet); cornerRadius = k.dpf(8f); setStroke(dp(2), pal.sub) }
                setPadding(dp(4), dp(4), dp(4), dp(4)); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(26), dp(26)).apply { leftMargin = dp(7); rightMargin = dp(7) })
            row.addView(k.text(td.text, 15f, if (td.done) pal.sub else pal.text).apply {
                setPadding(dp(12), 0, dp(8), 0); maxLines = 3
                if (td.done) paintFlags = paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
            }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(k.text(s(R.string.hm_t_homework), 12f, pal.sub))
            k.tap(row, 12f) { model()?.let { mm -> save(mm.copy(todo = KidNotes.todosJson(KidNotes.toggle(KidNotes.todos(mm.todo), td.id)))) } }
            row.isLongClickable = true
            row.setOnLongClickListener { confirmDropTodo(td); true }
            row.contentDescription = (if (td.done) s(R.string.hm_t_done_state) + ": " else "") + td.text
            c.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        if (todos.size < KidNotes.TODO_MAX) c.addView(k.text(s(R.string.hm_t_add_homework), 14f, pal.accentText, true).apply {
            setPadding(dp(4), dp(12), dp(4), dp(4)); Ic.lead(this, R.drawable.li_plus, pal.accentText); compoundDrawablePadding = dp(8); gravity = Gravity.CENTER_VERTICAL
            k.tap(this, 10f) { addHomework() }
        }, LinearLayout.LayoutParams(-2, -2))
        col.addView(c, lp())
    }

    private fun choreDone(ch: Chore, st: Store) {
        if (!st.everPaired) {
            Modal(act, u).show(R.drawable.li_link, Modal.Tone.INFO, s(R.string.hm_pair_title), s(R.string.hm_pair_body), ok = s(R.string.hm_open_kg), cancel = s(R.string.cancel)) { openMain(); true }
            return
        }
        val ok = st.addRequest(ch.min, "", ch.id)
        if (ok) { st.log(R.string.log_more); Guard.poke(act) }
        Modal(act, u).show(if (ok) R.drawable.li_send else R.drawable.li_triangle_alert, if (ok) Modal.Tone.OK else Modal.Tone.WARN,
            s(if (ok) R.string.more_sent else R.string.more_fail), s(if (ok) R.string.hm_t_chore_sent else R.string.more_fail_body))
        refreshSoon()
    }

    private fun addHomework() {
        val md = Modal(act, u)
        val f = md.field(s(R.string.hm_t_homework_hint), KidNotes.TODO_TEXT_MAX)
        md.show(R.drawable.li_list_checks, Modal.Tone.INFO, s(R.string.hm_t_add_homework), content = f, ok = s(R.string.hm_t_add), cancel = s(R.string.cancel)) {
            val mm = model() ?: return@show true
            val l = KidNotes.addTodo(KidNotes.todos(mm.todo), f.text.toString())
            if (l.size == KidNotes.todos(mm.todo).size) return@show f.text.isBlank()
            save(mm.copy(todo = KidNotes.todosJson(l))); true
        }
        f.requestFocus()
    }

    private fun confirmDropTodo(td: KidNotes.Todo) {
        Modal(act, u).show(R.drawable.li_trash_2, Modal.Tone.DANGER, s(R.string.hm_t_drop_q), td.text, ok = s(R.string.hm_t_delete), cancel = s(R.string.cancel)) {
            model()?.let { mm -> save(mm.copy(todo = KidNotes.todosJson(KidNotes.dropTodo(KidNotes.todos(mm.todo), td.id)))) }; true
        }
    }

    // ---- Ghi chú ----

    private fun notes() {
        val m = model() ?: return
        val l = KidNotes.notes(m.notes)
        val c = card()
        title(c, R.drawable.li_sticky_note, s(R.string.hm_t_notes), if (l.size > 3) (if (notesOpen) s(R.string.hm_t_less) else s(R.string.hm_t_all, l.size)) else null) {
            notesOpen = !notesOpen; refreshSoon()
        }
        for ((i, n) in l.take(if (notesOpen) l.size else 3).withIndex()) {
            if (i > 0) divider(c)
            val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, dp(4)) }
            row.addView(k.text(n.text, 15f, pal.text).apply { maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END; includeFontPadding = true }, LinearLayout.LayoutParams(0, -2, 1f))
            if (n.share) row.addView(k.chip(s(R.string.hm_t_parents), pal.accentText, pal.tint, R.drawable.li_eye), LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
            k.tap(row, 12f) { editNote(n) }
            row.contentDescription = n.text + if (n.share) ". " + s(R.string.hm_t_share) else ""
            c.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        if (l.size < KidNotes.NOTES_MAX) c.addView(k.text(s(R.string.hm_t_add_note), 14f, pal.accentText, true).apply {
            setPadding(dp(4), dp(12), dp(4), dp(4)); Ic.lead(this, R.drawable.li_plus, pal.accentText); compoundDrawablePadding = dp(8); gravity = Gravity.CENTER_VERTICAL
            k.tap(this, 10f) { editNote(null) }
        }, LinearLayout.LayoutParams(-2, -2))
        col.addView(c, lp())
    }

    private fun editNote(n: KidNotes.Note?) {
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        val f = EditText(act).apply {
            setText(n?.text ?: ""); hint = s(R.string.hm_t_note_hint); setTextColor(u.text); setHintTextColor(u.sub); textSize = 16f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 3; maxLines = 8; gravity = Gravity.TOP or Gravity.START; filters = arrayOf(InputFilter.LengthFilter(KidNotes.TEXT_MAX))
            background = u.shape(u.bg, 14, u.stroke, 1); setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        box.addView(f, LinearLayout.LayoutParams(-1, -2))
        val sw = Switch(act).apply {
            text = s(R.string.hm_t_share); setTextColor(u.text); textSize = 15f; isChecked = n?.share ?: false
            setPadding(dp(2), dp(12), 0, dp(2))
            thumbTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(pal.accentText, pal.sheet))
            trackTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(pal.accentText, pal.sub))
        }
        box.addView(sw, LinearLayout.LayoutParams(-1, -2))
        box.addView(k.text(s(R.string.hm_t_share_sub), 12f, pal.sub).apply { setPadding(dp(2), dp(4), 0, 0); includeFontPadding = true })
        var dlg: android.app.Dialog? = null
        if (n != null) box.addView(k.text(s(R.string.hm_t_delete), 15f, pal.dangerText, true).apply {
            setPadding(dp(4), dp(14), dp(4), dp(4)); Ic.lead(this, R.drawable.li_trash_2, pal.dangerText); compoundDrawablePadding = dp(8); gravity = Gravity.CENTER_VERTICAL
            k.tap(this, 10f) {
                model()?.let { mm -> putNote(mm, n.id, "", false) }
                dlg?.dismiss()
            }
        }, LinearLayout.LayoutParams(-2, -2))
        dlg = Modal(act, u).show(R.drawable.li_sticky_note, Modal.Tone.INFO, s(if (n == null) R.string.hm_t_add_note else R.string.hm_t_edit_note), content = box,
            ok = s(R.string.hm_t_save), cancel = s(R.string.cancel)) {
            model()?.let { mm -> putNote(mm, n?.id, f.text.toString(), sw.isChecked) }; true
        }
        if (n == null) f.requestFocus()
    }

    private fun putNote(mm: HomeModel, id: String?, text: String, share: Boolean) {
        val before = KidNotes.notes(mm.notes)
        val after = KidNotes.putNote(before, id, text, share, System.currentTimeMillis())
        if (after == before) return
        save(mm.copy(notes = KidNotes.notesJson(after)))
        // a shared note changed (added, edited, unshared, deleted): tell the parent side soon
        if (before.any { it.share } || after.any { it.share }) Guard.poke(act)
    }

    // ---- Thư mục KidGuard ----

    private fun files() {
        // both ways: parent -> kid ("in", opens here) and what the kid sent ("out", the parent page downloads it)
        val l = runCatching { Files.all(act) }.getOrDefault(emptyList()).filter { it.st != FilesLogic.GONE }.sortedByDescending { it.at }
        val paired = Store.of(act).everPaired
        if (l.isEmpty() && !paired) return
        val c = card()
        title(c, R.drawable.li_folder, s(R.string.hm_t_files), if (l.size > 3) l.size.toString() else null)
        for ((i, e) in l.take(3).withIndex()) {
            if (i > 0) divider(c)
            val out = e.dir == FilesLogic.OUT
            val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, dp(4)) }
            row.addView(k.badge(if (out) R.drawable.li_send else R.drawable.li_file, pal.onTint, pal.tint, 40, 20, square = true))
            val t = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(8), 0) }
            t.addView(k.text(e.name, 15f, pal.text, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE })
            val ago = android.text.format.DateUtils.getRelativeTimeSpanString(e.at, System.currentTimeMillis(), 60_000L)
            t.addView(k.text(s(if (out) R.string.hm_t_to_parent else R.string.hm_t_from_parent, ago), 12f, pal.sub).apply { setPadding(0, dp(2), 0, 0) })
            row.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
            if (out) { if (e.st == FilesLogic.FETCHED) row.addView(k.chip(s(R.string.hm_t_fetched), pal.onTint, pal.tint, R.drawable.li_check)) }
            else {
                if (e.st != FilesLogic.OPENED) row.addView(View(act).apply { background = k.circle(pal.danger); contentDescription = s(R.string.hm_t_new) }, LinearLayout.LayoutParams(dp(9), dp(9)))
                k.tap(row, 12f) {
                    val i2 = runCatching { Files.viewIntent(act, e.id) }.getOrNull()
                    if (i2 == null || runCatching { act.startActivity(i2.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isFailure)
                        android.widget.Toast.makeText(act, R.string.hm_t_file_fail, android.widget.Toast.LENGTH_SHORT).show()
                    refreshSoon()
                }
            }
            c.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        // the system file picker, then the same outbox as the share sheet's "Gửi cho bố mẹ" (ShareInActivity)
        if (paired) c.addView(k.text(s(R.string.files_menu), 14f, pal.accentText, true).apply {
            setPadding(dp(4), dp(12), dp(4), dp(4)); Ic.lead(this, R.drawable.li_send, pal.accentText); compoundDrawablePadding = dp(8); gravity = Gravity.CENTER_VERTICAL
            k.tap(this, 10f) { ShareInActivity.pick(act) }
        }, LinearLayout.LayoutParams(-2, -2))
        col.addView(c, lp())
    }

    var onRefresh: () -> Unit = {}
    private fun refreshSoon() = view.post { onRefresh() }

    /** Counts for the 2x2 widget: chores to do (not waiting), notes, new files. */
    companion object {
        fun counts(act: Activity, r: Rules, m: HomeModel): Triple<Int, Int, Int> {
            val reqs = Store.of(act).requests()
            val waiting = (0 until reqs.length()).mapNotNull { reqs.optJSONObject(it)?.optString("chore") }.toSet()
            val chores = r.chores.count { it.id !in waiting } + KidNotes.todos(m.todo).count { !it.done }
            val files = runCatching { Files.all(act) }.getOrDefault(emptyList()).count { it.dir == "in" && it.st != "gone" && it.st != "opened" }
            return Triple(chores, KidNotes.notes(m.notes).size, files)
        }
    }
}

/** "Thời gian dùng máy": the part of today's limit already used, as an arc from 12 o'clock. */
private class UseRing(ctx: android.content.Context, val f: Float, val fg: Int, val bg: Int, val w: Float) : View(ctx) {
    private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.STROKE; strokeWidth = w; strokeCap = android.graphics.Paint.Cap.ROUND }
    private val r = android.graphics.RectF()
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    override fun onDraw(c: android.graphics.Canvas) {
        r.set(w / 2, w / 2, width - w / 2, height - w / 2)
        p.color = bg; c.drawOval(r, p)
        if (f > 0f) { p.color = fg; c.drawArc(r, -90f, 360f * f.coerceAtLeast(0.02f), false, p) }
    }
}
