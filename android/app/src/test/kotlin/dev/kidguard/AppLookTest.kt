package dev.kidguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLookTest {
    @Test fun firstEmojiTakesOneGraphemeAndRejectsText() {
        assertEquals("🚀", AppLook.firstEmoji("🚀🎮"))
        assertEquals("👩🏽‍💻", AppLook.firstEmoji(" 👩🏽‍💻 "))      // skin tone + ZWJ = one grapheme, 7 chars
        assertEquals("❤️", AppLook.firstEmoji("❤️"))
        assertNull(AppLook.firstEmoji("abc")); assertNull(AppLook.firstEmoji("7")); assertNull(AppLook.firstEmoji(""))
        assertNotNull(AppLook.parseSpec(AppLook.emojiSpec("👩🏽‍💻", AppLook.COLORS[0])))
    }

    private fun model(custom: Map<String, AppCustom> = emptyMap(), style: HStyle = HStyle()) =
        HomeLayout.fresh("teen", (1..6).map { "com.app$it" }, emptyList()).copy(custom = custom, style = style)

    @Test fun customSurvivesRoundTrip() {
        val c = mapOf("com.app1" to AppCustom("Bài tập", AppLook.glyphSpec("calendar", AppLook.COLORS[3])), "com.app2" to AppCustom(icon = AppLook.emojiSpec("🚀", AppLook.COLORS[0])))
        val back = HomeLayout.parse(model(c).toJson().toString())!!
        assertEquals(c, back.custom)
    }

    @Test fun oldAndBrokenJsonIsTolerated() {
        val old = model().toJson().also { it.remove("custom") }
        assertTrue(HomeLayout.parse(old.toString())!!.custom.isEmpty())
        val bad = model().toJson().put("custom", org.json.JSONObject("""{"a":{"l":"  x  ","i":"zzz"},"b":"str","c":{"l":"","i":"g:nope:#112233"},"d":{"i":"g:phone:#12"},"e":{"i":"e:😀:#FF0000"}}"""))
        val c = HomeLayout.parse(bad.toString())!!.custom
        assertEquals(setOf("a", "e"), c.keys)
        assertEquals("x", c["a"]!!.label); assertEquals("", c["a"]!!.icon)
    }

    @Test fun emptyCustomIsNotWritten() {
        assertFalse(model().toJson().has("custom"))
        assertTrue(AppLook.with(mapOf("p" to AppCustom("x")), "p", AppCustom()).isEmpty())   // restore
    }

    @Test fun labelAndIconKeyUseOverride() {
        AppLook.set(mapOf("com.a" to AppCustom("Mine", AppLook.glyphSpec("car", AppLook.COLORS[1]))))
        try {
            assertEquals("Mine", AppLook.label("com.a", "X", "Real"))
            assertEquals("Real", AppLook.label("com.b", "X", "Real"))
            assertEquals("#g:car:#F76B15", AppLook.iconKey("com.a", ""))
            assertEquals("", AppLook.iconKey("com.b", ""))
            assertNull(AppLook.get("com.a", Shortcuts.PREFIX + "s1"))   // pinned shortcuts keep their own look
        } finally { AppLook.set(emptyMap()) }
    }

    @Test fun searchMatchesCustomAndRealLabel() {
        assertTrue(AppLook.matches("Bài tập", "Google Classroom", "bai"))
        assertTrue(AppLook.matches("Bài tập", "Google Classroom", "class"))
        assertFalse(AppLook.matches("Bài tập", "Google Classroom", "zalo"))
    }

    @Test fun specParsing() {
        val s = AppLook.parseSpec("g:phone:#0091FF")
        assertNotNull(s); assertTrue(s!!.glyph); assertEquals(0xFF0091FF.toInt(), s.color)
        assertNull(AppLook.parseSpec("g:phone")); assertNull(AppLook.parseSpec("x:phone:#0091FF")); assertNull(AppLook.parseSpec("e:a b:#0091FF"))
        assertNull(AppLook.parseSpec("g:phone:#GG91FF"))
    }

    @Test fun readingAidStyleFields() {
        val st = HomeLayout.parse(model(style = HStyle(face = "easy", speak = true, calm = true, solid = true)).toJson().toString())!!.style
        assertEquals("easy", st.face); assertTrue(st.speak && st.calm && st.solid)
        val old = HStyle.fromJson(org.json.JSONObject("""{"preset":"teen"}"""))
        assertFalse(old.speak || old.calm || old.solid)
        assertEquals("sys", HStyle.fromJson(org.json.JSONObject("""{"face":"comic"}""")).face)
    }

    @Test fun motionAndVoiceDecisions() {
        assertTrue(ReadAids.reduce(true, 1f)); assertTrue(ReadAids.reduce(false, 0f)); assertFalse(ReadAids.reduce(false, 1f))
        assertTrue(ReadAids.voiceOk(0)); assertTrue(ReadAids.voiceOk(2)); assertFalse(ReadAids.voiceOk(-1)); assertFalse(ReadAids.voiceOk(-2))
        assertTrue(ReadAids.spacing("easy") > 0f); assertEquals(0f, ReadAids.spacing("sys"), 0f)
    }

    /** A restored backup changes the overrides: the version moves so the launcher reads its labels again. */
    @Test fun versionMovesOnlyOnChange() {
        AppLook.set(emptyMap()); val v0 = AppLook.version
        AppLook.set(emptyMap()); assertEquals(v0, AppLook.version)
        AppLook.set(mapOf("a.b" to AppCustom(label = "X"))); assertTrue(AppLook.version > v0)
        AppLook.set(emptyMap())
    }
}
