package dev.kidguard

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class BatLogTest {
    private val M = 60_000L

    @Test fun storesChangesAndAHeartbeat() {   // owner 2026-11-22: battery chart
        var a = JSONArray()
        a = BatLog.add(a, 0, 80, false)!!
        assertNull(BatLog.add(a, 5 * M, 80, false))                 // same value, < 30 min: nothing
        a = BatLog.add(a, 6 * M, 79, false)!!                       // percent changed
        a = BatLog.add(a, 7 * M, 79, true)!!                        // plugged in
        a = BatLog.add(a, 7 * M + BatLog.HEART_MS, 79, true)!!      // flat for 30 min: heartbeat
        assertEquals(4, a.length())
        assertEquals(1, a.getJSONArray(2).getInt(2))
        assertNull(BatLog.add(a, 8 * M, -1, false))                 // no battery reading
    }

    @Test fun keepsSevenDaysAndDropsFutureClock() {
        var a = JSONArray().put(JSONArray().put(0L).put(50).put(0)).put(JSONArray().put(10 * BatLog.KEEP_MS).put(40).put(0))
        a = BatLog.add(a, BatLog.KEEP_MS + 10 * M, 49, false)!!
        assertEquals(1, a.length())                                  // the old point and the one "from the future" are gone
        assertEquals(1, BatLog.since(a, BatLog.KEEP_MS).length())
    }
}
