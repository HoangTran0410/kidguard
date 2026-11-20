package dev.kidguard

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LanCallLogicTest {
    private val id = "0123456789abcdef"
    private val id2 = "fedcba9876543210"
    private fun offer(i: String = id, kind: String = "") = JSONObject().put("op", "offer").put("id", i).put("sdp", "v=0 offer").apply { if (kind.isNotEmpty()) put("kind", kind) }
    private fun ring(t: Long = 0, kind: String = "") = LanCallLogic.post(null, offer(kind = kind), false, "", false, t).slot!!

    @Test fun offerRingsAndTellsThePhone() {
        val o = LanCallLogic.post(null, offer(), false, "Mom", false, 1000)
        assertEquals(200, o.code); assertTrue(o.tell)
        assertEquals("ring", o.slot!!.st)
        val v = o.body.getJSONObject("call")
        assertEquals("v=0 offer", v.getString("sdp")); assertFalse(v.has("kind"))
        val p = LanCallLogic.forPhone(o.slot!!)
        assertEquals(0, p.getJSONArray("ice").length()); assertEquals("v=0 offer", p.getString("sdp"))
    }
    @Test fun camKeepsFaceAndSound() {
        val o = LanCallLogic.post(null, offer(kind = "cam").put("face", "back").put("snd", true), false, "", false, 0)
        val v = LanCallLogic.view(o.slot)!!
        assertEquals("cam", v.getString("kind")); assertEquals("back", v.getString("face")); assertTrue(v.getBoolean("snd"))
        val l = LanCallLogic.post(null, offer(kind = "listen").put("snd", true), false, "", false, 0).slot!!
        assertEquals("listen", l.kind); assertFalse(l.snd)
        assertEquals("", LanCallLogic.post(null, offer(kind = "weird"), false, "", false, 0).slot!!.kind)
    }
    @Test fun busyRules() {
        val r = ring()
        assertEquals(409, LanCallLogic.post(r, offer(id2), false, "", false, 10).code)               // one slot
        assertEquals(200, LanCallLogic.post(r, offer(id), false, "", false, 10).code)                // same id again: same slot
        assertEquals(409, LanCallLogic.post(null, offer(id2), false, "", true, 10).code)             // phone busy (relay call, LAN stream)
        val ended = r.copy(st = "end", endAt = 5)
        assertEquals(200, LanCallLogic.post(ended, offer(id2), false, "", false, 10).code)           // an ended slot is free
    }
    @Test fun answerOnlyByPhoneWhileRinging() {
        val r = ring()
        val ans = JSONObject().put("op", "answer").put("id", id).put("sdp", "v=0 ans")
        assertEquals(400, LanCallLogic.post(r, ans, false, "", false, 1).code)   // the page never answers
        val o = LanCallLogic.post(r, ans, true, "", false, 1)
        assertEquals("on", o.slot!!.st); assertFalse(o.tell)
        assertEquals("v=0 ans", o.body.getJSONObject("call").getString("sdp"))
        assertEquals(409, LanCallLogic.post(o.slot, ans, true, "", false, 2).code)
        assertEquals(404, LanCallLogic.post(r, ans.put("id", id2), true, "", false, 1).code)
    }
    @Test fun endWhoAndWhy() {
        val r = ring()
        val p = LanCallLogic.post(r, JSONObject().put("op", "end").put("id", id).put("why", "nonsense"), false, "", false, 5)
        assertEquals("hangup", p.slot!!.why); assertEquals("parent", p.slot!!.endBy); assertTrue(p.tell)
        val k = LanCallLogic.post(r, JSONObject().put("op", "end").put("id", id).put("why", "screen off"), true, "", false, 5)
        assertEquals("screen off", k.slot!!.why); assertEquals("kid", k.slot!!.endBy); assertFalse(k.tell)
        assertFalse(LanCallLogic.post(p.slot, JSONObject().put("op", "end").put("id", id), false, "", false, 6).tell)   // twice: no change
    }
    @Test fun badInput() {
        assertEquals(400, LanCallLogic.post(null, offer("xyz"), false, "", false, 0).code)
        assertEquals(400, LanCallLogic.post(null, offer().put("sdp", "nope"), false, "", false, 0).code)
        assertEquals(400, LanCallLogic.post(null, offer().put("sdp", "v=0" + "a".repeat(LanCallLogic.SDP_MAX)), false, "", false, 0).code)
        assertEquals(400, LanCallLogic.post(null, offer(), true, "", false, 0).code)   // the phone never offers
        assertEquals(400, LanCallLogic.post(null, JSONObject().put("op", "x").put("id", id), false, "", false, 0).code)
    }
    @Test fun timeouts() {
        val r = ring(0)
        assertSame(r, LanCallLogic.at(r, LanCallLogic.RING_MS))
        val m = LanCallLogic.at(r, LanCallLogic.RING_MS + 1)!!
        assertEquals("end", m.st); assertEquals("missed", m.why)
        assertNull(LanCallLogic.at(m, m.endAt + LanCallLogic.KEEP_MS + 1))
        val call = r.copy(st = "on", on = 100)
        assertEquals("on", LanCallLogic.at(call, 100 + LanCallLogic.LISTEN_MAX + 1)!!.st)   // a video call runs an hour
        assertEquals("timeout", LanCallLogic.at(call, 100 + LanCallLogic.CALL_MAX + 1)!!.why)
        val cam = ring(0, "cam").copy(st = "on", on = 100)
        assertEquals("timeout", LanCallLogic.at(cam, 100 + LanCallLogic.LISTEN_MAX + 1)!!.why)
        assertEquals(LanCallLogic.RING_MS + 1, LanCallLogic.due(r)); assertEquals(0L, LanCallLogic.due(null))
        // a ring that ran out frees the slot for the next offer
        assertEquals(200, LanCallLogic.post(r, offer(id2), false, "", false, LanCallLogic.RING_MS + 5).code)
    }
}
