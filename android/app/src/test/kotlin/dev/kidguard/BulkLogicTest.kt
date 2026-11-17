package dev.kidguard

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.TimeZone

/** Bulk delete (2026-11-17): id lists, select by date, photo history pages, chat message removal, listen history. */
class BulkLogicTest {
    private val a = "0123456789abcdef"; private val b = "fedcba9876543210"; private val c = "00000000000000aa"
    private val utc = TimeZone.getTimeZone("UTC")
    private val day = 86_400_000L
    private val now = 100 * day + 15 * 3_600_000L   // 15:00 UTC on day 100

    // ---- id-list commands ----
    @Test fun idsBulkAndSingleForm() {
        assertEquals(listOf(a, b), FilesLogic.ids(JSONObject().put("ids", JSONArray(listOf(a, b, a)))))   // duplicates dropped
        assertEquals(listOf(a), FilesLogic.ids(JSONObject().put("id", a)))                                   // the old single form still works
        assertEquals(listOf(a), FilesLogic.ids(JSONObject().put("file", a), "file"))
    }
    @Test fun idsRejectBadShapes() {
        assertNull(FilesLogic.ids(JSONObject()))
        assertNull(FilesLogic.ids(JSONObject().put("ids", JSONArray())))
        assertNull(FilesLogic.ids(JSONObject().put("ids", JSONArray(listOf(a, "../x")))))
        assertNull(FilesLogic.ids(JSONObject().put("ids", JSONArray((0..FilesLogic.MAX_IDS).map { String.format("%016x", it) }))))
        assertNotNull(FilesLogic.ids(JSONObject().put("ids", JSONArray((0 until FilesLogic.MAX_IDS).map { String.format("%016x", it) }))))
    }

    // ---- select by date ----
    private val items = listOf("today" to now - 3_600_000L, "d3" to now - 3 * day, "d6" to now - 6 * day, "d7" to now - 7 * day, "d29" to now - 29 * day, "d30" to now - 30 * day, "d60" to now - 60 * day)
    private fun pick(r: SelLogic.Range, from: Long = 0, to: Long = 0) = SelLogic.pick(items, r, now, from, to, utc)
    @Test fun rangesAreCalendarDays() {
        assertEquals(listOf("today"), pick(SelLogic.Range.TODAY))
        assertEquals(listOf("today", "d3", "d6"), pick(SelLogic.Range.DAYS7))                 // today + 6 days before
        assertEquals(listOf("today", "d3", "d6", "d7", "d29"), pick(SelLogic.Range.DAYS30))   // 30 calendar days incl. today
        assertEquals(listOf("d30", "d60"), pick(SelLogic.Range.OLDER30))
    }
    @Test fun last30AndOlderPartitionEverything() {
        val ids = items.map { it.first }.toSet()
        assertEquals(ids, (pick(SelLogic.Range.DAYS30) + pick(SelLogic.Range.OLDER30)).toSet())
        assertTrue((pick(SelLogic.Range.DAYS30).toSet() intersect pick(SelLogic.Range.OLDER30).toSet()).isEmpty())
    }
    @Test fun customRangeIncludesBothDays() {
        assertEquals(listOf("d6", "d7"), pick(SelLogic.Range.CUSTOM, now - 7 * day + 5 * 3_600_000L, now - 6 * day - 7 * 3_600_000L))   // any time inside the first / last day
        assertEquals(listOf("d29", "d30", "d60"), pick(SelLogic.Range.CUSTOM, 0, now - 29 * day))   // open start
        assertEquals(listOf("today", "d3"), pick(SelLogic.Range.CUSTOM, now - 3 * day, 0))          // open end
    }
    @Test fun dayKeyGroupsByLocalDay() {
        assertEquals("1970-04-11", SelLogic.dayKey(now, utc))
        assertEquals(SelLogic.dayKey(100 * day, utc), SelLogic.dayKey(100 * day + 86_399_000L, utc))
        assertNotEquals(SelLogic.dayKey(100 * day, utc), SelLogic.dayKey(101 * day, utc))
    }

    // ---- photo history ----
    private fun meta(id: String, at: Long, ok: Boolean = true) = SnapLogic.Meta(id, at, "back", "auto", ok, if (ok) "" else "screen off", if (ok) 1234 else 0)
    @Test fun metaRoundTrip() {
        val ok = meta(a, 5); val bad = meta(b, 6, false)
        assertEquals(ok, SnapLogic.Meta.fromJson(JSONObject(ok.toJson().toString())))
        assertEquals(bad, SnapLogic.Meta.fromJson(JSONObject(bad.toJson().toString())))
        assertEquals("screen off", bad.toJson().getString("err")); assertFalse(bad.toJson().has("size"))
        assertNull(SnapLogic.Meta.fromJson(JSONObject().put("id", "nope")))
        assertEquals("front", SnapLogic.Meta.fromJson(JSONObject().put("id", a).put("cam", "x"))!!.cam)   // clamped
    }
    @Test fun historyPagesNewestFirstWithoutCap() {
        val all = (1..70).map { meta(String.format("%016x", it), it * 1000L, it % 5 != 0) }.shuffled()
        val p1 = SnapLogic.page(all, 0, 0)
        assertEquals(30, p1.getJSONArray("snaps").length()); assertTrue(p1.getBoolean("more"))
        assertEquals(70000L, p1.getJSONArray("snaps").getJSONObject(0).getLong("at"))
        val before = p1.getJSONArray("snaps").getJSONObject(29).getLong("at")
        val p2 = SnapLogic.page(all, before, 60)
        assertEquals(40, p2.getJSONArray("snaps").length()); assertFalse(p2.getBoolean("more"))   // nothing was dropped: 30 + 40 = 70
        assertEquals(before - 1000, p2.getJSONArray("snaps").getJSONObject(0).getLong("at"))
        assertEquals(60, SnapLogic.count(500)); assertEquals(30, SnapLogic.count(0))
    }
    @Test fun failedTriesAreListedWithReason() {
        val j = SnapLogic.page(listOf(meta(a, 1, false), meta(b, 2)), 0, 30).getJSONArray("snaps")
        assertEquals(b, j.getJSONObject(0).getString("id")); assertTrue(j.getJSONObject(0).getBoolean("ok"))
        assertFalse(j.getJSONObject(1).getBoolean("ok")); assertEquals("screen off", j.getJSONObject(1).getString("err"))
    }

    // ---- chat messages ----
    private fun thread(): JSONArray = JSONArray()
        .put(JSONObject().put("id", "m1").put("t", 1).put("from", "p").put("text", "hi"))
        .put(JSONObject().put("id", "m2").put("t", 2).put("from", "k").put("text", "").put("att", ChatLogic.att(a, "x.jpg", "image/jpeg", 10)))
        .put(JSONObject().put("id", "m3").put("t", 3).put("from", "k").put("text", "").put("att", ChatLogic.att(b, "y.mp4", "video/mp4", 20)))
    @Test fun removeMessagesAndTheirFiles() {
        val t = thread()
        val left = ChatLogic.remove(t, setOf("m1", "m3"))
        assertEquals(1, left.length()); assertEquals("m2", left.getJSONObject(0).getString("id"))
        assertSame(t, ChatLogic.remove(t, setOf("zz")))
        assertEquals(listOf(a, b), ChatLogic.attIdsOf(t, setOf("m2", "m3")))
        assertEquals(emptyList<String>(), ChatLogic.attIdsOf(t, setOf("m1")))
    }
    @Test fun markGoneAllOnlyTouchesTheseFiles() {
        val g = ChatLogic.markGoneAll(thread(), setOf(a))
        assertEquals("gone", g.getJSONObject(1).getJSONObject("att").getString("st")); assertEquals("ok", g.getJSONObject(2).getJSONObject("att").getString("st"))
        val t = thread(); assertSame(t, ChatLogic.markGoneAll(t, setOf(c)))
    }

    // ---- listen history ----
    @Test fun listenHistoryKeepsNewestAndSkipsBlips() {
        var h = JSONArray()
        h = ListenLogic.addHist(h, 1000, 500, "lan"); assertEquals(0, h.length())   // under 1 s: not a row
        for (i in 1..ListenLogic.HIST_KEEP + 5) h = ListenLogic.addHist(h, i * 1000L, 61_000, if (i % 2 == 0) "relay" else "wss")
        assertEquals(ListenLogic.HIST_KEEP, h.length())
        val t = ListenLogic.histTail(h)
        assertEquals(ListenLogic.HIST_STATE, t.length())
        assertEquals((ListenLogic.HIST_KEEP + 5) * 1000L, t.getJSONObject(0).getLong("at"))   // newest first
        assertEquals(61L, t.getJSONObject(0).getLong("dur"))
        assertEquals("lan", t.getJSONObject(0).getString("via")); assertEquals("relay", t.getJSONObject(1).getString("via"))   // anything but "relay" is lan
    }
}
