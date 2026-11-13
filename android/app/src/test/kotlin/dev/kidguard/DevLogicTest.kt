package dev.kidguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test fun tcpOpen() {
        assertEquals(true, DevLogic.tcpOpen("5555", false))
        assertEquals(false, DevLogic.tcpOpen("0", true))    // after `adb usb`
        assertEquals(false, DevLogic.tcpOpen("-1", true))
        assertEquals(true, DevLogic.tcpOpen(null, true))    // property unreadable: our own rule decides
        assertEquals(false, DevLogic.tcpOpen(null, false))
    }

    @Test fun tcpPortClosesAfterTwoHours() {
        assertFalse(DevLogic.tcpExpired(0, 1_000))
        assertFalse(DevLogic.tcpExpired(5_000, 4_999))
        assertTrue(DevLogic.tcpExpired(5_000, 5_000))
        assertEquals(1_000 + DevLogic.TCP_MAX_MS, DevLogic.tcpDeadline(0, 1_000))   // left on by an older version: clock starts now
        assertEquals(7_000L, DevLogic.tcpDeadline(7_000, 1_000))
    }
}
