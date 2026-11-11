package dev.kidguard

import java.util.Calendar
import java.util.TimeZone

/** Pure part of the log / event history ([HistDb]): day keys in the phone's time zone, retention, per-day counts. Unit-tested. */
object HistLogic {
    const val KEEP_DAYS = 90
    const val MAX_ROWS = 30_000          // both kinds together; oldest go first
    const val DAY_ROWS = 500             // rows in one day answer (newest kept)
    const val DAY_BYTES = 90_000         // the answer is also trimmed to this (relay stores <= 100 KB per day)
    const val TRIM_EVERY_MS = 3_600_000L
    private val DAY = Regex("^\\d{4}-\\d\\d-\\d\\d$")

    fun validDay(d: String?): Boolean = d != null && DAY.matches(d)

    /** `yyyy-MM-dd` of [t] in [tz]. */
    fun dayKey(t: Long, tz: TimeZone): String {
        val c = Calendar.getInstance(tz).apply { timeInMillis = t }
        return "%04d-%02d-%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    /** [start, end) in ms of the local day [day] (23 / 25 h on a DST change); null when [day] is not a date. */
    fun dayRange(day: String, tz: TimeZone): Pair<Long, Long>? {
        if (!validDay(day)) return null
        val c = Calendar.getInstance(tz).apply {
            isLenient = false; clear()
            runCatching { set(day.substring(0, 4).toInt(), day.substring(5, 7).toInt() - 1, day.substring(8, 10).toInt()); timeInMillis }.getOrNull() ?: return null
        }
        val s = c.timeInMillis; c.add(Calendar.DAY_OF_MONTH, 1)
        return s to c.timeInMillis
    }

    /** Oldest time kept. */
    fun cutoff(now: Long) = now - KEEP_DAYS * 86_400_000L

    /** Rows per local day for the times [ts] (only the last [KEEP_DAYS] days); days without rows are absent. */
    fun counts(ts: LongArray, tz: TimeZone, now: Long): Map<String, Int> {
        val out = java.util.TreeMap<String, Int>(); val min = cutoff(now)
        var lastS = 0L; var lastE = -1L; var lastK = ""   // consecutive rows are mostly the same day: one Calendar per day, not per row
        for (t in ts) {
            if (t < min || t > now + 86_400_000L) continue
            if (t < lastS || t >= lastE) { lastK = dayKey(t, tz); dayRange(lastK, tz)?.let { lastS = it.first; lastE = it.second } }
            out[lastK] = (out[lastK] ?: 0) + 1
        }
        return out
    }
}
