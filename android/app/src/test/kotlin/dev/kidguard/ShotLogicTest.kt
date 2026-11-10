package dev.kidguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShotLogicTest {
    private val now = 10_000_000L

    @Test fun thirdSixthWrongOnly() {
        assertEquals(listOf(3, 6, 9), (1..10).filter { ShotLogic.due(true, it, 0, now) })
    }

    @Test fun offOrTooSoon() {
        assertFalse(ShotLogic.due(false, 3, 0, now))
        assertFalse(ShotLogic.due(true, 6, now - 60_000, now))
        assertTrue(ShotLogic.due(true, 6, now - ShotLogic.GAP_MS, now))
    }

    @Test fun pickSize() {
        assertEquals(960 to 720, ShotLogic.pick(listOf(4000 to 3000, 1920 to 1080, 960 to 720, 640 to 480)))
        assertEquals(1280 to 720, ShotLogic.pick(listOf(4000 to 3000, 1280 to 720)))
        assertNull(ShotLogic.pick(emptyList()))
    }
}
