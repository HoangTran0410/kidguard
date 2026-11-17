package dev.kidguard

import org.json.JSONObject

/**
 * [Guard] commands about who may control the phone: PIN, pairing, relay link, AI token, Authenticator, selfOpen.
 * Split out of Guard.run (maintainability review 2026-11-17); Guard.run routes every other name here. PIN gating already
 * happened in Guard.gate ([CmdGate]). relay/test/contract.test.mjs reads the command names of both files.
 */
internal class GuardAccess(
    private val store: Store,
    private val putPin: (hash: String, salt: String) -> Unit,
    private val kickRelay: () -> Unit,
    private val kick: () -> Unit,
) {
    /** null = not an access command. */
    fun run(t: String, c: JSONObject): String? = when (t) {
        "relay" -> {
            store.relayUrl = c.optString("url")   // "" = relay off (kept off: no default again)
            if (store.relayUrl.isNotBlank() && (store.relayToken.isBlank() || store.relayId.isBlank())) { store.relayToken = store.addToken(); store.relayId = Store.randomHex(8); store.touchDevice(store.relayToken, "relay") }
            if (c.optBoolean("renew") && store.relayUrl.isNotBlank()) { store.rotateRelay(); store.log(R.string.log_relay_rotated) }   // `relayShared` clears; the reply carries the new id + token
            kickRelay(); "ok"
        }
        // no PIN yet: the code shows on the child's screen and nothing else would stop the child pairing first
        "openPairing" -> if (store.everPaired && store.rules.pinHash.isBlank()) "set PIN first" else { store.openPairing(); "ok" }
        "pinSet" -> CmdGate.newPin(c.optString("new"))?.let { n ->   // the old PIN was checked by gate()
            val salt = Rules.salt8(); putPin(Rules.hashPin(n, salt), salt); store.log(R.string.log_pin_changed); "ok"
        } ?: "failed: bad pin"
        // forgot the PIN: no old one needed, but it only applies after CmdGate.RESET_MS and every parent hears of it now
        "pinReset" -> if (store.rules.pinHash.isBlank()) "no pin" else CmdGate.newPin(c.optString("new"))?.let { n ->
            val salt = Rules.salt8(); val at = System.currentTimeMillis() + CmdGate.RESET_MS
            store.setPinReset(Rules.hashPin(n, salt), salt, at)
            store.event("tamper", R.string.ev_pin_reset, java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(at)))
            "ok"
        } ?: "failed: bad pin"
        "pinResetCancel" -> { if (store.pinResetAt > 0) { store.clearPinReset(); store.log(R.string.log_pin_reset_cancel) }; "ok" }
        "unpairAll" -> { store.clearTokens(); store.tg.unlinkAll(); Tg.wake(); "ok" }   // Telegram chats control the phone too
        "aiToken" -> when (c.optString("op")) {   // the parent page makes the token, the phone keeps its sha256 (AiScope); PIN-gated
            "set" -> if (store.setAiHash(c.optString("hash"))) { store.log(R.string.log_ai_on); kickRelay(); "ok" } else "failed: bad hash"
            "revoke" -> { store.setAiHash(""); store.log(R.string.log_ai_off); kickRelay(); "ok" }
            else -> "failed: bad op"
        }
        "unpair" -> if (store.unpair(c.optString("id"))) { kickRelay(); kick(); "ok" } else "no such device"
        "selfOpen" -> if (c.optBoolean("on")) {
            store.selfOpenUntil = System.currentTimeMillis() + CmdGate.SELF_OPEN_MS; store.log(R.string.log_self_open); "ok"
        } else { store.selfOpenUntil = 0L; "ok" }
        // the parent page made the secret and shows it as an Authenticator QR; "" turns codes off
        "totpSet" -> c.optString("secret").let { s ->
            if (s.isNotEmpty() && Totp.decode(s) == null) "bad secret"
            else { store.totpSecret = s.uppercase().filter { it in 'A'..'Z' || it in '2'..'7' }; store.unlockUntil = 0; store.log(if (s.isEmpty()) R.string.log_totp_off else R.string.log_totp_on); "ok" }
        }
        else -> null
    }
}
