package dev.kidguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeLayoutTest {
    private val apps = (1..30).map { "com.app$it" }

    private fun noOverlap(m: HomeModel) {
        for (p in m.pages) for (y in 0 until m.rows) for (x in 0 until m.cols) assertTrue("cell $x,$y", p.count { it.covers(x, y) } <= 1)
        for (p in m.pages) for (i in p) assertTrue(i.x >= 0 && i.y >= 0 && i.x + i.w <= m.cols && i.y + i.h <= m.rows)
    }

    @Test fun freshTeenPageOneIsWidgetsThenApps() {
        val m = HomeLayout.fresh("teen", apps.take(6), listOf("com.sms", "com.cam", "com.web"))
        assertEquals(4, m.cols); assertEquals(5, m.rows)
        assertEquals(listOf("clock", "time", "quick"), m.pages[0].filter { it.kind == "widget" }.map { it.type })
        val first = m.pages[0].filter { it.kind == "app" }
        assertEquals("com.app1", first.first().pkg); assertEquals(0 to 3, first.first().x to first.first().y)
        assertEquals(listOf(HomeLayout.PHONE, "com.sms", "com.cam", "com.web"), m.dock)
        noOverlap(m)
    }

    @Test fun freshOverflowsToNextPagesAndSkipsDockApps() {
        val m = HomeLayout.fresh("be", apps, listOf("com.app1"))
        // Bé 3x4: 3 widget rows, 3 app cells on page 1, 12 on each next page
        assertEquals(3, m.pages[0].count { it.kind == "app" })
        assertEquals(29, m.pages.flatten().count { it.kind == "app" })
        assertFalse(m.pages.flatten().any { it.pkg == "com.app1" })
        assertEquals(1 + 3, m.pages.size)
        m.pages.forEach { noOverlap(m) }
    }

    @Test fun gonPutsClockAndSmallTimeOnOneRow() {
        val m = HomeLayout.fresh("gon", emptyList(), emptyList())
        val w = m.pages[0].associateBy { it.type }
        assertEquals(0 to 0, w.getValue("clock").x to w.getValue("clock").y); assertEquals(3, w.getValue("clock").w)
        assertEquals(3 to 0, w.getValue("time").x to w.getValue("time").y); assertEquals(2, w.getValue("time").w)
        assertEquals(5, w.getValue("quick").w)
    }

    @Test fun phoneIsInDockSlotOneForEveryPreset() {
        for (p in HomeStyle.PRESETS) {
            val m = HomeLayout.fresh(p.id, apps, listOf(HomeLayout.PHONE, "a", "b", "c", "d"))
            assertTrue(HomeLayout.sosOk(m)); assertEquals(4, m.dock.size)
            assertTrue(HomeLayout.sosOk(HomeLayout.remove(m, HomeLayout.PHONE)))
            assertTrue(HomeLayout.sosOk(HomeLayout.parse(m.toJson().toString())!!))
        }
        // a file that moved the phone or dropped the dock gets it back
        assertTrue(HomeLayout.sosOk(HomeLayout.parse("""{"dock":[{"pkg":"com.x"}],"pages":[]}""")!!))
    }

    @Test fun newInstallGoesToFirstFreeCellOnce() {
        val m0 = HomeLayout.fresh("teen", apps.take(2), emptyList())
        val m1 = HomeLayout.appendNew(m0, apps.take(2) + "com.new")
        val n = m1.pages.flatten().single { it.pkg == "com.new" }
        assertEquals(2 to 3, n.x to n.y)
        // removed by the kid: never comes back on its own
        val m2 = HomeLayout.remove(m1, "com.new")
        assertSame(m2, HomeLayout.appendNew(m2, apps.take(2) + "com.new"))
        // but "Ghim" puts it back
        assertTrue("com.new" in HomeLayout.pin(m2, "com.new").onHome())
    }

    private fun assertSame(a: HomeModel, b: HomeModel) = assertTrue(a === b)

    @Test fun appendFillsHolesBeforeANewPage() {
        val m0 = HomeLayout.fresh("teen", apps.take(8), emptyList())   // page 1 full (3 widget rows + 8 apps)
        assertEquals(1, m0.pages.size)
        val m1 = HomeLayout.remove(m0, "com.app2")
        val m2 = HomeLayout.appendNew(m1, listOf("com.x"))
        assertEquals(1, m2.pages.size)
        assertEquals(1 to 3, m2.pages[0].single { it.pkg == "com.x" }.let { it.x to it.y })
        val m3 = HomeLayout.appendNew(m2, listOf("com.y"))
        assertEquals(2, m3.pages.size); assertEquals(0 to 0, m3.pages[1].single().let { it.x to it.y })
    }

    @Test fun roundTrip() {
        val m = HomeLayout.appendNew(HomeLayout.fresh("gon", apps, listOf("com.a")), listOf("com.z"))
        assertEquals(m.toJson().toString(), HomeLayout.parse(m.toJson().toString())!!.toJson().toString())
    }

    @Test fun badFilesAreRepairedOrRejected() {
        assertNull(HomeLayout.parse("not json")); assertNull(HomeLayout.parse(null))
        val m = HomeLayout.parse("""{"grid":{"cols":9,"rows":1},"pages":[{"items":[
            {"id":"w1","kind":"widget","type":"clock","x":0,"y":0,"w":9,"h":1},
            {"id":"w2","kind":"widget","type":"bogus","x":0,"y":1,"w":2,"h":1},
            {"id":"a1","kind":"app","pkg":"com.a","x":0,"y":0},
            {"id":"a1","kind":"app","pkg":"com.b","x":7,"y":7},
            {"id":"a3","kind":"app","pkg":"com.a","x":1,"y":2},
            {"kind":"spaceship","x":0,"y":3}]}],"dock":[{"pkg":"com.b"},{"pkg":"com.b"},{"pkg":"com.c"},{"pkg":"com.c"},{"pkg":"com.d"}]}""")
        assertNotNull(m); m!!
        assertEquals(5, m.cols); assertEquals(4, m.rows)
        val items = m.pages.flatten()
        assertEquals(5, items.single { it.type == "clock" }.w)         // shrunk to the grid
        assertFalse(items.any { it.type == "bogus" })
        assertEquals(1, items.count { it.pkg == "com.a" })              // duplicate dropped
        assertEquals(1, items.count { it.pkg == "com.b" })              // overlapping / outside: moved to a free cell
        assertEquals(items.size, items.map { it.id }.toSet().size)      // ids unique
        assertEquals(listOf(HomeLayout.PHONE, "", "com.c", ""), m.dock)  // phone fixed, grid app not duplicated, dupes cleared
        noOverlap(m)
    }

    @Test fun pruneDropsUninstalledEverywhere() {
        val m = HomeLayout.fresh("teen", apps.take(10), listOf("com.app20"))
        val p = HomeLayout.prune(m) { it == "com.app3" || it == "com.app20" }
        assertFalse("com.app3" in p.onHome()); assertEquals("", p.dock[1]); assertTrue(HomeLayout.sosOk(p))
        assertTrue(HomeLayout.prune(m) { false } === m)
    }

    @Test fun trimKeepsFirstPage() {
        val m = HomeLayout.fresh("teen", apps.take(9), emptyList())   // 9th app on page 2
        assertEquals(2, m.pages.size)
        assertEquals(1, HomeLayout.remove(m, "com.app9").pages.size)
    }

    private fun grid(vararg it: HItem) = HomeModel(4, 5, listOf(it.toList()), listOf("", "", "", ""), HStyle())
    private fun app(id: String, x: Int, y: Int) = HItem(id, "app", x, y, pkg = "p.$id")

    @Test fun dropOnAnAppOnTheSamePageSwaps() {
        val m = grid(app("a", 0, 0), app("b", 1, 0), app("c", 2, 0))
        val t = HomeLayout.take(m, HomeLayout.Src.Item("a"))!!
        val out = HomeLayout.dropMove(t, 0, 2, 0)!!
        val at = out.pages[0].associate { it.id to (it.x to it.y) }
        assertEquals(2 to 0, at["a"]); assertEquals(0 to 0, at["c"]); assertEquals(1 to 0, at["b"])
        noOverlap(out)
    }

    @Test fun dropFromElsewhereMovesTheAppToTheNearestFreeCell() {
        val m = grid(app("b", 1, 0), app("c", 2, 0)).let { it.copy(pages = it.pages + listOf(listOf(app("a", 0, 0)))) }
        val t = HomeLayout.take(m, HomeLayout.Src.Item("a"))!!          // from page 2 onto c on page 1
        val out = HomeLayout.dropMove(t, 0, 2, 0)!!
        val c = out.pages[0].single { it.id == "c" }
        assertEquals(1, Math.abs(c.x - 2) + Math.abs(c.y - 0))   // one cell away
        noOverlap(out)
    }

    @Test fun freeCellIsAPlainDropAndAWidgetIsNotSwapped() {
        val m = grid(app("a", 0, 0), HItem("w", "widget", 0, 1, 4, 2, type = "clock"))
        val t = HomeLayout.take(m, HomeLayout.Src.Item("a"))!!
        assertEquals(3 to 4, HomeLayout.dropMove(t, 0, 3, 4)!!.pages[0].single { it.id == "a" }.let { it.x to it.y })
        assertNull(HomeLayout.dropMove(t, 0, 1, 1))          // onto a 4x2 widget: no room, nothing moves
    }

    @Test fun shortcutAddedOnceKeptByParseNotCountedAsTheApp() {
        val m0 = HomeLayout.fresh("teen", apps.take(3), listOf("com.sms"))
        val m1 = HomeLayout.addShortcut(m0, "com.app1", "lock", "Khoá")
        val sc = m1.pages.flatten().single { it.kind == HomeLayout.SHORTCUT }
        assertEquals("com.app1" to "lock", sc.pkg to sc.cls); assertEquals("Khoá", sc.name)
        noOverlap(m1)
        // the same one again: no second item, a new label is taken
        assertEquals(m1, HomeLayout.addShortcut(m1, "com.app1", "lock", "Khoá"))
        val m2 = HomeLayout.addShortcut(m1, "com.app1", "lock", "Lock")
        assertEquals(listOf("Lock"), m2.pages.flatten().filter { it.kind == HomeLayout.SHORTCUT }.map { it.name })
        // round trip; the app's own icon is still on the home, the shortcut does not count as it
        val back = HomeLayout.parse(m2.toJson().toString())!!
        assertEquals(m2.pages.flatten().first { it.kind == HomeLayout.SHORTCUT }, back.pages.flatten().first { it.kind == HomeLayout.SHORTCUT })
        assertEquals(m0.onHome(), m2.onHome())
        // the app uninstalled: its shortcut goes; imported from KidGuard Home: dropped (pinned to that app)
        assertTrue(HomeLayout.prune(m2) { it == "com.app1" }.pages.flatten().none { it.kind == HomeLayout.SHORTCUT })
        assertTrue(HomeLayout.imported(m2).pages.flatten().none { it.kind == HomeLayout.SHORTCUT })
    }

    @Test fun shortcutGoesToANewPageWhenFull() {
        var m = HomeLayout.fresh("teen", emptyList(), emptyList())
        m = m.copy(pages = listOf((0 until m.rows).flatMap { y -> (0 until m.cols).map { x -> HItem("a$x$y", "app", x, y, pkg = "p$x$y") } }))
        val n = HomeLayout.addShortcut(m, "com.x", "s1", "S")
        assertEquals(2, n.pages.size); assertEquals(HomeLayout.SHORTCUT, n.pages[1].single().kind)
    }

    @Test fun firstRunShowsFeaturedOnlyRestInDrawer() {
        val all = listOf("com.x1", "com.miui.gallery", "com.google.android.apps.photos", "com.google.android.youtube", "com.x2", "com.x3")
        val f = HomeLayout.featured(all, used = listOf("com.x3", "com.nothere"), max = 3)
        assertEquals(listOf("com.google.android.youtube", "com.google.android.apps.photos", "com.x3"), f)
        val m = HomeLayout.fresh("teen", f, emptyList(), known = all)
        assertEquals(f.toSet(), m.pages.flatten().filter { it.kind == "app" }.map { it.pkg }.toSet())
        assertEquals(m, HomeLayout.appendNew(m, all))   // the others are "seen": they stay in the drawer
    }
    @Test fun shortcutLabelRejectsIds() {
        val p = "com.miui.securitycenter"
        assertFalse(HomeLayout.shortcutLabelOk("com.miui.securitycenter:shortcut_clean", p, "x"))
        assertFalse(HomeLayout.shortcutLabelOk("com.other.app.Clean", p, "x"))
        assertFalse(HomeLayout.shortcutLabelOk("clean_id", p, "clean_id"))
        assertFalse(HomeLayout.shortcutLabelOk(" ", p, "x"))
        assertTrue(HomeLayout.shortcutLabelOk("Dọn dẹp", p, "x"))
        assertTrue(HomeLayout.shortcutLabelOk("Clean", p, "x"))
        assertTrue(HomeLayout.shortcutLabelOk("v1.2", p, "x"))
    }

    // ---- shortcuts inside folders ----
    private fun scModel(): HomeModel {
        var m = HomeLayout.fresh("teen", apps.take(3), listOf("com.sms"))
        m = HomeLayout.addShortcut(m, "com.brave", "pwa1", "Zalo Web")
        m = HomeLayout.addShortcut(m, "com.brave", "pwa2", "Mail Web")
        return m
    }
    private fun sc(m: HomeModel, id: String) = m.pages.flatten().single { it.kind == HomeLayout.SHORTCUT && it.cls == id }

    @Test fun shortcutOnShortcutMakesAFolderAndTakesBackOut() {
        val m = scModel()
        val a = sc(m, "pwa1"); val b = sc(m, "pwa2")
        val t = HomeLayout.take(m, HomeLayout.Src.Item(a.id))!!
        val n = HomeLayout.dropOnto(t, b.id, "Web")!!
        val f = n.pages.flatten().single { it.kind == "folder" }
        val ea = HomeLayout.scEntry("com.brave", "pwa1"); val eb = HomeLayout.scEntry("com.brave", "pwa2")
        assertEquals(listOf(eb, ea), f.apps)
        assertEquals(mapOf(ea to "Zalo Web", eb to "Mail Web"), f.names)
        assertEquals(m.onHome(), n.onHome())        // a shortcut never counts as its app on the home
        assertTrue(n.pages.flatten().none { it.kind == HomeLayout.SHORTCUT })
        // an app joins it too; a shortcut joins a folder of apps
        val withApp = HomeLayout.dropOnto(HomeLayout.take(n, HomeLayout.Src.Item(n.pages.flatten().first { it.kind == "app" }.id))!!, f.id, "")!!
        assertEquals(3, withApp.pages.flatten().single { it.kind == "folder" }.apps.size)
        // reorder keeps the labels
        val r = HomeLayout.reorderFolder(n, f.id, listOf(ea, eb))!!
        assertEquals(listOf(ea, eb), r.pages.flatten().single { it.kind == "folder" }.apps)
        // take one out: it is a shortcut item again (label kept) and the folder of one turns back into a shortcut
        val out = HomeLayout.take(n, HomeLayout.Src.InFolder(f.id, ea))!!
        assertEquals(HomeLayout.SHORTCUT, out.item.kind)
        assertEquals(Triple("com.brave", "pwa1", "Zalo Web"), Triple(out.item.pkg, out.item.cls, out.item.name))
        val left = out.m.pages.flatten().single { it.id == f.id }
        assertEquals(HomeLayout.SHORTCUT to "pwa2", left.kind to left.cls); assertEquals("Mail Web", left.name)
        // a shortcut folder dragged by "Gom nhóm" with an app
        val g = HomeLayout.group(m, listOf(a.id, b.id, m.pages.flatten().first { it.kind == "app" }.id), "")!!
        assertEquals(3, g.pages.flatten().single { it.kind == "folder" }.apps.size)
    }

    @Test fun oldFolderJsonLoadsUnchangedAndNamesRoundTrip() {
        val old = """{"v":1,"grid":{"cols":4,"rows":5},"pages":[{"items":[{"id":"f1","kind":"folder","x":0,"y":0,"name":"F","apps":["com.a","com.b"]}]}],"dock":[{"pkg":"@phone"}]}"""
        val m = HomeLayout.parse(old)!!
        val f = m.pages[0].single()
        assertEquals(listOf("com.a", "com.b"), f.apps); assertTrue(f.names.isEmpty())
        assertFalse(f.toJson().has("names"))          // serialized only when non-empty
        val e = HomeLayout.scEntry("com.brave", "x/1")
        val m2 = m.copy(pages = listOf(listOf(f.copy(apps = f.apps + e, names = mapOf(e to "X one")))))
        val j = m2.toJson().toString()
        val back = HomeLayout.parse(j)!!.pages[0].single()
        assertEquals(listOf("com.a", "com.b", e), back.apps); assertEquals(mapOf(e to "X one"), back.names)
        assertEquals("com.brave", HomeLayout.pkgOf(e)); assertEquals("x/1", HomeLayout.scIdOf(e)); assertEquals("com.a", HomeLayout.pkgOf("com.a"))
    }

    @Test fun uninstalledAppsShortcutLeavesFolderAndImportDropsIt() {
        val m = scModel()
        val a = sc(m, "pwa1"); val b = sc(m, "pwa2")
        val n = HomeLayout.dropOnto(HomeLayout.take(m, HomeLayout.Src.Item(a.id))!!, b.id, "")!!
        val withApp = HomeLayout.addTo(n, "com.app1", n.pages.flatten().single { it.kind == "folder" }.id, "")!!
        assertEquals(3, withApp.pages.flatten().single { it.kind == "folder" }.apps.size)
        // the browser goes: both shortcuts leave, the folder of the app left turns back into it
        val gone = HomeLayout.prune(withApp) { it == "com.brave" }
        assertTrue(gone.pages.flatten().none { it.kind == "folder" || it.kind == HomeLayout.SHORTCUT })
        assertTrue(gone.pages.flatten().any { it.kind == "app" && it.pkg == "com.app1" })
        // another app goes: the folder keeps its shortcuts and their labels
        val keep = HomeLayout.prune(withApp) { it == "com.app1" }.pages.flatten().single { it.kind == "folder" }
        assertEquals(2, keep.apps.size); assertEquals(2, keep.names.size)
        // imported from KidGuard Home: shortcuts leave the folders too
        val imp = HomeLayout.imported(withApp)
        assertTrue(imp.pages.flatten().none { it.kind == "folder" })
        // duplicate entries in a stored folder are loaded once
        val e = HomeLayout.scEntry("com.brave", "pwa1")
        val dup = HomeLayout.parse(n.copy(pages = listOf(listOf(n.pages.flatten().single { it.kind == "folder" }.copy(apps = listOf(e, e, "com.z"))))).toJson().toString())!!
        assertEquals(listOf(e, "com.z"), dup.pages[0].single().apps)
    }
}
