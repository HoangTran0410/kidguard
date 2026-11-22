package dev.kidguard

import org.json.JSONArray

/**
 * Battery history for the parent's chart (owner 2026-11-22: "pin tụt như nào, khi nào đang sạc"). Point = `[t, pct, charging 0/1]`.
 * A point is stored when the percent or the charging flag changes, else once per [HEART_MS] (so a flat line is told
 * apart from a phone that was off: the page breaks the line after a gap of more than [GAP_MS]). 7 days, [MAX] points.
 * Pure (unit-tested: BatLogTest); Store keeps the array in prefs, Guard.batteryCheck feeds it.
 */
object BatLog {
    const val KEEP_MS = 7 * 24 * 3_600_000L
    const val HEART_MS = 30 * 60_000L
    const val GAP_MS = 90 * 60_000L   // page side: no point for longer than this = no data (phone off, app killed)
    const val MAX = 3000

    /** The array with [pct] / [charging] added at [now], or null when nothing needs storing. */
    fun add(a: JSONArray, now: Long, pct: Int, charging: Boolean): JSONArray? {
        if (pct !in 0..100) return null
        val c = if (charging) 1 else 0
        val last = if (a.length() > 0) a.optJSONArray(a.length() - 1) else null
        if (last != null && last.optInt(1) == pct && last.optInt(2) == c && now - last.optLong(0) in 0 until HEART_MS) return null
        val from = now - KEEP_MS
        val keep = ArrayList<JSONArray>()
        for (i in 0 until a.length()) { val q = a.optJSONArray(i) ?: continue; val t = q.optLong(0); if (t in from until now) keep += q }
        keep += JSONArray().put(now).put(pct).put(c)
        return JSONArray(keep.takeLast(MAX))
    }

    /** Points at or after [from] (the state sends today + yesterday). */
    fun since(a: JSONArray, from: Long): JSONArray {
        val out = JSONArray()
        for (i in 0 until a.length()) { val q = a.optJSONArray(i) ?: continue; if (q.optLong(0) >= from) out.put(q) }
        return out
    }
}
