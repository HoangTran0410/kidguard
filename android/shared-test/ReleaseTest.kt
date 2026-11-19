package dev.kidguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReleaseTest {
    private val h = "ab".repeat(32)
    private val m = """{"version": "0.9.49", "code": 58, "files": {"kidguard.apk": "${h.uppercase()}", "kidguard-parent.apk": "${"cd".repeat(32)}"}}"""

    @Test fun shaInPicksThatFile() {
        assertEquals(h, Release.shaIn(m, "kidguard.apk"))
        assertEquals("cd".repeat(32), Release.shaIn(m, "kidguard-parent.apk"))
        assertNull(Release.shaIn(m, "kidguard-home.apk"))
        assertNull(Release.shaIn("<html>not found</html>", "kidguard.apk"))
        assertNull(Release.shaIn("""{"files": {"kidguard.apk": "abc"}}""", "kidguard.apk"))
    }
}
