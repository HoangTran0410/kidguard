package dev.kidguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class PairedTest {
    private val a = Rules.sha256("tokenA"); private val b = Rules.sha256("tokenB")

    @Test fun idIsEightHexOfHash() { assertEquals(a.take(8), Paired.id(a)); assertEquals(8, Paired.id(a).length) }

    @Test fun findByIdOnly() {
        val hs = setOf(a, b)
        assertEquals(b, Paired.find(hs, Paired.id(b)))
        assertNull(Paired.find(hs, "deadbeef")); assertNull(Paired.find(hs, "")); assertNull(Paired.find(hs, a.take(7)))
        assertNull(Paired.find(hs, a))        // the full hash is not an id
        assertNull(Paired.find(hs, "ZZZZZZZZ"))
    }

    @Test fun findRefusesAmbiguousPrefix() {
        val x = "abcd1234" + "0".repeat(56); val y = "abcd1234" + "1".repeat(56)
        assertNull(Paired.find(setOf(x, y), "abcd1234"))
    }

    @Test fun touchAddsThenThrottlesLast() {
        var m = Paired.touch(emptyMap(), a, 1000, " Mẹ's phone\n")
        assertEquals(Paired.Dev("Mẹ's phone", 1000, 1000), m[a])
        val same = Paired.touch(m, a, 1000 + 60_000, null)
        assertSame(m, same)
        m = Paired.touch(m, a, 1000 + Paired.LAST_EVERY_MS, "ignored")
        assertEquals(1000 + Paired.LAST_EVERY_MS, m[a]!!.last); assertEquals("Mẹ's phone", m[a]!!.name); assertEquals(1000L, m[a]!!.at)
    }

    @Test fun touchFillsMissingNameOnce() {
        var m = Paired.touch(emptyMap(), a, 0, "")
        m = Paired.touch(m, a, 1, "Dad")
        assertEquals("Dad", m[a]!!.name)
        m = Paired.touch(m, a, 2, "Other")
        assertEquals("Dad", m[a]!!.name)
    }

    @Test fun rowsSortedAndSelf() {
        val m = mapOf(a to Paired.Dev("A", 50, 60), b to Paired.Dev("B", 10, 20))
        val r = Paired.rows(setOf(a, b), m, a)
        assertEquals(listOf("B", "A"), r.map { it.name })
        assertTrue(r.single { it.name == "A" }.self); assertFalse(r.single { it.name == "B" }.self)
        val legacy = Paired.rows(setOf(a), emptyMap())
        assertEquals(Paired.Row(Paired.id(a), "", 0, 0, false), legacy.single())
    }

    @Test fun nameCleaned() { assertEquals(40, Paired.cleanName("x".repeat(100)).length); assertEquals("ab", Paired.cleanName("a\u0000b\n")) }

    @Test fun pruneDropsRevoked() { assertEquals(setOf(b), Paired.prune(mapOf(a to Paired.Dev("", 0, 0), b to Paired.Dev("", 0, 0)), setOf(b)).keys) }
}
