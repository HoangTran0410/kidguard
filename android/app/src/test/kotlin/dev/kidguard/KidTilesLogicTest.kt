package dev.kidguard

import org.junit.Assert.*
import org.junit.Test

class KidTilesLogicTest {
    @Test fun defaultWhenNeverSaved() = assertEquals(KidTilesLogic.DEFAULT, KidTilesLogic.parse(null))
    @Test fun emptySavedMeansNoTiles() = assertEquals(emptyList<String>(), KidTilesLogic.parse(""))
    @Test fun parseDropsUnknownAndDuplicates() = assertEquals(listOf("lock", "usage"), KidTilesLogic.parse("lock,bogus,usage,lock"))
    @Test fun addCapsAtMax() {
        val full = KidTilesLogic.ALL.take(KidTilesLogic.MAX)
        assertEquals(full, KidTilesLogic.add(full, KidTilesLogic.ALL.last()))
        assertEquals(listOf("lock", "usage"), KidTilesLogic.add(listOf("lock"), "usage"))
        assertEquals(listOf("lock"), KidTilesLogic.add(listOf("lock"), "sos"))
    }
    @Test fun rowsKeepSosFirstAndEditLast() {
        assertEquals(listOf(listOf("sos", "arrived", "pickup", "lock"), listOf("edit")), KidTilesLogic.rows(KidTilesLogic.DEFAULT))
        assertEquals(listOf(listOf("sos", "edit")), KidTilesLogic.rows(emptyList()))
        assertEquals(2, KidTilesLogic.rows(KidTilesLogic.ALL.take(KidTilesLogic.MAX)).size)
    }
    @Test fun cleanTileOnlyWhenAllowed() {
        assertEquals(listOf("lock"), KidTilesLogic.parse("clean,lock"))
        assertEquals(listOf("clean", "lock"), KidTilesLogic.parse("clean,lock", clean = true))
        assertEquals(listOf("lock"), KidTilesLogic.add(listOf("lock"), "clean"))
        assertEquals(listOf("lock", "clean"), KidTilesLogic.add(listOf("lock"), "clean", clean = true))
        assertFalse("clean" in KidTilesLogic.addable(emptyList())); assertTrue("clean" in KidTilesLogic.addable(emptyList(), true))
    }
    @Test fun cleanOncePerTenMinutes() {
        assertEquals(0, KidTilesLogic.cleanWaitMin(0, 1_000)); assertEquals(10, KidTilesLogic.cleanWaitMin(1_000, 1_000))
        assertEquals(1, KidTilesLogic.cleanWaitMin(0 + 1, 1 + 9 * 60_000 + 30_000)); assertEquals(0, KidTilesLogic.cleanWaitMin(1_000, 1_000 + 600_000))
        assertEquals("340 MB", KidTilesLogic.mbText(340)); assertEquals("1.5 GB", KidTilesLogic.mbText(1536))
    }
    @Test fun roundTrip() = assertEquals(listOf("usage", "lock"), KidTilesLogic.parse(KidTilesLogic.join(listOf("usage", "lock"))))
}
