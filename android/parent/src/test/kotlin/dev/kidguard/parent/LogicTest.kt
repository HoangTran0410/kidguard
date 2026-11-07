package dev.kidguard.parent

import org.junit.Assert.*
import org.junit.Test

class LogicTest {
    private fun t(s: String) = Logic.encodeTarget(s)

    @Test fun targetRoundTrip() {
        assertEquals("http://192.168.1.5:8770", Logic.decodeTarget(t("http://192.168.1.5:8770")))
        assertEquals("https://kg.example.workers.dev", Logic.decodeTarget(t("https://kg.example.workers.dev")))
        assertEquals("http://kid-an.tail123.ts.net:8770", Logic.decodeTarget(t("http://kid-an.tail123.ts.net:8770")))
        assertFalse(Logic.encodeTarget("http://a:1").contains("="))
    }

    @Test fun targetRejects() {
        for (bad in listOf("ftp://a:1", "file:///etc/passwd", "http://", "http://a/b", "http://a:8770/x", "http://u@a:1", "http://a:99999",
            "http://a:", "http://a b:1", "javascript:alert(1)", "http://a:1?x=1", "http://-bad:1"))
            assertNull(bad, Logic.decodeTarget(t(bad)))
        assertNull(Logic.decodeTarget(""))
        assertNull(Logic.decodeTarget("!!!notbase64"))
        assertNull(Logic.decodeTarget("a".repeat(400)))
    }

    @Test fun kidPath() {
        val p = Logic.parseKidPath("/k/${t("http://10.0.0.2:8770")}/api/state")
        assertEquals("http://10.0.0.2:8770" to "/api/state", p)
        assertNull(Logic.parseKidPath("/k/${t("gopher://x")}/api/state"))
        assertNull(Logic.parseKidPath("/k/${t("http://10.0.0.2:8770")}"))
        assertNull(Logic.parseKidPath("/x/abc/def"))
    }

    @Test fun statePaths() {
        assertTrue(Logic.isStatePath("GET", "/api/state"))
        assertTrue(Logic.isStatePath("GET", "/c/0123456789abcdef/state"))
        assertFalse(Logic.isStatePath("POST", "/api/state"))
        assertFalse(Logic.isStatePath("GET", "/api/rules"))
        assertFalse(Logic.isStatePath("GET", "/c/xyz/state"))
        assertEquals("/api/state", Logic.normRest("api/state"))
        assertEquals("/api/state", Logic.normRest("/api/state"))
    }

    @Test fun cacheFallback() {
        assertTrue(Logic.useCache(null, true))
        assertTrue(Logic.useCache(502, false))
        assertTrue(Logic.useCache(500, false))
        assertFalse(Logic.useCache(200, false))
        assertFalse(Logic.useCache(401, false))
        assertFalse(Logic.useCache(404, false))
    }

    private val now = 1_800_000_000_000L
    private fun seen(vararg r: Pair<String, Int>, ok: Boolean = true, act: Long = now) = Logic.Seen(ok, r.toList(), act)

    @Test fun firstLookOnlyRecords() {
        val o = Logic.evaluate("An", seen("a" to 30), Logic.NotifyState(), now, tr = TestTr.VI)
        assertTrue(o.events.isEmpty())
        assertTrue(o.state.init && "a" in o.state.seenIds)
    }

    @Test fun requestNotifiedOnce() {
        var st = Logic.evaluate("An", seen("a" to 30), Logic.NotifyState(), now, tr = TestTr.VI).state
        val o1 = Logic.evaluate("An", seen("a" to 30, "b" to 15), st, now, tr = TestTr.VI)
        assertEquals(listOf("An xin thêm 15 phút"), o1.events.map { it.text })
        val o2 = Logic.evaluate("An", seen("a" to 30, "b" to 15), o1.state, now, tr = TestTr.VI)
        assertTrue(o2.events.isEmpty())
        // survives JSON persistence
        val o3 = Logic.evaluate("An", seen("a" to 30, "b" to 15), Logic.NotifyState.fromJson(o1.state.toJson()), now, tr = TestTr.VI)
        assertTrue(o3.events.isEmpty())
    }

    @Test fun lostNotifiedOnceThenResets() {
        val base = Logic.NotifyState(init = true)
        val o1 = Logic.evaluate("An", seen(act = now - 31 * 60_000), base, now, tr = TestTr.VI)
        assertEquals(listOf("Mất kết nối với máy của An"), o1.events.map { it.text })
        assertTrue(Logic.evaluate("An", seen(act = now - 40 * 60_000), o1.state, now, tr = TestTr.VI).events.isEmpty())
        val back = Logic.evaluate("An", seen(act = now), o1.state, now, tr = TestTr.VI)
        assertFalse(back.state.lost)
        assertEquals(1, Logic.evaluate("An", seen(act = now - 31 * 60_000), back.state, now, tr = TestTr.VI).events.size)
    }

    @Test fun accessibilityOffNotifiedOncePerTransition() {
        val base = Logic.NotifyState(init = true)
        val off = Logic.Seen(true, emptyList(), now, accOff = true)
        val o1 = Logic.evaluate("An", off, base, now, tr = TestTr.VI)
        assertEquals(listOf("An đã tắt Trợ năng của KidGuard: app không bị chặn nữa"), o1.events.map { it.text })
        assertTrue(Logic.evaluate("An", off, o1.state, now, tr = TestTr.VI).events.isEmpty())
        // a failed fetch keeps the flag; turning it back on re-arms
        val failed = Logic.evaluate("An", seen(ok = false), o1.state, now, tr = TestTr.VI)
        assertTrue(failed.state.accOff)
        val on = Logic.evaluate("An", seen(), o1.state, now, tr = TestTr.VI)
        assertFalse(on.state.accOff)
        assertEquals(1, Logic.evaluate("An", off, on.state, now, tr = TestTr.VI).events.size)
        assertTrue(Logic.NotifyState.fromJson(o1.state.toJson()).accOff)
    }

    @Test fun fetchFailureNeedsThirtyMinutes() {
        val f = seen(ok = false, act = 0)
        val o1 = Logic.evaluate("An", f, Logic.NotifyState(init = true), now, tr = TestTr.VI)
        assertTrue(o1.events.isEmpty()); assertEquals(now, o1.state.failSince)
        val o2 = Logic.evaluate("An", f, o1.state, now + 29 * 60_000, tr = TestTr.VI)
        assertTrue(o2.events.isEmpty())
        val o3 = Logic.evaluate("An", f, o2.state, now + 31 * 60_000, tr = TestTr.VI)
        assertEquals(1, o3.events.size)
        assertTrue(Logic.evaluate("An", f, o3.state, now + 50 * 60_000, tr = TestTr.VI).events.isEmpty())
        val ok = Logic.evaluate("An", seen(act = now + 60 * 60_000), o3.state, now + 60 * 60_000, tr = TestTr.VI)
        assertFalse(ok.state.lost); assertEquals(0L, ok.state.failSince)
    }

    @Test fun parseKids() {
        val j = """[{"name":"An","target":"http://10.0.0.2:8770","via":"lan","id":"x","token":"tk"},
            {"name":"Bad","target":"ftp://x","via":"lan"},{"name":"R","target":"https://w.dev","via":"relay","id":"0123456789abcdef","token":"t"}]"""
        assertEquals(listOf("An", "R"), Logic.parseKids(j).map { it.name })
        assertTrue(Logic.parseKids("garbage").isEmpty())
    }

    // ---- history pruning ----
    private val MB = 1024L * 1024

    @Test fun retention() {
        val days = listOf("2026-11-06", "2026-11-05", "2026-10-08", "2026-10-07", "2026-01-01")
        assertTrue(Logic.retentionDrop(days, 0, "2026-11-06").isEmpty())            // unlimited
        // 30 days = today and the 29 before (oldest kept 2026-10-08)
        assertEquals(setOf("2026-10-07", "2026-01-01"), Logic.retentionDrop(days, 30, "2026-11-06").toSet())
        assertTrue(Logic.retentionDrop(days, 365, "2026-11-06").isEmpty())
    }

    @Test fun storageGuard() {
        val days = (1..20).map { "2026-10-%02d".format(it) to 10 * MB }
        assertTrue(Logic.storageDrop(days, 500 * MB).isEmpty())                     // enough space
        // 195 MB free, need 200: dropping the oldest day (10 MB) is enough
        assertEquals(listOf("2026-10-01"), Logic.storageDrop(days, 195 * MB))
        // 150 MB free: 5 days
        assertEquals((1..5).map { "2026-10-%02d".format(it) }, Logic.storageDrop(days, 150 * MB))
        // nothing helps: stop at 7 days left (drop 13)
        val d = Logic.storageDrop(days, 0)
        assertEquals(13, d.size); assertEquals("2026-10-13", d.last())
        assertTrue(Logic.storageDrop(days.take(7), 0).isEmpty())                     // only 7 left: never delete
    }

    @Test fun sosNotifiesOnceEvenOnFirstLook() {
        val s = Logic.Seen(true, emptyList(), now, listOf("x1" to now - 60_000, "old" to now - 7 * 3600_000L))
        val o1 = Logic.evaluate("An", s, Logic.NotifyState(), now, tr = TestTr.VI)
        assertEquals(listOf("sos:x1"), o1.events.map { it.key })
        assertTrue(o1.events[0].text.startsWith("An bấm SOS lúc "))
        val o2 = Logic.evaluate("An", s, o1.state, now + 60_000, tr = TestTr.VI)
        assertTrue(o2.events.isEmpty())
    }

    @Test fun dayKeyAndOrder() {
        assertEquals("2026-11-06", Logic.dayKey(java.time.LocalDate.of(2026, 11, 6).atTime(12, 0).atZone(java.time.ZoneId.of("UTC")).toInstant().toEpochMilli(), java.time.ZoneId.of("UTC")))
    }

    @Test fun pushEventMapsAndDedupes() {
        val kids = Logic.parseKids("""[{"name":"An","target":"https://w.example.dev","via":"relay","id":"0123456789abcdef","token":"t"},
            {"name":"Bo","target":"http://192.168.1.5:8770","via":"lan","id":"","token":"t"}]""")
        val none = { _: Logic.KidCfg -> Logic.NotifyState() }
        val sos = Logic.pushEvent(kids, mapOf("kind" to "sos", "kid" to "0123456789abcdef", "eid" to "s:a1", "t" to "0"), none, tr = TestTr.VI)!!
        assertEquals("An", sos.first.name); assertEquals("sos:a1", sos.second.key)
        val req = Logic.pushEvent(kids, mapOf("kind" to "req", "kid" to "0123456789abcdef", "eid" to "r:q9", "min" to "30"), none, tr = TestTr.VI)!!
        assertEquals("req:q9", req.second.key); assertEquals("An xin thêm 30 phút", req.second.text)
        // same keys as the background check -> same notification id; already seen -> nothing
        val seen = { _: Logic.KidCfg -> Logic.NotifyState(setOf("s:a1", "q9")) }
        assertNull(Logic.pushEvent(kids, mapOf("kind" to "sos", "kid" to "0123456789abcdef", "eid" to "s:a1"), seen, tr = TestTr.VI))
        assertNull(Logic.pushEvent(kids, mapOf("kind" to "req", "kid" to "0123456789abcdef", "eid" to "r:q9"), seen, tr = TestTr.VI))
        assertNull(Logic.pushEvent(kids, mapOf("kind" to "sos", "kid" to "ffffffffffffffff", "eid" to "s:a1"), none, tr = TestTr.VI))
        assertNull(Logic.pushEvent(kids, mapOf("kind" to "zap", "kid" to "0123456789abcdef", "eid" to "s:a1"), none, tr = TestTr.VI))
        assertEquals(setOf("x", "q9"), Logic.withSeen(Logic.NotifyState(setOf("x")), Logic.seenIdOf("r:q9")!!).seenIds)
        // what the check stores must match what the push marks, or both would notify
        val after = Logic.evaluate("An", Logic.Seen(true, listOf("q9" to 30), System.currentTimeMillis(), listOf("a1" to System.currentTimeMillis())),
            Logic.NotifyState(setOf("s:a1", "q9"), init = true), System.currentTimeMillis(), tr = TestTr.VI)
        assertTrue(after.events.isEmpty())
    }

    @Test fun allowedRequestOnlySavedTargetsOrPairing() {
        val kids = """[{"name":"An","target":"http://192.168.1.5:8770","via":"lan","id":"a","token":"t"},{"name":"Bo","target":"https://r.example.workers.dev","via":"relay","id":"0123456789abcdef","token":"t"}]"""
        assertTrue(Logic.allowedRequest(kids, "http://192.168.1.5:8770", "POST", "/api/rules"))
        assertTrue(Logic.allowedRequest(kids, "https://r.example.workers.dev", "GET", "/c/0123456789abcdef/state"))
        assertFalse(Logic.allowedRequest(kids, "http://10.0.0.1:80", "GET", "/api/state"))
        assertFalse(Logic.allowedRequest(kids, "https://evil.example", "GET", "/api/hello"))
        assertTrue(Logic.allowedRequest(kids, "http://10.0.0.9:8770", "GET", "/api/hello"))
        assertTrue(Logic.allowedRequest("[]", "http://10.0.0.9:8770", "POST", "/api/pair"))
        assertFalse(Logic.allowedRequest("[]", "http://10.0.0.9:8770", "POST", "/api/rules"))
    }

    @Test fun lanFailuresIgnoredOffWifi() {
        assertFalse(Logic.failCounts("lan", false)); assertTrue(Logic.failCounts("lan", true)); assertTrue(Logic.failCounts("relay", false))
        val f = Logic.Seen(false, emptyList(), 0)
        val s0 = Logic.NotifyState(init = true, failSince = 0L)
        val o = Logic.evaluate("An", f, s0, now + 60 * 60_000, failCounts = false, tr = TestTr.VI)
        assertTrue(o.events.isEmpty()); assertEquals(0L, o.state.failSince)
    }

    @Test fun pushWithoutTimeUsesNow() {
        val kids = listOf(Logic.KidCfg("An", "https://r.example", "relay", "0123456789abcdef", "t"))
        val e = Logic.pushEvent(kids, mapOf("kind" to "sos", "kid" to "0123456789abcdef", "eid" to "s:a1"), { Logic.NotifyState() }, 1_700_000_000_000L, tr = TestTr.VI)!!
        assertEquals("An bấm SOS lúc " + Logic.hhmm(1_700_000_000_000L), e.second.text)
    }

    private fun evs(vararg e: Pair<String, String>, t: Long = now - 60_000) = e.map { Logic.Ev(it.first, it.second, t) }
    private fun seenEv(e: List<Logic.Ev>) = Logic.Seen(true, emptyList(), now, emptyList(), e)

    @Test fun eventsFirstLookOnlyRecordsThenNotifyOnce() {
        val o0 = Logic.evaluate("An", seenEv(evs("e1" to "Đã đến Nhà")), Logic.NotifyState(init = true, evInit = false), now, tr = TestTr.VI)
        assertTrue(o0.events.isEmpty())   // also for a kid that already had init=true before events existed
        assertTrue(o0.state.evInit && "e:e1" in o0.state.seenIds)
        val o1 = Logic.evaluate("An", seenEv(evs("e1" to "Đã đến Nhà", "e2" to "Pin còn 14%")), o0.state, now, tr = TestTr.VI)
        assertEquals(listOf("ev:e2"), o1.events.map { it.key })
        assertEquals("An: Pin còn 14%", o1.events[0].text)
        val o2 = Logic.evaluate("An", seenEv(evs("e1" to "Đã đến Nhà", "e2" to "Pin còn 14%")), Logic.NotifyState.fromJson(o1.state.toJson()), now, tr = TestTr.VI)
        assertTrue(o2.events.isEmpty())
    }

    @Test fun staleEventsAreRecordedNotShown() {
        val st = Logic.NotifyState(init = true, evInit = true)
        val o = Logic.evaluate("An", seenEv(evs("old" to "Đã rời Nhà", t = now - 7 * 3600_000L)), st, now, tr = TestTr.VI)
        assertTrue(o.events.isEmpty())
        assertTrue("e:old" in o.state.seenIds)
    }

    @Test fun eventIdsDontClashWithRequests() {
        val st = Logic.NotifyState(setOf("x"), init = true, evInit = true)   // request id "x" already seen
        val o = Logic.evaluate("An", seenEv(evs("x" to "Đã cài Game")), st, now, tr = TestTr.VI)
        assertEquals(1, o.events.size)
    }

    @Test fun pushEvCheckKid() {
        val kids = listOf(Logic.KidCfg("An", "https://r.dev", "relay", "0123456789abcdef", "tok"), Logic.KidCfg("Bo", "http://10.0.0.2:8770", "lan", "ffff", "t"))
        assertEquals("An", Logic.pushCheckKid(kids, mapOf("kind" to "ev", "kid" to "0123456789abcdef", "eid" to "e:abc"))?.name)
        assertNull(Logic.pushCheckKid(kids, mapOf("kind" to "ev", "kid" to "ffff")))   // LAN kids have no push
        assertNull(Logic.pushCheckKid(kids, mapOf("kind" to "sos", "kid" to "0123456789abcdef")))
        assertNull(Logic.pushEvent(kids, mapOf("kind" to "ev", "kid" to "0123456789abcdef", "eid" to "e:abc"), { Logic.NotifyState() }, tr = TestTr.VI))   // not a notification by itself
    }
}
