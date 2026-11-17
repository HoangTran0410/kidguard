package dev.kidguard

import org.json.JSONArray
import org.json.JSONObject

/**
 * Family chat (2026-11-17): one thread per child phone, text only, both directions.
 * Entry = {id, t, from: "p" (a parent) | "k" (the child), who (the parent's name, "" for the child), text}.
 * Pure and Android-free (unit-tested); Store keeps the last [KEEP], state carries the last [STATE].
 */
object ChatLogic {
    const val KEEP = 100
    const val STATE = 30
    const val MAX_TEXT = 500
    /** Text of the event the parent app notifies with (the app prefixes the kid's name). */
    const val EVENT_TEXT = 120

    /** Trimmed, control characters (except new lines) dropped, at most [MAX_TEXT]. Empty = nothing to send. */
    fun clean(t: String?): String = (t ?: "").filter { it == '\n' || !it.isISOControl() }.trim().take(MAX_TEXT)

    fun who(s: String?): String = (s ?: "").filter { !it.isISOControl() && it != '<' && it != '>' }.trim().take(30)

    /** [a] + one message, newest last, the same id twice is ignored, only the last [KEEP] kept. Empty text / bad side = unchanged. */
    fun add(a: JSONArray, from: String, who: String, text: String, now: Long, id: String): JSONArray {
        val t = clean(text)
        if (t.isEmpty() || (from != "p" && from != "k")) return a
        val out = ArrayList<JSONObject>()
        for (i in 0 until a.length()) a.optJSONObject(i)?.let { if (it.optString("id") == id) return a else out += it }
        out += JSONObject().put("id", id).put("t", now).put("from", from).put("who", if (from == "p") who(who) else "").put("text", t)
        return JSONArray().also { j -> out.takeLast(KEEP).forEach { j.put(it) } }
    }

    /** Rows per "older messages" page (default 30, max 50). */
    fun count(n: Int): Int = if (n <= 0) STATE else minOf(n, 50)

    /**
     * One page for the parent's "older messages": entries with `t < before` (0 = from the newest), the newest [n] of them,
     * oldest first, plus `more` = there are still older ones. Pure: the phone passes its whole kept thread.
     */
    fun page(a: JSONArray, before: Long, n: Int): JSONObject {
        val old = ArrayList<JSONObject>()
        for (i in 0 until a.length()) a.optJSONObject(i)?.let { if (before <= 0 || it.optLong("t") < before) old += it }
        val take = old.takeLast(n)
        return JSONObject().put("chat", JSONArray().also { j -> take.forEach { j.put(it) } }).put("more", old.size > take.size)
    }

    /** A parent message the child has not opened yet: the newest parent message is newer than the last time the chat was read. */
    fun unread(lastParent: Long, readAt: Long) = lastParent > 0 && lastParent > readAt

    /** The last [n] entries (what state carries). */
    fun tail(a: JSONArray, n: Int = STATE): JSONArray = JSONArray().also { j -> for (i in maxOf(0, a.length() - n) until a.length()) j.put(a.get(i)) }
}
