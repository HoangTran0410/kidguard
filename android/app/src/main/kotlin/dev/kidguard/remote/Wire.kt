package dev.kidguard

import org.json.JSONObject

/**
 * Relay command codes (docs/protocol.md "Command codes"): the relay worker only sees these short codes, never the
 * command names (its bundle stays free of feature words, plan B 2026-11-18). The parent page encodes (core.js `WIRE`,
 * same table: relay/test/contract.test.mjs checks both against the worker's CMDS); the phone decodes here before
 * Guard.command. LAN keeps the names.
 */
object Wire {
    val CODES: Map<String, String> = linkedMapOf(
        "aiToken" to "ai", "refusedDrop" to "rd", "uiUpdate" to "uu", "smsPull" to "sp", "chatPull" to "cp", "chatClear" to "cc",
        "chatFileDel" to "cf", "chatDel" to "cd", "snapList" to "sl", "snapUp" to "su", "snapDel" to "sd", "snap" to "sn",
        "lock" to "lk", "locate" to "lc", "message" to "ms", "ring" to "rg", "openPairing" to "op", "unpairAll" to "ua",
        "unpair" to "up", "release" to "rl", "answer" to "an", "reboot" to "rb", "tailscaleOff" to "to", "sosAck" to "sa",
        "clearData" to "cl", "update" to "ud", "totpSet" to "ts", "wake" to "wk", "ringStop" to "rs", "listenStop" to "ls",
        "devOff" to "d0", "devOn" to "d1", "setting" to "st", "fileUp" to "fu", "fileDel" to "fd", "shotUp" to "sh",
        "notifPull" to "np", "tgSet" to "gs", "tgCode" to "gc", "tgUnlink" to "gu", "tgOff" to "go", "pinSet" to "ps",
        "pinReset" to "pr", "pinResetCancel" to "pc", "support" to "sx", "supportSay" to "sy", "adbShell" to "ab", "shClear" to "hc",
        "adbTcp" to "ap", "bankEnd" to "be", "unlockEnd" to "ue", "clearCache" to "ca", "rescan" to "re", "bgLimit" to "bl",
        "bgRestore" to "br", "fixPerms" to "fp", "selfOpen" to "so", "tsInstall" to "ti",
    )
    private val NAMES = CODES.entries.associate { (k, v) -> v to k }

    /** Relay command -> the same command with its name back (an unknown code stays as it is: Guard says "unknown"). */
    fun decode(cmd: JSONObject): JSONObject {
        val n = NAMES[cmd.optString("type")] ?: return cmd
        return JSONObject(cmd.toString()).put("type", n)
    }
}
