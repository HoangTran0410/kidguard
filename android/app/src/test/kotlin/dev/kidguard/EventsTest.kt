package dev.kidguard

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EventsTest {
    private val home = Zone("z1", "Nhà", 21.0, 105.0, 100)
    // 0.001 deg lat = ~111 m
    private fun lat(m: Double) = 21.0 + m / 111_195.0

    @Test fun enterAndLeaveWithHysteresis() {
        var (ins, ev) = Events.zoneStep(listOf(home), emptySet(), lat(500.0), 105.0, false)
        assertTrue(ev.isEmpty()); assertTrue(ins.isEmpty())
        Events.zoneStep(listOf(home), ins, lat(90.0), 105.0, false).let { ins = it.first; ev = it.second }
        assertEquals(listOf("zoneIn"), ev.map { it.kind }); assertEquals("Đã đến Nhà", ev[0].text); assertEquals("z1", ev[0].zone)
        Events.zoneStep(listOf(home), ins, lat(120.0), 105.0, false).let { ins = it.first; ev = it.second }   // r+20: still inside
        assertTrue(ev.isEmpty()); assertEquals(setOf("z1"), ins)
        Events.zoneStep(listOf(home), ins, lat(160.0), 105.0, false).let { ins = it.first; ev = it.second }   // > r+50
        assertEquals(listOf("zoneOut"), ev.map { it.kind }); assertEquals("Đã rời Nhà", ev[0].text); assertTrue(ins.isEmpty())
    }

    @Test fun edgeFixDoesNotFlap() {
        // outside at r+30 is not "in" again (needs <= r), inside at r+30 is not "out"
        assertTrue(Events.zoneStep(listOf(home), emptySet(), lat(130.0), 105.0, false).second.isEmpty())
        assertTrue(Events.zoneStep(listOf(home), setOf("z1"), lat(130.0), 105.0, false).second.isEmpty())
    }

    @Test fun firstFixOnlyRecords() {
        val (ins, ev) = Events.zoneStep(listOf(home), emptySet(), lat(10.0), 105.0, true)
        assertTrue(ev.isEmpty()); assertEquals(setOf("z1"), ins)
        // removed zone leaves the set silently
        assertTrue(Events.zoneStep(emptyList(), ins, 0.0, 0.0, false).let { it.first.isEmpty() && it.second.isEmpty() })
    }

    @Test fun batteryOnceThenRearm() {
        assertEquals(false to true, Events.batteryStep(true, 15, false))
        assertEquals(false to false, Events.batteryStep(false, 10, false))      // no repeat
        assertEquals(false to false, Events.batteryStep(false, 29, true))       // not yet re-armed
        assertEquals(true to false, Events.batteryStep(false, 30, true))
        assertEquals(true to false, Events.batteryStep(true, 10, true))         // charging: no event
        assertEquals(true to false, Events.batteryStep(true, 16, false))
        assertEquals(true to false, Events.batteryStep(true, -1, false))
    }

    @Test fun clockJump() {
        assertFalse(Events.clockJumped(1_000_000, 5_000, 1_060_000, 65_000))
        assertFalse(Events.clockJumped(1_000_000, 5_000, 1_060_000 + 100_000, 65_000))   // 100 s: below 2 min
        assertTrue(Events.clockJumped(1_000_000, 5_000, 1_060_000 + 130_000, 65_000))
        assertTrue(Events.clockJumped(1_000_000, 5_000, 1_060_000 - 3_600_000, 65_000))
    }

    @Test fun pruneAgeAndCount() {
        val now = 10L * Events.KEEP_MS
        val a = JSONArray().put(JSONObject().put("id", "old").put("t", now - Events.KEEP_MS - 1))
        for (i in 0 until 60) a.put(JSONObject().put("id", "e$i").put("t", now - 1000 + i))
        val p = Events.prune(a, now)
        assertEquals(50, p.length()); assertEquals("e10", p.getJSONObject(0).getString("id")); assertEquals("e59", p.getJSONObject(49).getString("id"))
    }

    @Test fun answerText() {
        assertEquals("Bố mẹ đã đồng ý thêm 30 phút", Events.answerText(true, 30, ""))
        assertEquals("Bố mẹ đã đồng ý thêm 30 phút. Bố mẹ nhắn: giỏi lắm", Events.answerText(true, 30, "  giỏi lắm "))
        assertEquals("Bố mẹ chưa đồng ý. Bố mẹ nhắn: mai nhé", Events.answerText(false, 30, "mai nhé"))
        assertTrue(Events.answerText(true, 5, "x".repeat(300)).endsWith("x".repeat(200)))
    }

    @Test fun ruleDiffSummary() {
        val a = Rules(dailyMin = 120)
        assertEquals("Bố mẹ đổi luật: giới hạn 120 → 90 phút; giờ ngủ 21:30–06:30",
            Events.ruleDiff(a, a.copy(dailyMin = 90, bedtimeOn = true)))
        assertEquals("Bố mẹ đổi luật: giới hạn 120 → không giới hạn", Events.ruleDiff(a, a.copy(dailyMin = 0)))
        assertEquals("Bố mẹ đổi luật: chặn 0 → 2 app; tạm dừng máy; việc nhà 0 → 1; vùng 0 → 1",
            Events.ruleDiff(a, a.copy(blocked = setOf("a", "b"), chores = listOf(Chore("c", "C", 10)), zones = listOf(Zone("z", "Z", 1.0, 1.0, 100)), pausedUntil = 5)))
        assertEquals("Bố mẹ đổi luật: cài đặt khác", Events.ruleDiff(a, a.copy(noDebug = true)))
        assertEquals("Bố mẹ đổi luật: thưởng +20 phút; lưu vị trí mỗi 30 phút; bỏ khoá vị trí; không báo con khi xem màn hình",
            Events.ruleDiff(a, a.copy(bonusDay = "d", bonusMin = 20, trackMin = 30, lockLocation = false, showWatching = false)))
    }

    @Test fun ruleDiffCapped() {
        val s = Events.ruleDiff(Rules(), Rules(dailyByDay = listOf(1000, 1000, 1000, 1000, 1000, 1000, 1000), bedtimeOn = true,
            schedules = List(5) { Schedule("s", true, setOf(1), "10:00", "11:00") }, blocked = (1..30).map { "p$it" }.toSet(),
            allow = setOf("a"), pausedUntil = 1, bonusDay = "d", bonusMin = 20, trackMin = 30, lockLocation = false, showWatching = false,
            pinHash = "h", chores = listOf(Chore("c", "C", 10)), zones = listOf(Zone("z", "Z", 1.0, 1.0, 100))))
        assertEquals(160, s.length); assertTrue(s.endsWith("…"))
    }
}
