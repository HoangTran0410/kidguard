package dev.kidguard

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class DigestTest {
    private val tz = TimeZone.getTimeZone("Asia/Ho_Chi_Minh")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) =
        java.util.Calendar.getInstance(tz).apply { clear(); set(y, mo - 1, d, h, mi) }.timeInMillis
    private val min = 60_000L

    private val state = JSONObject("""{"usedMs": ${95 * min}, "limitMin": 120,
      "apps": [{"pkg":"a","label":"YouTube","ms": ${50 * min}}, {"pkg":"b","label":"Zalo","ms": ${30 * min}},
               {"pkg":"c","label":"Game","ms": ${10 * min}}, {"pkg":"d","label":"Tiny","ms": 1000}, {"pkg":"s","label":"Sys","ms": ${99 * min},"protected":true}],
      "history": [{"day":"2026-11-13","ms": ${60 * min},"top":[{"pkg":"a","label":"YouTube","ms": ${40 * min}}]}],
      "digest": {"days": [{"day":"2026-11-14","asked":2,"answered":1,"blocked":3,"tamper":0},{"day":"2026-11-13","asked":1,"answered":1,"blocked":0,"tamper":2}], "bedKept": true}}""")

    @Test fun dailyVi() {
        val t = Digest.daily(Digest.fromState(state, "An"), "vi")
        assertEquals(listOf("Hôm nay: An", "Màn hình 1 giờ 35 phút / 2 giờ", "Nhiều nhất: YouTube 50 phút, Zalo 30 phút, Game 10 phút",
            "Xin thêm giờ: 2, đã trả lời: 1", "App bị khoá: 3 lần", "Thử phá: 0", "Giờ ngủ: giữ đúng"), t.lines())
    }

    @Test fun dailyEnNoLimitNoBed() {
        val s = JSONObject(state.toString()).put("limitMin", 0).put("digest", JSONObject().put("days", org.json.JSONArray()))
        val t = Digest.daily(Digest.fromState(s, "An"), "en", withHint = true)
        assertTrue(t.contains("Screen time 1h 35m\n"))
        assertTrue(t.contains("More time asked: 0"))
        assertFalse(t.contains("Bedtime"))
        assertTrue(t.endsWith("/digest off to stop"))
    }

    @Test fun weeklySumsHistoryAndCounters() {
        val t = Digest.weekly(Digest.fromState(state, "An"), "en").lines()
        assertEquals("This week: An", t[0])
        assertEquals("Screen time 2h 35m, 1h 17m a day", t[1])   // 95 + 60 min over 2 days
        assertEquals("Top: YouTube 1h 30m, Zalo 30m, Game 10m", t[2])
        assertEquals("More time asked: 3, answered: 2", t[3])
        assertEquals("Apps locked: 3", t[4]); assertEquals("Tamper tries: 2", t[5])
    }

    @Test fun missingDataIsZeros() {
        val t = Digest.daily(Digest.fromState(JSONObject(), "X"), "en")
        assertEquals(listOf("Today: X", "Screen time 0m", "More time asked: 0", "Tamper tries: 0"), t.lines())
    }

    @Test fun sundayAddsWeekly() {
        val i = Digest.fromState(state, "An")
        assertEquals(1, Digest.messages(i, "en", at(2026, 11, 14, 20, 0), tz).size)   // Saturday
        assertEquals(2, Digest.messages(i, "en", at(2026, 11, 15, 20, 0), tz).size)   // Sunday
    }

    @Test fun dueOnceAfterTime() {
        val now = at(2026, 11, 14, 20, 5)
        assertTrue(Digest.due(true, "20:00", "2026-11-13", now, tz))
        assertFalse(Digest.due(true, "20:00", "2026-11-14", now, tz))
        assertFalse(Digest.due(true, "21:00", "", now, tz))
        assertFalse(Digest.due(false, "20:00", "", now, tz))
        assertTrue(Digest.due(true, "bad", "", now, tz))   // bad time = default 20:00
        assertEquals(20 * 60, Digest.minutes("20:00")); assertNull(Digest.minutes("25:00")); assertEquals(5 * 60 + 7, Digest.minutes("5:07"))
    }

    @Test fun countersBumpAndPrune() {
        var j: String? = null
        j = Digest.bump(j, "2026-11-14", "asked"); j = Digest.bump(j, "2026-11-14", "asked"); j = Digest.bump(j, "2026-11-14", "tamper")
        val d = Digest.days(j, "2026-11-14")
        assertEquals(1, d.length()); assertEquals(2, d.getJSONObject(0).getInt("asked")); assertEquals(1, d.getJSONObject(0).getInt("tamper"))
        for (n in 1..9) j = Digest.bump(j, "2026-11-0$n", "blocked")
        assertEquals(Digest.KEEP_DAYS, JSONObject(j!!).length())
        assertEquals("2026-11-14", Digest.days(j, "2026-11-15").getJSONObject(1).getString("day"))   // today first, even when empty
    }

    @Test fun bedWindowLastNight() {
        // 21:30 - 06:30; at 20:00 the window is last night; at 23:00 tonight so far; at 07:00 the night just ended
        assertEquals(at(2026, 11, 13, 21, 30) to at(2026, 11, 14, 6, 30), Digest.bedWindow(true, "21:30", "06:30", at(2026, 11, 14, 20, 0), tz))
        assertEquals(at(2026, 11, 14, 21, 30) to at(2026, 11, 14, 23, 0), Digest.bedWindow(true, "21:30", "06:30", at(2026, 11, 14, 23, 0), tz))
        assertEquals(at(2026, 11, 13, 21, 30) to at(2026, 11, 14, 6, 30), Digest.bedWindow(true, "21:30", "06:30", at(2026, 11, 14, 7, 0), tz))
        assertNull(Digest.bedWindow(false, "21:30", "06:30", 0, tz)); assertNull(Digest.bedWindow(true, "x", "06:30", 0, tz))
        assertTrue(Digest.bedKept(30_000)); assertFalse(Digest.bedKept(5 * 60_000))
    }
}
