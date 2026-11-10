package dev.kidguard

import org.junit.Assert.*
import org.junit.Test

class TsLogicTest {
    @Test fun apkNameFromJson() {
        val j = """{"Tarballs":{"arm64":"x.tgz"},"APKs":{"universal":"tailscale-android-universal-1.104.5.apk"},"APKsVersion":"1.104.5"}"""
        assertEquals("tailscale-android-universal-1.104.5.apk", TsLogic.apkName(j))
        assertNull(TsLogic.apkName("""{"APKs":{}}"""))
        assertNull(TsLogic.apkName("not json"))
        assertNull(TsLogic.apkName("""{"APKs":{"universal":"../evil/x.apk"}}"""))
        assertNull(TsLogic.apkName("""{"APKs":{"universal":"a.sh"}}"""))
    }

    @Test fun shaFirstToken() {
        val h = "a".repeat(64)
        assertEquals(h, TsLogic.sha256Of("${h.uppercase()}  file.apk\n"))
        assertEquals(h, TsLogic.sha256Of(h))
        assertNull(TsLogic.sha256Of("abc"))
        assertNull(TsLogic.sha256Of(""))
    }

    @Test fun tailnetRange() {
        fun ip(a: Int, b: Int) = byteArrayOf(a.toByte(), b.toByte(), 1, 2)
        assertTrue(TsLogic.isTailnet(ip(100, 64)))
        assertTrue(TsLogic.isTailnet(ip(100, 127)))
        assertFalse(TsLogic.isTailnet(ip(100, 63)))
        assertFalse(TsLogic.isTailnet(ip(100, 128)))
        assertFalse(TsLogic.isTailnet(ip(192, 168)))
        assertFalse(TsLogic.isTailnet(byteArrayOf(100, 64)))
    }

    @Test fun keyAndHost() {
        assertTrue(TsLogic.validKey("tskey-auth-kABC123CNTRL-abcdefghijklmnop"))
        assertFalse(TsLogic.validKey("short"))
        assertFalse(TsLogic.validKey("has space in it 123456"))
        assertFalse(TsLogic.validKey(""))
        assertEquals("kid-ab12", TsLogic.hostName("", "AB12cdef0123"))
        assertEquals("phone-an", TsLogic.hostName("  Phone An!! ", "x"))
        assertEquals("kid-x", TsLogic.hostName("!!!", "---"))
        assertEquals(40, TsLogic.hostName("a".repeat(80), "x").length)
    }

    @Test fun restrictionsCarryKeyAndPolicy() {
        val m = TsLogic.restrictions("tskey-xyz", "kid-1")
        assertEquals("tskey-xyz", m["AuthKey"]); assertEquals("kid-1", m["Hostname"])
        assertEquals(true, m["ForceEnabled"]); assertEquals("hide", m["OnboardingFlow"])
        assertEquals("never", m["UseTailscaleDNSSettings"]); assertEquals("always", m["AllowIncomingConnections"])
        assertEquals("KidGuard", m["ManagedByOrganizationName"])
    }

    @Test fun apiTokenFlow() {
        assertTrue(TsLogic.isApiToken("tskey-api-kABC-xyz0123456789"))
        assertFalse(TsLogic.isApiToken("tskey-auth-kABC-xyz0123456789"))
        val b = org.json.JSONObject(TsLogic.keyBody())
        val c = b.getJSONObject("capabilities").getJSONObject("devices").getJSONObject("create")
        assertFalse(c.getBoolean("reusable")); assertFalse(c.getBoolean("ephemeral")); assertTrue(c.getBoolean("preauthorized"))
        assertEquals(3600, b.getInt("expirySeconds"))
        assertEquals("tskey-auth-kX-abcdefghij", TsLogic.keyFrom("""{"id":"kX","key":"tskey-auth-kX-abcdefghij"}"""))
        assertNull(TsLogic.keyFrom("""{"id":"kX"}"""))
        assertNull(TsLogic.keyFrom("""{"key":"evil key with spaces"}"""))
        val devs = """{"devices":[{"id":"111","nodeId":"nA","addresses":["100.64.0.1","fd7a::1"]},{"id":"222","nodeId":"nB","addresses":["100.101.2.3"]}]}"""
        assertEquals("nB", TsLogic.deviceId(devs, "100.101.2.3"))
        assertNull(TsLogic.deviceId(devs, "100.1.1.1"))
        assertEquals("333", TsLogic.deviceId("""{"devices":[{"id":"333","addresses":["100.9.9.9"]}]}""", "100.9.9.9"))
        assertNull(TsLogic.deviceId("""{"devices":[{"nodeId":"../x","addresses":["100.9.9.9"]}]}""", "100.9.9.9"))
    }

    @Test fun reviveSteps() {
        val m = 60_000L; val big = Long.MAX_VALUE / 2
        assertEquals(0, TsLogic.reviveStep(10_000, big, big, true, true))          // short gap: wait
        assertEquals(1, TsLogic.reviveStep(25_000, big, big, true, true))          // down: broadcast first
        assertEquals(0, TsLogic.reviveStep(50_000, 25_000, big, true, true))       // broadcast at most every 60 s
        assertEquals(2, TsLogic.reviveStep(2 * m, 30_000, big, true, true))        // still down on home: open once
        assertEquals(1, TsLogic.reviveStep(2 * m, m, big, false, true))            // not on home: only broadcast
        assertEquals(1, TsLogic.reviveStep(2 * m, m, big, true, false))            // no background start right
        assertEquals(1, TsLogic.reviveStep(5 * m, m, 3 * m, true, true))           // opened 3 min ago: no second open
    }
}
