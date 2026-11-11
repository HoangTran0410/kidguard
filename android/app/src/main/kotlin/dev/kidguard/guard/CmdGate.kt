package dev.kidguard

/**
 * Commands that could take the phone away from its parents need the parent PIN (or an Authenticator code) in the
 * command itself, checked here on the phone, whatever the route (LAN, relay). Owner 2026-11-11: a leaked parent link
 * (forwarded chat, screenshot, shared browser) must not be enough to release / unpair / re-point the phone.
 * openPairing too: a leaked link can watch the screen, so it could read the code and pair a device of its own.
 * Wire: protocol "Commands" > "PIN-gated commands". Everything else (lock, ring, rules, bonus...) stays one tap.
 */
object CmdGate {
    val GATED = setOf(
        "release", "unpairAll", "unpair", "clearData", "reboot", "relay", "tailscaleOff", "openPairing",
        "totpSet", "tgSet", "tgCode", "tgOff", "pinSet", "pinResetCancel",
    )

    /** "Forgot the PIN": a new PIN set without the old one applies after this, with an alert to every parent at once. */
    const val RESET_MS = 24 * 3_600_000L

    private fun norm(u: String) = u.trim().trimEnd('/')

    /**
     * True when [type] needs the PIN. `relay` with the URL it already has only hands out the existing relay link
     * (the page learns it on LAN: `core.js` learnAddrs), so only a change of URL (incl. "" = off) is gated.
     */
    fun needs(type: String, url: String? = null, relayUrl: String = ""): Boolean =
        type in GATED && !(type == "relay" && url != null && norm(url) == norm(relayUrl) && relayUrl.isNotBlank())

    /** A valid new PIN (4–8 digits) or null. */
    fun newPin(s: String?): String? = s?.filter { it.isDigit() }?.takeIf { it.length in 4..8 && it.length == s.trim().length }

    /** A pending "forgot PIN" reset is due. */
    fun resetDue(at: Long, now: Long) = at > 0 && now >= at
}
