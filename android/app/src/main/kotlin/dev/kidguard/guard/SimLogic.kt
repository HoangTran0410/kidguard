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
            // READY with no operator yet = the modem is still coming up after a boot: not known
            5 -> operator.filter { it.isDigit() }.let { if (it.isEmpty()) "" else "ready:$it:$carrierId" }   // SIM_STATE_READY
            1 -> "absent"                                                // SIM_STATE_ABSENT
            else -> ""                                                   // unknown / pin / puk / not ready / network locked
        }
    }

    /** Phone up for less than this: the SIM may read absent / half-known while the modem starts, so nothing is
     *  recorded or reported yet; a real swap (usually done with the phone off) is still reported once it settles. */
    const val BOOT_MS = 3 * 60_000L

    /** (signature to store, changed). The first signature is only recorded. [upMs] = time since boot.
     *  INCIDENT 2026-11-17 (owner, Nam's phone): "Máy vừa khởi động lại" + two "Thẻ SIM đã đổi" in the same minute,
     *  same SIM: the boot-time readings (no operator / carrier id -1) counted as a new SIM and then as a change back. */
    fun step(last: String, now: String, upMs: Long = Long.MAX_VALUE): Pair<String, Boolean> = when {
        now.isEmpty() || upMs < BOOT_MS -> last to false
        last.isEmpty() -> now to false
        same(now, last) -> last to false
        else -> now to true
    }

    /** Same SIM: equal, or same operator while one carrier id is still unknown (-1). */
    fun same(a: String, b: String): Boolean {
        if (a == b) return true
        val x = a.split(":"); val y = b.split(":")
        return x.size == 3 && y.size == 3 && x[0] == "ready" && y[0] == "ready" && x[1] == y[1] && (x[2] == "-1" || y[2] == "-1")
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
