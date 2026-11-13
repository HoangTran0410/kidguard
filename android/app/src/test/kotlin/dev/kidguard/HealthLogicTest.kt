package dev.kidguard

import org.junit.Assert.*
import org.junit.Test

class HealthLogicTest {
    @Test fun cleanPhoneScores100() {
        val r = HealthLogic.evaluate(HealthLogic.In(devOpts = false, installsBlocked = true, patchDays = 30, freePct = 50, cacheMb = 10, sideloaded = 0, battery = 80, bgApps = 3, otherAdmins = 0))
        assertEquals(100, r.score); assertTrue(r.issues.isEmpty())
    }
    @Test fun unknownValuesAreSkipped() {
        val r = HealthLogic.evaluate(HealthLogic.In())
        assertEquals(100, r.score); assertEquals(listOf("cache"), r.checks.map { it.id })
    }
    @Test fun issuesCostTheirWeightAndFixableNeedsACapability() {
        val i = HealthLogic.In(devOpts = true, cacheMb = 600, bgApps = 25, canDevOff = true, canCache = true)
        val r = HealthLogic.evaluate(i)
        assertEquals(100 - 15 - 10 - 15, r.score)
        assertEquals(setOf("devOpts", "cache"), r.issues.filter { it.fixable }.map { it.id }.toSet())   // bg: no adb
        assertFalse(r.issues.first { it.id == "bg" }.fixable)
    }
    @Test fun scoreNeverBelowZero() {
        val r = HealthLogic.evaluate(HealthLogic.In(devOpts = true, installsBlocked = false, patchDays = 400, freePct = 2, cacheMb = 900, sideloaded = 9, bgApps = 40, otherAdmins = 3, battery = 5))
        assertTrue(r.score in 0..10)
    }
    @Test fun lowBatteryOkWhileCharging() = assertTrue(HealthLogic.evaluate(HealthLogic.In(battery = 5, charging = true)).checks.first { it.id == "battery" }.ok)
    @Test fun stores() { assertTrue(HealthLogic.isStore("com.android.vending")); assertFalse(HealthLogic.isStore(null)); assertFalse(HealthLogic.isStore("com.android.shell")) }
}
