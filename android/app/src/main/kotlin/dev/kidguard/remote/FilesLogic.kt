package dev.kidguard

import org.json.JSONArray
import org.json.JSONObject

/**
 * Shared folder between parent and child (docs/protocol.md "Shared files"): pure parts, Android-free and unit-tested.
 * It is a folder of what was SENT (parent -> kid "in", kid -> parent "out"), never a view of the phone's storage.
 */
object FilesLogic {
    const val PULL_RETRY_MS = 10 * 60_000L   // a relay file that could not be saved is tried again this much later (it lives 1 h there)
    const val MAX_LAN = 200L * 1024 * 1024      // one LAN upload / one shared file
    const val MAX_RELAY = 10L * 1024 * 1024     // through the worker (1 MiB chunks in the Durable Object)
    const val CHUNK = 1024 * 1024
    const val MAX_NAME = 120                    // chars, extension kept
    const val MAX_ENTRIES = 100                 // index size; oldest dropped first
    const val MAX_OUT = 50                      // files in the outbox
    const val STATE_N = 30                      // newest entries in the state JSON

    private val ID = Regex("^[0-9a-f]{16}$")
    private val BAD = Regex("[<>:\"|?*]")
    private val SPACE = Regex("\\s+")

    fun isId(s: String?) = s != null && ID.matches(s)

    /**
     * A file name from the parent or another app -> a safe single name, or null (rejected).
     * Rejected: any path separator, control chars, "." / "..", blank. Cleaned: reserved chars -> "_", leading dots
     * (hidden / ".." tricks) dropped, spaces collapsed, cut to [MAX_NAME] chars keeping a short extension.
     */
    fun cleanName(raw: String): String? {
        if (raw.isEmpty() || raw.length > 1024) return null
        if (raw.any { it == '/' || it == '\\' || it.code < 32 || it.code == 127 }) return null
        var n = SPACE.replace(raw, " ").trim()
        if (n == "." || n == "..") return null
        n = n.trimStart('.', ' ').trimEnd('.', ' ')
        n = BAD.replace(n, "_")
        if (n.isEmpty()) return null
        if (n.length > MAX_NAME) {
            val dot = n.lastIndexOf('.')
            val ext = if (dot > 0 && n.length - dot <= 16) n.substring(dot) else ""
            n = n.substring(0, MAX_NAME - ext.length).trimEnd('.', ' ') + ext
        }
        return n
    }

    /** "a.pdf" taken -> "a (1).pdf", "a (2).pdf"... */
    fun unique(name: String, taken: (String) -> Boolean): String {
        if (!taken(name)) return name
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        for (i in 1..999) { val c = "$stem ($i)$ext"; if (!taken(c)) return c }
        return "$stem (${System.nanoTime() % 100000})$ext"
    }

    /** null = ok, else the error code the API answers. */
    fun sizeError(size: Long, max: Long): String? = when {
        size < 0 -> "length required"
        size > max -> "too big"
        else -> null
    }

    /** All files a parent may leave in the phone's Download/KidGuard through the app (sum of the index): one full disk is no use to anyone. */
    const val MAX_IN_TOTAL = 1024L * 1024 * 1024
    /** "empty" for a zero-byte file, "quota" when [used] + [len] goes past [MAX_IN_TOTAL]; null = ok. */
    fun intakeError(len: Long, used: Long): String? = when {
        len <= 0 -> "empty"
        used + len > MAX_IN_TOTAL -> "quota"
        else -> null
    }

    fun chunks(size: Long) = maxOf(1L, (size + CHUNK - 1) / CHUNK).toInt()

    fun ext(name: String) = name.substringAfterLast('.', "").lowercase().takeIf { it.length in 1..10 && it.all(Char::isLetterOrDigit) } ?: ""

    /** Content-Disposition for a download: ASCII fallback + RFC 5987 UTF-8 name. */
    fun disposition(name: String): String {
        val ascii = name.map { if (it.code in 32..126 && it != '"' && it != '\\') it else '_' }.joinToString("")
        val pct = StringBuilder()
        for (b in name.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xff
            if (c.toChar().isLetterOrDigit() && c < 128 || c.toChar() in "-._~") pct.append(c.toChar()) else pct.append('%').append("%02X".format(c))
        }
        return "attachment; filename=\"$ascii\"; filename*=UTF-8''$pct"
    }

    /** One shared file. dir "in" = parent -> kid (in Download/KidGuard), "out" = kid -> parent (app outbox). [loc] stays on the phone. */
    data class Entry(val id: String, val dir: String, val name: String, val size: Long, val at: Long, val mime: String,
                     val st: String, val loc: String = "") {
        fun toJson(full: Boolean = false): JSONObject = JSONObject().put("id", id).put("dir", dir).put("name", name)
            .put("size", size).put("at", at).put("st", st).also { if (full) it.put("mime", mime).put("loc", loc) }

        companion object {
            fun fromJson(o: JSONObject): Entry? {
                val id = o.optString("id"); val dir = o.optString("dir")
                if (!isId(id) || (dir != IN && dir != OUT)) return null
                return Entry(id, dir, o.optString("name"), o.optLong("size"), o.optLong("at"), o.optString("mime", OCTET),
                    o.optString("st"), o.optString("loc"))
            }
        }
    }

    const val IN = "in"; const val OUT = "out"; const val OCTET = "application/octet-stream"
    // "in": delivered (saved on the phone) -> opened (the kid tapped it) / gone (deleted on the phone);
    // "out": new -> fetched (a parent downloaded it)
    const val DELIVERED = "delivered"; const val OPENED = "opened"; const val GONE = "gone"; const val NEW = "new"; const val FETCHED = "fetched"

    /** Newest first, at most [limit]: what the page shows (GET /api/files, state "files"). */
    fun listJson(list: List<Entry>, limit: Int = MAX_ENTRIES): JSONArray =
        JSONArray().also { a -> list.sortedByDescending { it.at }.take(limit).forEach { a.put(it.toJson()) } }

    fun parseIndex(s: String?): List<Entry> = runCatching {
        val a = JSONArray(s ?: "[]"); (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(Entry::fromJson) }
    }.getOrDefault(emptyList())

    fun indexJson(list: List<Entry>): String = JSONArray().also { a -> list.forEach { a.put(it.toJson(true)) } }.toString()

    /** Adds [e]; over [MAX_ENTRIES] / [MAX_OUT] returns which old entries to drop (oldest first) along with the new list. */
    fun add(list: List<Entry>, e: Entry): Pair<List<Entry>, List<Entry>> {
        val all = (list.filter { it.id != e.id } + e).sortedBy { it.at }
        val drop = mutableListOf<Entry>()
        val outs = all.filter { it.dir == OUT }
        if (outs.size > MAX_OUT) drop += outs.take(outs.size - MAX_OUT)
        val rest = all - drop.toSet()
        if (rest.size > MAX_ENTRIES) drop += rest.take(rest.size - MAX_ENTRIES)
        return (all - drop.toSet()) to drop
    }
}
