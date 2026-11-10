package dev.kidguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotifLogicTest {
    private fun e(k: String, t: Long, title: String = "a", text: String = "b") = NotifEntry(k, t, "p", "App", title, text)
    private val day = NotifLogic.DAY_MS

    @Test fun addAppends() = assertEquals(listOf("1", "2"), NotifLogic.add(listOf(e("1", 1)), e("2", 2)).map { it.key })

    @Test fun sameKeyReplaces() {
        val l = NotifLogic.add(listOf(e("1", 1), e("2", 2)), e("1", 3, text = "new"))
        assertEquals(listOf("2", "1"), l.map { it.key }); assertEquals("new", l.last().text)
    }

    @Test fun exactDuplicateIgnored() {
        val l = listOf(e("1", 1))
        assertEquals(l, NotifLogic.add(l, e("1", 5)))
    }

    @Test fun emptyIgnored() = assertEquals(emptyList<NotifEntry>(), NotifLogic.add(emptyList(), e("1", 1, "", " ")))

    @Test fun trimsAgeAndCount() {
        val now = 10 * day
        assertEquals(listOf("new"), NotifLogic.trim(listOf(e("old", now - 8 * day), e("new", now - day)), now).map { it.key })
        val many = (1..350).map { e("k$it", now - 1000 + it) }
        val t = NotifLogic.trim(many, now)
        assertEquals(300, t.size); assertEquals("k350", t.last().key); assertEquals("k51", t.first().key)
    }

    @Test fun daysAgo() {
        val t0 = 100 * day
        assertEquals(0, NotifLogic.daysAgo(t0 + 5, t0)); assertEquals(1, NotifLogic.daysAgo(t0 - 1, t0))
        assertEquals(1, NotifLogic.daysAgo(t0 - day, t0)); assertEquals(2, NotifLogic.daysAgo(t0 - day - 1, t0))
    }

    @Test fun jsonRoundTrip() {
        val l = listOf(e("1", 1, "x\"y", "line\nbreak"), e("2", 2))
        assertEquals(l, NotifLogic.fromJson(NotifLogic.toJson(l)))
        assertEquals(emptyList<NotifEntry>(), NotifLogic.fromJson("garbage"))
    }

    @Test fun jsonRoundTripRichFields() {
        val r = e("1", 5, "t", "x").copy(
            postTime = 1700000000123, whenMs = 1700000000000, removedAt = 1700000009000, reason = "click", channel = "chat", category = "msg",
            subText = "sub", summary = "sum", bigText = "long text", lines = listOf("a", "b"), msgs = listOf(NotifMsg("Bo", "hi"), NotifMsg("", "yo")),
            actions = listOf("Reply", "Mark read"), progress = "3/10", group = "g", priority = 1, importance = 4, largeIcon = "l.webp", picture = "p.webp")
        assertEquals(listOf(r), NotifLogic.fromJson(NotifLogic.toJson(listOf(r))))
        assertEquals(r.removedAt, r.clickedAt)
        assertEquals(0L, r.copy(reason = "swipe").clickedAt)
    }

    @Test fun oldJsonStillReads() {
        val old = """[{"k":"a","t":5,"p":"pk","a":"App","ti":"T","tx":"X"}]"""
        assertEquals(listOf(NotifEntry("a", 5, "pk", "App", "T", "X")), NotifLogic.fromJson(old))
        // an entry without optional fields writes no optional keys
        assertEquals(false, NotifLogic.toJson(listOf(e("1", 1))).contains("\"pt\""))
    }

    @Test fun reasonMapping() {
        assertEquals("click", NotifLogic.reason(1)); assertEquals("swipe", NotifLogic.reason(2)); assertEquals("clear", NotifLogic.reason(3))
        assertEquals("app", NotifLogic.reason(8)); assertEquals("app", NotifLogic.reason(9)); assertEquals("timeout", NotifLogic.reason(19))
        assertEquals("other", NotifLogic.reason(10)); assertEquals("other", NotifLogic.reason(-1))
    }

    @Test fun markRemovedUpdatesLiveEntryOnce() {
        val l = listOf(e("1", 1).copy(postTime = 100), e("2", 2))
        val m = NotifLogic.markRemoved(l, "1", 100, 500, "click")
        assertEquals(500L, m[0].removedAt); assertEquals("click", m[0].reason); assertEquals(500L, m[0].clickedAt)
        assertEquals(m, NotifLogic.markRemoved(m, "1", 100, 900, "swipe"))               // already removed: first reason wins
        assertEquals(l, NotifLogic.markRemoved(l, "1", 50, 500, "click"))                 // removal of an older post
        assertEquals(l, NotifLogic.markRemoved(l, "zz", 100, 500, "click"))
    }

    @Test fun repostAfterRemovalIsNewEntry() {
        val removed = listOf(e("1", 1).copy(removedAt = 9, reason = "swipe"))
        val l = NotifLogic.add(removed, e("1", 20))
        assertEquals(1, l.size); assertEquals(0L, l[0].removedAt); assertEquals(20L, l[0].time)
    }

    @Test fun recentClickSelection() {
        val now = 100_000L
        val recs = listOf(
            ClickRec("a", "p", now - 5_000, "click"), ClickRec("b", "p", now - 1_000, "click"),
            ClickRec("c", "p", now - 500, "swipe"), ClickRec("d", "q", now - 100, "click"), ClickRec("e", "p", 0, ""))
        assertEquals("b", NotifLogic.pickClick(recs, "p", 10_000, now)?.key)
        assertEquals("a", NotifLogic.pickClick(recs.filter { it.key != "b" }, "p", 10_000, now)?.key)
        assertNull(NotifLogic.pickClick(recs, "p", 500, now))        // too old
        assertNull(NotifLogic.pickClick(recs, "zz", 10_000, now))
        assertNull(NotifLogic.pickClick(emptyList(), "p", 10_000, now))
    }

    @Test fun trimOrphansImageFiles() {
        val now = 10 * day
        val old = e("old", now - 8 * day).copy(largeIcon = "o.webp", picture = "op.webp")
        val keep = e("new", now - day).copy(largeIcon = "n.webp")
        val before = listOf(old, keep)
        assertEquals(setOf("o.webp", "op.webp"), NotifLogic.orphans(before, NotifLogic.trim(before, now)))
        // count trim
        val many = (1..305).map { e("k$it", now - 1000 + it).copy(picture = "p$it.webp") }
        assertEquals((1..5).map { "p$it.webp" }.toSet(), NotifLogic.orphans(many, NotifLogic.trim(many, now)))
        // replacing a key with a new image drops the old file, keeping the same file name drops nothing
        assertEquals(setOf("n.webp"), NotifLogic.orphans(listOf(keep), NotifLogic.add(listOf(keep), e("new", now, text = "changed").copy(largeIcon = "n2.webp"), now)))
        assertEquals(emptySet<String>(), NotifLogic.orphans(listOf(keep), listOf(keep)))
        assertEquals(setOf("n.webp"), NotifLogic.orphans(listOf(keep), emptyList()))
    }

    @Test fun fitScalesDownOnly() {
        assertEquals(256 to 128, NotifLogic.fit(512, 256, 256, 256))
        assertEquals(720 to 360, NotifLogic.fit(1440, 720, 720, 1280))
        assertEquals(100 to 50, NotifLogic.fit(100, 50, 256, 256))
        assertEquals(360 to 1280, NotifLogic.fit(720, 2560, 720, 1280))
    }

    @Test fun imgNameStable() {
        assertEquals(NotifLogic.imgName("k", 5, 'l'), NotifLogic.imgName("k", 5, 'l'))
        assertEquals(false, NotifLogic.imgName("k", 5, 'l') == NotifLogic.imgName("k", 5, 'p'))
    }
}
