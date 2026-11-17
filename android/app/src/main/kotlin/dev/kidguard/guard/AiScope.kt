package dev.kidguard

import org.json.JSONObject

/**
 * What a request with the AI token (role "ai", docs/protocol.md "AI token") may do. Pure: the same lists are in the relay
 * worker (`AI_CMDS`, `AI_LOCKED` in relay/src/index.js); the phone enforces them on LAN, the worker on the relay.
 * The AI token is made by the parent page (only its sha256 reaches the phone), so a stolen AI config never holds parent power.
 */
object AiScope {
    /** Commands an AI may queue. `answer` = reply to the child's "more time" request. */
    val CMDS = setOf("lock", "locate", "message", "ring", "ringStop", "sosAck", "answer")

    /** Rule keys an AI cannot change: the PIN and every protection / restriction switch. They keep the stored value. */
    val LOCKED = setOf(
        "pin", "pinHash", "pinSalt", "pinAt", "pinSet", "noInstall", "noDebug", "pinShot", "lockLocation", "noAirplane",
        "frpIds", "safeDns", "safeDnsPauseUntil", "noVpn", "tsKeep", "vpnPause", "accPause", "devPause", "tcpPause",
        "autoUpdate", "showWatching", "unlockMin", "hidden", "approved", "mode", "watch",
    )
    private val PIN_KEYS = setOf("pin", "pinHash", "pinSalt", "pinAt", "pinSet")

    fun cmdAllowed(type: String?): Boolean = type in CMDS

    /** [path] without the "/api" prefix. Reads: state, usage tracks, icons. Writes: rules, allowed commands. */
    fun routeAllowed(method: String, path: String): Boolean = when (path) {
        "/state", "/track", "/icon" -> method == "GET"
        "/rules" -> method == "PUT"
        "/cmd" -> method == "POST"
        else -> false
    }

    /**
     * The rules an AI sent, with every [LOCKED] key taken from [current] (the stored rules as JSON, without PIN).
     * A locked key the phone has not stored is dropped, so the phone's default applies (rules are a full replace).
     * PIN keys are always dropped: the caller keeps the stored PIN.
     */
    fun lockRules(incoming: JSONObject, current: JSONObject): JSONObject {
        val o = JSONObject(incoming.toString())
        for (k in LOCKED) { o.remove(k); if (k !in PIN_KEYS && current.has(k)) o.put(k, current.get(k)) }
        return o
    }
}
