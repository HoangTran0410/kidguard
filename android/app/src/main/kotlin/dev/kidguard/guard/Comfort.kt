package dev.kidguard

import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure logic for the "comfort" features (protocol v0.9.7): bedtime wind-down (grayscale), lock-screen info, the
 * friendlier block message and the child's check-in buttons. Android-free, unit-tested (ComfortTest).
 */
object WindDown {
    val STEPS = setOf(0, 15, 30, 45, 60)
    const val NUDGE_MIN = 10

    /** Grayscale wanted now: bedtime on, wind-down on, and [minute] is inside [bedStart - windMin, bedEnd). */
    fun active(r: Rules, minute: Int): Boolean {
        if (!r.bedtimeOn || !r.windGray) return false
        val s = Rules.parseHm(r.bedStart) ?: return false
        val e = Rules.parseHm(r.bedEnd) ?: return false
        if (s == e) return false
        val from = ((s - r.windMin) % 1440 + 1440) % 1440
        // a lead so long that it reaches the end of the window: the whole bedtime window still counts
        return Rules.inWindow(minute, from, e) || Rules.inWindow(minute, s, e)
    }
}

/**
 * Grayscale via Settings.Secure (needs WRITE_SECURE_SETTINGS; DPM.setSecureSetting does not allow these keys).
 * We remember what the phone had before we switched it on and put exactly that back.
 */
object Gray {
    const val KEY_ON = "accessibility_display_daltonizer_enabled"
    const val KEY_MODE = "accessibility_display_daltonizer"
    const val MONO = "0"            // monochromacy
    const val DEFAULT_MODE = "12"   // AOSP default when the key was never set (deuteranomaly)

    /** What the phone had before; null = the key did not exist. */
    data class Saved(val on: String?, val mode: String?)

    /** [write]: key -> value to put (in this order). [owned]: we hold the setting afterwards. [saved]: to keep. */
    data class Step(val write: List<Pair<String, String>>, val owned: Boolean, val saved: Saved?)

    /** One Guard tick. Writes only what differs, so a tick with the right state does nothing. */
    fun step(want: Boolean, owned: Boolean, saved: Saved?, curOn: String?, curMode: String?): Step {
        val w = ArrayList<Pair<String, String>>()
        if (want) {
            if (curMode != MONO) w += KEY_MODE to MONO
            if (curOn != "1") w += KEY_ON to "1"
            return Step(w, true, if (owned) saved else Saved(curOn, curMode))
        }
        if (!owned) return Step(w, false, null)
        val r = restore(saved ?: Saved(null, null))
        // mode first, then the switch, so a phone that had it off never flashes another colour mode
        if (curMode != r.second) w += KEY_MODE to r.second
        if (curOn != r.first) w += KEY_ON to r.first
        return Step(w, false, null)
    }

    /** (on, mode) to put back. A missing switch = off; a missing mode = the system default. */
    fun restore(s: Saved): Pair<String, String> = (s.on ?: "0") to (s.mode ?: DEFAULT_MODE)

    /** Store form of [Saved]: "\u0000" marks a missing key (never a real setting value). */
    fun enc(v: String?) = v ?: "\u0000"
    fun dec(v: String?) = v?.takeIf { it != "\u0000" }
}

/** Lock-screen owner info and the support messages (DPM). */
object Notes {
    const val MSG_MAX = 60
    const val NOTE_MAX = 120

    /** One line: control characters out, runs of spaces collapsed, at most [max] chars. */
    fun clean(s: String, max: Int): String =
        s.map { if (it.isISOControl()) ' ' else it }.joinToString("").replace(Regex("\\s+"), " ").trim().take(max)

    /** "Máy của Bin · Gọi mẹ 09xx"; "" when both are empty (then the lock screen info is cleared). */
    fun lockText(msg: String, contact: String): String = listOf(clean(msg, MSG_MAX), clean(contact, MSG_MAX)).filter { it.isNotEmpty() }.joinToString(" · ")

    /** Short support message (Settings "disabled by admin" dialog, shown by the system): the parent note, else the default. */
    fun short(note: String, def: String): String = clean(note, NOTE_MAX).ifEmpty { def }

    /** Long support message: the default text, plus "[noteLine]" (already formatted with the note) when there is one. */
    fun long(def: String, noteLine: String?): String = if (noteLine.isNullOrBlank()) def else "$def\n\n$noteLine"
}

/** "Con tới nơi rồi" / "Đón con với": a non-alarm event (kind `checkin`) with the location attached. */
object Checkin {
    const val KIND = "checkin"
    val TYPES = listOf("arrived", "pickup")
    const val GAP_MS = 60_000L        // a second press of the same button within a minute is one check-in
    const val LOC_FRESH_MS = 10 * 60_000L
    const val FILL_MS = 2 * 60_000L   // a fix coming in this long after the press is attached to it

    /** Event shape (protocol v0.9.7): {id, t, kind: "checkin", ck, text, loc?: {lat, lng, acc, t}}. */
    fun event(id: String, t: Long, ck: String, text: String, loc: JSONObject?): JSONObject =
        JSONObject().put("id", id).put("t", t).put("kind", KIND).put("ck", ck).put("text", text).also { o -> locOf(loc)?.let { o.put("loc", it) } }

    /** Only lat / lng / acc / t of a stored location, or null when it has no numbers. */
    fun locOf(l: JSONObject?): JSONObject? {
        if (l == null) return null
        val lat = l.optDouble("lat", Double.NaN); val lng = l.optDouble("lng", Double.NaN)
        if (lat.isNaN() || lng.isNaN()) return null
        return JSONObject().put("lat", lat).put("lng", lng).put("acc", l.optDouble("acc", 0.0)).put("t", l.optLong("t"))
    }

    /** The last fix, when it is young enough to say where the child is now. */
    fun fresh(l: JSONObject?, now: Long): JSONObject? = l?.takeIf { now - it.optLong("t") in 0..LOC_FRESH_MS }

    fun tooSoon(events: JSONArray, ck: String, now: Long): Boolean = (0 until events.length()).any { i ->
        val o = events.optJSONObject(i)
        o != null && o.optString("kind") == KIND && o.optString("ck") == ck && now - o.optLong("t") in 0 until GAP_MS
    }

    /** A new fix: attach it to check-ins of the last 2 min that have none or an older one. Returns true if one changed. */
    fun fill(events: JSONArray, loc: JSONObject, now: Long): Boolean {
        val l = locOf(loc) ?: return false
        var hit = false
        for (i in 0 until events.length()) {
            val o = events.optJSONObject(i) ?: continue
            if (o.optString("kind") != KIND || now - o.optLong("t") !in 0..FILL_MS) continue
            val had = o.optJSONObject("loc")
            if (had == null || had.optLong("t") < l.optLong("t")) { o.put("loc", l); hit = true }
        }
        return hit
    }
}

/** v0.9.7 one-tap request templates on the child's "more time" dialog. The request carries `tpl` = id. */
object ReqTpl {
    data class Tpl(val id: String, val min: Int)
    val ALL = listOf(Tpl("homework", 15), Tpl("play", 30), Tpl("study", 15))

    /** The request note: the template's label, then what the child typed (100 chars max like the free form). */
    fun note(label: String, typed: String): String = listOf(label.trim(), typed.trim()).filter { it.isNotEmpty() }.joinToString(": ").take(100)
}
