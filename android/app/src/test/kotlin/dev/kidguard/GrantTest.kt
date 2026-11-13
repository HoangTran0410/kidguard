package dev.kidguard

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GrantTest {
    private val min = 60_000L

    @Test fun freeOrDailyRaisesTodaysBonus() {
        val r = Rules(rev = 5, bonusDay = "2026-11-13", bonusMin = 10)
        for (why in listOf("", "daily")) {
            val g = Grant.apply(r, 1_000, why, 15, "2026-11-13")
            assertEquals(25, g.bonusMin); assertEquals("2026-11-13", g.bonusDay); assertEquals(0L, g.passUntil); assertTrue(g.rev > r.rev)
        }
        // another day's bonus is replaced, not added to
        assertEquals(15, Grant.apply(r, 1_000, "", 15, "2026-11-14").bonusMin)
    }

    @Test fun anythingElseOpensAPass() {
        for (why in listOf("bedtime", "schedule", "paused", "pass", "code")) {
            val g = Grant.apply(Rules(), 10_000, why, 15, "2026-11-13")
            assertEquals(10_000 + 15 * min, g.passUntil); assertEquals(0, g.bonusMin)
        }
        // a pass already open grows
        val g2 = Grant.apply(Rules(passUntil = 50_000 + 30 * min), 50_000, "pass", 15, "d")
        assertEquals(50_000 + 45 * min, g2.passUntil)
        // an expired pass starts from now
        assertEquals(100_000 + 5 * min, Grant.apply(Rules(passUntil = 10), 100_000, "bedtime", 5, "d").passUntil)
    }

    @Test fun endClearsThePass() = assertEquals(0L, Grant.end(Rules(passUntil = 99_999_999), 5).passUntil)

    @Test fun passUntilSurvivesJson() {
        assertEquals(1234L, Rules.fromJson(Rules(passUntil = 1234).toJson()).passUntil)
        assertEquals(0L, Rules.fromJson(JSONObject("{}")).passUntil)
    }

    @Test fun passLiftsEverythingButBlockedAndNew() {
        val apps = setOf("yt", "game"); val prot = emptySet<String>()
        val r = Rules(blocked = setOf("game"), dailyMin = 1, passUntil = 10_000)
        val out = decide(r, mapOf("yt" to 5 * min), 12 * 60, 1_000, "2026-11-06", apps, prot)
        assertEquals(mapOf("game" to "blocked"), out)
        val over = decide(r, mapOf("yt" to 5 * min), 12 * 60, 20_000, "2026-11-06", apps, prot)
        assertEquals("daily", over["yt"])
    }

    @Test fun oneMoreOncePerLockPeriod() {
        val bed = NowWhy("bedtime", 900_000)
        assertTrue(OneMore.allowed(Rules(), bed, ""))
        assertFalse(OneMore.allowed(Rules(), bed, OneMore.key(bed)!!))
        assertTrue(OneMore.allowed(Rules(), NowWhy("bedtime", 999), OneMore.key(bed)!!))   // a new period
        assertFalse(OneMore.allowed(Rules(oneMore = false), bed, ""))
        for (w in listOf("", "paused", "lost", "pass", "code")) assertFalse(OneMore.allowed(Rules(), NowWhy(w, 5), ""))
    }
}
