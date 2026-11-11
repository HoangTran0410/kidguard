package dev.kidguard

import org.junit.Assert.assertEquals
import org.junit.Test

class DevLogicTest {
    @Test fun verdict() {
        assertEquals("not allowed", DevLogic.verdict(false, true, null))
        assertEquals("not allowed", DevLogic.verdict(false, false, "SecurityException"))
        assertEquals("ok", DevLogic.verdict(true, false, "SecurityException"))   // a fallback error is fine once it is off
        assertEquals("failed: SecurityException", DevLogic.verdict(true, true, "SecurityException"))
        assertEquals("failed: still on", DevLogic.verdict(true, true, null))
    }

    @org.junit.Test fun bankMaskRestoresOnlyWhatWasOn() {
        assertEquals(0, DevLogic.mask(false, false, false))
        assertEquals(3, DevLogic.mask(true, true, false))
        assertEquals(listOf("dev", "adb"), DevLogic.restoreKeys(3, true))
        assertEquals(listOf("dev", "adb", "wifi"), DevLogic.restoreKeys(7, true))
        assertEquals(listOf("dev", "adb"), DevLogic.restoreKeys(7, false))   // no wireless debugging before API 30
        assertEquals(listOf("dev"), DevLogic.restoreKeys(1, true))
    }
}
