package dev.kidguard

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * The LAN signal channel (docs/flows/remote.md 2.17): `/api/call` on the phone = the relay's `/call`, for a page that reaches the phone
 * straight (LAN, Tailscale). One slot ([LanCallLogic]); a parent's offer / end goes to the same entry the relay's sync uses
 * ([Call.fromRelay], which hands listen / cam on), and the phone's own answer / end ([Call.post]) lands back here when the id is ours.
 * So video call, listen and live camera need nothing per kind. Media stays WebRTC peer to peer (host candidates, no STUN / TURN).
 */
object LanCall {
    private val lock = Object()
    private var slot: LanCallLogic.Slot? = null
    private var mine = ""   // ids this channel carried (last few): a late end for one never goes to the relay
    @Volatile private var app: Guard? = null
    private val main = Handler(Looper.getMainLooper())
    private val tick = Runnable { now() }

    /** This call id came over the LAN: its answer / end stay here. */
    fun ours(id: String) = id.isNotEmpty() && synchronized(lock) { mine.split(' ').contains(id) }

    private fun busy() = Call.cur != null || ListenCall.active || CamCall.active || Listen.active

    /** GET /api/call[?ice=1][&id=&st=]: the slot; with id + st, waits up to 12 s for it to change (the page's fetch gives up at 15 s). */
    fun get(g: Guard, q: Map<String, String>): JSONObject {
        app = g
        var c = now()
        val id = q["id"]; val st = q["st"] ?: ""
        if (!id.isNullOrEmpty() && c != null && c.id == id && c.st == st) {
            val end = System.currentTimeMillis() + 12_000
            synchronized(lock) {
                while (true) {
                    val left = end - System.currentTimeMillis()
                    val s = slot
                    if (left <= 0 || s == null || s.id != id || s.st != st) break
                    try { lock.wait(left) } catch (_: InterruptedException) { break }
                }
            }
            c = now()
        }
        return JSONObject().put("call", LanCallLogic.view(c) ?: JSONObject.NULL).apply { if (q["ice"] == "1") put("ice", JSONArray()) }
    }

    /** POST /api/call from the parent's page -> HTTP code + body. */
    fun post(g: Guard, b: JSONObject, who: String): Pair<Int, String> {
        app = g
        if (Flavor.HOME) return 404 to """{"error":"not found"}"""
        val o = apply(b, phone = false, who = who)
        return o.code to o.body.toString()
    }

    /** From [Call.post]: the phone's answer / end for a LAN call. Same reply shape as the relay's. */
    fun phone(op: String, id: String, sdp: String, why: String): String {
        val b = JSONObject().put("op", op).put("id", id).apply { if (sdp.isNotEmpty()) put("sdp", sdp); if (why.isNotEmpty()) put("why", why) }
        return apply(b, phone = true, who = "").body.toString()
    }

    private fun apply(b: JSONObject, phone: Boolean, who: String): LanCallLogic.Out {
        val o = synchronized(lock) {
            val o = LanCallLogic.post(slot, b, phone, who, busy = !phone && busy(), now = System.currentTimeMillis())
            if (o.slot != slot) set(o.slot)
            o
        }
        if (o.tell) o.slot?.let { tell(it) }
        return o
    }

    /** The slot now; a ring / session that ran out ends here and the phone's call code hears it. */
    private fun now(): LanCallLogic.Slot? {
        var ended: LanCallLogic.Slot? = null
        val c = synchronized(lock) {
            val was = slot; val c = LanCallLogic.at(was, System.currentTimeMillis())
            if (c != was) { set(c); if (c != null && was != null && was.st != "end") ended = c }
            c
        }
        ended?.let { tell(it) }
        return c
    }

    /** Holds [lock]. Wakes the long-polls and re-arms the deadline check. */
    private fun set(s: LanCallLogic.Slot?) {
        slot = s
        if (s != null && !mine.split(' ').contains(s.id)) mine = (mine.split(' ').filter { it.isNotEmpty() }.takeLast(4) + s.id).joinToString(" ")
        lock.notifyAll()
        main.removeCallbacks(tick)
        val due = LanCallLogic.due(s)
        if (due > 0) main.postDelayed(tick, (due - System.currentTimeMillis()).coerceAtLeast(50))
    }

    /** Hand the slot to the phone's call code, the way the relay's sync reply does (no relay base / token: answers come back here). */
    private fun tell(s: LanCallLogic.Slot) {
        val g = app ?: Guard.running ?: return
        val c = LanCallLogic.forPhone(s)
        Thread({ runCatching { Call.fromRelay(g, c, "", "", "") }.onFailure { Log.w("KidGuard", "lan call", it) } }, "lan-call").start()
    }
}
