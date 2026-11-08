package dev.kidguard

/**
 * Pure logic for: pairing that must carry a PIN, the on-device parent session, legacy (paired, no PIN) phones and the
 * hold-to-confirm buttons. Android-free, unit-tested (ParentLogicTest).
 */
object PairPin {
    sealed class Check {
        /** A PIN is set: the request must carry it (checked with the lockout by Store). */
        object UsePin : Check()
        /** No PIN yet: this pairing sets [pin] in the same step. */
        data class SetPin(val pin: String) : Check()
        /** Refused before any state changes; [error] goes to the page as is. */
        data class Bad(val error: String) : Check()
    }

    fun valid(pin: String) = pin.length in 4..8 && pin.all { it in '0'..'9' }

    /** [newPin] is the raw `newPin` field (the page asks for it twice and sends it once). */
    fun decide(pinSet: Boolean, newPin: String): Check = when {
        pinSet -> Check.UsePin
        newPin.isEmpty() -> Check.Bad("pin required")
        !valid(newPin) -> Check.Bad("bad pin")
        else -> Check.SetPin(newPin)
    }

    /** Paired before PINs were mandatory: the page forces "Đặt PIN", the child app shows "Bố mẹ chưa đặt PIN". */
    fun legacyNoPin(everPaired: Boolean, pinSet: Boolean) = everPaired && !pinSet

    /**
     * The child app's "Bố mẹ quản lý trên máy này" entry: needs something only a parent knows. Before setup (never paired,
     * no PIN, no Authenticator) there is no parent yet, so the page opens without a code to preview / configure.
     * [everPaired] defaults to true = the old behaviour.
     */
    fun adminAvailable(pinSet: Boolean, totpOn: Boolean, everPaired: Boolean = true) = pinSet || totpOn || adminOpen(pinSet, totpOn, everPaired)

    /** True = open the local page with no code (nobody to ask). */
    fun adminOpen(pinSet: Boolean, totpOn: Boolean, everPaired: Boolean) = !everPaired && !pinSet && !totpOn
}

/**
 * Pairings whose token went out but was never used. Nothing is stored until the page uses the token once
 * (`Store.commitPair`), so a page that closes or crashes mid-way leaves the phone unpaired and the code still valid.
 * Memory only: an app restart drops them too.
 */
class PendingPairs<T>(private val ttlMs: Long = 5 * 60_000L, private val max: Int = 3) {
    private class E<T>(val at: Long, val v: T)
    private val m = LinkedHashMap<String, E<T>>()

    @Synchronized fun add(tokenHash: String, v: T, now: Long) {
        prune(now)
        while (m.size >= max) m.remove(m.keys.first())
        m[tokenHash] = E(now, v)
    }

    /** The pending pairing for [tokenHash] (removed), or null when unknown or older than the TTL. */
    @Synchronized fun take(tokenHash: String, now: Long): T? { prune(now); return m.remove(tokenHash)?.v }
    @Synchronized fun clear() = m.clear()
    @Synchronized fun size(now: Long): Int { prune(now); return m.size }
    private fun prune(now: Long) { m.entries.removeAll { now - it.value.at > ttlMs || now < it.value.at } }
}

/**
 * The on-device parent session: one short-lived token, kept as a hash in memory only. It dies after [idleMs] with no
 * touch, or when revoked (screen off, the activity leaves the foreground). Page requests do not count as touches
 * (the page polls every few seconds).
 */
class AdminSession(private val idleMs: Long = 5 * 60_000L) {
    private var hash: String? = null
    private var last = 0L

    @Synchronized fun open(tokenHash: String, now: Long) { hash = tokenHash; last = now }
    @Synchronized fun touch(now: Long) { if (hash != null && active(now)) last = now }
    @Synchronized fun revoke() { hash = null; last = 0 }
    @Synchronized fun active(now: Long): Boolean {
        if (hash == null) return false
        if (now - last > idleMs || now < last) { revoke(); return false }
        return true
    }
    @Synchronized fun valid(tokenHash: String?, now: Long): Boolean =
        tokenHash != null && active(now) && java.security.MessageDigest.isEqual(tokenHash.toByteArray(), hash!!.toByteArray())
    @Synchronized fun leftMs(now: Long): Long = if (active(now)) idleMs - (now - last) else 0
}

/** Hold-to-confirm buttons ("Giữ tiếp… 3 giây"). */
object Hold {
    /** Whole seconds still to hold, rounded up; 0 when done. */
    fun secondsLeft(elapsedMs: Long, totalMs: Long): Int =
        if (elapsedMs >= totalMs) 0 else ((totalMs - elapsedMs.coerceAtLeast(0) + 999) / 1000).toInt()

    fun fraction(elapsedMs: Long, totalMs: Long): Float =
        if (totalMs <= 0) 1f else (elapsedMs.toFloat() / totalMs).coerceIn(0f, 1f)
}
