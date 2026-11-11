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

    @Test fun removeTakesAnAppOutOfItsFolder() {
        val a1 = app(base, "com.app1"); val a2 = app(base, "com.app2"); val a3 = app(base, "com.app3")
        val m1 = HomeLayout.dropOnto(HomeLayout.take(base, Src.Item(a1.id))!!, a2.id, "F")!!
        val f = m1.pages.flatten().single { it.kind == "folder" }
        val m2 = HomeLayout.dropOnto(HomeLayout.take(m1, Src.Item(a3.id))!!, f.id, "F")!!
        val r = HomeLayout.remove(m2, "com.app1")
        assertFalse("com.app1" in r.onHome()); assertTrue("com.app1" in r.seen)
        assertEquals(2, r.pages.flatten().single { it.kind == "folder" }.apps.size)
        val r2 = HomeLayout.remove(r, "com.app2")   // 1 app left: the folder is that app again
        assertFalse(r2.pages.flatten().any { it.kind == "folder" })
        assertTrue("com.app3" in r2.onHome() && "com.app2" !in r2.onHome())
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
        // nothing moves; widgets that filled the 3-wide row fill the 5-wide one
        assertEquals(small.pages.map { p -> p.map { if (it.kind == "widget" && it.x == 0 && it.w == 3) it.copy(w = 5) else it } }, big.pages)
        noOverlap(big)
    }

    @Test fun fullRowWidgetsFollowTheColumnCount() {
        assertEquals(5, HomeLayout.span(4, 5)); assertEquals(3, HomeLayout.span(4, 3)); assertEquals(2, HomeLayout.span(2, 5))
        val f = HomeLayout.fresh("gon", apps, emptyList())
        val m4 = HomeLayout.regrid(f, 4, f.rows).let { it.copy(pages = listOf(listOf(HItem("w1", "widget", 0, 0, 4, 2, type = "clock"), HItem("a1", "app", 0, 2, pkg = "com.app1")))) }
        val m5 = HomeLayout.regrid(m4, 5, m4.rows)
        val w4 = m4.pages[0].filter { it.kind == "widget" && it.w == 4 }
        assertTrue(w4.isNotEmpty())
        for (w in w4) assertEquals(5, m5.pages[0].first { it.id == w.id }.w)
        noOverlap(m5)
        // an old 5-column layout with a 4-wide card: spans the row when the 5th cell beside it is free, not when an icon sits there
        val old = m5.copy(pages = listOf(listOf(HItem("w1", "widget", 0, 0, 4, 1, type = "clock"), HItem("w2", "widget", 0, 1, 4, 1, type = "quick"),
            HItem("a1", "app", 4, 1, pkg = "com.app1"))))
        val back = HomeLayout.parse(old.toJson().toString())!!
        assertEquals(5, back.pages[0].first { it.id == "w1" }.w)
        assertEquals(4, back.pages[0].first { it.id == "w2" }.w)
        // the owner's POCO: 3 -> 5 columns with an old build left a 3-wide clock; it now spans the row
        val three = m5.copy(pages = listOf(listOf(HItem("w1", "widget", 0, 0, 3, 2, type = "clock"), HItem("a1", "app", 0, 2, pkg = "com.app1"))))
        assertEquals(5, HomeLayout.parse(three.toJson().toString())!!.pages[0].first { it.id == "w1" }.w)
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

    @Test fun freshGridGrowingFillsTheNewRow() {
        val apps = (1..40).map { "a$it" }
        val m = HomeLayout.fresh("teen", apps, emptyList(), parent = false)
        val big = HomeLayout.regrid(m, m.cols, m.rows + 1)
        // every page but the last is full, nothing lost, order kept
        assertTrue(big.pages.dropLast(1).all { HomeLayout.full(it, big.cols, big.rows) })
        val order = { mm: HomeModel -> mm.pages.flatMap { p -> p.sortedWith(compareBy({ it.y }, { it.x })) }.filter { it.kind == "app" }.map { it.pkg } }
        assertEquals(order(m), order(big))
    }

    @Test fun handMadeGridGrowingKeepsPlaces() {
        val m = HomeLayout.fresh("teen", (1..40).map { "a$it" }, emptyList(), parent = false)
        val gap = m.copy(pages = listOf(m.pages[0].drop(3)) + m.pages.drop(1))   // a hole on page 1
        val big = HomeLayout.regrid(gap, gap.cols, gap.rows + 1)
        assertEquals(gap.pages, big.pages)
    }

    @Test fun importedLayoutDropsOtherAppsWidgetsOnly() {
        val m = HomeLayout.fresh("teen", (1..6).map { "a$it" }, emptyList(), parent = false)
        val other = HItem("w9", "widget", 0, 0, 2, 2, type = HomeLayout.APP)
        val withOther = m.copy(pages = m.pages + listOf(listOf(other)))
        val im = HomeLayout.imported(withOther)
        assertEquals(m.pages, im.pages)   // its page was only that widget: gone too
        assertEquals(withOther.timetable.toString(), im.timetable.toString())
    }
}
