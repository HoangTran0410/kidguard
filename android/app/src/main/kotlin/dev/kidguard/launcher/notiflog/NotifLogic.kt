package dev.kidguard

import org.json.JSONArray
import org.json.JSONObject

/** One remembered notification. [key] = the system's StatusBarNotification key (updates replace the entry). */
data class NotifEntry(val key: String, val time: Long, val pkg: String, val app: String, val title: String, val text: String)

/** Pure logic of the kid's notification history: add / dedupe / trim / day buckets / JSON. */
object NotifLogic {
    const val MAX = 300
    const val DAYS = 7
    const val DAY_MS = 24L * 3600 * 1000

    /** Adds [e] (newest last). Same key replaces the old entry; an exact repeat of the same key's content is ignored. */
    fun add(list: List<NotifEntry>, e: NotifEntry, now: Long = e.time): List<NotifEntry> {
        if (e.title.isBlank() && e.text.isBlank()) return list
        val old = list.lastOrNull { it.key == e.key }
        if (old != null && old.title == e.title && old.text == e.text && old.pkg == e.pkg) return list
        return trim(list.filter { it.key != e.key } + e, now)
    }

    /** Drops entries older than 7 days and keeps the newest [MAX]. */
    fun trim(list: List<NotifEntry>, now: Long): List<NotifEntry> {
        val l = list.filter { now - it.time < DAYS * DAY_MS }
        return if (l.size > MAX) l.subList(l.size - MAX, l.size) else l
    }

    /** Days back from today (0 = today, 1 = yesterday ...) given [today0] = local midnight of today in ms. */
    fun daysAgo(time: Long, today0: Long): Int = if (time >= today0) 0 else ((today0 - time + DAY_MS - 1) / DAY_MS).toInt()

    fun toJson(list: List<NotifEntry>): String = JSONArray().also { a ->
        for (e in list) a.put(JSONObject().put("k", e.key).put("t", e.time).put("p", e.pkg).put("a", e.app).put("ti", e.title).put("tx", e.text))
    }.toString()

    fun fromJson(s: String?): List<NotifEntry> = runCatching {
        val a = JSONArray(s ?: return emptyList())
        (0 until a.length()).map { val o = a.getJSONObject(it); NotifEntry(o.optString("k"), o.optLong("t"), o.optString("p"), o.optString("a"), o.optString("ti"), o.optString("tx")) }
    }.getOrDefault(emptyList())
}
