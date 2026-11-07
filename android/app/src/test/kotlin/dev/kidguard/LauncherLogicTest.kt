package dev.kidguard

import dev.kidguard.LauncherLogic.Show
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherLogicTest {
    private val self = "dev.kidguard"

    @Test fun blockedNewHiddenAndPlumbingNeverShow() {
        val r = Rules(blocked = setOf("com.tiktok"), hidden = setOf("com.bloat"))
        val want = mapOf("com.tiktok" to "blocked", "com.newgame" to "new", "com.yt" to "appLimit", "com.game" to "daily")
        assertEquals(Show.HIDE, LauncherLogic.show("com.tiktok", want, r, self))
        assertEquals(Show.HIDE, LauncherLogic.show("com.newgame", want, r, self))
        assertEquals(Show.HIDE, LauncherLogic.show("com.bloat", want, r, self))
        assertEquals(Show.HIDE, LauncherLogic.show(self, want, r, self))
        assertEquals(Show.HIDE, LauncherLogic.show("com.google.android.gms", want, r, self))
        assertEquals(Show.HIDE, LauncherLogic.show(TsLogic.PKG, want, r, self))
        // blocked in the rules but the guard has not ticked yet: still hidden
        assertEquals(Show.HIDE, LauncherLogic.show("com.tiktok", emptyMap(), r, self))
        assertEquals(Show.PAUSED, LauncherLogic.show("com.yt", want, r, self))
        assertEquals(Show.PAUSED, LauncherLogic.show("com.game", want, r, self))
        assertEquals(Show.OK, LauncherLogic.show("com.zalo", want, r, self))
    }

    @Test fun settingsOnlyWhenTheRuleAllows() {
        assertEquals(Show.HIDE, LauncherLogic.show("com.android.settings", emptyMap(), Rules(), self))
        assertEquals(Show.OK, LauncherLogic.show("com.android.settings", emptyMap(), Rules(launcher = LauncherRule(on = true, settings = true)), self))
    }

    @Test fun filterUsesDecide() {
        // the same decide() the guard runs: daily limit used up greys every countable app, protected / allowed stay
        val r = Rules(dailyMin = 60, allow = setOf("com.zalo"), blocked = setOf("com.bad"))
        val want = decide(r, mapOf("com.yt" to 61 * 60_000L), 600, 0, "2026-11-07", setOf("com.yt", "com.zalo", "com.bad", "com.dialer"), setOf("com.dialer"))
        assertEquals(Show.PAUSED, LauncherLogic.show("com.yt", want, r, self))
        assertEquals(Show.OK, LauncherLogic.show("com.zalo", want, r, self))
        assertEquals(Show.OK, LauncherLogic.show("com.dialer", want, r, self))
        assertEquals(Show.HIDE, LauncherLogic.show("com.bad", want, r, self))
    }

    @Test fun appLeftTakesTheSmallestLimit() {
        val r = Rules(appMin = mapOf("com.yt" to 45), groups = listOf(AppGroup("g", "Game", setOf("com.yt", "com.mc"), 30, emptyList())))
        val use = mapOf("com.yt" to 10 * 60_000L, "com.mc" to 12 * 60_000L)
        assertEquals(8 * 60_000L, LauncherLogic.appLeftMs(r, use, "com.yt"))
        assertEquals(-1L, LauncherLogic.appLeftMs(r, use, "com.zalo"))
        assertEquals(0L, LauncherLogic.appLeftMs(r, mapOf("com.yt" to 99 * 60_000L), "com.yt"))
    }

    @Test fun reopenTimes() {
        val r = Rules(bedtimeOn = true, bedEnd = "06:30", pausedUntil = 5_000_000,
            groups = listOf(AppGroup("g", "Game", setOf("com.mc"), 0, listOf(Schedule("", true, setOf(5), "17:00", "19:00")))))
        assertEquals(LauncherLogic.Reopen.Tomorrow, LauncherLogic.reopen(r, "appLimit", "com.yt", 600, 5, 0))
        assertEquals(LauncherLogic.Reopen.AtMinute(390), LauncherLogic.reopen(r, "bedtime", "com.yt", 1300, 5, 0))
        assertEquals(LauncherLogic.Reopen.AtMs(5_000_000), LauncherLogic.reopen(r, "paused", "com.yt", 600, 5, 1_000_000))
        assertEquals(LauncherLogic.Reopen.Unknown, LauncherLogic.reopen(r, "paused", "com.yt", 600, 5, 9_000_000))
        assertEquals(LauncherLogic.Reopen.AtMinute(17 * 60), LauncherLogic.reopen(r, "groupHours", "com.mc", 600, 5, 0))
        assertEquals(LauncherLogic.Reopen.Tomorrow, LauncherLogic.reopen(r, "groupHours", "com.mc", 1200, 5, 0))
    }

    @Test fun nextEventToday() {
        val r = Rules(bedtimeOn = true, bedStart = "21:30", schedules = listOf(Schedule("Giờ chơi game", true, setOf(5), "17:00", "19:00")))
        assertEquals(17 * 60 to "Giờ chơi game", LauncherLogic.nextEvent(r, 16 * 60, 5))
        assertEquals(21 * 60 + 30 to "", LauncherLogic.nextEvent(r, 18 * 60, 5))
        assertNull(LauncherLogic.nextEvent(r, 22 * 60, 5))
        assertEquals(21 * 60 + 30 to "", LauncherLogic.nextEvent(r, 16 * 60, 4))
    }

    @Test fun searchIsAccentInsensitive() {
        assertTrue(LauncherLogic.matches("Đồng hồ", "dong"))
        assertTrue(LauncherLogic.matches("Đồng hồ", "đồng hồ"))
        assertTrue(LauncherLogic.matches("Máy tính", "may t"))
        assertTrue(LauncherLogic.matches("YouTube", "tube"))
        assertTrue(LauncherLogic.matches("Anything", "  "))
        assertFalse(LauncherLogic.matches("Zalo", "tik"))
    }

    @Test fun letterAndOrder() {
        assertEquals("Đ", LauncherLogic.letter("Đồng hồ")); assertEquals("A", LauncherLogic.letter("Ảnh")); assertEquals("#", LauncherLogic.letter("1+2"))
        val sorted = listOf("Zalo", "Đồng hồ", "Duolingo", "Ảnh", "1+2", "Gmail", "Docs").sortedBy { LauncherLogic.sortKey(it) }
        assertEquals(listOf("Ảnh", "Docs", "Duolingo", "Đồng hồ", "Gmail", "Zalo", "1+2"), sorted)
    }

    @Test fun crashCounter() {
        val (a, x) = LauncherLogic.crash(emptyList(), 1000); assertEquals(LauncherLogic.CrashAct.NONE, x)
        val (b, y) = LauncherLogic.crash(a, 2000); assertEquals(LauncherLogic.CrashAct.RESET, y)
        val (_, z) = LauncherLogic.crash(b, 3000); assertEquals(LauncherLogic.CrashAct.DISABLE, z)
        // older than 5 min: forgotten
        val (c, w) = LauncherLogic.crash(b, 1999 + LauncherLogic.CRASH_WINDOW_MS); assertEquals(LauncherLogic.CrashAct.RESET, w); assertEquals(2, c.size)
    }

    @Test fun bannerAndErr() {
        assertNull(LauncherLogic.banner(true, true, true, true, true))
        assertEquals("owner", LauncherLogic.banner(false, false, false, false, false))
        assertEquals("location", LauncherLogic.banner(true, true, true, false, false))
        assertEquals("", LauncherLogic.err(false, false, true, false))
        assertEquals("crash", LauncherLogic.err(true, true, true, false))
        assertEquals("home", LauncherLogic.err(true, true, false, false))
        assertEquals("", LauncherLogic.err(true, true, false, true))
    }

    @Test fun ruleJson() {
        val r = LauncherRule(true, true, false, true, "gon")
        assertEquals(r, LauncherRule.fromJson(r.toJson()))
        assertEquals(LauncherRule(), LauncherRule.fromJson(null))
    }

    @Test fun drawerReleaseFlingOrFortyPercent() {
        // from the home: 40 % up opens, less springs back; a fling wins over distance either way
        assertFalse(LauncherLogic.drawerOpens(0f, 500f, 0.39f, false))
        assertTrue(LauncherLogic.drawerOpens(0f, 500f, 0.41f, false))
        assertTrue(LauncherLogic.drawerOpens(-900f, 500f, 0.05f, false))
        assertFalse(LauncherLogic.drawerOpens(900f, 500f, 0.9f, false))
        // pulled down from the open drawer: 40 % down (p < 0.6) closes, a short pull stays open, a flick down closes
        assertTrue(LauncherLogic.drawerOpens(0f, 500f, 0.85f, true))
        assertFalse(LauncherLogic.drawerOpens(0f, 500f, 0.59f, true))
        assertFalse(LauncherLogic.drawerOpens(900f, 500f, 0.95f, true))
        assertTrue(LauncherLogic.drawerOpens(-900f, 500f, 0.3f, true))
        // a slow drift under the fling speed is not a fling
        assertTrue(LauncherLogic.drawerOpens(400f, 500f, 0.7f, true))
    }
}
