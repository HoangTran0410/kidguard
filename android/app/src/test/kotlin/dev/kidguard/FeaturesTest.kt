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
    }
}
