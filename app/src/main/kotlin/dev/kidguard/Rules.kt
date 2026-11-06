package dev.kidguard

import org.json.JSONArray
import org.json.JSONObject

/** A bedtime-like window on chosen days (ISO 1=Mon..7=Sun). It may cross midnight and then belongs to the day it starts. */
data class Schedule(val name: String, val on: Boolean, val days: Set<Int>, val start: String, val end: String) {
    fun toJson(): JSONObject = JSONObject().put("name", name).put("on", on).put("days", JSONArray(days.sorted()))
        .put("start", start).put("end", end)

    /** [dow]: ISO weekday of today. */
    fun active(minute: Int, dow: Int): Boolean {
        if (!on) return false
        val s = Rules.parseHm(start) ?: return false; val e = Rules.parseHm(end) ?: return false
        return when {
            s == e -> false
            s < e -> dow in days && minute in s until e
            else -> (dow in days && minute >= s) || ((if (dow == 1) 7 else dow - 1) in days && minute < e)
        }
    }

    companion object {
        fun fromJson(o: JSONObject): Schedule? {
            val st = o.optString("start", ""); val en = o.optString("end", "")
            if (Rules.parseHm(st) == null || Rules.parseHm(en) == null) return null
            val a = o.optJSONArray("days") ?: return null
            val days = (0 until a.length()).map { a.optInt(it, 0) }.filter { it in 1..7 }.toSet()
            if (days.isEmpty()) return null
            return Schedule(o.optString("name", "").take(40), o.optBoolean("on", true), days, st, en)
        }
    }
}

/** What the parent set. Plain data + JSON, so the same shape travels over LAN and the relay. */
data class Rules(
    val rev: Long = 0,                       // ms of the last edit; the newer one wins
    val dailyMin: Int = 0,                   // 0 = no daily limit
    val appMin: Map<String, Int> = emptyMap(),
    val blocked: Set<String> = emptySet(),
    val bedtimeOn: Boolean = false,
    val bedStart: String = "21:30",
    val bedEnd: String = "06:30",
    val allow: Set<String> = emptySet(),     // never blocked by daily / bedtime / pause
    val pausedUntil: Long = 0,
    val bonusDay: String = "",
    val bonusMin: Int = 0,
    val noInstall: Boolean = false,
    val noDebug: Boolean = false,
    val pinHash: String = "",                // sha256 of the parent PIN, guards "pair another device" on the child
    val dailyByDay: List<Int> = emptyList(), // Monday..Sunday minutes, or empty; replaces dailyMin when present
    val schedules: List<Schedule> = emptyList(),
    val blockNew: Boolean = false,           // apps installed after setup stay blocked until approved
    val approved: Set<String> = emptySet(),
    val lockLocation: Boolean = true,
    val noAirplane: Boolean = true,
    val safeDns: String = "",                // Private DNS host, "" = off
) {
    fun toJson(): JSONObject = JSONObject()
        .put("rev", rev).put("dailyMin", dailyMin)
        .put("appMin", JSONObject().apply { appMin.forEach { (k, v) -> put(k, v) } })
        .put("blocked", JSONArray(blocked.sorted()))
        .put("bedtime", JSONObject().put("on", bedtimeOn).put("start", bedStart).put("end", bedEnd))
        .put("allow", JSONArray(allow.sorted()))
        .put("pausedUntil", pausedUntil)
        .put("bonus", JSONObject().put("day", bonusDay).put("min", bonusMin))
        .put("noInstall", noInstall).put("noDebug", noDebug)
        .put("pinHash", pinHash)
        .put("dailyByDay", JSONArray(dailyByDay))
        .put("schedules", JSONArray().apply { schedules.forEach { put(it.toJson()) } })
        .put("blockNew", blockNew).put("approved", JSONArray(approved.sorted()))
        .put("lockLocation", lockLocation).put("noAirplane", noAirplane).put("safeDns", safeDns)

    companion object {
        fun fromJson(o: JSONObject): Rules {
            val bed = o.optJSONObject("bedtime") ?: JSONObject()
            val bonus = o.optJSONObject("bonus") ?: JSONObject()
            val appMin = mutableMapOf<String, Int>()
            o.optJSONObject("appMin")?.let { m -> m.keys().forEach { k -> m.optInt(k, 0).takeIf { it > 0 }?.let { appMin[k] = it } } }
            return Rules(
                rev = o.optLong("rev", 0),
                dailyMin = o.optInt("dailyMin", 0).coerceIn(0, 24 * 60),
                appMin = appMin,
                blocked = o.optJSONArray("blocked").strings(),
                bedtimeOn = bed.optBoolean("on", false),
                bedStart = bed.optString("start", "21:30").takeIf { parseHm(it) != null } ?: "21:30",
                bedEnd = bed.optString("end", "06:30").takeIf { parseHm(it) != null } ?: "06:30",
                allow = o.optJSONArray("allow").strings(),
                pausedUntil = o.optLong("pausedUntil", 0),
                bonusDay = bonus.optString("day", ""),
                bonusMin = bonus.optInt("min", 0).coerceIn(0, 24 * 60),
                noInstall = o.optBoolean("noInstall", false),
                noDebug = o.optBoolean("noDebug", false),
                // the page sends a new PIN in plain ("pin"); only its hash is kept
                pinHash = o.optString("pin", "").filter { it.isDigit() }.takeIf { it.length >= 4 }?.let { Store.sha256("kg:$it") }
                    ?: o.optString("pinHash", ""),
                dailyByDay = o.optJSONArray("dailyByDay")?.let { a ->
                    (0 until a.length()).map { a.optInt(it, -1) }.takeIf { l -> l.size == 7 && l.all { it in 0..24 * 60 } }
                } ?: emptyList(),
                schedules = o.optJSONArray("schedules")?.let { a ->
                    (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(Schedule::fromJson) }.take(20)
                } ?: emptyList(),
                blockNew = o.optBoolean("blockNew", false),
                approved = o.optJSONArray("approved").strings(),
                lockLocation = o.optBoolean("lockLocation", true),
                noAirplane = o.optBoolean("noAirplane", true),
                safeDns = o.optString("safeDns", "").trim().lowercase().takeIf { it.matches(DNS_HOST) } ?: "",
            )
        }

        private val DNS_HOST = Regex("[a-z0-9]([a-z0-9.-]{0,251}[a-z0-9])?")

        /** ISO weekday (1=Mon..7=Sun) of "yyyy-MM-dd", or 1 if unparsable. Pure, no java.time (old API levels). */
        fun isoDow(day: String): Int {
            val p = day.split("-").mapNotNull { it.toIntOrNull() }
            if (p.size != 3) return 1
            val y = if (p[1] <= 2) p[0] - 1 else p[0]
            val t = intArrayOf(0, 3, 2, 5, 0, 3, 5, 1, 4, 6, 2, 4)
            val sun0 = (y + y / 4 - y / 100 + y / 400 + t[(p[1] - 1).coerceIn(0, 11)] + p[2]) % 7   // 0=Sunday
            return if (sun0 == 0) 7 else sun0
        }

        private fun JSONArray?.strings(): Set<String> =
            if (this == null) emptySet() else (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotBlank() } }.toSet()

        /** "21:30" -> 1290 minutes, or null. */
        fun parseHm(s: String): Int? {
            val p = s.split(":")
            if (p.size != 2) return null
            val h = p[0].toIntOrNull() ?: return null
            val m = p[1].toIntOrNull() ?: return null
            return if (h in 0..23 && m in 0..59) h * 60 + m else null
        }

        /** Is minute-of-day [now] inside [start, end), the window may cross midnight. */
        fun inWindow(now: Int, start: Int, end: Int): Boolean = when {
            start == end -> false
            start < end -> now in start until end
            else -> now >= start || now < end
        }
    }
}

/** Today's daily limit in minutes without bonus (0 = none): the weekday entry of dailyByDay when present. */
fun baseLimitMin(r: Rules, today: String): Int = if (r.dailyByDay.size == 7) r.dailyByDay[Rules.isoDow(today) - 1] else r.dailyMin

/**
 * Which launcher apps to suspend right now, and why. Pure, so it is unit-tested.
 * [usageMs]: foreground ms today per package. [launchable]: apps with a launcher icon. [protected]: never touched.
 * [newApps]: installed after setup (only looked at when blockNew). Priority: blocked > appLimit > new > paused > schedule > bedtime > daily.
 */
fun decide(
    r: Rules, usageMs: Map<String, Long>, minuteOfDay: Int, nowMs: Long, today: String,
    launchable: Set<String>, protected: Set<String>, newApps: Set<String> = emptySet(),
): Map<String, String> {
    val out = linkedMapOf<String, String>()
    val free = protected + r.allow
    for (p in r.blocked) if (p !in protected) out[p] = "blocked"
    for ((p, min) in r.appMin) if (p !in protected && p !in out && (usageMs[p] ?: 0) >= min * 60_000L) out[p] = "appLimit"
    if (r.blockNew) for (p in newApps) if (p !in free && p !in r.approved && p !in out) out[p] = "new"

    val dow = Rules.isoDow(today)
    val bed = r.bedtimeOn && run {
        val s = Rules.parseHm(r.bedStart); val e = Rules.parseHm(r.bedEnd)
        s != null && e != null && Rules.inWindow(minuteOfDay, s, e)
    }
    val sched = r.schedules.any { it.active(minuteOfDay, dow) }
    val paused = r.pausedUntil > nowMs
    val base = baseLimitMin(r, today)
    val limit = base + if (r.bonusDay == today) r.bonusMin else 0
    val used = usageMs.filterKeys { it in launchable && it !in free }.values.sum()
    val overDaily = base > 0 && used >= limit * 60_000L
    val all = when { paused -> "paused"; sched -> "schedule"; bed -> "bedtime"; overDaily -> "daily"; else -> null }
    if (all != null) for (p in launchable) if (p !in free && p !in out) out[p] = all
    return out
}

/** ms of use left today against the daily limit, or -1 when there is none. */
fun dailyLeftMs(r: Rules, usageMs: Map<String, Long>, today: String, launchable: Set<String>, protected: Set<String>): Long {
    val base = baseLimitMin(r, today)
    if (base <= 0) return -1
    val free = protected + r.allow
    val used = usageMs.filterKeys { it in launchable && it !in free }.values.sum()
    val limit = (base + if (r.bonusDay == today) r.bonusMin else 0) * 60_000L
    return (limit - used).coerceAtLeast(0)
}
