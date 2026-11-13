package dev.kidguard.parent

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ActTest {
    @Test fun answerCmdMatchesPage() {
        // page: cmd({type: "answer", id, ok: true, min: n, ...(note ? {note} : {})})
        val y = Act.answerCmd("r1", true, 15)
        assertEquals("answer", y.getString("type")); assertEquals("r1", y.getString("id")); assertTrue(y.getBoolean("ok")); assertEquals(15, y.getInt("min"))
        assertFalse(y.has("note"))
        assertEquals(setOf("type", "id", "req", "ok", "min"), y.keys().asSequence().toSet())
        // page: cmd({type: "answer", id, ok: false, ...}) has no min
        val n = Act.answerCmd("r1", false, 30)
        assertEquals(setOf("type", "id", "req", "ok"), n.keys().asSequence().toSet()); assertFalse(n.getBoolean("ok"))
        assertEquals("ok", Act.answerCmd("r1", true, 15, "  ok  ").getString("note"))
        assertFalse(Act.answerCmd("r1", true, 15, "   ").has("note"))
        assertEquals(200, Act.answerCmd("r1", true, 15, "x".repeat(300)).getString("note").length)
    }

    @Test fun grantChoices() {
        // N and 2N (rounded to 5); the "N/2" button does not fit Android's 3 actions
        assertEquals(listOf(15, 30), Act.grantChoices(0))
        assertEquals(listOf(15, 30), Act.grantChoices(15))
        assertEquals(listOf(30, 60), Act.grantChoices(30))
        assertEquals(listOf(20, 40), Act.grantChoices(20))
        assertEquals(listOf(10, 20), Act.grantChoices(10))
        assertEquals(listOf(7, 15), Act.grantChoices(7))
        assertEquals(listOf(60, 120), Act.grantChoices(60))
        assertEquals(listOf(600), Act.grantChoices(600))
        assertEquals(listOf(15, 30), Act.grantChoices(9999))
    }

    @Test fun actionsPerEvent() {
        assertEquals(listOf(Act.A(Act.GRANT, 30), Act.A(Act.GRANT, 60), Act.A(Act.DECLINE)), Act.actionsFor("req:r1", 30))
        assertEquals(listOf(Act.A(Act.GRANT, 20), Act.A(Act.GRANT, 40), Act.A(Act.DECLINE)), Act.actionsFor("req:r1", 20))
        assertEquals(listOf(Act.A(Act.APPROVE), Act.A(Act.DECLINE)), Act.actionsFor("req:r1", 0, app = true))
        assertEquals(listOf(Act.A(Act.MAP)), Act.actionsFor("sos:s1"))
        assertEquals(listOf(Act.A(Act.MAP)), Act.actionsFor("ci:c1"))   // v0.9.7 check-in
        assertEquals(emptyList<Act.A>(), Act.actionsFor("ev:e1"))
        assertEquals(listOf(Act.A(Act.MAP)), Act.actionsFor("sos:s1", phone = " "))
        assertEquals(listOf(Act.A(Act.MAP), Act.A(Act.CALL)), Act.actionsFor("sos:s1", phone = "0901234567"))
        for (k in listOf("ev:e1", "lost", "acc", "req:")) assertEquals(k, emptyList<Act.A>(), Act.actionsFor(k, 30))
        assertEquals("r1", Act.reqId("req:r1")); assertNull(Act.reqId("sos:r1"))
    }

    @Test fun eventsCarryMinAndLoc() {
        val now = System.currentTimeMillis()
        val loc = Logic.Loc(21.0, 105.8, now)
        val o = Logic.evaluate("An", Logic.Seen(true, listOf("a" to 20), now, sos = listOf("s1" to now), loc = loc), Logic.NotifyState(init = true), now, tr = TestTr.VI)
        val req = o.events.first { it.key == "req:a" }; val sos = o.events.first { it.key == "sos:s1" }
        assertEquals(20, req.min); assertSame(loc, sos.loc)
        val kid = Logic.KidCfg("An", "https://r.example", "relay", "0123456789abcdef", "tok")
        val p = Logic.pushEvent(listOf(kid), mapOf("kind" to "req", "kid" to kid.id, "eid" to "r:x", "min" to "45"), { Logic.NotifyState() }, tr = TestTr.VI)!!
        assertEquals("req:x", p.second.key); assertEquals(45, p.second.min)
    }

    private val relayBody = JSONObject("""{"state":{"day":"2026-11-07","rules":{"rev":3,"dailyMin":60,"pinSet":true,"bonus":{"day":"2026-11-06","min":30}},
        "requests":[{"id":"r1","t":1,"min":20}],"location":{"lat":21.03,"lng":105.85,"t":5}},"rules":{"rev":5,"dailyMin":90,"pinSet":true,"bonus":{"day":"2026-11-07","min":10}},"seen":1}""")

    @Test fun rulesPickAndBonus() {
        assertEquals(90, Act.pickRules(relayBody, "relay")!!.getInt("dailyMin"))   // pending PUT is newer
        val older = JSONObject(relayBody.toString()).apply { getJSONObject("rules").put("rev", 1) }
        assertEquals(60, Act.pickRules(older, "relay")!!.getInt("dailyMin"))
        assertNull(Act.pickRules(JSONObject("""{"state":null,"rules":null}"""), "relay"))
        val lan = relayBody.getJSONObject("state")
        assertEquals(60, Act.pickRules(lan, "lan")!!.getInt("dailyMin"))

        val b = Act.bonusRules(Act.pickRules(relayBody, "relay")!!, "2026-11-07", 15)
        assertEquals(25, b.getJSONObject("bonus").getInt("min")); assertEquals("2026-11-07", b.getJSONObject("bonus").getString("day"))
        assertFalse(b.has("pinSet")); assertFalse(b.has("pinHash")); assertEquals(90, b.getInt("dailyMin"))
        // yesterday's bonus does not count
        assertEquals(15, Act.bonusRules(lan.getJSONObject("rules"), "2026-11-07", 15).getJSONObject("bonus").getInt("min"))
    }

    @Test fun requestAndResult() {
        assertTrue(Act.hasRequest(relayBody, "relay", "r1")); assertFalse(Act.hasRequest(relayBody, "relay", "r2"))
        assertTrue(Act.hasRequest(relayBody.getJSONObject("state"), "lan", "r1"))
        assertEquals(Act.Res.OK, Act.cmdResult(200, """{"result":"ok"}"""))
        assertEquals(Act.Res.QUEUED, Act.cmdResult(200, """{"result":"queued"}"""))
        assertEquals(Act.Res.ALREADY, Act.cmdResult(200, """{"result":"no such request"}"""))
        assertEquals(Act.Res.FAIL, Act.cmdResult(429, """{"error":"queue full"}"""))
        assertEquals(Act.Res.FAIL, Act.cmdResult(null, ""))
        assertEquals(Act.Res.FAIL, Act.cmdResult(200, """{"result":"failed"}"""))
    }

    @Test fun location() {
        val l = Act.locOf(relayBody.getJSONObject("state"))!!
        assertEquals("geo:21.03000,105.85000?q=21.03000,105.85000(B%C3%A9%20An)", Act.geoUri(l, "Bé An"))
        assertNull(Act.locOf(JSONObject("""{"location":null}""")))
        assertNull(Act.locOf(JSONObject("""{"location":{"lat":"x","lng":1}}""")))
        assertNull(Act.locOf(null))
    }
}
