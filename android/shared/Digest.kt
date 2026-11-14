package dev.kidguard

import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone

/**
 * Evening summary for the parent (daily, and weekly on Sunday). Pure: text from a kid state JSON, Android-free, unit-tested
 * (app module). Compiled into BOTH the child app and the parent app (`android/shared`, see both build.gradle.kts), so the
 * Telegram message (phone) and the parent-app notification say the same thing. Own vi/en texts (docs/copy.md style).
 */
object Digest {
    const val DEFAULT_AT = "20:00"

    /** One local day of counters (state.digest.days[]): the kid asked / the parent answered / apps locked / tamper + wrong PIN. */
    data class Day(val day: String, val asked: Int = 0, val answered: Int = 0, val blocked: Int = 0, val tamper: Int = 0)

    class Input(
        val name: String,
        val usedMs: Long,                 // today so far
        val limitMin: Int,                // today's limit, 0 = none
        val top: List<Pair<String, Long>>,  // today's apps, most used first
        val history: List<Triple<String, Long, List<Pair<String, Long>>>>,   // state.history: (day, ms, top), newest first
        val days: List<Day>,              // state.digest.days, newest first (today first)
        val bedKept: Boolean?,            // null = no bedtime / unknown
    )

    /** From a state JSON (LAN `/api/state` or the relay's `state`). Missing parts = zeros / empty. */
    fun fromState(st: JSONObject, name: String): Input {
        fun apps(a: JSONArray?): List<Pair<String, Long>> = (0 until (a?.length() ?: 0)).mapNotNull { i ->
            val o = a!!.optJSONObject(i) ?: return@mapNotNull null
            if (o.optBoolean("protected") || o.optLong("ms") <= 0) null
            else o.optString("label").ifEmpty { o.optString("pkg") } to o.optLong("ms")
        }.sortedByDescending { it.second }
        val h = st.optJSONArray("history")
        val hist = (0 until (h?.length() ?: 0)).mapNotNull { i ->
            val o = h!!.optJSONObject(i) ?: return@mapNotNull null
            Triple(o.optString("day"), o.optLong("ms"), apps(o.optJSONArray("top")))
        }
        val dg = st.optJSONObject("digest")
        val da = dg?.optJSONArray("days")
        val days = (0 until (da?.length() ?: 0)).mapNotNull { i ->
            val o = da!!.optJSONObject(i) ?: return@mapNotNull null
            Day(o.optString("day"), o.optInt("asked"), o.optInt("answered"), o.optInt("blocked"), o.optInt("tamper"))
        }
        val bed = if (dg != null && dg.has("bedKept") && !dg.isNull("bedKept")) dg.optBoolean("bedKept") else null
        return Input(name, st.optLong("usedMs"), st.optInt("limitMin"), apps(st.optJSONArray("apps")), hist, days, bed)
    }

    /** Sums [apps] by label, most used first. */
    fun mergeTop(lists: List<List<Pair<String, Long>>>): List<Pair<String, Long>> =
        lists.flatten().groupBy { it.first }.map { it.key to it.value.sumOf { p -> p.second } }.sortedByDescending { it.second }

    private val EN = mapOf(
        "dHead" to "Today: %1\$s",
        "wHead" to "This week: %1\$s",
        "time" to "Screen time %1\$s of %2\$s",
        "timeNoLimit" to "Screen time %1\$s",
        "wTime" to "Screen time %1\$s, %2\$s a day",
        "top" to "Top: %1\$s",
        "req" to "More time asked: %1\$d, answered: %2\$d",
        "reqNone" to "More time asked: 0",
        "blocked" to "Apps locked: %1\$d",
        "tamper" to "Tamper tries: %1\$d",
        "tamperNone" to "Tamper tries: 0",
        "bedOk" to "Bedtime: kept",
        "bedBad" to "Bedtime: phone was used",
        "settings" to "Summary: /digest off to stop",
    )
    private val VI = mapOf(
        "dHead" to "Hôm nay: %1\$s",
        "wHead" to "Tuần này: %1\$s",
        "time" to "Màn hình %1\$s / %2\$s",
        "timeNoLimit" to "Màn hình %1\$s",
        "wTime" to "Màn hình %1\$s, %2\$s mỗi ngày",
        "top" to "Nhiều nhất: %1\$s",
        "req" to "Xin thêm giờ: %1\$d, đã trả lời: %2\$d",
        "reqNone" to "Xin thêm giờ: 0",
        "blocked" to "App bị khoá: %1\$d lần",
        "tamper" to "Thử phá: %1\$d lần",
        "tamperNone" to "Thử phá: 0",
        "bedOk" to "Giờ ngủ: giữ đúng",
        "bedBad" to "Giờ ngủ: máy bị dùng",
        "settings" to "Tắt tóm tắt: /digest off",
    )

    private fun s(lang: String, key: String, vararg a: Any?): String {
        val f = (if (lang == "en") EN else VI)[key] ?: key
        return if (a.isEmpty()) f else String.format(java.util.Locale.ROOT, f, *a)
    }

    /** "1h 5m" / "1 giờ 5 phút"; under a minute = 0. */
    fun fmt(ms: Long, lang: String): String {
        val m = (ms / 60_000).coerceAtLeast(0); val h = m / 60; val r = m % 60
        return if (lang == "en") (if (h > 0) "${h}h" + (if (r > 0) " ${r}m" else "") else "${m}m")
        else (if (h > 0) "$h giờ" + (if (r > 0) " $r phút" else "") else "$m phút")
    }

    private fun topLine(lang: String, top: List<Pair<String, Long>>): String? {
        val t = top.filter { it.second >= 60_000 }.take(3)
        if (t.isEmpty()) return null
        return s(lang, "top", t.joinToString(", ") { "${it.first.take(24)} ${fmt(it.second, lang)}" })
    }

    private fun reqLine(lang: String, asked: Int, answered: Int) =
        if (asked == 0) s(lang, "reqNone") else s(lang, "req", asked, minOf(answered, asked))

    /** Today's summary, one short message (<= ~8 lines). [withHint] adds the "how to stop it" line (Telegram only). */
    fun daily(i: Input, lang: String, withHint: Boolean = false): String {
        val t = i.days.firstOrNull()
        val out = ArrayList<String>()
        out += s(lang, "dHead", i.name)
        out += if (i.limitMin > 0) s(lang, "time", fmt(i.usedMs, lang), fmt(i.limitMin * 60_000L, lang)) else s(lang, "timeNoLimit", fmt(i.usedMs, lang))
        topLine(lang, i.top)?.let { out += it }
        out += reqLine(lang, t?.asked ?: 0, t?.answered ?: 0)
        if ((t?.blocked ?: 0) > 0) out += s(lang, "blocked", t!!.blocked)
        out += if ((t?.tamper ?: 0) > 0) s(lang, "tamper", t!!.tamper) else s(lang, "tamperNone")
        i.bedKept?.let { out += s(lang, if (it) "bedOk" else "bedBad") }
        if (withHint) out += s(lang, "settings")
        return out.joinToString("\n")
    }

    /** The last 7 days: today so far + the 6 days before it from `history`. */
    fun weekly(i: Input, lang: String, withHint: Boolean = false): String {
        val past = i.history.take(6)
        val total = i.usedMs + past.sumOf { it.second }
        val n = 1 + past.size
        val c = i.days.take(7)
        val out = ArrayList<String>()
        out += s(lang, "wHead", i.name)
        out += s(lang, "wTime", fmt(total, lang), fmt(total / n, lang))
        topLine(lang, mergeTop(listOf(i.top) + past.map { it.third }))?.let { out += it }
        out += reqLine(lang, c.sumOf { it.asked }, c.sumOf { it.answered })
        val bl = c.sumOf { it.blocked }; if (bl > 0) out += s(lang, "blocked", bl)
        val tp = c.sumOf { it.tamper }; out += if (tp > 0) s(lang, "tamper", tp) else s(lang, "tamperNone")
        if (withHint) out += s(lang, "settings")
        return out.joinToString("\n")
    }

    // ---------- schedule (shared by phone + parent app) ----------

    /** "HH:mm" -> minutes since midnight, null when not a time. */
    fun minutes(hm: String?): Int? {
        val m = Regex("^([01]?\\d|2[0-3]):([0-5]\\d)$").matchEntire(hm?.trim() ?: return null) ?: return null
        return m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt()
    }

    fun dayKey(now: Long, tz: TimeZone): String {
        val c = Calendar.getInstance(tz).apply { timeInMillis = now }
        return "%04d-%02d-%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    /** Due = enabled, [at] has passed today and [lastDay] is not today. No catch-up for a day that already ended. */
    fun due(on: Boolean, at: String?, lastDay: String, now: Long, tz: TimeZone = TimeZone.getDefault()): Boolean {
        if (!on) return false
        val c = Calendar.getInstance(tz).apply { timeInMillis = now }
        val cur = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        return cur >= (minutes(at) ?: minutes(DEFAULT_AT)!!) && lastDay != dayKey(now, tz)
    }

    fun isSunday(now: Long, tz: TimeZone = TimeZone.getDefault()) =
        Calendar.getInstance(tz).apply { timeInMillis = now }.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY

    /** Parent-app notification body: the daily text, plus the weekly one on Sunday. */
    fun messages(i: Input, lang: String, now: Long, tz: TimeZone = TimeZone.getDefault(), withHint: Boolean = false): List<String> =
        listOf(daily(i, lang, withHint)) + (if (isSunday(now, tz)) listOf(weekly(i, lang, withHint)) else emptyList())

    // ---------- counters (phone side, kept in Store as JSON) ----------

    const val KEEP_DAYS = 8

    /** [json] = `{"yyyy-MM-dd": {"asked":n,"answered":n,"blocked":n,"tamper":n}}`; adds 1 to [key] of [day], drops days beyond [KEEP_DAYS]. */
    fun bump(json: String?, day: String, key: String): String {
        val o = runCatching { JSONObject(json ?: "{}") }.getOrDefault(JSONObject())
        val d = o.optJSONObject(day) ?: JSONObject().also { o.put(day, it) }
        d.put(key, d.optInt(key) + 1)
        val keep = o.keys().asSequence().toList().sortedDescending().drop(KEEP_DAYS)
        keep.forEach { o.remove(it) }
        return o.toString()
    }

    /** state.digest.days: today (even when empty) then older days with counters, newest first. */
    fun days(json: String?, today: String): JSONArray {
        val o = runCatching { JSONObject(json ?: "{}") }.getOrDefault(JSONObject())
        val ks = (o.keys().asSequence().toSet() + today).filter { it <= today }.sortedDescending().take(7)
        return JSONArray().also { a -> ks.forEach { k ->
            val d = o.optJSONObject(k) ?: JSONObject()
            a.put(JSONObject().put("day", k).put("asked", d.optInt("asked")).put("answered", d.optInt("answered")).put("blocked", d.optInt("blocked")).put("tamper", d.optInt("tamper")))
        } }
    }

    /**
     * Last night's bedtime window [from, to) in ms ending no later than [now]; null when [on] is false or the times are bad.
     * Evening start belongs to the previous day when the window crosses midnight and [now] is before [end] ... else today's window.
     */
    fun bedWindow(on: Boolean, start: String, end: String, now: Long, tz: TimeZone = TimeZone.getDefault()): Pair<Long, Long>? {
        if (!on) return null
        val s = minutes(start) ?: return null; val e = minutes(end) ?: return null
        if (s == e) return null
        fun at(dayOffset: Int, min: Int) = Calendar.getInstance(tz).apply {
            timeInMillis = now; add(Calendar.DAY_OF_MONTH, dayOffset)
            set(Calendar.HOUR_OF_DAY, min / 60); set(Calendar.MINUTE, min % 60); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val from: Long; val to: Long
        if (s > e) {   // crosses midnight: the latest window that has begun is last night's if the morning end is past, else tonight's
            val tonight = at(0, s)
            if (now >= tonight) { from = tonight; to = at(1, e) } else { from = at(-1, s); to = at(0, e) }
        } else { from = at(0, s); to = at(0, e); if (now < from) return Pair(at(-1, s), at(-1, e)) }
        return from to minOf(to, now)
    }

    /** Bedtime kept = under 1 minute of use in the window. */
    fun bedKept(usedMs: Long) = usedMs < 60_000L
}
