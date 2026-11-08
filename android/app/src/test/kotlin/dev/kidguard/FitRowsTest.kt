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
}
