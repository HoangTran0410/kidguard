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

    /** "Sửa app" edits (name + icon) live in HomeModel.custom, so a backup must carry them and restore them. */
    @Test fun customNameAndIconSurvive() {
        val c = m.copy(custom = mapOf("a.b" to AppCustom(label = "Học", icon = AppLook.glyphSpec(AppLook.GLYPHS[0], AppLook.COLORS[2])),
            "c.d" to AppCustom(icon = AppLook.emojiSpec(AppLook.EMOJIS[0], AppLook.COLORS[5]))))
        val p = HomeBackup.parse(HomeBackup.export(c, null, null).first)!!
        assertEquals(c.custom, p.model.custom)
    }

    @Test fun bareHomeJson() { assertNotNull(HomeBackup.parse(m.toJson().toString())) }
}
