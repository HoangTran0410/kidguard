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
        val s = Store.of(act)
        if (!s.everPaired) { sosUnpaired(); return }
        Modal(act, u).show(R.drawable.li_siren, Modal.Tone.DANGER, act.getString(R.string.sos_q), act.getString(R.string.sos_q_body),
            ok = act.getString(R.string.sos_send), cancel = act.getString(R.string.cancel)) {
            val g = Guard.running
            val ok = if (g != null) g.sos() else { val o = s.addSos(); if (o != null) Guard.start(act); o != null }
            onChange()
            ui.post {
                val online = KidNow.online(act)
                val num = KidNow.parentNumber(s.rules)
                when {
                    !ok -> Modal(act, u).show(R.drawable.li_siren, Modal.Tone.WARN, act.getString(R.string.sos_again), act.getString(R.string.sos_again_body))
                    online -> Modal(act, u).show(R.drawable.li_circle_check, Modal.Tone.OK, act.getString(R.string.sos_done), act.getString(R.string.sos_done_body))
                    // nothing left the phone yet: say so, and offer a call when we know whom to call
                    num != null -> Modal(act, u).show(R.drawable.li_phone, Modal.Tone.WARN, act.getString(R.string.sos_offline_t), act.getString(R.string.sos_offline_b),
                        ok = act.getString(R.string.sos_call), cancel = act.getString(R.string.cancel)) { KidNow.dial(act, num); true }
                    else -> Modal(act, u).show(R.drawable.li_siren, Modal.Tone.WARN, act.getString(R.string.sos_offline_t), act.getString(R.string.sos_offline_b))
                }
            }
            true
        }
    }

    /** Not paired: SOS reaches nobody, so say that it calls 112 and let the child decide. */
    private fun sosUnpaired() {
        Modal(act, u).show(R.drawable.li_siren, Modal.Tone.DANGER, act.getString(R.string.sos_unpaired_t), act.getString(R.string.sos_unpaired_b),
            ok = act.getString(R.string.sos_call_112), cancel = act.getString(R.string.cancel)) { KidNow.dial(act, "112"); true }
    }

    // ---- requests: one open request at a time (contract C3); the kid sees "waiting" + "remind" and the last answer ----

    /** "Đã xin lúc 14:05, đang chờ bố mẹ" while a request is open, else null. */
    fun pendingText(): String? = Store.of(act).openRequest()?.let { act.getString(R.string.req_pending_at, KidNow.hhmm(it.optLong("at"))) }

    /** The parent's last answer ("Bố mẹ đã cho thêm 15 phút"), kept by the store until the next unlock / day; else null. */
    fun answerText(): String? = Store.of(act).lastAnswer()?.let { a ->
        when {
            !a.optBoolean("ok") -> act.getString(R.string.req_ans_no)
            a.optString("kind") == "app" -> act.getString(R.string.req_ans_app, a.optString("app"))
            else -> act.getString(R.string.req_ans_time, a.optInt("min"))
        }
    }

    /** Raises the open request again (same id on the store side; the parent alert is rate-limited there). */
    fun remind() {
        val s = Store.of(act)
        val o = s.openRequest() ?: return
        val res = s.addRequest(o.optString("kind", "time"), o.optInt("min"), o.optString("note"), o.optString("pkg").ifEmpty { null },
            o.optString("app").ifEmpty { null }, o.optString("chore").ifEmpty { null }, o.optString("tpl").ifEmpty { null })
        reported(res, 0)
    }

    /** What the child sees after a send: new = the "sent" card, remind / wait = a short toast. */
    private fun reported(res: String, body: Int) {
        val s = Store.of(act)
        if (res != "wait") { if (res == "new") s.log(R.string.log_more); Guard.poke(act) }
        onChange()
        ui.post {
            when (res) {
                "new" -> Modal(act, u).show(R.drawable.li_send, Modal.Tone.OK, act.getString(R.string.more_sent), act.getString(if (body != 0) body else R.string.more_sent_body))
                "remind" -> Toasts.show(act, R.string.req_reminded)
                else -> Toasts.show(act, R.string.req_remind_wait)
            }
        }
    }

    /** A request is already open: show it with "Nhắc lại" instead of a second ask. */
    private fun pendingCard() {
        Modal(act, u).show(R.drawable.li_clock, Modal.Tone.INFO, act.getString(R.string.req_pending_t), pendingText(),
            ok = act.getString(R.string.req_remind), cancel = act.getString(R.string.cancel)) { remind(); true }
    }

    /** Ask to open a blocked / new app: kind "app" with the package and its label. */
    fun askApp(pkg: String, label: String) {
        if (Store.of(act).openRequest() != null) { pendingCard(); return }
        Modal(act, u).show(R.drawable.li_send, Modal.Tone.INFO, act.getString(R.string.req_app_q, label), act.getString(R.string.req_app_body),
            ok = act.getString(R.string.send), cancel = act.getString(R.string.cancel)) {
            reported(Store.of(act).addRequest("app", 0, "", pkg, label), R.string.req_app_sent); true
        }
    }

    /** "Xong" on a chore: a time request tagged with the chore id. */
    fun sendChore(ch: Chore) {
        val s = Store.of(act)
        if (s.openRequest() != null) { pendingCard(); return }
        reported(s.addRequest("time", ch.min, "", chore = ch.id), R.string.hm_t_chore_sent)
    }

    /** The child asks for 15 / 30 / 60 more minutes, with an optional short note; the parent sees it in the state. */
    fun askMore() {
        val s = Store.of(act)
        if (s.openRequest() != null) { pendingCard(); return }
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
            val res = s.addRequest("time", tp.min, ReqTpl.note(act.getString(tplLabel(tp.id)), note.text.toString()), tpl = tp.id)
            dlg?.dismiss(); reported(res, 0)
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
            val res = if (chore != null) s.addRequest("time", chore.min, note.text.toString(), chore = chore.id) else s.addRequest("time", mins[pick.selected.coerceIn(0, 2)], note.text.toString())
            reported(res, 0)
            true
        }
    }

    private fun tplLabel(id: String) = when (id) { "homework" -> R.string.tpl_homework; "play" -> R.string.tpl_play; else -> R.string.tpl_study }

}
