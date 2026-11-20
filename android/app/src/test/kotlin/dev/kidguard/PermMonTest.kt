package dev.kidguard

import org.junit.Assert.*
import org.junit.Test

class PermMonTest {
    @Test fun colorOsFromPropsOrMaker() {
        assertTrue(PermMonLogic.colorOs("OPPO", "OPPO", "", ""))
        assertTrue(PermMonLogic.colorOs("realme", "realme", "", ""))
        assertTrue(PermMonLogic.colorOs("OnePlus", "OnePlus", "", ""))
        assertTrue(PermMonLogic.colorOs("Foo", "Bar", "V5.2.1", ""))   // test Oppo: ro.build.version.opporom
        assertTrue(PermMonLogic.colorOs("Foo", "Bar", "", "V13.0"))
        assertFalse(PermMonLogic.colorOs("Xiaomi", "POCO", "", ""))
        assertFalse(PermMonLogic.colorOs("samsung", "samsung", " ", ""))
    }

    @Test fun rowTextInEveryLanguage() {
        assertTrue(PermMonLogic.isRow("Tắt giám sát quyền"))
        assertTrue(PermMonLogic.isRow("TẮT GIÁM SÁT QUYỀN"))
        assertTrue(PermMonLogic.isRow("Tat giam sat quyen"))
        assertTrue(PermMonLogic.isRow("Disable permission monitoring"))
        assertTrue(PermMonLogic.isRow("Disable  Permission Monitor"))
        assertTrue(PermMonLogic.isRow("禁止权限监控"))
        assertTrue(PermMonLogic.isRow("关闭权限监控"))
        assertFalse(PermMonLogic.isRow("USB debugging"))
        assertFalse(PermMonLogic.isRow("Gỡ lỗi USB"))
        assertFalse(PermMonLogic.isRow("Quyền ứng dụng"))
    }

    @Test fun probeResult() {
        // owner 2026-11-20, new Oppo, Wireless debugging self setup
        val blocked = "Exception occurred while executing 'set':\njava.lang.SecurityException: uid 2000 does not have android.permission.MANAGE_APP_OPS_MODES."
        assertEquals(PermMonLogic.Probe.BLOCKED, PermMonLogic.probe(blocked))
        assertEquals(PermMonLogic.Probe.BLOCKED, PermMonLogic.probe("Error: java.lang.SecurityException: ... UPDATE_APP_OPS_STATS"))
        assertEquals(PermMonLogic.Probe.OK, PermMonLogic.probe(""))
        assertEquals(PermMonLogic.Probe.OTHER, PermMonLogic.probe("Error: Unknown operation string: GET_USAGE_STATS"))
    }

    @Test fun hintIsOnlyTrueFalseOrUnknown() {
        assertEquals(true, PermMonLogic.hintOn("true"))
        assertEquals(false, PermMonLogic.hintOn("false\n"))
        assertNull(PermMonLogic.hintOn(""))
    }

    @Test fun outlineNeverFullScreen() {
        assertTrue(PermMonLogic.sane(0, 1800, 1080, 1960, 1080, 2400))
        assertFalse(PermMonLogic.sane(0, 0, 1080, 2400, 1080, 2400))   // whole screen
        assertFalse(PermMonLogic.sane(0, 100, 1080, 100, 1080, 2400))  // empty
        assertFalse(PermMonLogic.sane(0, 2300, 1080, 2600, 1080, 2400)) // off the bottom
    }
}
