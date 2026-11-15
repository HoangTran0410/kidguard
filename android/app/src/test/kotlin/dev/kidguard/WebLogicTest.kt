package dev.kidguard

import org.json.JSONArray
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebLogicTest {
    @Test fun host() {
        assertEquals("m.youtube.com", WebLogic.host("https://M.YouTube.com/watch?v=1"))
        assertEquals("x.com", WebLogic.host("www.x.com"))
        assertEquals("tiktok.com", WebLogic.host("*.tiktok.com"))
        assertEquals("a.b.vn", WebLogic.host("http://user@a.b.vn:8080/p#h"))
        assertEquals("*", WebLogic.host(" * "))
        assertNull(WebLogic.host("hello"))
        assertNull(WebLogic.host("a b.com"))
        assertNull(WebLogic.host(""))
    }

    @Test fun list() {
        assertEquals(listOf("x.com", "y.vn"), WebLogic.list(JSONArray(listOf("x.com", "www.x.com", "junk", "Y.vn"))))
        assertEquals(emptyList<String>(), WebLogic.list(null))
    }

    @Test fun hostInText() {
        assertEquals("youtube.com", WebLogic.hostInText("Xem nè https://www.youtube.com/watch?v=abc hay lắm"))
        assertEquals("roblox.com", WebLogic.hostInText("roblox.com"))
        assertNull(WebLogic.hostInText("chỉ có chữ thôi"))
        assertNull(WebLogic.hostInText("mail me a@b.com"))
    }

    @Test fun covered() {
        assertTrue(WebLogic.covered("m.youtube.com", listOf("youtube.com")))
        assertTrue(WebLogic.covered("x.com", listOf("*")))
        assertFalse(WebLogic.covered("notyoutube.com", listOf("youtube.com")))
    }

    @Test fun config() {
        assertTrue(WebLogic.config(false, false, emptyList(), listOf("a.com")).isEmpty())
        val c = WebLogic.config(true, true, listOf("*"), listOf("a.com"))
        assertEquals("off", c["DnsOverHttpsMode"]); assertEquals("1", c["IncognitoModeAvailability"])
        assertArrayEquals(arrayOf("*"), c["URLBlocklist"] as Array<*>)
        assertArrayEquals(arrayOf("a.com"), c["URLAllowlist"] as Array<*>)
    }
}
