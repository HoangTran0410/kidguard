package dev.kidguard

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import dev.kidguard.HomeLayout.Src

/** Part 2 edit ops: drag and drop, folders, widgets, regrid; KidNotes; LauncherLogic.today. */
class HomeEditTest {
    private val apps = (1..30).map { "com.app$it" }

    private fun noOverlap(m: HomeModel) {
        for (p in m.pages) for (y in 0 until m.rows) for (x in 0 until m.cols) assertTrue("cell $x,$y", p.count { it.covers(x, y) } <= 1)
        for (p in m.pages) for (i in p) assertTrue(i.x >= 0 && i.y >= 0 && i.x + i.w <= m.cols && i.y + i.h <= m.rows)
    }
    private fun all(m: HomeModel) = m.pages.flatten().flatMap { if (it.kind == "folder") it.apps else listOf(it.pkg) }.filter { it.isNotEmpty() }
    private fun app(m: HomeModel, pkg: String) = m.pages.flatten().first { it.kind == "app" && it.pkg == pkg }
    private val base = HomeLayout.fresh("teen", apps.take(6), listOf("com.sms", "com.cam", "com.web"))

    @Test fun moveToEmptyCellAndRefuseOccupied() {
        val a1 = app(base, "com.app1")
        val t = HomeLayout.take(base, Src.Item(a1.id))!!
        val m = HomeLayout.dropCell(t, 0, 3, 4)!!
        assertEquals(3 to 4, app(m, "com.app1").let { it.x to it.y })
        noOverlap(m)
        val a2 = app(base, "com.app2")
        assertNull(HomeLayout.dropCell(HomeLayout.take(base, Src.Item(a1.id))!!, 0, a2.x, a2.y))
        // a new page at the edge
        val m2 = HomeLayout.dropCell(HomeLayout.take(base, Src.Item(a1.id))!!, base.pages.size, 0, 0)!!
        assertEquals(base.pages.size + 1, m2.pages.size)
        assertEquals(all(base).sorted(), all(m2).sorted())
    }

    @Test fun appOnAppMakesFolderAndLastAppOutDissolvesIt() {
        val a1 = app(base, "com.app1"); val a2 = app(base, "com.app2")
        val m = HomeLayout.dropOnto(HomeLayout.take(base, Src.Item(a1.id))!!, a2.id, "Trò chơi")!!
        val f = m.pages.flatten().single { it.kind == "folder" }
        assertEquals(listOf("com.app2", "com.app1"), f.apps); assertEquals(a2.x to a2.y, f.x to f.y); assertEquals("Trò chơi", f.name)
        assertEquals(all(base).sorted(), all(m).sorted()); noOverlap(m)
        // add a third, take two out: the folder turns back into the last app in its cell
        val a3 = app(m, "com.app3")
        val m3 = HomeLayout.dropOnto(HomeLayout.take(m, Src.Item(a3.id))!!, f.id, "x")!!
        assertEquals(3, m3.pages.flatten().single { it.kind == "folder" }.apps.size)
        val t = HomeLayout.take(m3, Src.InFolder(f.id, "com.app1"))!!
        val back = HomeLayout.dropCell(t, 0, 3, 4)!!
        val t2 = HomeLayout.take(back, Src.InFolder(f.id, "com.app3"))!!
        assertFalse(t2.m.pages.flatten().any { it.kind == "folder" })
        assertEquals(f.x to f.y, app(t2.m, "com.app2").let { it.x to it.y })
        // no folder in a folder, no widget in a folder, no app on itself
        assertNull(HomeLayout.dropOnto(HomeLayout.take(m, Src.Item(f.id))!!, a3.id, "x"))
        val w = base.pages[0].first { it.kind == "widget" }
        assertNull(HomeLayout.dropOnto(HomeLayout.take(base, Src.Item(w.id))!!, a2.id, "x"))
        assertNull(HomeLayout.dropOnto(HomeLayout.Taken(base, a2), a2.id, "x"))
    }

    @Test fun oneAppFoldersAreRepairedOnLoadAndPrune() {
        val a1 = app(base, "com.app1"); val a2 = app(base, "com.app2")
        val m = HomeLayout.dropOnto(HomeLayout.take(base, Src.Item(a1.id))!!, a2.id, "F")!!
        val pruned = HomeLayout.prune(m) { it == "com.app1" }
        assertEquals("app", pruned.pages.flatten().first { it.pkg == "com.app2" }.kind)
        val parsed = HomeLayout.parse(pruned.toJson().toString())!!
        assertFalse(parsed.pages.flatten().any { it.kind == "folder" })
    }

    @Test fun dockSwapsWithWhereTheDragCameFrom() {
        val a1 = app(base, "com.app1")
        val m = HomeLayout.dropDock(HomeLayout.take(base, Src.Item(a1.id))!!, 2)!!
        assertEquals("com.app1", m.dock[2]); assertEquals(a1.x to a1.y, app(m, "com.cam").let { it.x to it.y })
        // dock to dock swaps; slot 0 (phone) is never a target
        val m2 = HomeLayout.dropDock(HomeLayout.take(m, Src.Dock(1))!!, 3)!!
        assertEquals(listOf(HomeLayout.PHONE, "com.web", "com.app1", "com.sms"), m2.dock)
        assertNull(HomeLayout.dropDock(HomeLayout.take(m, Src.Dock(1))!!, 0))
        assertNull(HomeLayout.take(m, Src.Dock(0)))
    }

    @Test fun removeKeepsAppOffTheHome() {
        val a1 = app(base, "com.app1")
        val m = HomeLayout.dropRemove(HomeLayout.take(base, Src.Item(a1.id))!!)
        assertFalse("com.app1" in m.onHome()); assertTrue("com.app1" in m.seen)
        assertEquals(m.pages, HomeLayout.appendNew(m, listOf("com.app1")).pages)
    }

    @Test fun addToFolderFromMenu() {
        val a2 = app(base, "com.app2")
        val m = HomeLayout.addTo(base, "com.app5", a2.id, "F")!!
        assertEquals(listOf("com.app2", "com.app5"), m.pages.flatten().single { it.kind == "folder" }.apps)
        assertEquals(1, all(m).count { it == "com.app5" })
        // from the dock too
        val m2 = HomeLayout.addTo(base, "com.sms", a2.id, "F")!!
        assertEquals("", m2.dock[1]); assertTrue("com.sms" in m2.pages.flatten().single { it.kind == "folder" }.apps)
    }

    @Test fun renameTrimsAndIgnoresEmpty() {
        val a1 = app(base, "com.app1"); val a2 = app(base, "com.app2")
        val m = HomeLayout.dropOnto(HomeLayout.take(base, Src.Item(a1.id))!!, a2.id, "F")!!
        val id = m.pages.flatten().single { it.kind == "folder" }.id
        assertEquals("Học", HomeLayout.rename(m, id, "  Học  ").pages.flatten().single { it.id == id }.name)
        assertEquals("F", HomeLayout.rename(m, id, "   ").pages.flatten().single { it.id == id }.name)
    }

    @Test fun freshWithoutParentHasOnlyTheClock() {
        val m = HomeLayout.fresh("teen", apps.take(6), emptyList(), parent = false)
        val w = m.pages[0].filter { it.kind == "widget" }
        assertEquals(listOf("clock"), w.map { it.type }); assertEquals(2, w[0].h)
        assertEquals(6, m.pages.flatten().count { it.kind == "app" }); noOverlap(m)
    }

    @Test fun widgetsPlaceOnceResizeAndShrinkToGrid() {
        val empty = HomeLayout.fresh("teen", emptyList(), emptyList())
        val noTime = HomeLayout.dropRemove(HomeLayout.take(empty, Src.Item(empty.pages[0].first { it.type == "time" }.id))!!)
        assertFalse(HomeLayout.hasWidget(noTime, "time"))
        val m = HomeLayout.placeWidget(noTime, "counts", 2, 2, 0)!!
        assertTrue(HomeLayout.hasWidget(m, "counts")); noOverlap(m)
        assertNull(HomeLayout.placeWidget(m, "nope", 1, 1, 0))
        val c = m.pages.flatten().first { it.type == "clock" }
        val r = HomeLayout.resizeWidget(m, c.id, 4, 2)!!
        assertEquals(2, r.pages.flatten().first { it.id == c.id }.h); noOverlap(r)
        // grows in place on a busy page: same page, same cell, what it covers moves down / on, nothing lost
        val busy = HomeLayout.fresh("teen", apps, emptyList())
        val (bp, bc) = HomeLayout.find(busy, busy.pages.flatten().first { it.type == "clock" }.id)!!
        val g = HomeLayout.resizeWidget(busy, bc.id, 4, 2)!!
        val (gp, gc) = HomeLayout.find(g, bc.id)!!
        assertEquals(bp, gp); assertEquals(bc.x to bc.y, gc.x to gc.y); assertEquals(2, gc.h)
        noOverlap(g); assertEquals(all(busy).sorted(), all(g).sorted())
        assertEquals(busy.pages.flatten().size, g.pages.flatten().size)
        // full page => next page
        val full = HomeLayout.fresh("teen", apps.take(17), emptyList())
        val f2 = HomeLayout.placeWidget(full, "counts", 2, 2, 0)!!
        noOverlap(f2); assertTrue(f2.pages.size >= full.pages.size)
        // a 4-wide widget on a 3-column grid is 3 wide
        val be = HomeLayout.fresh("be", emptyList(), emptyList())
        assertEquals(3, HomeLayout.take(be, Src.NewWidget("next", 4, 1))!!.item.w)
    }

    @Test fun regridSmallerReflowsWithoutLosingAnything() {
        val m = HomeLayout.fresh("gon", apps, emptyList())
        val small = HomeLayout.regrid(m, 3, 4)
        assertEquals(3, small.cols); assertEquals(4, small.rows)
        noOverlap(small)
        assertEquals(all(m).sorted(), all(small).sorted())
        assertEquals(m.pages.flatten().count { it.kind == "widget" }, small.pages.flatten().count { it.kind == "widget" })
        val big = HomeLayout.regrid(small, 5, 6)
        assertEquals(small.pages, big.pages)
    }

    @Test fun folderKind() {
        assertEquals("games", HomeLayout.folderKind(0, 0)); assertEquals("", HomeLayout.folderKind(0, 4)); assertEquals("fun", HomeLayout.folderKind(1, 2))
    }

    // ---- KidNotes ----
    @Test fun notesShareOnlyWhatTheKidChose() {
        var l = KidNotes.putNote(emptyList(), null, "  bí mật  ", false, 1)
        l = KidNotes.putNote(l, null, "mua bút", true, 2)
        assertEquals(listOf("mua bút", "bí mật"), l.map { it.text })
        val st = KidNotes.stateJson(KidNotes.notesJson(l))
        assertEquals(1, st.length()); assertEquals("mua bút", st.getJSONObject(0).getString("text")); assertFalse(st.getJSONObject(0).has("share"))
        val id = l.first { it.text == "bí mật" }.id
        assertEquals(1, KidNotes.putNote(l, id, " ", false, 3).size)
        assertEquals(500, KidNotes.putNote(emptyList(), null, "x".repeat(900), false, 1)[0].text.length)
        assertEquals(l, KidNotes.putNote(l, "missing", "y", true, 4))
        assertEquals(2, KidNotes.notes(JSONArray("[{\"id\":\"a\",\"text\":\"x\"},{\"id\":\"a\",\"text\":\"y\"},{\"id\":\"\",\"text\":\"z\"},{\"id\":\"b\",\"text\":\"\"},{\"id\":\"c\",\"text\":\"ok\"}]")).size)
    }

    @Test fun homework() {
        var l = KidNotes.addTodo(emptyList(), "Toán trang 12")
        l = KidNotes.addTodo(l, "   ")
        assertEquals(1, l.size)
        l = KidNotes.toggle(l, l[0].id); assertTrue(l[0].done)
        assertTrue(KidNotes.dropTodo(l, l[0].id).isEmpty())
        val back = KidNotes.todos(KidNotes.todosJson(l)); assertEquals(l, back)
    }

    // ---- today ----
    @Test fun todayTimeline() {
        val r = Rules(
            schedules = listOf(Schedule("Học", true, setOf(1, 2, 3, 4, 5), "08:00", "11:00")),
            groups = listOf(AppGroup("g", "Game", emptySet(), 0, listOf(Schedule("", true, (1..7).toSet(), "17:00", "18:00")))),
            bedtimeOn = true, bedStart = "21:30", bedEnd = "06:30")
        val l = LauncherLogic.today(r, 9 * 60, 1)
        assertEquals(listOf("bed", "schedule", "group", "bed"), l.map { it.kind })
        assertEquals(listOf("past", "now", "next", "later"), l.map { it.state })
        assertEquals(0, l[0].start); assertEquals(6 * 60 + 30, l[0].end)
        assertEquals(6 * 60 + 30, l[3].end)
        // weekend: no school
        assertEquals(listOf("bed", "group", "bed"), LauncherLogic.today(r, 9 * 60, 6).map { it.kind })
    }
}
