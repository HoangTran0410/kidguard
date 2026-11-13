package dev.kidguard.parent

import org.json.JSONObject
import java.util.Locale

/**
 * Pure part of the notification actions (no Android classes, unit-tested in ActTest).
 * A time request ("req:<id>") gets grant buttons + "Decline"; an SOS ("sos:<id>") gets "Show location" (+ "Call" when a
 * number is known). Answering does what the page's request card does: PUT the rules with today's `bonus` raised, then
 * the `answer` command (protocol "Commands" / v0.6 "Note with an answer"). Decline is only the command.
 */
object Act {
    /** One notification button. [min] for GRANT. */
    data class A(val type: String, val min: Int = 0)
    const val APPROVE = "approve"; const val GRANT = "grant"; const val DECLINE = "decline"; const val MAP = "map"; const val CALL = "call"

    /** The request id of a "req:<id>" event key, else null. */
    fun reqId(key: String): String? = key.takeIf { it.startsWith("req:") && it.length > 4 }?.substring(4)
    fun isSos(key: String) = key.startsWith("sos:")
    /** v0.9.7 check-in event ("ci:<event id>"): normal priority, "Show location" at the fix sent with it. */
    fun isCheckin(key: String) = key.startsWith("ci:")

    /**
     * Android shows 3 actions: [N, 2N, Decline] where N = what the child asked for (default 15 -> 15 / 30) and 2N is rounded
     * to 5 (cap 600). "N/2" is left to the page's amount picker (a 4th button does not fit). 0.9.16 (was 15 / 30 or the asked + 30).
     */
    fun grantChoices(reqMin: Int): List<Int> {
        val n = if (reqMin <= 0 || reqMin > 600) 15 else reqMin
        val twice = minOf(600, maxOf(5, (n * 2 + 2) / 5 * 5))
        return if (twice == n) listOf(n) else listOf(n, twice)
    }

    fun actionsFor(key: String, reqMin: Int = 0, phone: String? = null, app: Boolean = false): List<A> = when {
        reqId(key) != null -> (if (app) listOf(A(APPROVE)) else grantChoices(reqMin).map { A(GRANT, it) }) + A(DECLINE)
        isSos(key) -> listOf(A(MAP)) + (if (phone.isNullOrBlank()) emptyList() else listOf(A(CALL)))
        isCheckin(key) -> listOf(A(MAP))
        else -> emptyList()
    }

    /** The command the page sends for a request answer: `answer {id, ok, min?, note?}` (note <= 200 chars). */
    fun answerCmd(id: String, ok: Boolean, min: Int = 0, note: String? = null): JSONObject = JSONObject().put("type", "answer").put("id", id).put("req", id).put("ok", ok).apply {
        if (ok) put("min", min)
        note?.trim()?.take(200)?.takeIf { it.isNotEmpty() }?.let { put("note", it) }
    }

    /** The state object out of a GET state reply (LAN: the body; relay: body.state, null if the child never synced). */
    fun stateOf(body: JSONObject, via: String): JSONObject? = if (via == "lan") body else body.optJSONObject("state")

    /** The rules to edit: the state's, or (relay) the newer stored PUT the child has not taken yet. Same pick as the page. */
    fun pickRules(body: JSONObject, via: String): JSONObject? {
        val st = stateOf(body, via) ?: return null
        val r = st.optJSONObject("rules") ?: return null
        val pending = if (via == "lan") null else body.optJSONObject("rules")
        return if (pending != null && pending.optLong("rev") > r.optLong("rev")) pending else r
    }

    fun hasRequest(body: JSONObject, via: String, id: String): Boolean {
        val a = stateOf(body, via)?.optJSONArray("requests") ?: return false
        return (0 until a.length()).any { a.optJSONObject(it)?.optString("id") == id }
    }

    /** Rules PUT body: [rules] with today's bonus raised by [add] minutes (page: `bonus = {day, min: cur + n}`), PIN fields dropped. */
    fun bonusRules(rules: JSONObject, day: String, add: Int): JSONObject {
        val o = JSONObject(rules.toString())
        val b = o.optJSONObject("bonus")
        val cur = if (b != null && b.optString("day") == day) b.optInt("min") else 0
        o.put("bonus", JSONObject().put("day", day).put("min", cur + add))
        o.remove("pinHash"); o.remove("pinSet"); o.remove("pin")
        return o
    }

    /** The open request [id] in the state (kind "time" when the field is missing), or null. */
    fun requestOf(body: JSONObject, via: String, id: String): JSONObject? {
        val a = stateOf(body, via)?.optJSONArray("requests") ?: return null
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.firstOrNull { it.optString("id") == id }
    }

    /** state.now.why ("" when the phone sends none). */
    fun nowWhy(body: JSONObject, via: String): String = stateOf(body, via)?.optJSONObject("now")?.optString("why").orEmpty()

    /** QUEUED: the relay holds it until the phone is online. HALF: the bonus is saved, the `answer` is not sent yet. */
    enum class Res { OK, QUEUED, ALREADY, HALF, FAIL }

    /** A cmd reply: LAN `{result: "ok" | "no such request" | …}`, relay `{result: "queued"}`. */
    fun cmdResult(status: Int?, body: String): Res {
        if (status != 200) return Res.FAIL
        val r = runCatching { JSONObject(body).optString("result") }.getOrDefault("")
        return when (r) {
            "", "ok" -> Res.OK
            "queued" -> Res.QUEUED
            "no such request" -> Res.ALREADY
            else -> Res.FAIL
        }
    }

    /** Maps app intent at the last fix, labelled with the kid's name. */
    fun geoUri(loc: Logic.Loc, label: String): String {
        val ll = String.format(Locale.ROOT, "%.5f,%.5f", loc.lat, loc.lng)
        return "geo:$ll?q=$ll(" + java.net.URLEncoder.encode(label, "UTF-8").replace("+", "%20") + ")"
    }

    /** state.location -> Loc, null when missing / not numbers. */
    fun locOf(st: JSONObject?): Logic.Loc? {
        val l = st?.optJSONObject("location") ?: return null
        val lat = l.optDouble("lat", Double.NaN); val lng = l.optDouble("lng", Double.NaN)
        if (lat.isNaN() || lng.isNaN() || lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
        return Logic.Loc(lat, lng, l.optLong("t"))
    }
}
