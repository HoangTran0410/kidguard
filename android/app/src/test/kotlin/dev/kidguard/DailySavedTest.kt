package dev.kidguard

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class DailySavedTest {
    @Test fun pausedDailyLimitRoundTripsAndCountsAsNoLimit() {
        val r = Rules.fromJson(JSONObject("""{"dailyMin":0,"dailyByDay":[],"dailySaved":{"min":120,"byDay":[60,60,60,60,60,120,120]}}"""))
        assertEquals(120, r.dailySavedMin)
        assertEquals(7, r.dailySavedBy.size)
        assertEquals(0, baseLimitMin(r, "2026-11-19"))
        val back = Rules.fromJson(JSONObject(r.toJson().toString()))
        assertEquals(r.dailySavedMin, back.dailySavedMin); assertEquals(r.dailySavedBy, back.dailySavedBy)
        assertEquals(-1, Rules.fromJson(JSONObject("{}")).dailySavedMin)
    }
}
