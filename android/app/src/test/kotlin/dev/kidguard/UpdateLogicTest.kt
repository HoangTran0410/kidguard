package dev.kidguard

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UpdateLogicTest {
    @Test fun tagFromRedirect() {
        assertEquals("0.9.0", UpdateLogic.tagFromLocation("https://github.com/HoangTran0410/kidguard/releases/tag/v0.9.0"))
        assertEquals("1.2.3.4", UpdateLogic.tagFromLocation("https://github.com/x/y/releases/tag/1.2.3.4"))
        assertNull(UpdateLogic.tagFromLocation("https://github.com/x/y/releases"))   // no release yet
        assertNull(UpdateLogic.tagFromLocation("https://github.com/x/y/releases/tag/v1.0-beta"))
        assertNull(UpdateLogic.tagFromLocation("https://github.com/x/y/releases/tag/../../evil"))
        assertNull(UpdateLogic.tagFromLocation(null))
    }

    @Test fun versionOrder() {
        assertTrue(UpdateLogic.newer("0.9.0", "0.8.1"))
        assertTrue(UpdateLogic.newer("0.10.0", "0.9.9"))
        assertTrue(UpdateLogic.newer("1.0", "0.99.99"))
        assertTrue(UpdateLogic.newer("0.8.0.1", "0.8.0"))
        assertFalse(UpdateLogic.newer("0.8.0", "0.8.0"))
        assertFalse(UpdateLogic.newer("0.8", "0.8.0"))
        assertFalse(UpdateLogic.newer("0.7.9", "0.8.0"))
        assertFalse(UpdateLogic.newer("", "0.8.0"))
    }

    @Test fun apkUrlUsesTag() {
        assertEquals("https://github.com/HoangTran0410/kidguard/releases/download/v0.9.0/kidguard.apk.sha256",
            UpdateLogic.apkUrl("0.9.0", "kidguard.apk.sha256"))
    }

    @Test fun autoUpdateRuleDefaultsOnAndRoundTrips() {
        assertTrue(Rules.fromJson(JSONObject()).autoUpdate)
        val off = Rules.fromJson(Rules(autoUpdate = false).toJson())
        assertFalse(off.autoUpdate)
        assertTrue(Events.ruleDiff(Rules(), off).contains("tắt tự cập nhật KidGuard"))
    }
}
