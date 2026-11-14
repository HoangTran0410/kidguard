package dev.kidguard

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NowWhyTest {
    private val apps = setOf("yt", "game"); private val prot = emptySet<String>()
    private val min = 60_000L
    // 2026-11-06 Fri, 11-07 Sat, 11-08 Sun, 11-09 Mon
    private val day0 = 1_700_000_000_000L - 1_700_000_000_000L % (24 * 60 * min)   // a UTC midnight; only the offsets matter
    private fun nw(r: Rules, minute: Int, today: String = "2026-11-06", use: Map<String, Long> = emptyMap(), unlock: Long = 0, now: Long = day0 + minute * min) =
        nowWhy(r, minute, now, today, use, apps, prot, unlock)

    @Test fun freeByDefault() = assertEquals(NowWhy("", 0), nw(Rules(), 12 * 60))

    @Test fun bedtimeEndsAtBedEnd() {
        val r = Rules(bedtimeOn = true, bedStart = "21:30", bedEnd = "06:30")
        val a = nw(r, 22 * 60)   // 22:00 -> 06:30 next day = 8.5 h
        assertEquals("bedtime", a.why); assertEquals(day0 + 22 * 60 * min + 510 * min, a.until)
        val b = nw(r, 5 * 60)    // 05:00 -> 06:30 = 1.5 h
        assertEquals(day0 + 5 * 60 * min + 90 * min, b.until)
        assertEquals("", nw(r, 12 * 60).why)
    }

    @Test fun scheduleHasNameAndEnd() {
        val r = Rules(schedules = listOf(Schedule("Học", true, (1..7).toSet(), "13:00", "15:00")))
        val a = nw(r, 14 * 60)
        assertEquals("schedule", a.why); assertEquals("Học", a.name); assertEquals(day0 + 15 * 60 * min, a.until)
    }

    @Test fun pausedUntil() {
        val now = day0 + 600 * min
        val a = nw(Rules(pausedUntil = now + 20 * min), 600, now = now)
        assertEquals("paused", a.why); assertEquals(now + 20 * min, a.until)
        assertEquals("", nw(Rules(pausedUntil = now - 1), 600, now = now).why)
    }

    @Test fun dailyUsedUp() {
        val r = Rules(dailyMin = 30)
        assertEquals("daily", nw(r, 12 * 60, use = mapOf("yt" to 31 * min)).why)
        assertEquals("", nw(r, 12 * 60, use = mapOf("yt" to 10 * min)).why)
        assertEquals("", nw(r.copy(bonusDay = "2026-11-06", bonusMin = 30), 12 * 60, use = mapOf("yt" to 31 * min)).why)
    }

    @Test fun passAndCodeWinOverLocksButNotLost() {
        val now = day0 + 22 * 60 * min
        val r = Rules(bedtimeOn = true, passUntil = now + 15 * min)
        val a = nw(r, 22 * 60, now = now)
        assertEquals("pass", a.why); assertEquals(now + 15 * min, a.until)
        assertEquals("code", nw(Rules(bedtimeOn = true), 22 * 60, now = now, unlock = now + 30 * min).why)
        assertEquals("lost", nw(r.copy(lostOn = true), 22 * 60, now = now).why)
    }

    @Test fun priorityPausedScheduleBedtimeDaily() {
        val now = day0 + 22 * 60 * min
        val r = Rules(bedtimeOn = true, dailyMin = 1, schedules = listOf(Schedule("", true, (1..7).toSet(), "21:00", "23:00")), pausedUntil = now + min)
        assertEquals("paused", nw(r, 22 * 60, now = now, use = mapOf("yt" to 99 * min)).why)
        assertEquals("schedule", nw(r.copy(pausedUntil = 0), 22 * 60, now = now, use = mapOf("yt" to 99 * min)).why)
        assertEquals("bedtime", nw(r.copy(pausedUntil = 0, schedules = emptyList()), 22 * 60, now = now, use = mapOf("yt" to 99 * min)).why)
    }

    // ---- C6 bedtime per night ----
    private val byDay = listOf("21:00-07:00", "21:00-07:00", "21:00-07:00", "21:00-07:00", "23:00-09:00", "23:00-09:00", "")   // Mon..Sun nights; Sunday night: none

    @Test fun earlyMorningBelongsToThePreviousNight() {
        val r = Rules(bedtimeOn = true, bedByDay = byDay)
        // Saturday 2026-11-07 at 08:00: Friday night (23:00-09:00) is still on
        assertEquals(9 * 60, bedEndNow(r, 8 * 60, "2026-11-07"))
        // Saturday 10:00: over
        assertNull(bedEndNow(r, 10 * 60, "2026-11-07"))
        // Saturday 23:30: Saturday's own night starts
        assertEquals(9 * 60, bedEndNow(r, 23 * 60 + 30, "2026-11-07"))
        // Saturday 21:30 is NOT bedtime on a weekend night (starts 23:00)
        assertNull(bedEndNow(r, 21 * 60 + 30, "2026-11-07"))
        // Monday 2026-11-09 06:00: Sunday night has no bedtime
        assertNull(bedEndNow(r, 6 * 60, "2026-11-09"))
        // Monday 22:00 on
        assertEquals(7 * 60, bedEndNow(r, 22 * 60, "2026-11-09"))
        // Sunday 2026-11-08 02:00 belongs to Saturday night
        assertEquals(9 * 60, bedEndNow(r, 2 * 60, "2026-11-08"))
    }

    @Test fun decideAndNowWhyFollowTheNight() {
        val r = Rules(bedtimeOn = true, bedByDay = byDay)
        assertEquals("bedtime", decide(r, emptyMap(), 8 * 60, 1, "2026-11-07", apps, prot)["yt"])
        assertTrue(decide(r, emptyMap(), 6 * 60, 1, "2026-11-09", apps, prot).isEmpty())
        val n = nw(r, 8 * 60, "2026-11-07")
        assertEquals("bedtime", n.why); assertEquals(day0 + 9 * 60 * min, n.until)
    }

    @Test fun bedByDayJsonIsAllowListed() {
        assertEquals(byDay, Rules.fromJson(Rules(bedByDay = byDay).toJson()).bedByDay)
        assertTrue(Rules.fromJson(JSONObject("""{"bedByDay":["21:00-07:00"]}""")).bedByDay.isEmpty())   // not 7 entries
        assertTrue(Rules.fromJson(JSONObject("""{"bedByDay":["a","","","","","",""]}""")).bedByDay.isEmpty())   // bad entry
        assertEquals(true, Rules.fromJson(JSONObject("{}")).oneMore)
        assertEquals(false, Rules.fromJson(Rules(oneMore = false).toJson()).oneMore)
    }

    @Test fun lostFieldsRoundTripAndAreClamped() {
        val r = Rules.fromJson(Rules(lostOn = true, lostMsg = "x".repeat(300), lostPhone = "0901 234 567").toJson())
        assertTrue(r.lostOn); assertEquals(200, r.lostMsg.length); assertEquals("0901 234 567", r.lostPhone)
        assertTrue(r.lostLock); assertTrue(r.lostRing)   // missing = on (0.9.21)
        val off = Rules.fromJson(Rules(lostOn = true, lostLock = false, lostRing = false).toJson())
        assertFalse(off.lostLock); assertFalse(off.lostRing)
    }

    @Test fun quickMessagesAreCleanedAndCapped() {
        val r = Rules.fromJson(Rules(msgs = listOf("Về ăn cơm", "", "Về ăn cơm", "y".repeat(200)) + List(20) { "m$it" }).toJson())
        assertEquals("Về ăn cơm", r.msgs[0]); assertEquals(120, r.msgs[1].length); assertEquals(Rules.MSGS_MAX, r.msgs.size)
        assertTrue(Rules.fromJson(Rules().toJson()).msgs.isEmpty())
    }

    @Test fun importantEventKinds() {
        for (k in listOf("sos", "tamper", "lost", "request")) assertTrue(Events.important(k))
        for (k in listOf("sim", "zoneIn", "battery", "unlock", "released")) assertTrue(!Events.important(k))
    }

    @Test fun simStep() {
        assertEquals("" to false, SimLogic.step("", ""))
        assertEquals("ready:45204:1" to false, SimLogic.step("", "ready:45204:1"))   // first sight: recorded only
        assertEquals("ready:45204:1" to false, SimLogic.step("ready:45204:1", ""))   // unknown (booting): keep
        assertEquals("absent" to true, SimLogic.step("ready:45204:1", "absent"))
        assertEquals("ready:45201:2" to true, SimLogic.step("absent", "ready:45201:2"))
        assertEquals("", SimLogic.sig(5, "45204", 1, false))
        assertEquals("ready:45204:1", SimLogic.sig(5, "452-04", 1, true))
        assertEquals("", SimLogic.sig(2, "", 0, true))   // PIN required
    }

    @Test fun lostLockText() {
        assertEquals("Máy mất · 0901", Notes.lostText("Máy mất", "0901"))
        assertEquals("0901", Notes.lostText("", "0901"))
        assertEquals("", Notes.lostText("", ""))
        assertTrue(SupportMsg.canAsk("bedtime", true)); assertTrue(!SupportMsg.canAsk("paused", true)); assertTrue(!SupportMsg.canAsk("daily", false))
    }
}
