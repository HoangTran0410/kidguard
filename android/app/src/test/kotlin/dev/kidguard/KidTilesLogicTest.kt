package dev.kidguard

import org.junit.Assert.assertEquals
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
    @Test fun roundTrip() = assertEquals(listOf("usage", "lock"), KidTilesLogic.parse(KidTilesLogic.join(listOf("usage", "lock"))))
}
