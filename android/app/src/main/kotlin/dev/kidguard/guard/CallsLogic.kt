package dev.kidguard

import org.json.JSONArray
import org.json.JSONObject

/** Rule `calls` (docs/protocol.md "Calls"): block = "off" | "unknown" | "hidden"; allow = numbers that always ring (max 30). */
data class CallRule(val block: String = "off", val allow: List<String> = emptyList()) {
    fun toJson(): JSONObject = JSONObject().put("block", block).put("allow", JSONArray(allow))

    companion object {
        fun fromJson(o: JSONObject?): CallRule {
            if (o == null) return CallRule()
            val b = o.optString("block", "off").takeIf { it in CallsLogic.BLOCKS } ?: "off"
            val a = o.optJSONArray("allow")
            val list = (0 until (a?.length() ?: 0)).mapNotNull { CallsLogic.clean(a!!.optString(it)) }.distinctBy { CallsLogic.canon(it) }
            return CallRule(b, list.take(CallsLogic.MAX_ALLOW))
        }
    }
}

/** Pure part of call blocking: number forms and the block decision. Android-free, tested in CallsLogicTest. */
object CallsLogic {
    const val MAX_ALLOW = 30
    val BLOCKS = setOf("off", "unknown", "hidden")
    /** Never blocked, whatever the rule (the Android side also asks PhoneNumberUtils.isEmergencyNumber). */
    private val EMERGENCY = setOf("112", "113", "114", "115", "911", "999", "000", "110", "118", "119")

    /** What the parent typed, kept readable: "+84 912 345 678" -> "+84912345678"; null when it has no 3..15 digits. */
    fun clean(raw: String): String? {
        val t = raw.trim()
        val d = t.filter { it.isDigit() }
        if (d.length !in 3..15) return null
        return (if (t.startsWith("+")) "+" else "") + d
    }

    /** One comparable form: digits only; Vietnam "+84", "0084" and "84" + 9 digits become "0" + 9 digits. "" = hidden / no number. */
    fun canon(raw: String?): String {
        val d = (raw ?: "").filter { it.isDigit() }
        return when {
            d.startsWith("0084") && d.length in 13..14 -> "0" + d.substring(4)
            d.startsWith("84") && d.length in 11..12 -> "0" + d.substring(2)
            else -> d
        }
    }

    fun isEmergency(number: String?): Boolean = canon(number) in EMERGENCY

    fun inAllow(allow: List<String>, number: String?): Boolean { val c = canon(number); return c.isNotEmpty() && allow.any { canon(it) == c } }

    /**
     * true = reject this incoming call. [number] null / blank = withheld. "hidden" blocks only withheld numbers;
     * "unknown" also blocks every number that is neither in the contacts nor in the allow list.
     * [contactsKnown] false (no READ_CONTACTS) = we cannot tell, so a shown number is let through.
     */
    fun shouldBlock(rule: CallRule, number: String?, inContacts: Boolean, contactsKnown: Boolean = true): Boolean {
        if (rule.block == "off" || isEmergency(number)) return false
        if (canon(number).isEmpty()) return true
        if (rule.block == "hidden") return false
        if (inAllow(rule.allow, number)) return false
        return contactsKnown && !inContacts
    }

    /** CallLog.Calls.TYPE -> "in" | "out" | "missed" | "rejected" | "blocked". */
    fun typeName(type: Int): String = when (type) { 2 -> "out"; 3 -> "missed"; 5 -> "rejected"; 6 -> "blocked"; else -> "in" }

    /** Calls we (or the system block list) turned away show as "rejected" / "blocked" in the log. */
    fun wasBlocked(type: String) = type == "rejected" || type == "blocked"
}
