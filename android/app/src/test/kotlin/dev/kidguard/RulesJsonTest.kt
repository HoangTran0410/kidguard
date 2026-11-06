package dev.kidguard

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RulesJsonTest {
    private val rich = Rules(
        rev = 1_700_000_000_123, dailyMin = 90, appMin = mapOf("com.a" to 30, "com.b" to 5),
        blocked = setOf("com.x", "com.y"), bedtimeOn = true, bedStart = "22:00", bedEnd = "07:15",
        allow = setOf("com.phone"), pausedUntil = 1_700_000_999_000, bonusDay = "2026-11-07", bonusMin = 20,
        noInstall = true, noDebug = true, pinHash = "ab".repeat(32), pinSalt = "salt123",
        dailyByDay = listOf(60, 60, 60, 60, 60, 120, 120),
        schedules = listOf(
            Schedule("Học", true, setOf(1, 2, 3, 4, 5), "19:00", "21:00"),
            Schedule("Đêm", false, setOf(6, 7), "23:00", "02:00"),
        ),
        blockNew = true, approved = setOf("com.ok"), lockLocation = false, noAirplane = false,
        safeDns = "family.cloudflare-dns.com", trackMin = 30, showWatching = false,
    )

    @Test fun roundTripPersist() = assertEquals(rich, Rules.fromJson(rich.toJson(persist = true)))

    @Test fun roundTripThroughString() = assertEquals(rich, Rules.fromJson(JSONObject(rich.toJson(true).toString())))

    @Test fun defaultsRoundTrip() = assertEquals(Rules(), Rules.fromJson(Rules().toJson(true)))

    @Test fun parentCopyHasNoPinHashButPinSet() {
        val j = rich.toJson()
        assertFalse(j.has("pinHash")); assertFalse(j.has("pinSalt"))
        assertEquals(true, j.getBoolean("pinSet"))
    }

    @Test fun unknownFieldsIgnored() {
        val j = rich.toJson(true).put("futureField", 42).put("nested", JSONObject().put("a", 1))
        assertEquals(rich, Rules.fromJson(j))
    }

    @Test fun badValuesFallBackToDefaults() {
        val r = Rules.fromJson(JSONObject("""{"dailyMin":99999,"trackMin":7,"safeDns":"bad host!","bedtime":{"start":"25:99"}}"""))
        assertEquals(24 * 60, r.dailyMin); assertEquals(10, r.trackMin); assertEquals("", r.safeDns); assertEquals("21:30", r.bedStart)
    }
}
