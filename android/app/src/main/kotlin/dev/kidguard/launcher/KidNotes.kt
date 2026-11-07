package dev.kidguard

import org.json.JSONArray
import org.json.JSONObject

/**
 * The kid's own notes and homework list on the launcher's "Hôm nay" page (home.json `notes` / `todo`, protocol
 * "v0.9.9: kid launcher"). Pure, unit-tested. A note is private unless the kid turns on "Bố mẹ xem được" for it;
 * only those go to the state. Homework never leaves the phone.
 */
object KidNotes {
    const val TEXT_MAX = 500
    const val NOTES_MAX = 50
    const val TODO_MAX = 50
    const val TODO_TEXT_MAX = 100
    const val STATE_N = 20

    data class Note(val id: String, val t: Long, val text: String, val share: Boolean) {
        fun toJson(): JSONObject = JSONObject().put("id", id).put("t", t).put("text", text).put("share", share)
    }
    data class Todo(val id: String, val text: String, val done: Boolean) {
        fun toJson(): JSONObject = JSONObject().put("id", id).put("text", text).put("done", done)
    }

    /** Multi-line text: control chars except new lines out, at most 3 blank lines in a row, [max] chars. */
    fun clean(s: String, max: Int): String = s.replace("\r", "").map { if (it.isISOControl() && it != '\n') ' ' else it }.joinToString("")
        .replace(Regex("\n{4,}"), "\n\n\n").trim().take(max)

    fun notes(a: JSONArray?): List<Note> = (0 until (a?.length() ?: 0)).mapNotNull { i ->
        val o = a!!.optJSONObject(i) ?: return@mapNotNull null
        val text = clean(o.optString("text"), TEXT_MAX)
        val id = o.optString("id").take(16)
        if (text.isEmpty() || id.isEmpty()) null else Note(id, o.optLong("t"), text, o.optBoolean("share", false))
    }.distinctBy { it.id }.sortedByDescending { it.t }.take(NOTES_MAX)

    fun todos(a: JSONArray?): List<Todo> = (0 until (a?.length() ?: 0)).mapNotNull { i ->
        val o = a!!.optJSONObject(i) ?: return@mapNotNull null
        val text = Notes.clean(o.optString("text"), TODO_TEXT_MAX)
        val id = o.optString("id").take(16)
        if (text.isEmpty() || id.isEmpty()) null else Todo(id, text, o.optBoolean("done", false))
    }.distinctBy { it.id }.take(TODO_MAX)

    fun notesJson(l: List<Note>) = JSONArray().apply { l.forEach { put(it.toJson()) } }
    fun todosJson(l: List<Todo>) = JSONArray().apply { l.forEach { put(it.toJson()) } }

    private fun newId(used: Set<String>, prefix: String): String { var n = 1; while ("$prefix$n" in used) n++; return "$prefix$n" }

    /** Adds or edits a note ([id] null = new). Empty text deletes it. */
    fun putNote(l: List<Note>, id: String?, text: String, share: Boolean, now: Long): List<Note> {
        val t = clean(text, TEXT_MAX)
        if (id != null && l.none { it.id == id }) return l
        if (t.isEmpty()) return if (id == null) l else l.filter { it.id != id }
        return if (id == null) (listOf(Note(newId(l.map { it.id }.toSet(), "n"), now, t, share)) + l).take(NOTES_MAX)
        else l.map { if (it.id == id) it.copy(text = t, share = share, t = if (it.text != t) now else it.t) else it }.sortedByDescending { it.t }
    }

    fun addTodo(l: List<Todo>, text: String): List<Todo> {
        val t = Notes.clean(text, TODO_TEXT_MAX)
        if (t.isEmpty() || l.size >= TODO_MAX) return l
        return l + Todo(newId(l.map { it.id }.toSet(), "h"), t, false)
    }
    fun toggle(l: List<Todo>, id: String) = l.map { if (it.id == id) it.copy(done = !it.done) else it }
    fun dropTodo(l: List<Todo>, id: String) = l.filter { it.id != id }

    /** state.notes: the shared ones only, newest [STATE_N], no `share` field. */
    fun stateJson(a: JSONArray?): JSONArray = JSONArray().apply {
        notes(a).filter { it.share }.take(STATE_N).forEach { put(JSONObject().put("id", it.id).put("t", it.t).put("text", it.text)) }
    }
}
