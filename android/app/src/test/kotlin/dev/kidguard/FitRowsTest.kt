package dev.kidguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class FitRowsTest {
    // Poco F-series, 20:9: page ~2056 px tall, 4 columns of ~291 px -> 7 rows, cells ~294 px (square)
    @Test fun tallPhoneGetsSquareCells() = assertEquals(7, HomeLayout.fitRows(2056, 291, 225, 5))

    // Oppo A5s at font scale 1.6: square would be 7 rows of 163 px, but an icon + a label line needs 182 -> 6
    @Test fun bigFontKeepsRoomForLabels() = assertEquals(6, HomeLayout.fitRows(1140, 171, 182, 5))

    // already close to square: a small change (banner, inset) never regrids
    @Test fun nearSquareStays() {
        assertEquals(6, HomeLayout.fitRows(1800, 290, 200, 6))
        assertEquals(6, HomeLayout.fitRows(1700, 290, 200, 6))
    }

    @Test fun boundsAndUnknownSize() {
        assertEquals(4, HomeLayout.fitRows(800, 300, 150, 4))
        assertEquals(7, HomeLayout.fitRows(4000, 200, 100, 4))
        assertEquals(5, HomeLayout.fitRows(0, 0, 100, 5))
    }

    @Test fun autoIsSavedAndDefaultsOn() {
        val m = HomeLayout.parse("""{"v":1,"grid":{"cols":4,"rows":7},"pages":[],"dock":[]}""")!!
        assertEquals(7, m.rows); assertEquals(true, m.autoRows)
        val off = HomeLayout.parse(m.copy(autoRows = false).toJson().toString())!!
        assertFalse(off.autoRows)
    }

    // another app's widget dragged from the picker lands on the cell it was dropped on, bound id kept
    @Test fun appWidgetDropLandsWhereDropped() {
        val m = HomeLayout.parse("""{"v":1,"grid":{"cols":4,"rows":6},"pages":[{"items":[{"id":"a1","kind":"app","x":0,"y":0,"pkg":"p.one"}]}],"dock":[]}""")!!
        val n = HomeLayout.placeAppWidgetAt(m, "com.w", "com.w.P", 42, 2, 2, 0, 2, 3)!!
        val w = n.pages[0].first { it.type == HomeLayout.APP }
        assertEquals(listOf(2, 3, 2, 2, 42), listOf(w.x, w.y, w.w, w.h, w.awid))
        // dropped over the app: no room there -> first free cells instead, nothing lost
        val o = HomeLayout.placeAppWidgetAt(m, "com.w", "com.w.P", 43, 2, 2, 0, 0, 0)!!
        assertEquals(2, o.pages.flatten().size)
    }

    // icon size up (7 -> 6 rows) and back (6 -> 7): every icon returns to its own cell, not the first free ones
    @Test fun rowShrinkThenGrowRestoresLayout() {
        val p = listOf(HItem("w1", "widget", 0, 0, 4, 2, type = "clock"),
            HItem("a1", "app", 0, 6, pkg = "p1"), HItem("a2", "app", 3, 6, pkg = "p2"), HItem("a3", "app", 2, 3, pkg = "p3"))
        val m = HomeModel(4, 7, listOf(p), emptyList(), HStyle())
        val (s, st) = HomeLayout.rows(m, 6, emptyList())
        assertEquals(6, s.rows); assertEquals(1, st.size)
        val (b, st2) = HomeLayout.rows(s, 7, st)
        assertEquals(7, b.rows); assertEquals(m.pages, b.pages); assertEquals(0, st2.size)
        assertEquals(7, HomeLayout.undoRows(s, st)); assertEquals(null, HomeLayout.undoRows(b, st2))
    }

    // the kid moved something while the grid was small: the stack no longer matches, a plain regrid keeps their move
    @Test fun editedInBetweenDoesNotRestore() {
        val m = HomeModel(4, 7, listOf(listOf(HItem("a1", "app", 0, 6, pkg = "p1"))), emptyList(), HStyle())
        val (s, st) = HomeLayout.rows(m, 6, emptyList())
        val moved = s.copy(pages = listOf(listOf(HItem("a1", "app", 2, 2, pkg = "p1"))))
        val (b, _) = HomeLayout.rows(moved, 7, st)
        assertEquals(moved.pages, b.pages)
    }
}
