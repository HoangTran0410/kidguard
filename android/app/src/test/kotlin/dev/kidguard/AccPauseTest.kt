package dev.kidguard

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccPauseTest {
    private val list = setOf("com.VCB", "com.mbmobile")
    private val min = 60_000L

    @Test fun bankInFrontKeepsPaused() {
        assertEquals(0L to false, AccPauseLogic.step("com.VCB", list, 0L, 1_000_000L))
        // came back before 10 min: the count restarts
        assertEquals(0L to false, AccPauseLogic.step("com.mbmobile", list, 1_000_000L - 9 * min, 1_000_000L))
    }

    @Test fun leavingStartsTheCount() {
        assertEquals(500L to false, AccPauseLogic.step("com.zing", list, 0L, 500L))
        assertEquals(500L to false, AccPauseLogic.step(null, list, 0L, 500L))   // unknown front app counts as left
    }

    @Test fun backOnAfterTenMinutesAway() {
        val t0 = 1_000_000L
        assertEquals(t0 to false, AccPauseLogic.step("com.zing", list, t0, t0 + 10 * min - 1))
        assertEquals(0L to true, AccPauseLogic.step("com.zing", list, t0, t0 + 10 * min))
    }

    @Test fun basicModeComesBackAfterOneMinute() {
        val t0 = 1_000_000L
        val b = AccPauseLogic.RESUME_BASIC_MS
        assertEquals(t0 to false, AccPauseLogic.step("com.zing", list, t0, t0 + b - 1, b))
        assertEquals(0L to true, AccPauseLogic.step("com.zing", list, t0, t0 + b, b))
    }

    @Test fun clockWentBackRestartsCount() {
        assertEquals(100L to false, AccPauseLogic.step("com.zing", list, 5_000L, 100L))
    }

    @Test fun rulesDefaultAndRoundTrip() {
        assertEquals(AccPauseLogic.DEFAULT, Rules.fromJson(JSONObject()).accPause)
        assertTrue("com.VCB" in AccPauseLogic.DEFAULT)
        assertEquals(emptySet<String>(), Rules.fromJson(JSONObject("""{"accPause":[]}""")).accPause)
        val r = Rules.fromJson(JSONObject("""{"accPause":["a.b","c.d"]}"""))
        assertEquals(setOf("a.b", "c.d"), Rules.fromJson(r.toJson()).accPause)
    }

    @Test fun resumesForSettingsPagesOnlyWhenPaired() {
        assertTrue(AccPauseLogic.resumeFor("blocked", "com.x", false))
        assertTrue(AccPauseLogic.resumeFor(null, "com.android.settings", true))
        assertFalse(AccPauseLogic.resumeFor(null, "com.android.settings", false))
        assertFalse(AccPauseLogic.resumeFor(null, "com.zing", true))
    }

    // INCIDENT 2026-11-16 (Poco, basic): bank pause countdown ended, the service was re-enabled, yet the parent got
    // "accessibility is off" and it stayed: the same Guard tick that resumed checked Acc.isOn before the service had bound.
    @Test fun noAccOffAlarmWhileTheServiceComesBackAfterAPause() {
        val t = 5_000_000L
        // during the pause: never
        assertFalse(AccPauseLogic.accOffAlarm(true, true, accOn = false, paused = true, resumedAt = 0L, now = t))
        // the tick that resumes (pause gone, service not bound yet) and the seconds after: no alarm
        assertFalse(AccPauseLogic.accOffAlarm(true, true, accOn = false, paused = false, resumedAt = t, now = t))
        assertFalse(AccPauseLogic.accOffAlarm(true, true, accOn = false, paused = false, resumedAt = t, now = t + 20_000L))
        // bound: nothing to say
        assertFalse(AccPauseLogic.accOffAlarm(true, true, accOn = true, paused = false, resumedAt = t, now = t + 40_000L))
        // still off well after the resume: the child really turned it off
        assertTrue(AccPauseLogic.accOffAlarm(true, true, accOn = false, paused = false, resumedAt = t, now = t + AccPauseLogic.SETTLE_MS))
        // no pause ever: off = alarm; not paired / not enforcing: none
        assertTrue(AccPauseLogic.accOffAlarm(true, true, accOn = false, paused = false, resumedAt = 0L, now = t))
        assertFalse(AccPauseLogic.accOffAlarm(true, false, accOn = false, paused = false, resumedAt = 0L, now = t))
    }
}
