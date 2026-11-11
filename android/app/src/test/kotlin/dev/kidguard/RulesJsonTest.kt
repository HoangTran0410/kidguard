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
        noInstall = true, noDebug = true, pinShot = false, pinHash = "ab".repeat(32), pinSalt = "salt123",
        dailyByDay = listOf(60, 60, 60, 60, 60, 120, 120),
        schedules = listOf(
            Schedule("Học", true, setOf(1, 2, 3, 4, 5), "19:00", "21:00"),
            Schedule("Đêm", false, setOf(6, 7), "23:00", "02:00"),
        ),
        blockNew = true, approved = setOf("com.ok"), lockLocation = false, noAirplane = false,
        safeDns = "family.cloudflare-dns.com", safeDnsPauseUntil = 1_800_000_000_000L, noVpn = true, trackMin = 30, showWatching = false,
        chores = listOf(Chore("c1", "Rửa bát", 20), Chore("c-2", "Dọn phòng", 45)),
        zones = listOf(Zone("z1", "Nhà", 21.03, 105.85, 150), Zone("z2", "Trường", 21.0, 105.8, 2000)),
        hidden = setOf("com.heytap.market", "com.facebook.katana"),
        launcher = LauncherRule(on = true, settings = true, notes = false, lockCustom = true, style = "be", shade = true),
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

    @Test fun safeDnsPauseSwitchesHostOffThenBack() {
        val r = Rules(safeDns = "family.adguard-dns.com", safeDnsPauseUntil = 1_000)
        assertEquals("", r.dnsHost(999)); assertEquals("family.adguard-dns.com", r.dnsHost(1_000)); assertEquals("family.adguard-dns.com", r.dnsHost(5_000))
        assertEquals("family.adguard-dns.com", Rules(safeDns = "family.adguard-dns.com").dnsHost(0))
        assertEquals("", Rules(safeDns = "", safeDnsPauseUntil = 1_000).dnsHost(5_000))
        assertEquals(0L, Rules.fromJson(JSONObject("""{"safeDnsPauseUntil":-5}""")).safeDnsPauseUntil)
    }

    @Test fun badValuesFallBackToDefaults() {
        val r = Rules.fromJson(JSONObject("""{"dailyMin":99999,"trackMin":7,"safeDns":"bad host!","bedtime":{"start":"25:99"}}"""))
        assertEquals(24 * 60, r.dailyMin); assertEquals(5, r.trackMin); assertEquals("", r.safeDns); assertEquals("21:30", r.bedStart)
    }

    @Test fun badChoresAndZonesAreDropped() {
        val r = Rules.fromJson(JSONObject("""{"chores":[{"id":"ok","name":"A","min":5},{"id":"bad id","name":"B","min":20},
            {"id":"c","name":"","min":20},{"id":"d","name":"D","min":4},{"id":"e","name":"E","min":121},{"id":"f","name":"${"x".repeat(41)}","min":20}],
            "zones":[{"id":"z","name":"N","lat":1,"lng":2,"r":50},{"id":"y","name":"N","lat":1,"lng":2,"r":49},{"id":"x","name":"N","lat":91,"lng":2,"r":100},
            {"id":"w","name":"N","lat":1,"lng":2,"r":2001},{"id":"v","name":"N","lat":1,"r":100}]}"""))
        assertEquals(listOf("ok"), r.chores.map { it.id }); assertEquals(listOf("z"), r.zones.map { it.id })
    }

    @Test fun launcherMissingIsOffAndBadStyleDropped() {
        assertEquals(LauncherRule(), Rules.fromJson(JSONObject("{}")).launcher)
        assertEquals(LauncherRule(on = true), Rules.fromJson(JSONObject("""{"launcher":{"on":true,"style":"neon"}}""")).launcher)
    }

    @Test fun choreAndZoneLimits() {
        val c = (1..30).joinToString(",") { """{"id":"c$it","name":"n","min":10}""" }
        val z = (1..15).joinToString(",") { """{"id":"z$it","name":"n","lat":1,"lng":1,"r":100}""" }
        val r = Rules.fromJson(JSONObject("""{"chores":[$c],"zones":[$z]}"""))
        assertEquals(20, r.chores.size); assertEquals(10, r.zones.size)
    }
}
