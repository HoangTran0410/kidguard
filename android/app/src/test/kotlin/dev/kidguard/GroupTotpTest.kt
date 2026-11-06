package dev.kidguard

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupTotpTest {
    private val apps = setOf("yt", "game", "chrome", "dialer")
    private val prot = setOf("dialer")
    private val min = 60_000L
    private fun d(r: Rules, use: Map<String, Long> = emptyMap(), minute: Int = 12 * 60, now: Long = 1000, unlock: Long = 0) =
        decide(r, use, minute, now, "2026-11-06", apps, prot, emptySet(), unlock)   // a Friday (5)
    private val all = setOf(1, 2, 3, 4, 5, 6, 7)
    private fun g(pkgs: Set<String>, m: Int = 0, hours: List<Schedule> = emptyList()) = AppGroup("g1", "Game", pkgs, m, hours)

    @Test fun groupLimitSumsItsApps() {
        val r = Rules(groups = listOf(g(setOf("yt", "game", "dialer"), 60)))
        assertTrue(d(r, mapOf("yt" to 30 * min, "game" to 29 * min)).isEmpty())
        val out = d(r, mapOf("yt" to 30 * min, "game" to 30 * min, "chrome" to 500 * min))
        assertEquals(mapOf("yt" to "groupLimit", "game" to "groupLimit"), out)   // protected dialer untouched
    }

    @Test fun groupHoursOnlyInsideWindow() {
        val r = Rules(groups = listOf(g(setOf("yt"), hours = listOf(Schedule("", true, all, "19:00", "20:00")))))
        assertEquals("groupHours", d(r, minute = 18 * 60 + 59)["yt"])
        assertNull(d(r, minute = 19 * 60)["yt"])
        assertEquals("groupHours", d(r, minute = 20 * 60)["yt"])
        // a weekend-only window is closed on Friday
        val wk = Rules(groups = listOf(g(setOf("game"), hours = listOf(Schedule("", true, setOf(6, 7), "08:00", "22:00")))))
        assertEquals("groupHours", d(wk)["game"])
    }

    @Test fun blockedWinsAndAllowDoesNotExemptGroup() {
        val r = Rules(blocked = setOf("game"), allow = setOf("yt"), groups = listOf(g(setOf("yt", "game"), 10)))
        val out = d(r, mapOf("yt" to 10 * min))
        assertEquals("blocked", out["game"]); assertEquals("groupLimit", out["yt"])
    }

    @Test fun unlockLiftsAllButBlocked() {
        val r = Rules(blocked = setOf("game"), dailyMin = 10, groups = listOf(g(setOf("yt"), 5)))
        val use = mapOf("yt" to 60 * min)
        assertEquals(setOf("yt", "game", "chrome"), d(r, use).keys)
        assertEquals(mapOf("game" to "blocked"), d(r, use, now = 1000, unlock = 2000))
        assertEquals(setOf("yt", "game", "chrome"), d(r, use, now = 3000, unlock = 2000).keys)   // expired
    }

    @Test fun groupsJsonRoundTrip() {
        val r = Rules.fromJson(JSONObject("""{"unlockMin":60,"groups":[
            {"id":"a","name":"Game","pkgs":["com.x.y","bad pkg"],"min":45,"hours":[{"days":[1,2],"start":"19:00","end":"20:00"}]},
            {"id":"b","name":"Empty","pkgs":[]},{"id":"a","name":"Dup","pkgs":["com.z"]}]}"""))
        assertEquals(2, r.groups.size)   // empty "b" kept (no effect), duplicate id dropped
        assertTrue(r.groups[1].pkgs.isEmpty())
        val gr = r.groups[0]
        assertEquals(setOf("com.x.y"), gr.pkgs); assertEquals(45, gr.min); assertEquals(1, gr.hours.size); assertEquals(60, r.unlockMin)
        assertEquals(r, Rules.fromJson(r.toJson()))
        assertEquals(30, Rules.fromJson(JSONObject("""{"unlockMin":7}""")).unlockMin)
    }

    // RFC 6238 appendix B, SHA1 secret "12345678901234567890" (last 6 of the 8-digit values)
    private val rfc = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"

    @Test fun totpRfcVectors() {
        val k = Totp.decode(rfc)!!
        assertEquals("287082", Totp.code(k, 59L / 30))
        assertEquals("081804", Totp.code(k, 1111111109L / 30))
        assertEquals("050471", Totp.code(k, 1111111111L / 30))
        assertEquals("005924", Totp.code(k, 1234567890L / 30))
    }

    @Test fun totpCheckWindowAndReuse() {
        val now = 1111111109_000L
        val step = now / 30_000
        assertEquals(step, Totp.check(rfc, "081804", now, 0))
        assertEquals(step, Totp.check(rfc.lowercase().chunked(4).joinToString(" "), "081804", now + 30_000, 0))   // one step late, spaced lowercase
        assertEquals(-1, Totp.check(rfc, "081804", now + 90_000, 0))   // too old
        assertEquals(-1, Totp.check(rfc, "081804", now, step))         // already used
        assertEquals(-1, Totp.check(rfc, "08180", now, 0))
        assertEquals(-1, Totp.check("AAAA", "081804", now, 0))          // secret too short
        assertNull(Totp.decode("ABC1"))
    }
}
