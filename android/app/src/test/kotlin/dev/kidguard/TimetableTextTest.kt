package dev.kidguard

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TimetableTextTest {
    private fun sample(): JSONObject {
        var tt = JSONObject()
        tt = TimetableLogic.withSession(tt, 1, false, listOf("Toán", "Tiếng Việt", "", "Anh"))
        tt = TimetableLogic.withSession(tt, 1, true, listOf("Thể dục"))
        tt = TimetableLogic.withSession(tt, 7, false, listOf("Vẽ"))
        tt = TimetableLogic.withBells(tt, false, listOf(intArrayOf(420, 465), intArrayOf(470, 515)))
        return tt
    }

    @Test fun roundTripBothLanguages() {   // owner 2026-11-21: reinstall = retype every period
        val tt = sample()
        for (vi in listOf(true, false)) {
            val r = TimetableText.read(TimetableText.write(tt, vi), JSONObject())!!
            for (d in 1..7) for (p in listOf(false, true)) assertEquals("$vi $d $p", TimetableLogic.session(tt, d, p), TimetableLogic.session(r.tt, d, p))
            assertEquals(listOf(420, 465, 470, 515), TimetableLogic.bells(r.tt, false).flatMap { it.toList() })
            assertEquals(3, r.sessions)
        }
        assertTrue(TimetableText.write(tt, true).contains("T2 sáng: Toán, Tiếng Việt, -, Anh"))
    }

    @Test fun handTypedLabels() {
        val r = TimetableText.read("Thứ Hai sáng: Toán\nthu 5 chieu: Văn\nChủ nhật sáng: Bơi\nThursday am: Music\nGio chieu: 13h30-14h15", JSONObject())!!
        assertEquals(listOf("Toán"), TimetableLogic.session(r.tt, 1, false))
        assertEquals(listOf("Văn"), TimetableLogic.session(r.tt, 4, true))
        assertEquals(listOf("Music"), TimetableLogic.session(r.tt, 4, false))
        assertEquals(listOf("Bơi"), TimetableLogic.session(r.tt, 7, false))
        assertEquals(listOf(810, 855), TimetableLogic.bells(r.tt, true).flatMap { it.toList() })
    }

    @Test fun wrongTextIsRefusedAndOldBellsKept() {
        assertNull(TimetableText.read("hello\nhttps://x.y/z", JSONObject()))
        val old = TimetableLogic.withBells(JSONObject(), false, listOf(intArrayOf(400, 440)))
        val r = TimetableText.read("T3 sáng: Toán", old)!!
        assertEquals(listOf(400, 440), TimetableLogic.bells(r.tt, false).flatMap { it.toList() })
    }
}
