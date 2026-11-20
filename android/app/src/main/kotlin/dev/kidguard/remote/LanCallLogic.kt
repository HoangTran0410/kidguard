package dev.kidguard

import org.json.JSONArray
import org.json.JSONObject

/**
 * The call slot on the phone itself (docs/flows/remote.md 2.17): the same slot the relay keeps for a video call / listen / live camera
 * (relay/src/index.js `callRoute`), so a page that reaches the phone straight (LAN, Tailscale) signals WebRTC here with no relay.
 * Same JSON, same states (ring -> on -> end), same limits; pure (unit-tested), [LanCall] holds it and wakes the long-polls.
 */
object LanCallLogic {
    const val RING_MS = 45_000L                     // relay CALL_RING
    const val CALL_MAX = 60 * 60_000L               // relay CALL_MAX (video call)
    const val LISTEN_MAX = 10 * 60_000L + 15_000L   // relay LISTEN_MAX (listen + cam)
    const val KEEP_MS = 60_000L                     // relay CALL_KEEP: an ended slot is still shown this long
    const val SDP_MAX = 16_000
    private val ID = Regex("^[0-9a-f]{16}$")
    // relay CALL_WHY + CAM_WHY: anything else is "hangup"
    private val WHY = setOf("hangup", "decline", "busy", "timeout", "failed", "noperm", "nocam", "missed", "no mic permission", "notice blocked",
        "mic blocked", "mic failed", "ringing", "off", "no camera permission", "no such camera", "screen off", "cam blocked", "cam failed")

    data class Slot(val id: String, val st: String, val by: String, val at: Long, val kind: String = "", val face: String = "front",
                    val snd: Boolean = false, val offer: String = "", val answer: String = "", val on: Long = 0, val why: String = "",
                    val endAt: Long = 0, val endBy: String = "")

    /** The slot at [now]: a ring nobody took ends "missed", a session past its cap "timeout", an end older than [KEEP_MS] is gone. */
    fun at(s: Slot?, now: Long): Slot? = when {
        s == null -> null
        s.st == "end" -> if (now - s.endAt > KEEP_MS) null else s
        s.st == "ring" && now - s.at > RING_MS -> s.copy(st = "end", why = "missed", endAt = now)
        s.st == "on" && now - s.on > (if (s.kind.isEmpty()) CALL_MAX else LISTEN_MAX) -> s.copy(st = "end", why = "timeout", endAt = now)
        else -> s
    }

    /** When [at] changes [s] next (ms epoch), or 0 when it never does. */
    fun due(s: Slot?): Long = when (s?.st) {
        "ring" -> s.at + RING_MS + 1
        "on" -> s.on + (if (s.kind.isEmpty()) CALL_MAX else LISTEN_MAX) + 1
        "end" -> s.endAt + KEEP_MS + 1
        else -> 0
    }

    /** What either side reads (relay `view`): ring carries the offer, on the answer. */
    fun view(s: Slot?): JSONObject? = s?.let {
        JSONObject().put("id", it.id).put("st", it.st).put("by", it.by).put("at", it.at).apply {
            if (it.kind.isNotEmpty()) put("kind", it.kind)
            if (it.kind == "cam") put("face", it.face).put("snd", it.snd)
            if (it.st == "ring") put("sdp", it.offer) else if (it.st == "on") put("sdp", it.answer)
            if (it.why.isNotEmpty()) put("why", it.why)
        }
    }

    /** The shape Call.fromRelay takes (the relay's sync `call`): + ice, empty (host candidates are enough on one network / Tailscale). */
    fun forPhone(s: Slot): JSONObject = (view(s) ?: JSONObject()).apply { if (s.st == "ring") put("ice", JSONArray()) else remove("sdp") }

    /** Result of a POST: the new slot, HTTP code, body, and whether the phone's call code must hear about it ([tell]). */
    class Out(val slot: Slot?, val code: Int, val body: JSONObject, val tell: Boolean = false)

    private fun err(s: Slot?, code: Int, e: String) = Out(s, code, JSONObject().put("error", e))
    private fun ok(s: Slot?, tell: Boolean = false) = Out(s, 200, JSONObject().put("call", view(s) ?: JSONObject.NULL), tell)

    /**
     * POST {op: offer|answer|end, id, sdp?, why?, kind?, face?, snd?} (relay `callRoute`). [phone] = the phone's own call code
     * (answer / end), else the parent's page. [busy] = the phone already runs a call / listen / camera session (relay or LAN stream).
     */
    fun post(cur: Slot?, b: JSONObject, phone: Boolean, who: String, busy: Boolean, now: Long): Out {
        val c = at(cur, now)
        val op = b.optString("op"); val id = b.optString("id"); val sdp = b.optString("sdp")
        if (!ID.matches(id)) return err(c, 400, "bad id")
        if ((op == "offer" || op == "answer") && (!sdp.startsWith("v=0") || sdp.length > SDP_MAX)) return err(c, 400, "bad sdp")
        when (op) {
            "offer" -> {
                if (phone) return err(c, 400, "bad op")
                if (c != null && c.id == id) return ok(c)   // a retried offer: same slot
                if ((c != null && c.st != "end") || busy) return Out(c, 409, JSONObject().put("error", "busy").put("by", c?.by ?: ""))
                val kind = b.optString("kind").takeIf { it == "listen" || it == "cam" } ?: ""
                val n = Slot(id, "ring", who.take(40), now, kind, if (b.optString("face") == "back") "back" else "front",
                    kind == "cam" && b.optBoolean("snd"), offer = sdp)
                return ok(n, tell = true)
            }
            "answer" -> {
                if (!phone) return err(c, 400, "bad op")
                if (c == null || c.id != id) return err(c, 404, "no call")
                if (c.st != "ring") return Out(c, 409, JSONObject().put("error", "not ringing").put("call", view(c)))
                return ok(c.copy(st = "on", on = now, answer = sdp))
            }
            "end" -> {
                if (c == null || c.id != id) return err(c, 404, "no call")
                if (c.st == "end") return ok(c)
                val why = b.optString("why").takeIf { it in WHY } ?: "hangup"
                return ok(c.copy(st = "end", why = why, endAt = now, endBy = if (phone) "kid" else "parent"), tell = !phone)
            }
        }
        return err(c, 400, "bad op")
    }
}
