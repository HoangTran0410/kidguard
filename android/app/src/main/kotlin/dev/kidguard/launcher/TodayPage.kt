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

    init { view.addView(col, FrameLayout.LayoutParams(-1, -2)) }

    fun pad(top: Int, bottom: Int) { padTop = top; padBottom = bottom; col.setPadding(dp(16), top + dp(16), dp(16), bottom + dp(20)) }

    /** Rebuilds everything (cheap: a few dozen views). */
    fun fill(r: Rules, minute: Int, dow: Int, now: Long) {
        val st = Store.of(act)
        col.removeAllViews()
        col.setPadding(dp(16), padTop + dp(16), dp(16), padBottom + dp(20))
        header(now)
        sosRow(r, st)
        schedule(r, minute, dow)
        chores(r, st)
        notes()
        files()
    }

    private fun lp(top: Int = 12) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }

    private fun header(now: Long) {
        val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val t = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        t.addView(k.text(s(R.string.hm_t_title), 30f, pal.text, true).apply { if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true })
        val date = SimpleDateFormat("EEEE d/M", act.resources.configuration.locales[0]).format(Date(now)).replaceFirstChar { it.titlecase() }
        t.addView(k.text(date, 14f, pal.sub).apply { setPadding(0, dp(4), 0, 0) })
        row.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(k.text("KidGuard", 14f, pal.accentText, true).apply {
            background = k.sheetCard(999f); setPadding(dp(14), dp(10), dp(16), dp(10)); gravity = Gravity.CENTER_VERTICAL
            Ic.lead(this, R.drawable.li_shield, pal.accentText); compoundDrawablePadding = dp(6)
            k.tap(this, 999f) { openMain() }
            contentDescription = s(R.string.hm_open_kg)
        })
        col.addView(row, lp(0))
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

    // ---- SOS: always first, can't be removed ----

    private fun sosRow(r: Rules, st: Store) {
        val row = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(12), dp(12), dp(12))
            background = android.graphics.drawable.GradientDrawable().apply { setColor(pal.sheet); cornerRadius = k.dpf(20f); setStroke(dp(2), pal.danger) }
        }
        val sent = st.sos().length() > 0
        row.addView(k.badge(R.drawable.li_siren, pal.onDanger, pal.danger, 44, 22))
        val t = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(8), 0) }
        t.addView(k.text(s(R.string.home_sos), 17f, pal.dangerText, true))
        t.addView(k.text(s(if (sent) R.string.hm_q_sent else R.string.hm_t_sos_sub), 13f, pal.sub).apply { setPadding(0, dp(2), 0, 0) })
        row.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
        k.tap(row, 20f) { if (st.everPaired) acts.askSos() else dial("112") }
        row.contentDescription = s(R.string.main_sos)
        val call = k.text(s(R.string.hm_t_call), 14f, pal.text, true).apply {
            background = android.graphics.drawable.GradientDrawable().apply { setColor(pal.sheet); cornerRadius = k.dpf(14f); setStroke(dp(1), pal.stroke) }
            setPadding(dp(12), dp(10), dp(14), dp(10)); gravity = Gravity.CENTER_VERTICAL
            Ic.lead(this, R.drawable.li_phone, pal.text); compoundDrawablePadding = dp(6)
        }
        // a number in the lock-screen contact ("Gọi mẹ 0912…"), else just the phone app
        val num = Regex("\\+?\\d[\\d .-]{6,}\\d").find(r.lockContact)?.value?.replace(Regex("[ .-]"), "")
        k.tap(call, 14f) { dial(num) }
        row.addView(call)
        col.addView(row, lp(16))
    }

    private fun dial(n: String?) {
        val i = if (n == null) Intent(Intent.ACTION_DIAL) else Intent(Intent.ACTION_DIAL, Uri.parse("tel:$n"))
        runCatching { act.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { android.widget.Toast.makeText(act, R.string.hm_no_dialer, android.widget.Toast.LENGTH_SHORT).show() }
    }

    // ---- Lịch ----

    private fun schedule(r: Rules, minute: Int, dow: Int) {
        val c = card()
        title(c, R.drawable.li_calendar, s(R.string.hm_t_schedule))
        val evs = LauncherLogic.today(r, minute, dow)
        if (evs.isEmpty()) c.addView(k.text(s(R.string.hm_t_no_schedule), 14f, pal.sub).apply { setPadding(0, dp(10), 0, dp(2)) })
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
        if (n == 0) c.addView(k.text(s(R.string.hm_t_no_chores), 14f, pal.sub).apply { setPadding(0, dp(10), 0, dp(2)) })
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
        if (l.isEmpty()) c.addView(k.text(s(R.string.hm_t_no_notes), 14f, pal.sub).apply { setPadding(0, dp(10), 0, dp(2)) })
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
        val l = runCatching { Files.all(act) }.getOrDefault(emptyList()).filter { it.dir == "in" && it.st != "gone" }.sortedByDescending { it.at }
        val c = card()
        title(c, R.drawable.li_folder, s(R.string.hm_t_files), if (l.isNotEmpty()) l.size.toString() else null)
        if (l.isEmpty()) c.addView(k.text(s(R.string.hm_t_no_files), 14f, pal.sub).apply { setPadding(0, dp(10), 0, dp(2)); includeFontPadding = true })
        for ((i, e) in l.take(3).withIndex()) {
            if (i > 0) divider(c)
            val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, dp(4)) }
            row.addView(k.badge(R.drawable.li_file, pal.onTint, pal.tint, 40, 20, square = true))
            val t = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(8), 0) }
            t.addView(k.text(e.name, 15f, pal.text, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE })
            t.addView(k.text(s(R.string.hm_t_from_parent, android.text.format.DateUtils.getRelativeTimeSpanString(e.at, System.currentTimeMillis(), 60_000L)), 12f, pal.sub).apply { setPadding(0, dp(2), 0, 0) })
            row.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
            if (e.st != "opened") row.addView(View(act).apply { background = k.circle(pal.danger); contentDescription = s(R.string.hm_t_new) }, LinearLayout.LayoutParams(dp(9), dp(9)))
            k.tap(row, 12f) {
                val i2 = runCatching { Files.viewIntent(act, e.id) }.getOrNull()
                if (i2 == null || runCatching { act.startActivity(i2.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isFailure)
                    android.widget.Toast.makeText(act, R.string.hm_t_file_fail, android.widget.Toast.LENGTH_SHORT).show()
                refreshSoon()
            }
            c.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
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
