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

    // ---- attachments (2026-11-17): entry.att = {id, name, mime, size, kind, dur?, st}; id = the Files index id ----
    val KINDS = setOf("image", "video", "audio", "file")
    val STS = setOf("ok", "wait", "gone")

    /** "image" | "video" | "audio" | "file" from the mime type, else from the extension of [name]. */
    fun kind(mime: String?, name: String = ""): String {
        val m = (mime ?: "").lowercase().substringBefore(';').trim()
        when {
            m.startsWith("image/") -> return "image"
            m.startsWith("video/") -> return "video"
            m.startsWith("audio/") -> return "audio"
        }
        return when (name.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp" -> "image"
            "mp4", "mov", "3gp", "mkv", "webm", "avi" -> "video"
            "m4a", "mp3", "aac", "ogg", "opus", "wav", "amr" -> "audio"
            else -> "file"
        }
    }

    /** One attachment object; [dur] seconds only for audio / video (0 = unknown, left out). */
    fun att(id: String, name: String, mime: String, size: Long, dur: Int = 0, st: String = "ok"): JSONObject =
        JSONObject().put("id", id).put("name", name.take(120)).put("mime", mime.take(80)).put("size", size)
            .put("kind", kind(mime, name)).also { if (dur > 0) it.put("dur", dur) }.put("st", if (st in STS) st else "ok")

    /** A received / stored attachment object checked again (id shape, kind, st); null = not usable. */
    fun cleanAtt(o: JSONObject?): JSONObject? {
        if (o == null || !FilesLogic.isId(o.optString("id"))) return null
        return att(o.optString("id"), o.optString("name").ifEmpty { "file" }, o.optString("mime", "application/octet-stream"), o.optLong("size"),
            o.optInt("dur", 0).coerceIn(0, 86_400), o.optString("st", "ok"))
    }

    /** What the reader sees: "gone" when the file was deleted (stored or no longer in the index), "wait" for a kid file no parent has fetched, else "ok". */
    fun attSt(stored: String?, inIndex: Boolean, waiting: Boolean): String = when {
        stored == "gone" || !inIndex -> "gone"
        waiting -> "wait"
        else -> "ok"
    }

    /** [a] with att.st of every entry derived from [look] (file id -> null = missing from the index, else true = kid file still waiting). */
    fun decorate(a: JSONArray, look: (String) -> Boolean?): JSONArray {
        var any = false
        val out = JSONArray()
        for (i in 0 until a.length()) {
            val e = a.optJSONObject(i)
            val at = e?.optJSONObject("att")
            if (e == null || at == null) { out.put(a.get(i)); continue }
            val l = look(at.optString("id"))
            val st = attSt(at.optString("st"), l != null, l == true)
            if (st == at.optString("st")) { out.put(e); continue }
            any = true
            out.put(JSONObject(e.toString()).also { it.put("att", JSONObject(at.toString()).put("st", st)) })
        }
        return if (any) out else a
    }

    /** The attachment of file [fileId] is deleted: st "gone", the text stays. Same instance when nothing matches. */
    fun markGone(a: JSONArray, fileId: String): JSONArray {
        var hit = false
        val out = JSONArray()
        for (i in 0 until a.length()) {
            val e = a.optJSONObject(i)
            if (e?.optJSONObject("att")?.let { it.optString("id") == fileId && it.optString("st") != "gone" } == true) {
                hit = true; out.put(JSONObject(e.toString()).also { it.getJSONObject("att").put("st", "gone") })
            } else out.put(a.get(i))
        }
        return if (hit) out else a
    }

    /** Short text for the event / notice of an attachment-only entry: its file name. */
    fun preview(text: String, att: JSONObject?): String = text.ifEmpty { att?.optString("name") ?: "" }

    /** [a] + one message, newest last, the same id twice is ignored, only the last [KEEP] kept. Empty text without [att] / bad side = unchanged. */
    fun add(a: JSONArray, from: String, who: String, text: String, now: Long, id: String, att: JSONObject? = null): JSONArray {
        val t = clean(text)
        val at = cleanAtt(att)
        if ((t.isEmpty() && at == null) || (from != "p" && from != "k")) return a
        val out = ArrayList<JSONObject>()
        for (i in 0 until a.length()) a.optJSONObject(i)?.let { if (it.optString("id") == id) return a else out += it }
        out += JSONObject().put("id", id).put("t", now).put("from", from).put("who", if (from == "p") who(who) else "").put("text", t).also { if (at != null) it.put("att", at) }
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
