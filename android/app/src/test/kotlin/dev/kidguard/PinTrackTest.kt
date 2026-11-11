package dev.kidguard

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinTrackTest {
    @Test fun pinSetNoHashToParent() {
        val r = Rules.fromJson(JSONObject("""{"pin":"1234"}"""))
        val parent = r.toJson()
        assertTrue(parent.getBoolean("pinSet")); assertFalse(parent.has("pinHash")); assertFalse(parent.has("pinSalt"))
        assertTrue(r.toJson(true).has("pinHash"))
    }

    @Test fun saltedVerify() {
        val r = Rules.fromJson(JSONObject("""{"pin":"1234"}"""))
        assertTrue(r.pinSalt.isNotEmpty())
        assertTrue(r.checkPin("1234")); assertFalse(r.checkPin("1235"))
        assertEquals(r, Rules.fromJson(JSONObject(r.toJson(true).toString())))
        assertTrue(Rules.fromJson(JSONObject("""{"pin":"1234"}""")).pinHash != r.pinHash)   // new salt each time
    }

    @Test fun legacyHashStillVerifies() {
        val legacy = Rules.sha256("kg:4321")
        val r = Rules.fromJson(JSONObject("""{"pinHash":"$legacy"}"""))
        assertEquals("", r.pinSalt)
        assertTrue(r.checkPin("4321")); assertFalse(r.checkPin("0000"))
    }

    @Test fun noPinNeverMatches() = assertFalse(Rules().checkPin(""))

    @Test fun dailyByDayClamped() {
        val r = Rules.fromJson(JSONObject("""{"dailyByDay":[10,-5,99999,0,60,60,60]}"""))
        assertEquals(listOf(10, 0, 1440, 0, 60, 60, 60), r.dailyByDay)
        assertTrue(Rules.fromJson(JSONObject("""{"dailyByDay":[1,2]}""")).dailyByDay.isEmpty())
    }

    @Test fun trackMin() {
        assertEquals(5, Rules.fromJson(JSONObject("{}")).trackMin)
        assertEquals(0, Rules.fromJson(JSONObject("""{"trackMin":0}""")).trackMin)
        assertEquals(30, Rules.fromJson(JSONObject("""{"trackMin":30}""")).trackMin)
        assertEquals(1, Rules.fromJson(JSONObject("""{"trackMin":1}""")).trackMin)
        assertEquals(5, Rules.fromJson(JSONObject("""{"trackMin":7}""")).trackMin)
    }

    @Test fun trackFilter() {
        val t = 1_000_000L
        assertTrue(Track.shouldStore(null, 0.0, 0.0, t, 10.0, 106.0))
        assertFalse(Track.shouldStore(t, 10.0, 106.0, t + 600_000, 10.0001, 106.0))      // ~11 m, 10 min
        assertTrue(Track.shouldStore(t, 10.0, 106.0, t + 600_000, 10.001, 106.0))        // ~111 m
        assertTrue(Track.shouldStore(t, 10.0, 106.0, t + 3_600_000, 10.0, 106.0))        // 60 min, same spot
        assertTrue(Track.shouldStore(t, 10.0, 106.0, t + 600_000, 10.0, 106.0, Track.gapFor(10)))    // 10-min step, same spot: kept
        assertFalse(Track.shouldStore(t, 10.0, 106.0, t + 240_000, 10.0, 106.0, Track.gapFor(10)))   // a quicker re-fix: not kept
        assertEquals(3_510_000L, Track.gapFor(60)); assertEquals(600_000L, Track.gapFor(5)); assertEquals(600_000L, Track.gapFor(1))   // still: >= 10 min apart
        // GPS jitter: 45 m apart with ±50 m fixes is not a move; 80 m is; a ±300 m tower fix never is
        assertFalse(Track.moved(10.0, 106.0, 50, 10.0004, 106.0, 20)); assertTrue(Track.moved(10.0, 106.0, 50, 10.00072, 106.0, 20))
        assertFalse(Track.moved(10.0, 106.0, 10, 10.01, 106.0, 300)); assertTrue(Track.moved(10.0, 106.0, 0, 10.0003, 106.0, 0))
        assertEquals(111_195.0, Track.meters(10.0, 106.0, 11.0, 106.0), 200.0)
    }
}
