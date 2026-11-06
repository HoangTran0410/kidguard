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
    private fun d(r: Rules, use: Map<String, Long> = emptyMap(), minute: Int = 12 * 60, now: Long = 1000, today: String = "2026-11-06") =
        decide(r, use, minute, now, today, apps, prot)

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
            allow = setOf("chrome"), pausedUntil = 9, bonusDay = "2026-11-06", bonusMin = 15, noInstall = true, pinHash = "x")
        assertEquals(r, Rules.fromJson(JSONObject(r.toJson().toString())))
    }

    @Test fun badTimeFallsBack() = assertEquals("21:30", Rules.fromJson(JSONObject("""{"bedtime":{"start":"25:99"}}""")).bedStart)
}
