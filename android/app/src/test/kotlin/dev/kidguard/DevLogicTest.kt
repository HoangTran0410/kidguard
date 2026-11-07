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
}
