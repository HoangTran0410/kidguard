package dev.kidguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Edit mode: several grid items at once (remove, delete a page, group, move to a page). */
class HomeBulkTest {
    private fun app(id: String, x: Int, y: Int) = HItem(id, "app", x, y, pkg = "p.$id")
    private fun model(vararg pages: List<HItem>) = HomeModel(4, 5, pages.toList(), listOf(HomeLayout.PHONE, "", "", ""), HStyle())
    private fun ids(m: HomeModel) = m.pages.map { p -> p.map { it.id }.sorted() }

    @Test fun removeIdsTakesAppsFoldersAndWidgetsAndRemembersTheApps() {
        val f = HItem("f1", "folder", 2, 0, name = "Chơi", apps = listOf("p.x", "p.y"))
        val w = HItem("w1", "widget", 0, 1, 4, 1, type = "clock")
        val m = model(listOf(app("a", 0, 0), app("b", 1, 0), f, w))
        val out = HomeLayout.removeIds(m, setOf("a", "f1", "w1"))
        assertEquals(listOf(listOf("b")), ids(out))
        assertEquals(setOf("p.a", "p.x", "p.y"), out.seen)   // widgets add nothing to seen
    }

    @Test fun removeIdsDropsAPageItEmpties() {
        val m = model(listOf(app("a", 0, 0)), listOf(app("b", 0, 0)))
        assertEquals(listOf(listOf("a")), ids(HomeLayout.removeIds(m, setOf("b"))))
    }

    @Test fun removePageKeepsTheOthersAndNeverLeavesNoPage() {
        val m = model(listOf(app("a", 0, 0)), listOf(app("b", 0, 0), app("c", 1, 0)), listOf(app("d", 0, 0)))
        val out = HomeLayout.removePage(m, 1)
        assertEquals(listOf(listOf("a"), listOf("d")), ids(out))
        assertEquals(setOf("p.b", "p.c"), out.seen)
        val one = HomeLayout.removePage(model(listOf(app("a", 0, 0))), 0)
        assertEquals(listOf(emptyList<String>()), ids(one))
        assertTrue(HomeLayout.removePage(m, 7) === m)
    }

    @Test fun groupMakesOneFolderInTheFirstCellInReadingOrder() {
        val m = model(listOf(app("a", 0, 0), app("b", 3, 0)), listOf(app("c", 1, 2)))
        val out = HomeLayout.group(m, listOf("c", "b"), "Nhóm")!!
        val f = out.pages[0].single { it.kind == "folder" }
        assertEquals(3 to 0, f.x to f.y)
        assertEquals(listOf("p.b", "p.c"), f.apps)
        assertEquals("Nhóm", f.name)
        assertEquals(1, out.pages.size)   // page 2 emptied and dropped
    }

    @Test fun groupIntoAPickedFolderKeepsItsNameAndSkipsWidgets() {
        val f = HItem("f1", "folder", 0, 0, name = "Học", apps = listOf("p.x", "p.y"))
        val w = HItem("w1", "widget", 0, 2, 2, 2, type = "note")
        val m = model(listOf(f, app("a", 1, 0), w))
        val out = HomeLayout.group(m, listOf("a", "f1", "w1"), "Mới")!!
        val g = out.pages[0].single { it.kind == "folder" }
        assertEquals("Học", g.name); assertEquals(listOf("p.x", "p.y", "p.a"), g.apps)
        assertTrue(out.pages[0].any { it.id == "w1" })
    }

    @Test fun groupNeedsTwoApps() {
        val w = HItem("w1", "widget", 0, 2, 2, 2, type = "note")
        assertNull(HomeLayout.group(model(listOf(app("a", 0, 0), w)), listOf("a", "w1"), "x"))
    }

    @Test fun moveToFillsFreeCellsAndANewPage() {
        val m = model(listOf(app("a", 0, 0), app("b", 1, 0)), listOf(app("c", 0, 0)))
        val (out, n) = HomeLayout.moveTo(m, listOf("a", "b"), 1)
        assertEquals(2, n)
        assertEquals(listOf(listOf("a", "b", "c")), ids(out))   // page 1 emptied and dropped
        val (nw, k) = HomeLayout.moveTo(m, listOf("c"), 2)
        assertEquals(1, k); assertEquals(listOf(listOf("a", "b"), listOf("c")), ids(nw))
    }

    @Test fun moveToLeavesWhatDoesNotFit() {
        val full = (0 until 20).map { app("f$it", it % 4, it / 4) }
        val m = model(listOf(app("a", 0, 0)), full)
        val (out, n) = HomeLayout.moveTo(m, listOf("a"), 1)
        assertEquals(0, n); assertTrue(out === m)
    }
}
