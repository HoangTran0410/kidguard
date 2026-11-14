package dev.kidguard

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Timed modes (0.9.22): base rules + an override until a moment, back to the base by itself. */
class ModeTest {
    private val h = 3_600_000L
    private val t0 = 1_800_000_000_000L   // any instant; modes are epoch ms, no timezone in the maths
    private fun over(s: String) = JSONObject(s)
    private fun base() = Rules(rev = 5, dailyMin = 90, bedtimeOn = true, bedStart = "21:00", bedEnd = "06:30", blocked = setOf("game"),
        pinHash = "H", pinSalt = "S", zones = listOf(Zone("z1", "Home", 1.0, 2.0, 100)))
    private fun mode(until: Long, o: String = """{"dailyMin":240,"bedtime":{"on":false,"start":"22:00","end":"07:00"}}""") =
        Mode("Weekend", until, over(o))

    @Test fun activeModeLaysOverBase() {
        val e = base().copy(mode = mode(t0 + 5 * h)).effective(t0)
        assertEquals(240, e.dailyMin); assertFalse(e.bedtimeOn)
        assertEquals(setOf("game"), e.blocked)          // untouched fields stay
        assertEquals(1, e.zones.size)
    }

    @Test fun revertsAtUntilExactly() {
        val r = base().copy(mode = mode(t0 + h))
        assertEquals(240, r.effective(t0 + h - 1).dailyMin)
        assertEquals(90, r.effective(t0 + h).dailyMin)          // now == until: over
        assertNull(r.effective(t0 + h).mode)
        assertEquals(90, r.effective(t0 + 30 * h).dailyMin)
    }

    @Test fun crossesMidnightBecauseUntilIsAbsolute() {
        // "until" is an instant: 23:00 -> 01:00 next day is just +2 h, whatever the zone of the parent or the phone
        val r = base().copy(mode = mode(t0 + 2 * h))
        assertEquals(240, r.effective(t0 + h).dailyMin)
        assertEquals(90, r.effective(t0 + 2 * h + 1).dailyMin)
    }

    @Test fun pinAndRevNeverFromOverride() {
        val evil = """{"pinHash":"X","pinSalt":"Y","pin":"1234","rev":99,"zones":[],"lostOn":true,"safeDns":"evil.example","mode":{"name":"x","until":9999999999999},"dailyMin":10}"""
        val m = Mode.fromJson(JSONObject().put("name", "Trip").put("until", t0 + h).put("rules", over(evil)))!!
        assertEquals(setOf("dailyMin"), m.over.keys().asSequence().toSet())
        val e = base().copy(mode = m).effective(t0)
        assertEquals("H", e.pinHash); assertEquals("S", e.pinSalt); assertEquals(5L, e.rev)
        assertEquals(10, e.dailyMin); assertFalse(e.lostOn); assertEquals("", e.safeDns); assertEquals(1, e.zones.size)
    }

    @Test fun decideUsesEffective() {
        val apps = setOf("yt", "game"); val min = 60_000L
        val r = base().copy(mode = mode(t0 + h, """{"dailyMin":0,"bedtime":{"on":false},"blocked":[]}"""))
        val use = mapOf("yt" to 100 * min)
        assertTrue(decide(r.effective(t0), use, 12 * 60, t0, "2026-11-06", apps, emptySet(), emptySet()).isEmpty())
        val back = decide(r.effective(t0 + 2 * h), use, 12 * 60, t0 + 2 * h, "2026-11-06", apps, emptySet(), emptySet())
        assertEquals("daily", back["yt"]); assertEquals("blocked", back["game"])
    }

    @Test fun roundTripsThroughFullReplaceJson() {
        val r = base().copy(mode = mode(t0 + h))
        val back = Rules.fromJson(JSONObject(r.toJson(persist = true).toString()))
        assertEquals("Weekend", back.mode?.name); assertEquals(t0 + h, back.mode?.until)
        assertEquals(240, back.effective(t0).dailyMin)
        // the parent's PUT of the whole object (watch pass-through) keeps it; without the field it is gone
        assertNull(Rules.fromJson(JSONObject(r.toJson().apply { remove("mode") }.toString())).mode)
        assertNull(base().toJson().opt("mode"))
    }

    @Test fun badModesAreDropped() {
        assertNull(Mode.fromJson(null))
        assertNull(Mode.fromJson(over("""{"name":"","until":5,"rules":{}}""")))
        assertNull(Mode.fromJson(over("""{"name":"A","until":0,"rules":{}}""")))
        assertNotNull(Mode.fromJson(over("""{"name":"A","until":5}""")))
        // an empty override changes nothing
        assertEquals(90, base().copy(mode = Mode("A", t0 + h, JSONObject())).effective(t0).dailyMin)
    }

    @Test fun expiredModeIsNotReapplied() {
        // a page that PUTs a stale copy (mode already over) cannot bring it back: effective ignores it
        val r = base().copy(mode = mode(t0 - 1))
        assertEquals(90, r.effective(t0).dailyMin)
    }
}
