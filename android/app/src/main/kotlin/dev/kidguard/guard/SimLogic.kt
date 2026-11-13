package dev.kidguard

/**
 * SIM change detection without any dangerous permission (no READ_PHONE_STATE, so no ICCID / serial): the SIM state and the
 * operator (MCC+MNC) and carrier id, which TelephonyManager gives to every app. A swap to another SIM of the SAME carrier
 * is not seen. Pure, unit-tested; [Sim] reads the phone.
 */
object SimLogic {
    /** A signature like "ready:45204:1234", "absent", or "" = not known now (booting, PIN screen, no telephony): keep the last one. */
    fun sig(state: Int, operator: String, carrierId: Int, hasTelephony: Boolean): String {
        if (!hasTelephony) return ""
        return when (state) {
            5 -> "ready:${operator.filter { it.isDigit() }}:$carrierId"   // SIM_STATE_READY
            1 -> "absent"                                                // SIM_STATE_ABSENT
            else -> ""                                                   // unknown / pin / puk / not ready / network locked
        }
    }

    /** (signature to store, "change" text key or null). The first signature is only recorded. */
    fun step(last: String, now: String): Pair<String, Boolean> = when {
        now.isEmpty() -> last to false
        last.isEmpty() -> now to false
        now == last -> last to false
        else -> now to true
    }
}

object Sim {
    fun sig(ctx: android.content.Context): String = runCatching {
        val tm = ctx.getSystemService(android.telephony.TelephonyManager::class.java) ?: return ""
        val has = ctx.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TELEPHONY)
        val cid = if (android.os.Build.VERSION.SDK_INT >= 28) runCatching { tm.simCarrierId }.getOrDefault(0) else 0
        SimLogic.sig(tm.simState, runCatching { tm.simOperator }.getOrNull() ?: "", cid, has)
    }.getOrDefault("")
}
