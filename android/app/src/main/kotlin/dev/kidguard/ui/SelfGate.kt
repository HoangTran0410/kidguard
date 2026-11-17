package dev.kidguard

import android.app.Activity

/**
 * KidGuard's own buttons that open a system settings page (accessibility, App info, usage access, Wireless debugging,
 * full setup ...). On a paired basic phone the anti-tamper scan (Acc, BasicLogic.selfProtect) closes those pages, so the
 * kid's own fix buttons bounced back to home (stuck audit 2026-11-17, Nam: "bị đá ra home"). Here the parent PIN, typed
 * on the phone (its own counter), opens the same 5-min window as the page's "Open KidGuard settings" (selfOpen), then
 * the page opens. Device Owner (no scan), never paired (the parent holds the phone) or a window already open: straight in.
 * Paired without a PIN: the page's Danger zone is the only way (a kid must not open it alone), said in a dialog.
 */
object SelfGate {
    fun free(act: Activity): Boolean {
        val s = Store.of(act)
        return Policy.isOwner(act) || !s.everPaired || CmdGate.selfOpen(s.selfOpenUntil, System.currentTimeMillis())
    }

    fun run(act: Activity, u: Ui, then: () -> Unit) {
        if (free(act)) { then(); return }
        val s = Store.of(act)
        if (s.rules.pinHash.isBlank()) {
            Modal(act, u).show(R.drawable.li_shield, Modal.Tone.WARN, act.getString(R.string.self_gate_t), act.getString(R.string.self_gate_nopin))
            return
        }
        fun lockText(left: Long) = ((left + 59_999) / 60_000).toInt().let { act.resources.getQuantityString(R.plurals.pin_locked, it, it) }
        s.localPinLockLeft().takeIf { it > 0 }?.let { Modal(act, u).show(R.drawable.li_triangle_alert, Modal.Tone.WARN, act.getString(R.string.locked_title), lockText(it)); return }
        val m = Modal(act, u)
        val input = m.field(act.getString(R.string.pin_field), 12, pin = true)
        m.show(R.drawable.li_shield, Modal.Tone.INFO, act.getString(R.string.self_gate_t), act.getString(R.string.self_gate_b), KidNow.withForgot(act, u, input),
            ok = act.getString(R.string.pin_ok), cancel = act.getString(R.string.cancel)) {
            if (s.checkPinOnPhone(input.text.toString().filter { it.isDigit() })) {
                s.selfOpenUntil = System.currentTimeMillis() + CmdGate.SELF_OPEN_MS
                runCatching { s.log(R.string.log_self_open) }
                then(); true
            } else {
                input.setText("")
                m.setError(s.localPinLockLeft().takeIf { it > 0 }?.let { lockText(it) }
                    ?: s.localPinTriesLeft().takeIf { it in 1..4 }?.let { act.resources.getQuantityString(R.plurals.pin_wrong_left, it, it) }
                    ?: act.getString(R.string.pin_wrong))
                false
            }
        }
        input.requestFocus()
    }
}
