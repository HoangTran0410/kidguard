package dev.kidguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SysLogicTest {
    @Test fun parseTypes() {
        assertEquals(SysLogic.Setting("vol.media", n = 7), SysLogic.parse("vol.media", 7))
        assertEquals(SysLogic.Setting("vol.ring", n = 3), SysLogic.parse("vol.ring", 3.6))   // truncated
        assertEquals(SysLogic.Setting("timeoutMs", n = 60_000), SysLogic.parse("timeoutMs", 60_000L))
        assertEquals(SysLogic.Setting("dnd", on = true), SysLogic.parse("dnd", true))
        assertEquals(SysLogic.Setting("autoBright", on = false), SysLogic.parse("autoBright", false))
        assertEquals(SysLogic.Setting("bright", n = 1_000_000_000), SysLogic.parse("bright", 1e30))   // huge: capped before toInt
    }

    @Test fun parseRejects() {
        assertNull(SysLogic.parse("vol.media", "7"))   // strings are not numbers
        assertNull(SysLogic.parse("vol.media", null))
        assertNull(SysLogic.parse("vol.media", Double.NaN))
        assertNull(SysLogic.parse("dnd", 1))
        assertNull(SysLogic.parse("bright", true))
        assertNull(SysLogic.parse("vol.call", 3))
        assertNull(SysLogic.parse("", 3))
        assertEquals("bad key", SysLogic.error("wifi"))
        assertEquals("bad value", SysLogic.error("dnd"))
    }

    @Test fun clamp() {
        fun c(k: String, n: Int, max: Int) = SysLogic.clamp(SysLogic.Setting(k, n = n), max)
        assertEquals(15, c("vol.media", 99, 15)); assertEquals(0, c("vol.alarm", -3, 7)); assertEquals(4, c("vol.ring", 4, 7))
        assertEquals(255, c("bright", 900, 255)); assertEquals(1, c("bright", 0, 255)); assertEquals(1, c("bright", 5, 0))
        assertEquals(15_000, c("timeoutMs", 1000, 0)); assertEquals(600_000, c("timeoutMs", 9_999_999, 0)); assertEquals(120_000, c("timeoutMs", 120_000, 0))
    }
}
