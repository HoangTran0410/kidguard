package dev.kidguard

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryLogicTest {
    private fun a(p: String, cat: Int = -1, sys: Boolean = false) = LibraryLogic.App(p, p, cat, sys)

    @Test fun groupsInOrder() {
        val apps = listOf(a("zalo", LibraryLogic.SOCIAL), a("fb", LibraryLogic.SOCIAL), a("pubg", LibraryLogic.GAME),
            a("clock", sys = true), a("bank"), a("yt", LibraryLogic.VIDEO), a("cam", LibraryLogic.IMAGE, sys = true))
        val g = LibraryLogic.groups(apps, listOf("yt", "gone", "zalo", "yt"), mapOf("bank" to 3L, "pubg" to 5L, "clock" to 9L))
        assertEquals(listOf("sug", "new", "social", "game", "photo", "system", "other"), g.map { it.first })
        assertEquals(listOf("yt", "zalo"), g[0].second)          // unknown + duplicate recent dropped
        assertEquals(listOf("pubg", "bank"), g[1].second)        // newest first, preinstalled left out
        assertEquals(listOf("fb", "zalo"), g[2].second)          // A-Z inside a category
        assertEquals(listOf("cam", "yt"), g[4].second)           // image + video together
    }

    @Test fun emptyGroupsDropped() {
        assertEquals(listOf("other"), LibraryLogic.groups(listOf(a("x")), emptyList(), emptyMap()).map { it.first })
    }
}
