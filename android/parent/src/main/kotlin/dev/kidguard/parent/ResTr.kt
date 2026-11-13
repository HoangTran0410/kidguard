package dev.kidguard.parent

import android.content.Context

/** Android side of [Tr]: string resources in the app's current locale (per-app language on Android 13+, else the phone's). */
class ResTr(private val ctx: Context) : Tr {
    override fun s(k: T, vararg a: Any): String = ctx.getString(id(k), *a)
    override fun q(k: T, n: Int, vararg a: Any): String = ctx.resources.getQuantityString(id(k), n, *a)

    private fun id(k: T): Int = when (k) {
        T.ev_request -> R.plurals.ev_request
        T.ev_sos -> R.string.ev_sos
        T.ev_offline -> R.string.ev_offline
        T.ev_request_app -> R.string.ev_request_app
        T.ev_acc_off -> R.string.ev_acc_off
    }
}
