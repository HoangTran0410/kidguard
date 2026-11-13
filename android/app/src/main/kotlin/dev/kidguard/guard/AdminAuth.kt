package dev.kidguard

/**
 * Which secret opens the on-device parent page and passes the PIN-gated commands (audit H1, owner decision 2026-11-13).
 * The Authenticator code is read out to the child for a temporary unlock, so while a parent PIN exists only the PIN
 * counts for admin; the code is the admin key only when no PIN is set yet (recovery). Pure, unit-tested.
 */
object AdminAuth {
    /** [pin] = the PIN can be tried now; [code] = an Authenticator code can be tried now. */
    data class Ways(val pin: Boolean, val code: Boolean) { val none get() = !pin && !code }

    fun ways(pinSet: Boolean, pinLocked: Boolean, totpSet: Boolean, totpLocked: Boolean) =
        Ways(pin = pinSet && !pinLocked, code = totpSet && !pinSet && !totpLocked)

    /** ms until a retry: the PIN lockout when a PIN is set, else the Authenticator lockout; 0 = nothing to wait for. */
    fun lockLeft(pinSet: Boolean, pinLockMs: Long, totpSet: Boolean, totpLockMs: Long): Long = when {
        pinSet -> pinLockMs
        totpSet -> totpLockMs
        else -> 0
    }
}

/** A lockout that survives a wall-clock change: monotonic time inside one boot, capped wall time across a reboot (audit L6). */
object Lockout {
    /** Left ms. Same boot ([bootSet] == [bootNow], both known): the elapsed-realtime deadline. Else the wall-clock deadline, never more than [durMs]. */
    fun left(wallUntil: Long, elapsedUntil: Long, bootSet: Int, bootNow: Int, nowWall: Long, nowElapsed: Long, durMs: Long): Long =
        if (bootSet == bootNow && bootNow >= 0) maxOf(0, elapsedUntil - nowElapsed)
        else maxOf(0, minOf(wallUntil - nowWall, durMs))
}
