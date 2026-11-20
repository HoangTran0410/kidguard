package dev.kidguard

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiScopeTest {
    @Test fun onlySafeCommands() {
        for (t in listOf("lock", "locate", "message", "ring", "ringStop", "sosAck", "answer")) assertTrue(t, AiScope.cmdAllowed(t))
        for (t in listOf("release", "unpairAll", "unpair", "clearData", "reboot", "adbShell", "adbTcp", "listen", "update", "pinSet", "pinReset",
            "aiToken", "openPairing", "relay", "fileUp", "wake", "setting", "totpSet", "tgSet", "", null)) assertFalse(t.toString(), AiScope.cmdAllowed(t))
    }

    @Test fun allowedCommandsNeedNoPin() {
        for (t in AiScope.CMDS) assertFalse(t, CmdGate.needs(t))
        assertTrue(CmdGate.needs("aiToken"))
    }

    @Test fun routes() {
        assertTrue(AiScope.routeAllowed("GET", "/state")); assertTrue(AiScope.routeAllowed("PUT", "/rules")); assertTrue(AiScope.routeAllowed("POST", "/cmd"))
        assertFalse(AiScope.routeAllowed("GET", "/rules")); assertFalse(AiScope.routeAllowed("POST", "/unpair"))
        for (p in listOf("/screen", "/input", "/listen", "/files", "/device", "/pair", "/shot/x", "/logday")) assertFalse(p, AiScope.routeAllowed("GET", p))
    }

    @Test fun lockedRulesKeepTheStoredValue() {
        val cur = JSONObject().put("noInstall", true).put("safeDns", "dns.example").put("dailyMin", 60)
        val inc = JSONObject().put("noInstall", false).put("safeDns", "").put("noVpn", true).put("pinHash", "evil").put("pin", "1234")
            .put("mode", JSONObject().put("until", 1)).put("dailyMin", 10).put("blocked", JSONArray(listOf("a.b")))
        val o = AiScope.lockRules(inc, cur)
        assertEquals(true, o.getBoolean("noInstall")); assertEquals("dns.example", o.getString("safeDns"))
        assertFalse(o.has("noVpn")); assertFalse(o.has("pinHash")); assertFalse(o.has("pin")); assertFalse(o.has("mode"))
        assertEquals(10, o.getInt("dailyMin")); assertTrue(o.has("blocked"))
    }

    @Test fun stateHidesPrivateParts() {
        val st = JSONObject().put("battery", 50).put("chat", JSONArray().put("hi")).put("notifs", JSONObject()).put("calls", JSONArray()).put("track", JSONArray())
            .put("events", JSONArray().put(JSONObject().put("kind", "word").put("text", "secret")).put(JSONObject().put("kind", "battery").put("text", "low")))
        val o = AiScope.state(st)
        assertEquals(50, o.getInt("battery"))
        for (k in listOf("chat", "notifs", "calls", "track")) assertFalse(k, o.has(k))
        assertEquals("", o.getJSONArray("events").getJSONObject(0).getString("text"))
        assertEquals("low", o.getJSONArray("events").getJSONObject(1).getString("text"))
        assertTrue(st.has("chat"))   // the phone's own state is untouched
    }
}
