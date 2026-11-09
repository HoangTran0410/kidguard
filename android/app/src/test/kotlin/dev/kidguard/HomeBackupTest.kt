package dev.kidguard

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class HomeBackupTest {
    private val m = HomeLayout.fresh("teen", listOf("a.b", "c.d"), listOf("e.f"), parent = false)

    @Test fun roundTrip() {
        val (text, inc) = HomeBackup.export(m, null, null)
        assertFalse(inc)
        val p = HomeBackup.parse(text)
        assertNotNull(p)
        assertEquals(m.pages.size, p!!.model.pages.size)
        assertEquals(m.cols, p.model.cols)
    }

    @Test fun rejects() {
        assertNull(HomeBackup.parse(null))
        assertNull(HomeBackup.parse("not json"))
        assertNull(HomeBackup.parse("{}"))
        assertNull(HomeBackup.parse(JSONObject().put("format", "other").put("version", 1).put("layout", m.toJson()).toString()))
        assertNull(HomeBackup.parse(JSONObject().put("format", HomeBackup.FORMAT).put("version", 99).put("layout", m.toJson()).toString()))
        assertNull(HomeBackup.parse(JSONObject().put("format", HomeBackup.FORMAT).put("version", 1).toString()))
        assertNull(HomeBackup.parse("x".repeat((HomeBackup.MAX_BYTES + 1).toInt())))
    }

    @Test fun bareHomeJson() { assertNotNull(HomeBackup.parse(m.toJson().toString())) }
}
