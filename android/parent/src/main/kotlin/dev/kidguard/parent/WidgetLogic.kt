package dev.kidguard.parent

import org.json.JSONObject

/** Pure part of the home-screen widget ([KidWidget]): what one row says. No Android classes, unit-tested in WidgetLogicTest. */
object WidgetLogic {
    const val MAX_KIDS = 3
    const val PLUS_MIN = 15

    /** What the kid's phone last told us. [leftMs] -1 = unknown (older phone). [seenMs] = the phone's own clock at that state (0 = never). */
    data class Snap(val why: String = "", val limitMin: Int = 0, val leftMs: Long = -1, val seenMs: Long = 0, val at: Long = 0) {
        fun toJson(): JSONObject = JSONObject().put("why", why).put("limitMin", limitMin).put("leftMs", leftMs).put("seen", seenMs).put("at", at)
        companion object {
            fun fromJson(o: JSONObject?): Snap? = o?.let { Snap(it.optString("why"), it.optInt("limitMin"), it.optLong("leftMs", -1), it.optLong("seen"), it.optLong("at")) }
        }
    }

    enum class Kind { LEFT, NO_LIMIT, LOCKED, PAUSED, BEDTIME, UNKNOWN }
    /** [min] = minutes left (rounded up) for [Kind.LEFT]. */
    data class Status(val kind: Kind, val min: Int = 0)

    /** state.now.why (lost > paused > schedule > bedtime > daily; "" / pass / code = nothing blocks) plus today's limit and time left. */
    fun status(s: Snap?): Status {
        if (s == null) return Status(Kind.UNKNOWN)
        return when (s.why) {
            "paused" -> Status(Kind.PAUSED)
            "bedtime" -> Status(Kind.BEDTIME)
            "lost", "schedule", "daily" -> Status(Kind.LOCKED)
            else -> when {
                s.limitMin <= 0 -> Status(Kind.NO_LIMIT)
                s.leftMs < 0 -> Status(Kind.UNKNOWN)
                s.leftMs == 0L -> Status(Kind.LOCKED)
                else -> Status(Kind.LEFT, ((s.leftMs + 59_999) / 60_000).toInt())
            }
        }
    }

    /** "45m", "1h 20m", "2h". */
    fun duration(min: Int): String {
        val m = maxOf(0, min)
        return if (m < 60) "${m}m" else if (m % 60 == 0) "${m / 60}h" else "${m / 60}h ${m % 60}m"
    }

    enum class Unit { NOW, MIN, HOUR, DAY, NEVER }
    data class Ago(val unit: Unit, val n: Int = 0)

    /** "Last seen" from the phone's own timestamp [seenMs] (0 = never). */
    fun ago(seenMs: Long, now: Long): Ago {
        if (seenMs <= 0) return Ago(Unit.NEVER)
        val min = maxOf(0L, now - seenMs) / 60_000
        return when {
            min < 1 -> Ago(Unit.NOW)
            min < 60 -> Ago(Unit.MIN, min.toInt())
            min < 48 * 60 -> Ago(Unit.HOUR, (min / 60).toInt())
            else -> Ago(Unit.DAY, (min / 1440).toInt())
        }
    }

    /** Snapshots by kid hash <-> the JSON kept in [Store]. Only the kids in [keep] survive (a removed kid leaves no data). */
    fun parseAll(json: String): Map<String, Snap> = try {
        val o = JSONObject(json)
        o.keys().asSequence().mapNotNull { k -> Snap.fromJson(o.optJSONObject(k))?.let { k to it } }.toMap()
    } catch (e: Exception) { emptyMap() }
    fun toJsonAll(m: Map<String, Snap>, keep: Set<String>): String =
        JSONObject().also { o -> m.forEach { (k, v) -> if (k in keep) o.put(k, v.toJson()) } }.toString()

    /** Transient button feedback: "sending" / "sent" / "fail" shown in place of the last-seen line until [until]. */
    data class Fb(val kind: String, val until: Long)
    const val SENDING_MS = 25_000L
    const val RESULT_MS = 6_000L
    fun fbParse(json: String): Map<String, Fb> = try {
        val o = JSONObject(json)
        o.keys().asSequence().mapNotNull { k -> o.optJSONObject(k)?.let { k to Fb(it.optString("k"), it.optLong("u")) } }.toMap()
    } catch (e: Exception) { emptyMap() }
    fun fbJson(m: Map<String, Fb>, now: Long): String =
        JSONObject().also { o -> m.forEach { (k, v) -> if (v.until > now) o.put(k, JSONObject().put("k", v.kind).put("u", v.until)) } }.toString()
    /** A new tap is ignored while the previous one is still sending. */
    fun busy(f: Fb?, now: Long) = f != null && f.kind == "sending" && f.until > now
}
