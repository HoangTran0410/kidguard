package dev.kidguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FolderOrderTest {
    private val m = HomeLayout.parse("""{"v":1,"grid":{"cols":4,"rows":6},"pages":[{"items":[{"id":"f1","kind":"folder","x":0,"y":0,"name":"F","apps":["a","h","b","c"]}]}],"dock":[]}""")!!

    @Test fun hiddenKeepsItsSlot() {
        // "h" is hidden: the shown a,b,c become c,a,b around it
        val n = HomeLayout.reorderFolder(m, "f1", listOf("c", "a", "b"))!!
        assertEquals(listOf("c", "h", "a", "b"), n.pages[0][0].apps)
    }

    @Test fun badInputIgnored() {
        assertNull(HomeLayout.reorderFolder(m, "f1", listOf("a", "b", "c")))      // same order
        assertNull(HomeLayout.reorderFolder(m, "f1", listOf("a", "x")))           // unknown app
        assertNull(HomeLayout.reorderFolder(m, "nope", listOf("a")))
    }
}
