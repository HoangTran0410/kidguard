package dev.kidguard

import java.util.Calendar
import java.util.TimeZone

/**
 * "Select by date" of every bulk delete (Google Photos style): today / 7 days / 30 days / older than 30 days / custom from-to.
 * Pure and Android-free (unit-tested); the page has the same rules in `ui/assets/select.js` (`selRange`).
 * Days are local calendar days: "7 days" = today and the 6 days before it.
 */
object SelLogic {
    enum class Range { TODAY, DAYS7, DAYS30, OLDER30, CUSTOM }

    fun startOfDay(t: Long, tz: TimeZone = TimeZone.getDefault()): Long =
        Calendar.getInstance(tz).apply { timeInMillis = t; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis

    private fun addDays(t: Long, d: Int, tz: TimeZone) = Calendar.getInstance(tz).apply { timeInMillis = t; add(Calendar.DAY_OF_YEAR, d) }.timeInMillis

    /** Does an item made at [at] fall in [r]? Custom: [from] / [to] = any time inside the first / last day (0 = open end), both days included. */
    fun inRange(r: Range, at: Long, now: Long, from: Long = 0, to: Long = 0, tz: TimeZone = TimeZone.getDefault()): Boolean {
        val today = startOfDay(now, tz)
        return when (r) {
            Range.TODAY -> at >= today
            Range.DAYS7 -> at >= addDays(today, -6, tz)
            Range.DAYS30 -> at >= addDays(today, -29, tz)
            Range.OLDER30 -> at < addDays(today, -29, tz)
            Range.CUSTOM -> (from <= 0 || at >= startOfDay(from, tz)) && (to <= 0 || at < addDays(startOfDay(to, tz), 1, tz))
        }
    }

    /** Ids of [items] (id to time) inside the range, in the given order. */
    fun pick(items: List<Pair<String, Long>>, r: Range, now: Long, from: Long = 0, to: Long = 0, tz: TimeZone = TimeZone.getDefault()): List<String> =
        items.filter { inRange(r, it.second, now, from, to, tz) }.map { it.first }

    /** Local day of [t] as yyyy-MM-dd: what a per-day checkbox groups by. */
    fun dayKey(t: Long, tz: TimeZone = TimeZone.getDefault()): String =
        Calendar.getInstance(tz).apply { timeInMillis = t }.let { String.format("%04d-%02d-%02d", it.get(Calendar.YEAR), it.get(Calendar.MONTH) + 1, it.get(Calendar.DAY_OF_MONTH)) }
}
