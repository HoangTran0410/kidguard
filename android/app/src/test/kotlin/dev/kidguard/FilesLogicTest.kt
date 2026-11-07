package dev.kidguard

import dev.kidguard.FilesLogic.Entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FilesLogicTest {
    @Test fun rejectsTraversalAndJunk() {
        for (bad in listOf("", ".", "..", "../x", "..\\x", "a/b", "/etc/passwd", "x\u0000y", "a\nb", " .. ", "...", "  "))
            assertNull(bad, FilesLogic.cleanName(bad))
    }

    @Test fun cleansNames() {
        assertEquals("bài tập.pdf", FilesLogic.cleanName("bài tập.pdf"))
        assertEquals("a_b_.txt", FilesLogic.cleanName("a<b*.txt"))
        assertEquals("hidden", FilesLogic.cleanName(".hidden"))
        assertEquals("a b.txt", FilesLogic.cleanName("  a    b.txt. "))
        val long = "x".repeat(300) + ".jpeg"
        val c = FilesLogic.cleanName(long)!!
        assertEquals(FilesLogic.MAX_NAME, c.length); assertTrue(c.endsWith(".jpeg"))
    }

    @Test fun uniquifies() {
        val taken = setOf("a.pdf", "a (1).pdf", "noext")
        assertEquals("a (2).pdf", FilesLogic.unique("a.pdf") { it in taken })
        assertEquals("noext (1)", FilesLogic.unique("noext") { it in taken })
        assertEquals("b.pdf", FilesLogic.unique("b.pdf") { it in taken })
    }

    @Test fun sizeCaps() {
        assertNull(FilesLogic.sizeError(0, FilesLogic.MAX_LAN))
        assertNull(FilesLogic.sizeError(FilesLogic.MAX_LAN, FilesLogic.MAX_LAN))
        assertEquals("too big", FilesLogic.sizeError(FilesLogic.MAX_LAN + 1, FilesLogic.MAX_LAN))
        assertEquals("too big", FilesLogic.sizeError(FilesLogic.MAX_RELAY + 1, FilesLogic.MAX_RELAY))
        assertEquals("length required", FilesLogic.sizeError(-1, FilesLogic.MAX_LAN))
        assertEquals(1, FilesLogic.chunks(0)); assertEquals(1, FilesLogic.chunks(FilesLogic.CHUNK.toLong()))
        assertEquals(10, FilesLogic.chunks(FilesLogic.MAX_RELAY)); assertEquals(11, FilesLogic.chunks(FilesLogic.MAX_RELAY + 1))
    }

    private fun e(id: Int, dir: String, at: Long) = Entry("%016x".format(id), dir, "f$id", 10, at, FilesLogic.OCTET, FilesLogic.NEW, "/secret/path/$id")

    @Test fun listJsonNewestFirstNoPaths() {
        val a = FilesLogic.listJson(listOf(e(1, "in", 100), e(2, "out", 300), e(3, "in", 200)), 2)
        assertEquals(2, a.length())
        assertEquals("0000000000000002", a.getJSONObject(0).getString("id"))
        assertEquals("0000000000000003", a.getJSONObject(1).getString("id"))
        val o = a.getJSONObject(0)
        assertFalse(o.has("loc")); assertFalse(o.has("mime"))
        assertEquals(setOf("id", "dir", "name", "size", "at", "st"), o.keys().asSequence().toSet())
    }

    @Test fun indexRoundTripAndCaps() {
        val l = listOf(e(1, "in", 1), e(2, "out", 2))
        assertEquals(l, FilesLogic.parseIndex(FilesLogic.indexJson(l)))
        assertEquals(emptyList<Entry>(), FilesLogic.parseIndex("junk"))
        assertEquals(1, FilesLogic.parseIndex("""[{"id":"../../x","dir":"in"},{"id":"00000000000000aa","dir":"out"}]""").size)
        var list = emptyList<Entry>(); val dropped = mutableListOf<Entry>()
        for (i in 1..60) { val (n, d) = FilesLogic.add(list, e(i, "out", i.toLong())); list = n; dropped += d }
        assertEquals(FilesLogic.MAX_OUT, list.size)
        assertEquals((1..10).map { "%016x".format(it) }, dropped.map { it.id })
    }

    @Test fun disposition() {
        assertEquals("attachment; filename=\"b_i t_p.pdf\"; filename*=UTF-8''b%C3%A0i%20t%E1%BA%ADp.pdf", FilesLogic.disposition("bài tập.pdf"))
    }
}
