package dev.kidguard

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TgLogicTest {
    private val now = 1_800_000_000_000L

    @Test fun parse() {
        assertEquals(TgLogic.Cmd("limit", listOf("90"), "90"), TgLogic.parse("/limit@KidBot 90"))
        assertEquals(TgLogic.Cmd("msg", listOf("về", "ăn", "cơm"), "về ăn cơm"), TgLogic.parse("/msg về ăn cơm"))
        assertEquals(TgLogic.Cmd("status", emptyList(), ""), TgLogic.parse(" /STATUS "))
        assertNull(TgLogic.parse("hello"))
        assertNull(TgLogic.parse("/"))
        assertNull(TgLogic.parse("/rm -rf"[0] + "a.b"))
    }

    @Test fun minutes() {
        assertEquals(30, TgLogic.minutes("30", 1, 600))
        assertEquals(30, TgLogic.minutes("30m", 1, 600))
        assertEquals(120, TgLogic.minutes("2h", 1, 600))
        assertEquals(90, TgLogic.minutes("1h30", 1, 600))
        assertEquals(0, TgLogic.minutes("0", 0, 600))
        assertNull(TgLogic.minutes("0", 1, 600))
        assertNull(TgLogic.minutes("abc", 1, 600))
        assertNull(TgLogic.minutes("", 1, 600))
        assertNull(TgLogic.minutes("999", 1, 600))
    }

    @Test fun token() {
        assertTrue(TgLogic.tokenOk("123456789:AAHdqTcvCH1vGWJxfSeofSAs0K5PALDsaw"))
        assertFalse(TgLogic.tokenOk("123456789:short"))
        assertFalse(TgLogic.tokenOk("abc:AAHdqTcvCH1vGWJxfSeofSAs0K5PALDsaw"))
    }

    @Test fun code() {
        assertTrue(TgLogic.codeOk(" 123456 ", "123456", now + 1, now))
        assertFalse(TgLogic.codeOk("123456", "123456", now, now))
        assertFalse(TgLogic.codeOk("123457", "123456", now + 1, now))
        assertFalse(TgLogic.codeOk("", "", now + 1, now))
    }

    @Test fun ruleEditsBumpRev() {
        val r = Rules(rev = now + 5, bonusDay = "2026-11-09", bonusMin = 20, dailyByDay = List(7) { 60 })
        val b = TgLogic.bonus(r, "2026-11-10", 30, now)
        assertEquals(30, b.bonusMin); assertEquals("2026-11-10", b.bonusDay); assertTrue(b.rev > r.rev)
        assertEquals(60, TgLogic.bonus(b, "2026-11-10", 30, now).bonusMin)
        assertEquals(List(7) { 90 }, TgLogic.limit(r, 90, now).dailyByDay)
        assertEquals(emptyList<Int>(), TgLogic.limit(Rules(), 90, now).dailyByDay)
        assertEquals(90, TgLogic.limit(Rules(), 90, now).dailyMin)
        assertEquals(now + 60 * 60_000L, TgLogic.pause(Rules(), 60, now).pausedUntil)
        assertEquals(0, TgLogic.resume(TgLogic.pause(Rules(), 60, now), now).pausedUntil)
        assertEquals(setOf("a.b"), TgLogic.block(Rules(), "a.b", true, now).blocked)
        assertEquals(emptySet<String>(), TgLogic.block(Rules(blocked = setOf("a.b")), "a.b", false, now).blocked)
    }

    private fun st() = JSONObject().put("usedMs", 65 * 60_000L).put("limitMin", 120).put("leftMs", 55 * 60_000L).put("battery", 80)
        .put("charging", true).put("screenOn", true).put("current", "YouTube")
        .put("net", JSONObject().put("type", "wifi").put("ssid", "Nha"))
        .put("rules", JSONObject().put("pausedUntil", 0))
        .put("requests", JSONArray().put(JSONObject()))
        .put("apps", JSONArray()
            .put(JSONObject().put("pkg", "y.t").put("label", "YouTube").put("ms", 3_900_000).put("blocked", JSONObject.NULL))
            .put(JSONObject().put("pkg", "p.h").put("label", "Phone").put("ms", 30_000).put("protected", true).put("blocked", JSONObject.NULL))
            .put(JSONObject().put("pkg", "g.m").put("label", "Game").put("ms", 120_000).put("blocked", "app")))

    @Test fun statusText() {
        val t = TgLogic.status(st(), "Bin", "vi", now) { "10:00" }
        assertTrue(t, t.contains("Bin") && t.contains("1 giờ 5 phút") && t.contains("còn 55 phút") && t.contains("80% ⚡") && t.contains("Wi-Fi Nha") && t.contains("1 lời xin"))
        val e = TgLogic.status(st(), "Bin", "en", now) { "10:00" }
        assertTrue(e, e.contains("1h 5m") && e.contains("55m left"))
    }

    @Test fun appsAndPicker() {
        val a = TgLogic.apps(st(), "en")
        assertTrue(a, a.contains("YouTube – 1h 5m") && a.contains("Game – 2m ⛔") && !a.contains("Phone"))
        assertEquals(listOf("y.t" to "YouTube", "g.m" to "Game"), TgLogic.pickApps(st(), emptySet(), false))
        assertEquals(listOf("y.t" to "YouTube"), TgLogic.pickApps(st(), setOf("g.m"), false))
        assertEquals(listOf("g.m" to "Game", "x.z" to "x.z"), TgLogic.pickApps(st(), setOf("x.z", "g.m"), true))
    }

    @Test fun keyboardAndSeen() {
        val k = TgLogic.keyboard(listOf(listOf("A" to "b:a", "B" to "b:" + "x".repeat(80))))
        assertEquals(64, k.getJSONArray("inline_keyboard").getJSONArray(0).getJSONObject(1).getString("callback_data").length)
        assertEquals(listOf("b", "c", "d"), TgLogic.remember(listOf("a", "b", "c"), listOf("c", "d"), 3))
    }

    @Test fun textsBothLanguages() {
        for (l in listOf("vi", "en")) {
            assertTrue(TgLogic.s(l, "help", "Bin").contains("/status"))
            assertEquals(if (l == "vi") "Đã cho thêm 30 phút hôm nay (tổng thêm 50 phút)." else "30 more minutes today (50 extra in total).", TgLogic.s(l, "bonusDone", 30, 50))
        }
        assertEquals("cần chế độ Device Owner", TgLogic.err("vi", "not owner"))
        assertEquals("weird", TgLogic.err("en", "weird"))
    }

    private fun datas(k: org.json.JSONObject) = k.getJSONArray("inline_keyboard").let { rows ->
        (0 until rows.length()).flatMap { i -> rows.getJSONArray(i).let { r -> (0 until r.length()).map { r.getJSONObject(it).getString("callback_data") } } } }

    @Test fun menuButtonsAllRun() {
        for (l in listOf("vi", "en")) {
            val all = datas(TgLogic.menu(l)) + listOf("bonus", "pause", "limit", "bed", "rec").flatMap { datas(TgLogic.sub(l, it)!!) } + datas(TgLogic.statusKb(l))
            for (d in all) when {
                d.startsWith("c:") -> assertTrue(d, TgLogic.fromData(d) != null)
                d.startsWith("n:") -> assertTrue(d, d == "n:main" || TgLogic.sub(l, d.removePrefix("n:")) != null)
                else -> assertEquals("r:status", d)
            }
            assertTrue(all.all { it.toByteArray().size <= 64 })
            val texts = TgLogic.menu(l).toString() + TgLogic.statusKb(l) + TgLogic.replyKb(l) + listOf("bonus", "pause", "limit", "bed", "rec").map { TgLogic.sub(l, it) }
            assertFalse(texts, Regex("\"(b|kbPh)\\.?[a-zA-Z]*\"").containsMatchIn(texts.replace("\"callback_data\"", "")) && texts.contains("\"b."))
            for (k in listOf("menu", "subBonus", "subPause", "subRec", "msgAsk", "botShort", "botDesc")) assertFalse(k, TgLogic.s(l, k, "x") == k)
        }
        assertEquals(TgLogic.Cmd("bonus", listOf("30"), "30"), TgLogic.fromData("c:bonus 30"))
        assertEquals(TgLogic.Cmd("limit", listOf("0"), "0"), TgLogic.fromData("c:limit 0"))
        assertNull(TgLogic.sub("vi", "nope"))
    }

    @Test fun replyKeyboard() {
        assertEquals("status", TgLogic.fromButton("📊 Tình hình")!!.name)
        assertEquals("menu", TgLogic.fromButton("📋 Menu")!!.name)
        assertEquals("locate", TgLogic.fromButton(" 📍 Location ")!!.name)
        assertNull(TgLogic.fromButton("hello"))
        assertTrue(TgLogic.replyKb("vi").getBoolean("is_persistent"))
    }

    @Test fun fmtDropsZero() {
        assertEquals("2 giờ", TgLogic.fmt(120 * 60_000L, "vi")); assertEquals("1h 30m", TgLogic.fmt(90 * 60_000L, "en"))
        assertEquals("15 phút", TgLogic.fmt(15 * 60_000L, "vi"))
    }

    @Test fun languages() {
        assertEquals("vi", TgLogic.langOf("vi-VN", "en")); assertEquals("en", TgLogic.langOf("fr", "vi"))
        assertEquals("vi", TgLogic.langOf("", "vi")); assertEquals("en", TgLogic.langOf(null, "en"))
        assertEquals("vi", TgLogic.langOf("vn", "en"))
        for (l in listOf("vi", "en")) for (k in listOf("busyOne", "newLink", "locOld", "e.upload", "e.gone", "e.no audio")) assertFalse(k, TgLogic.s(l, k, "x", 1) == k)
        assertTrue(TgLogic.s("en", "newLink", "Mom").contains("Mom"))
        val vi = datas(TgLogic.menu("vi")); val en = datas(TgLogic.menu("en"))
        assertTrue(vi.contains("c:lang en") && en.contains("c:lang vi"))
        assertEquals(TgLogic.Cmd("lang", listOf("en"), "en"), TgLogic.fromData("c:lang en"))
        assertTrue(TgLogic.s("vi", "help", "Bin").contains("/lang") && TgLogic.s("en", "help", "Bin").contains("/lang"))
        assertTrue(TgLogic.menu("en").toString().contains("Status") && TgLogic.menu("vi").toString().contains("Tình hình"))
    }

    @Test fun codeNowGivesCurrentCodeSecondsLeftAndNext() {
        val secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"   // RFC 6238 SHA1 key "12345678901234567890"
        val (c, left, next) = TgLogic.codeNow(secret, 59_000)!!
        assertEquals("287082", c)                         // RFC vector 94287082 at T=59 s, last 6 digits
        assertEquals(1, left)
        assertEquals(Totp.code(Totp.decode(secret)!!, 2), next)
        assertNull(TgLogic.codeNow("", 59_000))
        assertFalse(TgLogic.isAction("code"))
    }

    private fun req(id: String = "ab12", kind: String = "time", min: Int = 30) = JSONObject().put("id", id).put("at", now).put("kind", kind).put("min", min).put("pkg", "com.x").put("app", "Game")

    @Test fun requestButtonsHalfSameDouble() {
        assertEquals(listOf(15, 30, 60), TgLogic.reqOpts(30))
        assertEquals(listOf(5, 15, 30), TgLogic.reqOpts(15))
        assertEquals(listOf(15, 30, 60), TgLogic.reqOpts(0))
        assertEquals(listOf(300, 600), TgLogic.reqOpts(600).filter { it >= 300 })
        val d = datas(TgLogic.reqKb("vi", req()))
        assertEquals(listOf("ry:ab12:15", "ry:ab12:30", "ry:ab12:60", "rn:ab12"), d)
        assertEquals(listOf("ra:ab12", "rb:ab12"), datas(TgLogic.reqKb("en", req(kind = "app"))))
        assertTrue(TgLogic.reqText("vi", "An", req(kind = "app")).contains("Game"))
        // a remind is a new alert key, the first one keeps the plain key
        assertEquals("r:ab12", TgLogic.reqKey(req()))
        assertEquals("r:ab12@${now + 1000}", TgLogic.reqKey(req().put("remindAt", now + 1000)))
    }

    @Test fun staleTapsAfterGap() {
        assertEquals(0, TgLogic.gapMin(0, now))
        assertEquals(0, TgLogic.gapMin(now - 60_000, now))
        assertEquals(25, TgLogic.gapMin(now - 25 * 60_000, now))
        assertTrue(TgLogic.isActionData("ry:ab12:30")); assertTrue(TgLogic.isActionData("c:lock")); assertTrue(TgLogic.isActionData("k:1"))
        assertFalse(TgLogic.isActionData("n:main")); assertFalse(TgLogic.isActionData("r:status")); assertFalse(TgLogic.isActionData("c:status")); assertFalse(TgLogic.isActionData("f:1"))
        assertTrue(TgLogic.s("vi", "gapOld", 25).contains("25"))
    }

    @Test fun giveUsesGrant() {
        val r = Rules()
        val (daily, pass1) = TgLogic.give(r, "", 30, now, "2027-01-15")
        assertFalse(pass1); assertEquals(30, daily.bonusMin); assertTrue(daily.rev > r.rev)
        val (bed, pass2) = TgLogic.give(r, "bedtime", 30, now, "2027-01-15")
        assertTrue(pass2); assertEquals(now + 30 * 60_000L, bed.passUntil); assertEquals(0, bed.bonusMin)
    }

    @Test fun approveAndLimitAndLost() {
        val r = Rules(blocked = setOf("a"), approved = setOf("b"))
        assertEquals(setOf("a2") + "a", TgLogic.approve(r, "a2", false, now).blocked)
        TgLogic.approve(r, "a", true, now).let { assertTrue("a" in it.approved); assertFalse("a" in it.blocked) }
        val w = Rules(dailyByDay = listOf(60, 60, 90, 90, 90, 120, 120))
        assertTrue(TgLogic.limitDiffers(w)); assertFalse(TgLogic.limitDiffers(Rules(dailyByDay = List(7) { 60 }))); assertFalse(TgLogic.limitDiffers(Rules(dailyMin = 60)))
        assertEquals(listOf(60, 60, 45, 90, 90, 120, 120), TgLogic.limitToday(w, 45, 3, now).dailyByDay)
        assertEquals(List(7) { 45 }, TgLogic.limit(w, 45, now).dailyByDay)
        assertEquals("all" to "120", TgLogic.limitArgs(listOf("all", "120"))); assertEquals("today" to "90", TgLogic.limitArgs(listOf("today", "90"))); assertEquals("" to "90", TgLogic.limitArgs(listOf("90")))
        assertEquals(setOf("c:limit all 90", "c:limit today 90", "n:main"), datas(TgLogic.limitAskKb("vi", 90)).toSet())
        val l = TgLogic.lost(Rules(), true, "x".repeat(300), now); assertTrue(l.lostOn); assertEquals(200, l.lostMsg.length)
        assertEquals("Call 0912", TgLogic.lostMsgOf(TgLogic.parse("/lost on Call 0912")!!)); assertEquals("hi", TgLogic.lostMsgOf(TgLogic.parse("/lost hi")!!))
        assertNull(TgLogic.lostMsgOf(TgLogic.parse("/lost")!!)); assertNull(TgLogic.lostMsgOf(TgLogic.parse("/lost off")!!)); assertNull(TgLogic.lostMsgOf(TgLogic.parse("/lost go")!!))
    }

    @Test fun alertLevels() {
        for (imp in listOf("sos", "important", "info")) assertTrue(TgLogic.quietOk("all", imp))
        assertTrue(TgLogic.quietOk("important", "important")); assertFalse(TgLogic.quietOk("important", "info")); assertTrue(TgLogic.quietOk("important", "sos"))
        assertFalse(TgLogic.quietOk("sos", "important")); assertFalse(TgLogic.quietOk("sos", "info")); assertTrue(TgLogic.quietOk("sos", "sos"))
        assertEquals("sos", TgLogic.importance("sos", true)); assertEquals("important", TgLogic.importance("tamper", true)); assertEquals("info", TgLogic.importance("battery", false))
        assertTrue(datas(TgLogic.quietKb("vi", "sos")).contains("c:quiet important"))
    }

    @Test fun undoOnlyWhatChanged() {
        val before = Rules(bonusDay = "d", bonusMin = 10, pausedUntil = 5)
        val after = before.copy(bonusMin = 40)
        val cur = after.copy(pausedUntil = 999)   // a later edit of something else stays
        val u = TgLogic.undo(cur, before, after, now)
        assertEquals(10, u.bonusMin); assertEquals(999, u.pausedUntil); assertTrue(u.rev >= cur.rev)
    }

    @Test fun answeredAndNowAndStatus() {
        val hm = { _: Long -> "20:14" }
        assertEquals("✅ Bố đã cho 15 phút lúc 20:14", TgLogic.answered("vi", "Bố", true, "time", 15, "", now, hm))
        assertEquals("❎ Mom said no at 20:14", TgLogic.answered("en", "Mom", false, "time", 0, "", now, hm))
        assertTrue(TgLogic.answered("vi", "Bố", true, "app", 0, "Game", now, hm).contains("Game"))
        val st = JSONObject().put("now", JSONObject().put("why", "bedtime").put("until", now + 1000))
        assertTrue(TgLogic.status(st, "An", "vi", now, hm).contains("giờ ngủ (tới 20:14)"))
        assertEquals("", TgLogic.nowLine(JSONObject().put("now", JSONObject().put("why", "")), "en", hm))
        assertEquals("com.a", TgLogic.newAppPkg(JSONObject().put("apps", JSONArray().put(JSONObject().put("pkg", "com.a").put("label", "Game").put("new", true)).put(JSONObject().put("pkg", "com.b").put("label", "Game").put("new", false))), "Installed Game"))
        for (l in listOf("vi", "en")) for (k in TgLogic.menu(l).toString().let { listOf("b.requests", "b.quiet", "b.lost", "aReqApp", "lostAsk", "limitAsk", "groupHint", "passDone", "undone", "ansSos", "ansGone", "sosGot")}) assertFalse(k, TgLogic.s(l, k) == k)
        assertTrue(datas(TgLogic.menu("vi")).containsAll(listOf("c:requests", "c:quiet", "c:lost")))
        assertEquals("Vị trí lúc 20:14 (sai số ~30 m)", TgLogic.pinLabel("vi", JSONObject().put("t", now).put("acc", 30.0), now, hm))
    }
}
