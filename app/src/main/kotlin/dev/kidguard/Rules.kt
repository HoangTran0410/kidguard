package dev.kidguard

import org.json.JSONArray
import org.json.JSONObject

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
            )
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

/**
 * Which launcher apps to suspend right now, and why. Pure, so it is unit-tested.
 * [usageMs]: foreground ms today per package. [launchable]: apps with a launcher icon. [protected]: never touched.
 */
fun decide(
    r: Rules, usageMs: Map<String, Long>, minuteOfDay: Int, nowMs: Long, today: String,
    launchable: Set<String>, protected: Set<String>,
): Map<String, String> {
    val out = linkedMapOf<String, String>()
    val free = protected + r.allow
    for (p in r.blocked) if (p !in protected) out[p] = "blocked"
    for ((p, min) in r.appMin) if (p !in protected && p !in out && (usageMs[p] ?: 0) >= min * 60_000L) out[p] = "appLimit"

    val bed = r.bedtimeOn && run {
        val s = Rules.parseHm(r.bedStart); val e = Rules.parseHm(r.bedEnd)
        s != null && e != null && Rules.inWindow(minuteOfDay, s, e)
    }
    val paused = r.pausedUntil > nowMs
    val limit = r.dailyMin + if (r.bonusDay == today) r.bonusMin else 0
    val used = usageMs.filterKeys { it in launchable && it !in free }.values.sum()
    val overDaily = r.dailyMin > 0 && used >= limit * 60_000L
    val all = when { paused -> "paused"; bed -> "bedtime"; overDaily -> "daily"; else -> null }
    if (all != null) for (p in launchable) if (p !in free && p !in out) out[p] = all
    return out
}

/** ms of use left today against the daily limit, or -1 when there is none. */
fun dailyLeftMs(r: Rules, usageMs: Map<String, Long>, today: String, launchable: Set<String>, protected: Set<String>): Long {
    if (r.dailyMin <= 0) return -1
    val free = protected + r.allow
    val used = usageMs.filterKeys { it in launchable && it !in free }.values.sum()
    val limit = (r.dailyMin + if (r.bonusDay == today) r.bonusMin else 0) * 60_000L
    return (limit - used).coerceAtLeast(0)
}
