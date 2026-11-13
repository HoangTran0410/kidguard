package dev.kidguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeaturesTest {
    private fun f(c: Caps, id: String) = Features.status(c).first { it.id == id }

    @Test fun nothingGranted() {
        val s = Features.status(Caps())
        assertEquals(Features.TABLE.map { it.first }, s.map { it.id })
        assertTrue(s.none { it.ok })
        assertEquals(listOf("adb"), f(Caps(), "debloat").missing)   // fewest missing, first on ties
        assertEquals(listOf("owner"), f(Caps(), "limits").missing)
        assertEquals(listOf("owner"), f(Caps(), "block").missing)
        assertEquals(listOf("admin"), f(Caps(acc = true), "block").missing)
        assertEquals(listOf("owner"), f(Caps(), "noUninstall").missing)
    }

    @Test fun ownerUnlocksMost() {
        val s = Features.status(Caps(owner = true))
        for (id in listOf("launcherLock", "debloat", "limits", "block", "noUninstall", "dns")) assertTrue(id, s.first { it.id == id }.ok)
        assertFalse(s.first { it.id == "launcher" }.ok)
        assertEquals(listOf("home"), s.first { it.id == "launcher" }.missing)
    }

    @Test fun basicAlternative() {
        val c = Caps(acc = true, admin = true)
        assertTrue(f(c, "block").ok); assertTrue(f(c, "noUninstall").ok)
        assertFalse(f(c, "limits").ok); assertEquals(listOf("usage"), f(c, "limits").missing)
    }

    @Test fun launcherLockBasic() {
        assertTrue(f(Caps(home = true, acc = true), "launcherLock").ok)
        assertFalse(f(Caps(acc = true), "launcherLock").ok)
    }

    @Test fun adbIsAnAlternativeForDebloat() {
        assertTrue(f(Caps(adb = true), "debloat").ok)
        assertTrue(f(Caps(), "debloat").missing.size == 1)
    }

    @Test fun wssUnlocksDnsAndLocationLockWithoutOwner() {
        assertFalse(f(Caps(), "dns").ok); assertFalse(f(Caps(), "lockLocation").ok)
        assertEquals(listOf("owner"), f(Caps(), "dns").missing)
        val c = Caps(wss = true)
        assertTrue(f(c, "dns").ok); assertTrue(f(c, "lockLocation").ok)
        assertFalse(f(c, "limits").ok)
    }

    @Test fun mapHasEveryCap() {
        assertEquals(Features.CAPS.toSet(), Caps().toMap().keys)
        assertTrue(Features.TABLE.flatMap { it.second }.flatten().all { it in Features.CAPS })
        assertTrue(Features.PAUSES.values.flatten().all { it in Features.CAPS })
        // without() must know every cap (a new Caps field forgotten there would never be taken away)
        val all = Caps(true, true, true, true, true, true, true, true, true, true, true, true, true)
        assertTrue(all.without(Features.CAPS).toMap().values.none { it })
    }

    /** The bank-app popup lists Features.lost(): every pause kind needs a row in PAUSES. */
    @Test fun everyPauseKindHasCaps() {
        for (k in PauseAsk.Kind.values()) assertTrue(k.name, k.name.lowercase() in Features.PAUSES)
    }

    @Test fun lostByPause() {
        val c = Caps(owner = true, acc = true, adb = true, adbNet = true, vpn = true, paired = true)
        assertEquals(listOf("screen"), Features.lost(c, "acc"))                 // block / limits keep working through owner
        assertEquals(listOf("adbShell", "autoGrant", "pcAdb"), Features.lost(c, "dev"))  // debloat stays (owner)
        assertEquals(listOf("pcAdb"), Features.lost(c, "tcp"))
        assertEquals(listOf("tailscale"), Features.lost(c, "vpn"))
        assertEquals(listOf("debloat", "adbShell", "autoGrant", "pcAdb"), Features.lost(Caps(adb = true, adbNet = true), "dev"))
        assertEquals(listOf("block", "screen"), Features.lost(Caps(acc = true, admin = true), "acc"))
        assertTrue(Features.lost(Caps(), "acc").isEmpty())                      // nothing works, nothing lost
    }

    /** Every feature id needs its name + description, in English and Vietnamese (the popup / sheet / page print them). */
    @Test fun everyFeatureHasNames() {
        val res = listOf("values", "values-vi").map { d ->
            java.io.File("src/main/res/$d").listFiles()!!.filter { it.name.startsWith("strings") }.joinToString("") { it.readText() } }
        val page = listOf("en", "vi").map { java.io.File("../../ui/assets/i18n-$it.js").readText() }
        for ((id, _) in Features.TABLE) {
            for (r in res) for (k in listOf("ft_$id", "ft_${id}_d")) assertTrue("$k missing", r.contains("name=\"$k\""))
            for (p in page) assertTrue("page ft_$id missing", Regex("\"?ft_$id\"?:").containsMatchIn(p))
        }
        for (cap in Features.CAPS) for (r in res) assertTrue("feat_cap_$cap", r.contains("name=\"feat_cap_$cap\""))
    }
}
