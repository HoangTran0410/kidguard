package dev.kidguard.parent

import org.junit.Assert.*
import org.junit.Test

class UpdateTest {
    @Test fun tagAndOrder() {
        assertEquals("0.9.0", Logic.tagFromLocation("https://github.com/HoangTran0410/kidguard/releases/tag/v0.9.0"))
        assertNull(Logic.tagFromLocation("https://github.com/HoangTran0410/kidguard/releases"))
        assertNull(Logic.tagFromLocation("https://github.com/x/y/releases/tag/v1.0-rc1"))
        assertTrue(Logic.newer("0.10.0", "0.9.0"))
        assertFalse(Logic.newer("0.9.0", "0.9.0"))
        assertFalse(Logic.newer("0.8.9", "0.9.0"))
    }
}
