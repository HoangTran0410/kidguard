package dev.kidguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanNameTest {
    @Test fun nameIsStableAndHidesTheId() {
        val n = LanName.of("kid-123")
        assertEquals(n, LanName.of("kid-123"))
        assertEquals(15, n.length)   // "kg-" + 12 hex
        assertFalse(n.contains("123"))
    }

    @Test fun matchesRenamedServices() {
        val n = LanName.of("abc")
        assertTrue(LanName.matches(n, "abc"))
        assertTrue(LanName.matches("$n (2)", "abc"))
        assertFalse(LanName.matches(n, "abd"))
        assertFalse(LanName.matches(n, ""))
    }
}
