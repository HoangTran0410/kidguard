package dev.kidguard

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComfortTest {
    private val bed = Rules(bedtimeOn = true, bedStart = "21:30", bedEnd = "06:30", windGray = true, windMin = 30)
    private fun hm(s: String) = Rules.parseHm(s)!!

    @Test fun windWindow() {
        assertFalse(WindDown.active(bed, hm("20:59")))
        assertTrue(WindDown.active(bed, hm("21:00")))
        assertTrue(WindDown.active(bed, hm("23:59")))
        assertTrue(WindDown.active(bed, hm("03:00")))
        assertTrue(WindDown.active(bed, hm("06:29")))
        assertFalse(WindDown.active(bed, hm("06:30")))
        assertFalse(WindDown.active(bed, hm("12:00")))
    }

    @Test fun windOffCases() {
        assertFalse(WindDown.active(bed.copy(windGray = false), hm("22:00")))
        assertFalse(WindDown.active(bed.copy(bedtimeOn = false), hm("22:00")))
        assertFalse(WindDown.active(bed.copy(bedStart = "07:00", bedEnd = "07:00"), hm("07:00")))
    }

    @Test fun windLeadZeroAndWrap() {
        val atBed = bed.copy(windMin = 0)
        assertFalse(WindDown.active(atBed, hm("21:29"))); assertTrue(WindDown.active(atBed, hm("21:30")))
        // bedtime just after midnight: the lead wraps to the day before
        val late = bed.copy(bedStart = "00:15", bedEnd = "07:00", windMin = 30)
        assertTrue(WindDown.active(late, hm("23:45"))); assertFalse(WindDown.active(late, hm("23:44")))
        // a lead that reaches the end of the window still covers bedtime
        val short = bed.copy(bedStart = "13:00", bedEnd = "12:45", windMin = 30)
        assertTrue(WindDown.active(short, hm("13:30")))
    }

    @Test fun windRulesJson() {
        val r = Rules.fromJson(JSONObject("""{"bedtime":{"on":true},"windDown":{"gray":true,"min":45,"nudge":true}}"""))
        assertTrue(r.windGray); assertEquals(45, r.windMin); assertTrue(r.windNudge)
        assertEquals(30, Rules.fromJson(JSONObject("""{"windDown":{"min":7}}""")).windMin)
        val back = Rules.fromJson(r.toJson())
        assertEquals(r.windGray, back.windGray); assertEquals(r.windMin, back.windMin); assertEquals(r.windNudge, back.windNudge)
    }

    @Test fun grayFirstOnSavesPrevious() {
        val s = Gray.step(want = true, owned = false, saved = null, curOn = "0", curMode = "12")
        assertEquals(listOf(Gray.KEY_MODE to "0", Gray.KEY_ON to "1"), s.write)
        assertTrue(s.owned); assertEquals(Gray.Saved("0", "12"), s.saved)
    }

    @Test fun grayKeepsOnWritesNothingWhenSame() {
        val saved = Gray.Saved("0", "12")
        val s = Gray.step(true, true, saved, "1", "0")
        assertTrue(s.write.isEmpty()); assertEquals(saved, s.saved)
        // the child switched it off in Settings: only the switch is put back, the saved values stay the original ones
        val s2 = Gray.step(true, true, saved, "0", "0")
        assertEquals(listOf(Gray.KEY_ON to "1"), s2.write); assertEquals(saved, s2.saved)
    }

    @Test fun grayRestoresExactly() {
        val s = Gray.step(false, true, Gray.Saved("0", "11"), "1", "0")
        assertEquals(listOf(Gray.KEY_MODE to "11", Gray.KEY_ON to "0"), s.write)
        assertFalse(s.owned); assertNull(s.saved)
        // the phone had grayscale on before us: it stays on
        assertTrue(Gray.step(false, true, Gray.Saved("1", "0"), "1", "0").write.isEmpty())
        // keys that never existed: off + system default mode
        assertEquals(listOf(Gray.KEY_MODE to "12", Gray.KEY_ON to "0"), Gray.step(false, true, Gray.Saved(null, null), "1", "0").write)
        // not ours: never touched
        assertTrue(Gray.step(false, false, null, "1", "0").write.isEmpty())
    }

    @Test fun grayStoreEncoding() {
        assertNull(Gray.dec(Gray.enc(null))); assertEquals("0", Gray.dec(Gray.enc("0")))
    }

    @Test fun notes() {
        assertEquals("Máy của Bin · Gọi mẹ 09xx", Notes.lockText(" Máy của\nBin ", "Gọi mẹ 09xx"))
        assertEquals("Gọi mẹ", Notes.lockText("", "Gọi mẹ"))
        assertEquals("", Notes.lockText(" ", ""))
        assertEquals(60, Notes.lockText("x".repeat(100), "").length)
        assertEquals("def", Notes.short("  ", "def")); assertEquals("Học xong nhé", Notes.short("Học xong nhé", "def"))
        assertEquals("def", Notes.long("def", null)); assertEquals("def\n\nBố mẹ nhắn: hi", Notes.long("def", "Bố mẹ nhắn: hi"))
        val r = Rules.fromJson(JSONObject("""{"lockInfo":{"msg":"Máy của Bin","contact":"Gọi mẹ"},"blockNote":"Học xong nhé"}"""))
        assertEquals("Máy của Bin", r.lockMsg); assertEquals("Gọi mẹ", r.lockContact); assertEquals("Học xong nhé", r.blockNote)
        assertEquals("Học xong nhé", Rules.fromJson(r.toJson()).blockNote)
    }

    @Test fun checkinShape() {
        val loc = JSONObject().put("lat", 21.03).put("lng", 105.85).put("acc", 12.0).put("t", 1000L).put("src", "gps")
        val e = Checkin.event("a1b2c3", 2000L, "arrived", "Con báo: đã tới nơi", loc)
        assertEquals(setOf("id", "t", "kind", "ck", "text", "loc"), e.keys().asSequence().toSet())
        assertEquals("checkin", e.getString("kind")); assertEquals("arrived", e.getString("ck"))
        assertEquals(setOf("lat", "lng", "acc", "t"), e.getJSONObject("loc").keys().asSequence().toSet())   // no provider name
        assertFalse(Checkin.event("x", 1, "pickup", "t", null).has("loc"))
    }

    @Test fun checkinDedupeAndFill() {
        val a = JSONArray().put(Checkin.event("a", 10_000L, "arrived", "t", null))
        assertTrue(Checkin.tooSoon(a, "arrived", 50_000L))
        assertFalse(Checkin.tooSoon(a, "pickup", 50_000L))
        assertFalse(Checkin.tooSoon(a, "arrived", 70_000L))
        val fix = JSONObject().put("lat", 1.0).put("lng", 2.0).put("acc", 5.0).put("t", 20_000L)
        assertTrue(Checkin.fill(a, fix, 30_000L)); assertNotNull(a.getJSONObject(0).optJSONObject("loc"))
        assertFalse(Checkin.fill(a, fix, 31_000L))   // same fix again: nothing new
        assertFalse(Checkin.fill(a, fix.put("t", 99_000L), 10_000L + Checkin.FILL_MS + 1))   // too late
        assertNull(Checkin.fresh(fix.put("t", 0L), Checkin.LOC_FRESH_MS + 1))
    }

    @Test fun nudgeTenMinutes() {
        val r = Rules(bedtimeOn = true, bedStart = "21:30", bedEnd = "06:30", windNudge = true)
        val d = Warn.due(r, -1, hm("21:20"), "2026-11-07", emptySet(), TestTr.VI)
        assertEquals(setOf("2026-11-07:bed10"), d!!.keys); assertEquals("Còn 10 phút nữa là giờ ngủ", d.text)
        assertNull(Warn.due(r, -1, hm("21:20"), "2026-11-07", setOf("2026-11-07:bed10"), TestTr.VI))
        assertNull(Warn.due(r.copy(windNudge = false), -1, hm("21:20"), "2026-11-07", emptySet(), TestTr.VI))
        // the 5-minute heads-up still comes after it
        assertEquals(setOf("2026-11-07:bed"), Warn.due(r, -1, hm("21:26"), "2026-11-07", setOf("2026-11-07:bed10"), TestTr.VI)!!.keys)
    }

    @Test fun templateNote() {
        assertEquals("+15 phút làm bài", ReqTpl.note("+15 phút làm bài", " "))
        assertEquals("Cần app học: Duolingo", ReqTpl.note("Cần app học", "Duolingo"))
        assertEquals(3, ReqTpl.ALL.size)
    }
}
