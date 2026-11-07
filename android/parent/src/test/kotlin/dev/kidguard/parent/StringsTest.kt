package dev.kidguard.parent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every language file has the same keys and the same format args, and every pure-logic key (enum T) exists. */
class StringsTest {
    private val args = Regex("%(\\d+\\$)?[sd]")
    private fun sig(s: String) = args.findAll(s).map { it.value }.sorted().toList()

    @Test fun sameKeysAndArgs() {
        val en = TestTr.EN; val vi = TestTr.VI
        assertEquals(en.strings.keys, vi.strings.keys)
        assertEquals(en.plurals.keys, vi.plurals.keys)
        for (k in en.strings.keys) assertEquals(k, sig(en.strings.getValue(k)), sig(vi.strings.getValue(k)))
        for (k in en.plurals.keys) {
            assertTrue(k, "other" in en.plurals.getValue(k) && "other" in vi.plurals.getValue(k))
            assertEquals(k, sig(en.plurals.getValue(k).getValue("other")), sig(vi.plurals.getValue(k).getValue("other")))
        }
    }

    @Test fun pureKeysExist() {
        for (tr in listOf(TestTr.EN, TestTr.VI)) for (k in T.values()) assertTrue(k.name, k.name in tr.strings || k.name in tr.plurals)
    }

    @Test fun englishReads() {
        val now = System.currentTimeMillis()
        val o = Logic.evaluate("An", Logic.Seen(true, listOf("a" to 1), now), Logic.NotifyState(init = true), now, tr = TestTr.EN)
        assertEquals(listOf("An asks for 1 more minute"), o.events.map { it.text })
    }
}
