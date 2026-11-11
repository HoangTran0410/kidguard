package dev.kidguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BankListLogicTest {
    @Test fun addsOnlyNeverSeen() {
        val (list, seen) = BankListLogic.merge(setOf("com.VCB"), setOf("com.VCB", "com.mbmobile"), setOf("com.VCB", "com.mbmobile", "xyz.be.cake"), emptySet())
        assertEquals(setOf("com.VCB", "xyz.be.cake"), list)          // com.mbmobile was taken off by the parent: stays off
        assertEquals(setOf("com.VCB", "com.mbmobile", "xyz.be.cake"), seen)
    }

    @Test fun secondRunChangesNothing() {
        val (l1, s1) = BankListLogic.merge(setOf("a.b"), setOf("a.b"), setOf("a.b", "c.d"), emptySet())
        val (l2, s2) = BankListLogic.merge(l1 - "c.d", s1, setOf("a.b", "c.d"), emptySet())   // parent removed c.d
        assertEquals(setOf("a.b"), l2); assertEquals(s1, s2)
    }

    @Test fun dropOnlyWhatAListOffered() {
        val (list, _) = BankListLogic.merge(setOf("a.b", "my.own"), setOf("a.b"), setOf("x.y"), setOf("a.b", "my.own"))
        assertEquals(setOf("my.own", "x.y"), list)   // my.own was typed by the parent, never offered: kept
    }

    @Test fun legacyMakesTheNewOnesNew() {
        val (list, _) = BankListLogic.merge(BankListLogic.LEGACY, BankListLogic.LEGACY, AccPauseLogic.DEFAULT, emptySet())
        assertTrue("xyz.be.cake" in list); assertTrue("vms.com.vn.mymobifone" in list)
    }

    @Test fun capAt100() {
        val many = (1..150).map { "p.a$it" }.toSet()
        assertEquals(100, BankListLogic.merge(emptySet(), emptySet(), many, emptySet()).first.size)
    }

    @Test fun parseValidates() {
        val l = BankListLogic.parse("""{"v":1,"apps":[{"pkg":"com.VCB","name":"VCB"},{"pkg":"xyz.be.cake","acc":true,"vpn":true},{"pkg":"only.vpn","acc":false,"vpn":true},{"pkg":"bad pkg"},{"pkg":"x"}],"drop":["a.b","!!"]}""")!!
        assertEquals(setOf("com.VCB", "xyz.be.cake"), l.acc)
        assertEquals(setOf("xyz.be.cake", "only.vpn"), l.vpn)
        assertEquals(setOf("a.b"), l.drop)
        assertNull(BankListLogic.parse("not json"))
        assertNull(BankListLogic.parse("""{"apps":[]}"""))
    }

    @Test fun vpnSnapRoundTrip() {
        assertEquals("tailscale.x" to true, VpnPauseLogic.unsnap(VpnPauseLogic.snap("tailscale.x", true)))
        assertEquals("a.b" to false, VpnPauseLogic.unsnap(VpnPauseLogic.snap("a.b", false)))
        assertEquals("", VpnPauseLogic.snap(null, true))
        assertNull(VpnPauseLogic.unsnap("")); assertNull(VpnPauseLogic.unsnap("garbage"))
    }

    @Test fun rulesKeepVpnPause() {
        val r = Rules.fromJson(Rules(vpnPause = setOf("a.b")).toJson())
        assertEquals(setOf("a.b"), r.vpnPause)
        assertEquals(VpnPauseLogic.DEFAULT, Rules.fromJson(org.json.JSONObject("{}")).vpnPause)
        assertEquals(setOf("c.d"), Rules.fromJson(Rules(devPause = setOf("c.d")).toJson()).devPause)
        assertEquals(DevPauseLogic.DEFAULT, Rules.fromJson(org.json.JSONObject("{}")).devPause)
        val l = BankListLogic.parse("""{"apps":[{"pkg":"a.b","dev":true,"acc":false},{"pkg":"c.d"}]}""")!!
        assertEquals(setOf("a.b"), l.dev); assertEquals(setOf("c.d"), l.acc)
    }
}
