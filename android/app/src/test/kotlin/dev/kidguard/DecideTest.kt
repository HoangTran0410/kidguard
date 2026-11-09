package dev.kidguard

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DecideTest {
    private val apps = setOf("yt", "game", "chrome", "dialer")
    private val prot = setOf("dialer")
    private val min = 60_000L
    private fun d(r: Rules, use: Map<String, Long> = emptyMap(), minute: Int = 12 * 60, now: Long = 1000, today: String = "2026-11-06",
                  news: Set<String> = emptySet()) =
        decide(r, use, minute, now, today, apps, prot, news)
    // 2026-11-06 is a Friday; 11-07 Saturday; 11-08 Sunday; 11-09 Monday

    @Test fun monitorComputesButEnforcesNothing() {
        val r = Rules(blocked = setOf("game"), dailyMin = 30, monitor = true)
        val would = d(r, mapOf("yt" to 40 * min))
        assertEquals("blocked", would["game"]); assertEquals("daily", would["yt"])
        assertTrue(enforced(r, would).isEmpty())
        assertEquals(would, enforced(r.copy(monitor = false), would))
        assertTrue(Rules.fromJson(r.toJson()).monitor)
        assertFalse(Rules.fromJson(JSONObject("{}")).monitor)
    }

    @Test fun nothingByDefault() = assertTrue(d(Rules()).isEmpty())

    @Test fun blockedListed() = assertEquals(mapOf("game" to "blocked"), d(Rules(blocked = setOf("game", "dialer"))))

    @Test fun perAppLimit() {
        val r = Rules(appMin = mapOf("yt" to 30))
        assertTrue(d(r, mapOf("yt" to 29 * min)).isEmpty())
        assertEquals("appLimit", d(r, mapOf("yt" to 30 * min))["yt"])
    }

    @Test fun dailyLimitSpareAllowedAndProtected() {
        val r = Rules(dailyMin = 60, allow = setOf("chrome"))
        val out = d(r, mapOf("yt" to 40 * min, "game" to 20 * min, "chrome" to 300 * min, "dialer" to 300 * min))
        assertEquals(setOf("yt", "game"), out.keys)
        assertTrue(out.values.all { it == "daily" })
        assertTrue(d(r, mapOf("yt" to 40 * min, "chrome" to 300 * min)).isEmpty())   // allowed app doesn't count
    }

    @Test fun bonusOnlyToday() {
        val r = Rules(dailyMin = 60, bonusDay = "2026-11-06", bonusMin = 30)
        assertTrue(d(r, mapOf("yt" to 70 * min)).isEmpty())
        assertEquals(3, d(r.copy(bonusDay = "2026-11-05"), mapOf("yt" to 70 * min)).size)
    }

    @Test fun bedtimeAcrossMidnight() {
        val r = Rules(bedtimeOn = true, bedStart = "21:30", bedEnd = "06:30")
        assertEquals(3, d(r, minute = 23 * 60).size)
        assertEquals(3, d(r, minute = 5 * 60).size)
        assertTrue(d(r, minute = 7 * 60).isEmpty())
        assertTrue(d(r, minute = 21 * 60 + 29).isEmpty())
        assertFalse("dialer" in d(r, minute = 23 * 60))
    }

    @Test fun pause() {
        assertEquals("paused", d(Rules(pausedUntil = 2000), now = 1000)["yt"])
        assertTrue(d(Rules(pausedUntil = 500), now = 1000).isEmpty())
    }

    @Test fun jsonRoundTrip() {
        val r = Rules(rev = 5, dailyMin = 90, appMin = mapOf("yt" to 20), blocked = setOf("game"), bedtimeOn = true,
            allow = setOf("chrome"), pausedUntil = 9, bonusDay = "2026-11-06", bonusMin = 15, noInstall = true, pinHash = "x", showWatching = false)
        assertEquals(r, Rules.fromJson(JSONObject(r.toJson(true).toString())))
    }

    @Test fun badTimeFallsBack() = assertEquals("21:30", Rules.fromJson(JSONObject("""{"bedtime":{"start":"25:99"}}""")).bedStart)

    private val fri = "2026-11-06"; private val sat = "2026-11-07"; private val sun = "2026-11-08"; private val mon = "2026-11-09"
    private fun sch(days: Set<Int>, start: String, end: String, on: Boolean = true) = Schedule("s", on, days, start, end)

    @Test fun isoDow() {
        assertEquals(5, Rules.isoDow(fri)); assertEquals(6, Rules.isoDow(sat)); assertEquals(7, Rules.isoDow(sun)); assertEquals(1, Rules.isoDow(mon))
        assertEquals(4, Rules.isoDow("2024-02-29"))
    }

    @Test fun scheduleByWeekday() {
        val r = Rules(schedules = listOf(sch(setOf(1, 2, 3, 4, 5), "07:00", "11:30")))
        assertEquals("schedule", d(r, minute = 8 * 60, today = fri)["yt"])
        assertTrue(d(r, minute = 8 * 60, today = sat).isEmpty())
        assertTrue(d(r, minute = 12 * 60, today = fri).isEmpty())
        assertFalse("dialer" in d(r, minute = 8 * 60, today = fri))
        assertTrue(d(Rules(schedules = listOf(sch(setOf(5), "07:00", "11:30", on = false))), minute = 8 * 60, today = fri).isEmpty())
    }

    @Test fun scheduleCrossingMidnightBelongsToStartDay() {
        val r = Rules(schedules = listOf(sch(setOf(5), "22:00", "02:00")))   // Friday only
        assertEquals("schedule", d(r, minute = 23 * 60, today = fri)["yt"])
        assertEquals("schedule", d(r, minute = 60, today = sat)["yt"])       // Sat 01:00 is Friday's tail
        assertTrue(d(r, minute = 60, today = sun).isEmpty())                  // Sun 01:00: Saturday not in days
        assertTrue(d(r, minute = 23 * 60, today = sat).isEmpty())
        assertTrue(d(r, minute = 60, today = fri).isEmpty())                  // Fri 01:00 is Thursday's tail
        val sun2 = Rules(schedules = listOf(sch(setOf(7), "22:00", "02:00")))
        assertEquals("schedule", d(sun2, minute = 60, today = mon)["yt"])     // Sunday -> Monday wraps
    }

    @Test fun dailyByDay() {
        val r = Rules(dailyMin = 30, dailyByDay = listOf(60, 60, 60, 60, 60, 120, 0))
        val use = mapOf("yt" to 90 * min)
        assertEquals("daily", d(r, use, today = fri)["yt"])
        assertTrue(d(r, use, today = sat).isEmpty())          // 120 on Saturday
        assertTrue(d(r, mapOf("yt" to 900 * min), today = sun).isEmpty())   // 0 = no limit that day
        assertEquals(0L, dailyLeftMs(r, use, fri, apps, prot))
        assertEquals(-1, dailyLeftMs(r, use, sun, apps, prot))
        assertEquals(30 * min, dailyLeftMs(r, use, sat, apps, prot))
    }

    @Test fun dailyByDayValidation() {
        assertTrue(Rules.fromJson(JSONObject("""{"dailyByDay":[1,2,3]}""")).dailyByDay.isEmpty())
        assertEquals(1440, Rules.fromJson(JSONObject("""{"dailyByDay":[1,2,3,4,5,6,1441]}""")).dailyByDay[6])   // clamped, not discarded
        assertEquals(7, Rules.fromJson(JSONObject("""{"dailyByDay":[1,2,3,4,5,6,1440]}""")).dailyByDay.size)
    }

    @Test fun scheduleValidation() {
        val r = Rules.fromJson(JSONObject("""{"schedules":[{"days":[0,3,9],"start":"07:00","end":"08:00"},{"days":[1],"start":"7","end":"8"},{"days":[],"start":"07:00","end":"08:00"}]}"""))
        assertEquals(listOf(Schedule("", true, setOf(3), "07:00", "08:00")), r.schedules)
    }

    @Test fun newAppsBlockedUntilApproved() {
        val news = setOf("game", "chrome", "dialer")
        assertTrue(d(Rules(), news = news).isEmpty())                                  // blockNew off
        val r = Rules(blockNew = true, allow = setOf("chrome"))
        assertEquals(mapOf("game" to "new"), d(r, news = news))                         // allow + protected spared
        assertTrue(d(r.copy(approved = setOf("game")), news = news).isEmpty())
    }

    @Test fun priority() {
        // blocked > appLimit > new > paused > schedule > bedtime > daily
        val r = Rules(blocked = setOf("yt"), appMin = mapOf("yt" to 1, "game" to 1), blockNew = true, pausedUntil = 2000,
            schedules = listOf(sch(setOf(5), "00:00", "23:59")), bedtimeOn = true, dailyMin = 1)
        val use = mapOf("yt" to 5 * min, "game" to 5 * min, "chrome" to 5 * min)
        val out = d(r, use, now = 1000, news = setOf("game", "chrome"))
        assertEquals("blocked", out["yt"]); assertEquals("appLimit", out["game"]); assertEquals("new", out["chrome"])
        val r2 = r.copy(blocked = emptySet(), appMin = emptyMap(), blockNew = false)
        assertEquals("paused", d(r2, use, now = 1000)["yt"])
        val r3 = r2.copy(pausedUntil = 0)
        assertEquals("schedule", d(r3, use, minute = 60)["yt"])
        assertEquals("bedtime", d(r3.copy(schedules = emptyList()), use, minute = 23 * 60)["yt"])
        assertEquals("daily", d(r3.copy(schedules = emptyList(), bedtimeOn = false), use)["yt"])
    }

    @Test fun newFieldsRoundTrip() {
        val r = Rules(dailyByDay = listOf(1, 2, 3, 4, 5, 6, 7), schedules = listOf(sch(setOf(1, 2), "07:00", "08:00")), blockNew = true,
            approved = setOf("a"), lockLocation = false, noAirplane = false, safeDns = "family.adguard-dns.com")
        assertEquals(r, Rules.fromJson(JSONObject(r.toJson(true).toString())))
        val dflt = Rules.fromJson(JSONObject("{}"))
        assertTrue(dflt.lockLocation && dflt.noAirplane && !dflt.blockNew)
    }
}
