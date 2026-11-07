package dev.kidguard

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * The kid's quick actions (SOS, check-ins, ask for more time) with their confirm / done / "already sent" modals.
 * Shared by MainActivity and the kid launcher (HomeActivity). [onChange] runs after something was sent.
 */
class KidActions(private val act: Activity, private val u: Ui, private val onChange: () -> Unit) {
    private val ui = Handler(Looper.getMainLooper())
    private fun dp(v: Int) = u.dp(v)

    /** One confirm (no accidental taps; not an alarm), then send. A second send within a minute says "already sent". */
    fun askCheckin(ck: String) {
        val icon = if (ck == "pickup") R.drawable.li_car else R.drawable.li_map_pin
        Modal(act, u).show(icon, Modal.Tone.INFO, act.getString(if (ck == "pickup") R.string.ci_q_pickup else R.string.ci_q_arrived), act.getString(R.string.ci_q_body),
            ok = act.getString(R.string.ci_send), cancel = act.getString(R.string.cancel)) { ui.post { sendCheckin(ck) }; true }
    }

    private fun sendCheckin(ck: String) {
        val s = Store.of(act)
        val g = Guard.running
        val ok = if (g != null) g.checkin(ck) else Guard.checkinNow(act, s, ck).also { if (it) Guard.start(act) }
        val hasLoc = Checkin.fresh(s.location, System.currentTimeMillis()) != null
        onChange()
        if (ok) Modal(act, u).show(R.drawable.li_circle_check, Modal.Tone.OK, act.getString(R.string.ci_done), act.getString(if (hasLoc) R.string.ci_done_loc else R.string.ci_done_noloc))
        else Modal(act, u).show(R.drawable.li_timer, Modal.Tone.INFO, act.getString(R.string.ci_again), act.getString(R.string.ci_again_body))
    }

    /** SOS: one confirm (no pocket presses), then the state carries it to the parent with a fresh location. */
    fun askSos() {
        Modal(act, u).show(R.drawable.li_siren, Modal.Tone.DANGER, act.getString(R.string.sos_q), act.getString(R.string.sos_q_body),
            ok = act.getString(R.string.sos_send), cancel = act.getString(R.string.cancel)) {
            val g = Guard.running
            val ok = if (g != null) g.sos() else { val o = Store.of(act).addSos(); if (o != null) Guard.start(act); o != null }
            onChange()
            ui.post {
                if (ok) Modal(act, u).show(R.drawable.li_circle_check, Modal.Tone.OK, act.getString(R.string.sos_done), act.getString(R.string.sos_done_body))
                else Modal(act, u).show(R.drawable.li_siren, Modal.Tone.WARN, act.getString(R.string.sos_again), act.getString(R.string.sos_again_body))
            }
            true
        }
    }

    /** The child asks for 15 / 30 / 60 more minutes, with an optional short note; the parent sees it in the state. */
    fun askMore() {
        val s = Store.of(act)
        val mins = intArrayOf(15, 30, 60)
        val chores = s.rules.chores
        val m = Modal(act, u)
        val pick = m.choice(mins.map { act.resources.getQuantityString(R.plurals.min_n, it, it) })
        val note = m.field(act.getString(R.string.more_note), 100)
        // chores first, the last entry is the free form (minutes row below)
        val chorePick = if (chores.isEmpty()) null else m.choice(chores.map { act.getString(R.string.chore_item, it.name, it.min) } + act.getString(R.string.more_other), chores.size, vertical = true)
        // v0.9.7 one-tap templates: a tap sends at once (typed note is added), the dialog closes
        var dlg: android.app.Dialog? = null
        val tplBox = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        tplBox.addView(u.label(act.getString(R.string.more_quick), 13f), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        for (tp in ReqTpl.ALL) tplBox.addView(u.button(act.getString(tplLabel(tp.id)), R.drawable.li_send, false) {
            val ok = s.addRequest(tp.min, ReqTpl.note(act.getString(tplLabel(tp.id)), note.text.toString()), tpl = tp.id)
            if (ok) { s.log(R.string.log_more); Guard.poke(act) }
            dlg?.dismiss(); onChange()
            ui.post {
                if (ok) Modal(act, u).show(R.drawable.li_send, Modal.Tone.OK, act.getString(R.string.more_sent), act.getString(R.string.more_sent_body))
                else Modal(act, u).show(R.drawable.li_triangle_alert, Modal.Tone.WARN, act.getString(R.string.more_fail), act.getString(R.string.more_fail_body))
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            addView(tplBox, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
            if (chorePick != null) addView(chorePick.view, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
            addView(pick.view, LinearLayout.LayoutParams(-1, -2))
            addView(note, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        }
        dlg = m.show(R.drawable.li_timer, Modal.Tone.INFO, act.getString(R.string.main_more), act.getString(if (chores.isEmpty()) R.string.more_body else R.string.more_body_chores), ScrollView(act).apply { addView(box) }, ok = act.getString(R.string.send), cancel = act.getString(R.string.cancel)) {
            val chore = chorePick?.selected?.let { chores.getOrNull(it) }
            val ok = if (chore != null) s.addRequest(chore.min, note.text.toString(), chore.id) else s.addRequest(mins[pick.selected.coerceIn(0, 2)], note.text.toString())
            if (ok) { s.log(R.string.log_more); Guard.poke(act) }
            onChange()
            ui.post {
                if (ok) Modal(act, u).show(R.drawable.li_send, Modal.Tone.OK, act.getString(R.string.more_sent), act.getString(R.string.more_sent_body))
                else Modal(act, u).show(R.drawable.li_triangle_alert, Modal.Tone.WARN, act.getString(R.string.more_fail), act.getString(R.string.more_fail_body))
            }
            true
        }
    }

    private fun tplLabel(id: String) = when (id) { "homework" -> R.string.tpl_homework; "play" -> R.string.tpl_play; else -> R.string.tpl_study }

}
