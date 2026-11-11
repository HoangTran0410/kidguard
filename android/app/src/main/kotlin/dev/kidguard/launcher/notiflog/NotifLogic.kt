package dev.kidguard

import org.json.JSONArray
import org.json.JSONObject

/** One message of a MessagingStyle notification. */
data class NotifMsg(val sender: String, val text: String)

/**
 * One remembered notification. [key] = the system's StatusBarNotification key (updates replace the entry).
 * Everything after [text] is optional (old files have none of it): [postTime] / [whenMs] in ms, [removedAt] + [reason]
 * (short code, see [NotifLogic.reason]), [largeIcon] / [picture] = file names under notiflog_img/.
 */
data class NotifEntry(
    val key: String, val time: Long, val pkg: String, val app: String, val title: String, val text: String,
    val postTime: Long = 0, val whenMs: Long = 0, val removedAt: Long = 0, val reason: String = "",
    val channel: String = "", val category: String = "", val subText: String = "", val summary: String = "", val bigText: String = "",   // only when it differs from [text]
    val lines: List<String> = emptyList(), val msgs: List<NotifMsg> = emptyList(), val actions: List<String> = emptyList(),
    val progress: String = "", val group: String = "", val priority: Int = 0, val importance: Int = 0,
    val largeIcon: String = "", val picture: String = "",
) {
    /** The tap time (removed by a click), 0 if it was not clicked. */
    val clickedAt: Long get() = if (reason == NotifLogic.R_CLICK) removedAt else 0
}

/** What [NotifLog.recentClick] chooses from: a notification that was removed, and why. */
data class ClickRec(val key: String, val pkg: String, val removedAt: Long, val reason: String)

/** Pure logic of the kid's notification history: add / dedupe / trim / removal / day buckets / JSON. */
object NotifLogic {
    const val MAX = 300
    const val DAYS = 7
    const val DAY_MS = 24L * 3600 * 1000
    const val MAX_LINES = 8
    const val MAX_MSGS = 10
    const val MAX_ACTIONS = 5
    const val ICON_PX = 256
    const val PIC_W = 720
    const val PIC_H = 1280

    const val R_CLICK = "click"; const val R_SWIPE = "swipe"; const val R_CLEAR = "clear"
    const val R_APP = "app"; const val R_TIMEOUT = "timeout"; const val R_OTHER = "other"

    /** NotificationListenerService.REASON_* (ints are stable API) -> short code. */
    fun reason(r: Int): String = when (r) {
        1 -> R_CLICK            // REASON_CLICK
        2 -> R_SWIPE            // REASON_CANCEL (user dismissed one)
        3 -> R_CLEAR            // REASON_CANCEL_ALL (clear all)
        8, 9 -> R_APP           // REASON_APP_CANCEL / _ALL
        19 -> R_TIMEOUT         // REASON_TIMEOUT
        else -> R_OTHER
    }

    /** An update that shows nothing new (progress ticks, the same text again) while the entry is still live. */
    fun isRepeat(list: List<NotifEntry>, e: NotifEntry): Boolean {
        val old = list.lastOrNull { it.key == e.key } ?: return false
        return old.removedAt == 0L && old.title == e.title && old.text == e.text && old.pkg == e.pkg
    }

    /** Adds [e] (newest last). Same key replaces the old entry; an exact repeat of a live entry is ignored. */
    fun add(list: List<NotifEntry>, e: NotifEntry, now: Long = e.time): List<NotifEntry> {
        if (e.title.isBlank() && e.text.isBlank()) return list
        if (isRepeat(list, e)) return list
        return trim(list.filter { it.key != e.key } + e, now)
    }

    /** Marks the entry of [key] as removed at [at] with [reason]; only a live entry posted at or before [postTime]. */
    fun markRemoved(list: List<NotifEntry>, key: String, postTime: Long, at: Long, reason: String): List<NotifEntry> {
        val i = list.indexOfLast { it.key == key }
        if (i < 0) return list
        val e = list[i]
        if (e.removedAt != 0L || (e.postTime != 0L && postTime != 0L && e.postTime > postTime)) return list
        return list.toMutableList().also { it[i] = e.copy(removedAt = at, reason = reason) }
    }

    /** Drops entries older than 7 days and keeps the newest [MAX]. */
    fun trim(list: List<NotifEntry>, now: Long): List<NotifEntry> {
        val l = list.filter { now - it.time < DAYS * DAY_MS }
        return if (l.size > MAX) l.subList(l.size - MAX, l.size) else l
    }

    /** Image file names an entry points at. */
    fun refs(e: NotifEntry): List<String> = listOf(e.largeIcon, e.picture).filter { it.isNotEmpty() }
    fun refs(list: List<NotifEntry>): Set<String> = list.flatMapTo(HashSet()) { refs(it) }

    /** Files that [before] used and [after] no longer does (trimmed / replaced entries): delete them. */
    fun orphans(before: List<NotifEntry>, after: List<NotifEntry>): Set<String> = refs(before) - refs(after)

    /** Image file name for an entry: kind 'l' (large icon) or 'p' (picture). */
    fun imgName(key: String, postTime: Long, kind: Char) = "${postTime}_${key.hashCode().toUInt()}_$kind.webp"

    /** Size that fits [w]x[h] into [maxW]x[maxH], never upscaling, keeping the aspect ratio. */
    fun fit(w: Int, h: Int, maxW: Int, maxH: Int): Pair<Int, Int> {
        if (w <= 0 || h <= 0) return 1 to 1
        val s = minOf(1.0, maxW.toDouble() / w, maxH.toDouble() / h)
        return maxOf(1, Math.round(w * s).toInt()) to maxOf(1, Math.round(h * s).toInt())
    }

    /**
     * The notification of [pkg] that was removed by a tap within [within] ms of [now] (newest tap wins), among [recs]
     * (the ones whose PendingIntent is still held). Null if none.
     */
    fun pickClick(recs: Collection<ClickRec>, pkg: String, within: Long, now: Long): ClickRec? =
        recs.filter { it.pkg == pkg && it.reason == R_CLICK && it.removedAt in 1..now + 1000 && now - it.removedAt <= within }
            .maxByOrNull { it.removedAt }

    /** Days back from today (0 = today, 1 = yesterday ...) given [today0] = local midnight of today in ms. */
    fun daysAgo(time: Long, today0: Long): Int = if (time >= today0) 0 else ((today0 - time + DAY_MS - 1) / DAY_MS).toInt()

    fun toJson(list: List<NotifEntry>): String = JSONArray().also { a -> for (e in list) a.put(toObj(e)) }.toString()

    fun toObj(e: NotifEntry) = JSONObject().put("k", e.key).put("t", e.time).put("p", e.pkg).put("a", e.app).put("ti", e.title).put("tx", e.text).apply {
        // optional parts are written only when set: an old-style entry costs the same bytes as before
        if (e.postTime != 0L) put("pt", e.postTime)
        if (e.whenMs != 0L) put("w", e.whenMs)
        if (e.removedAt != 0L) put("ra", e.removedAt)
        if (e.reason.isNotEmpty()) put("rr", e.reason)
        if (e.channel.isNotEmpty()) put("ch", e.channel)
        if (e.category.isNotEmpty()) put("ca", e.category)
        if (e.subText.isNotEmpty()) put("st", e.subText)
        if (e.summary.isNotEmpty()) put("su", e.summary)
        if (e.bigText.isNotEmpty()) put("bt", e.bigText)
        if (e.lines.isNotEmpty()) put("ln", JSONArray(e.lines))
        if (e.msgs.isNotEmpty()) put("ms", JSONArray().also { a -> e.msgs.forEach { a.put(JSONObject().put("s", it.sender).put("t", it.text)) } })
        if (e.actions.isNotEmpty()) put("ac", JSONArray(e.actions))
        if (e.progress.isNotEmpty()) put("pg", e.progress)
        if (e.group.isNotEmpty()) put("g", e.group)
        if (e.priority != 0) put("pr", e.priority)
        if (e.importance != 0) put("im", e.importance)
        if (e.largeIcon.isNotEmpty()) put("li", e.largeIcon)
        if (e.picture.isNotEmpty()) put("pic", e.picture)
    }

    private fun strs(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }

    fun fromObj(o: JSONObject): NotifEntry {
        val text = o.optString("tx")
        val ms = o.optJSONArray("ms")
        return NotifEntry(
            o.optString("k"), o.optLong("t"), o.optString("p"), o.optString("a"), o.optString("ti"), text,
            postTime = o.optLong("pt"), whenMs = o.optLong("w"), removedAt = o.optLong("ra"), reason = o.optString("rr"),
            channel = o.optString("ch"), category = o.optString("ca"), subText = o.optString("st"), summary = o.optString("su"),
            bigText = o.optString("bt"),
            lines = strs(o.optJSONArray("ln")), actions = strs(o.optJSONArray("ac")),
            msgs = if (ms == null) emptyList() else (0 until ms.length()).map { val m = ms.getJSONObject(it); NotifMsg(m.optString("s"), m.optString("t")) },
            progress = o.optString("pg"), group = o.optString("g"), priority = o.optInt("pr"), importance = o.optInt("im"),
            largeIcon = o.optString("li"), picture = o.optString("pic"),
        )
    }

    fun fromJson(s: String?): List<NotifEntry> = runCatching {
        val a = JSONArray(s ?: return emptyList())
        (0 until a.length()).map { fromObj(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())
}
