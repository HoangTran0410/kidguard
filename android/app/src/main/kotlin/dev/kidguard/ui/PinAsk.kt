package dev.kidguard

import android.app.Activity
import android.content.Context

/**
 * The parent-PIN dialog typed on the kid phone (phone-local counter, [Store.checkPinOnPhone]) and its lock / tries texts.
 * One copy for MainActivity.askPin, the Focus end gate and [SelfGate] (maintainability review 2026-11-17: it was written 3x).
 */
object PinAsk {
    /** "Locked for N min" with [plural] (pin_locked, unl_locked ...), rounded up to whole minutes. */
    fun lockText(ctx: Context, left: Long, plural: Int = R.plurals.pin_locked): String =
        ((left + 59_999) / 60_000).toInt().let { ctx.resources.getQuantityString(plural, it, it) }

    /** After a wrong PIN on the phone: the lock, else the tries left (when 1..4), else plain "wrong". */
    fun wrongText(ctx: Context, s: Store): String =
        s.localPinLockLeft().takeIf { it > 0 }?.let { lockText(ctx, it) }
            ?: s.localPinTriesLeft().takeIf { it in 1..4 }?.let { ctx.resources.getQuantityString(R.plurals.pin_wrong_left, it, it) }
            ?: ctx.getString(R.string.pin_wrong)

    /** Asks the PIN ([s] must have one), runs [then] when right. Locked: says so and stops. */
    fun local(act: Activity, u: Ui, title: Int, body: Int, ok: Int = R.string.pin_ok, then: () -> Unit) {
        val s = Store.of(act)
        s.localPinLockLeft().takeIf { it > 0 }?.let {
            Modal(act, u).show(R.drawable.li_triangle_alert, Modal.Tone.WARN, act.getString(R.string.locked_title), lockText(act, it)); return
        }
        val m = Modal(act, u)
        val input = m.field(act.getString(R.string.pin_field), 12, pin = true)
        m.show(R.drawable.li_shield, Modal.Tone.INFO, act.getString(title), act.getString(body), KidNow.withForgot(act, u, input),
            ok = act.getString(ok), cancel = act.getString(R.string.cancel)) {
            if (s.checkPinOnPhone(input.text.toString().filter { it.isDigit() })) { then(); true }
            else { input.setText(""); m.setError(wrongText(act, s)); false }
        }
        input.requestFocus()
    }
}
