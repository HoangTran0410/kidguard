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
}
