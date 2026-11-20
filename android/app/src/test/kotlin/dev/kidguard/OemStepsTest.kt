package dev.kidguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OemStepsTest {
    private fun ids(e: OemSteps.Env) = OemSteps.forEnv(e).map { it.id }

    @Test fun xiaomiNeedsUsbAutostartBattery() {
        val e = OemSteps.Env("Xiaomi", "POCO", 34, miui = true)
        assertEquals(listOf("xi_usb", "autostart", "battery", "xi_space"), ids(e))
        assertEquals("xiaomi", OemSteps.family(e))
    }

    @Test fun samsungAutoBlockerOnlyFromOneUi61() {
        assertTrue("sm_blocker" in ids(OemSteps.Env("samsung", "samsung", 34, oneUi = 60100)))
        assertTrue("sm_blocker" !in ids(OemSteps.Env("samsung", "samsung", 33, oneUi = 50100)))
    }

    @Test fun restrictedSettingsOnlySideloadedAndroid13() {
        assertEquals("restricted", ids(OemSteps.Env("Google", "google", 33, fromStore = false)).first())
        assertEquals(emptyList<String>(), ids(OemSteps.Env("Google", "google", 33, fromStore = true)))
        assertEquals(emptyList<String>(), ids(OemSteps.Env("Google", "google", 32, fromStore = false)))
    }

    @Test fun privateSpaceNoteOnAndroid15() {
        assertEquals(listOf("private_space"), ids(OemSteps.Env("Google", "google", 35)))
    }

    @Test fun brandFamilies() {
        assertEquals("oppo", OemSteps.family(OemSteps.Env("realme", "realme", 33)))
        assertEquals("oppo", OemSteps.family(OemSteps.Env("OnePlus", "OnePlus", 33)))
        assertEquals("vivo", OemSteps.family(OemSteps.Env("vivo", "iQOO", 33)))
        assertEquals("huawei", OemSteps.family(OemSteps.Env("HONOR", "HONOR", 33)))
        assertEquals("transsion", OemSteps.family(OemSteps.Env("TECNO", "TECNO", 33)))
        assertEquals("", OemSteps.family(OemSteps.Env("Google", "google", 33)))
    }

    @Test fun everyStepOpensSomethingOrIsANote() {
        for (m in listOf("xiaomi", "samsung", "oppo", "vivo", "huawei", "tecno"))
            for (s in OemSteps.forEnv(OemSteps.Env(m, m, 35, oneUi = 60100, fromStore = false)))
                if (s.check != OemSteps.Check.NONE) assertTrue(s.id, s.go.isNotEmpty())
    }

    @Test fun pendingListsOnlyCheckedAndFailed() {
        val steps = OemSteps.forEnv(OemSteps.Env("xiaomi", "xiaomi", 34, miui = true))
        assertEquals(listOf("battery"), OemSteps.pending(steps) { false })
        assertEquals(emptyList<String>(), OemSteps.pending(steps) { null })
        assertEquals(emptyList<String>(), OemSteps.pending(steps) { true })
    }
}
