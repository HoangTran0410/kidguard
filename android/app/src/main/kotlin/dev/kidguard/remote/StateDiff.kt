package dev.kidguard

import org.json.JSONArray
import org.json.JSONObject

/**
 * Relay bandwidth (owner 2026-11-11: the Poco uploaded ~20 MB a day, whole states with 2 days of track and every app):
 * once the worker says it can merge (`merge: 1` in a sync reply), the phone sends only what changed since the last state
 * the worker took: changed top-level keys, `_drop` for removed keys, `_apps` {set, del} per package, `_trk` {from, add}
 * new track points, `_bat` {from, add} new battery points (2026-11-22, same rule). The worker applies it with `mergeState` (relay/src/index.js). Pure, unit-tested; one per Relay.
 */
class StateDiff {
    class Snap internal constructor(val keys: Map<String, Int>, val apps: Map<String, Int>, val trkLast: Long, val trkFrom: Long,
                                    val batLast: Long = 0L, val batFrom: Long = 0L)

    /** Lists of `[t, ...]` points that only grow at the end (old ones age out): key -> its diff field. */
    private val LISTS = mapOf("track" to "_trk", "batLog" to "_bat")

    private var sent: Snap? = null

    /** Forget what the worker has: the next [build] is a whole state. */
    fun reset() { sent = null }

    fun commit(s: Snap) { sent = s }

    /** (what to send, what the worker has after taking it). [whole] or nothing sent yet => the state itself. */
    fun build(state: JSONObject, whole: Boolean): Pair<JSONObject, Snap> {
        val keys = HashMap<String, Int>(); val apps = HashMap<String, Int>()
        val it = state.keys()
        while (it.hasNext()) { val k = it.next(); if (k != "apps" && k !in LISTS) keys[k] = state.opt(k).toString().hashCode() }
        val appArr = state.optJSONArray("apps") ?: JSONArray()
        val appObj = LinkedHashMap<String, JSONObject>()
        for (i in 0 until appArr.length()) appArr.optJSONObject(i)?.let { a -> val p = a.optString("pkg"); appObj[p] = a; apps[p] = a.toString().hashCode() }
        fun ends(k: String): LongArray { val a = state.optJSONArray(k); val n = a?.length() ?: 0
            return if (n == 0) longArrayOf(0, 0) else longArrayOf(a!!.optJSONArray(n - 1)?.optLong(0) ?: 0L, a.optJSONArray(0)?.optLong(0) ?: 0L) }
        val trk = ends("track"); val bat = ends("batLog")
        val snap = Snap(keys, apps, trk[0], trk[1], bat[0], bat[1])
        val old = sent
        if (whole || old == null) return state to snap

        val out = JSONObject().put("_part", 1)
        for ((k, h) in keys) if (old.keys[k] != h) out.put(k, state.opt(k))
        val drop = JSONArray(); for (k in old.keys.keys) if (k !in keys) drop.put(k)
        if (drop.length() > 0) out.put("_drop", drop)

        val set = JSONArray(); for ((p, h) in apps) if (old.apps[p] != h) set.put(appObj[p])
        val del = JSONArray(); for (p in old.apps.keys) if (p !in apps) del.put(p)
        if (set.length() > 0 || del.length() > 0) out.put("_apps", JSONObject().put("set", set).put("del", del))

        // track / batLog: append when the last point the worker has is still here (points only grow, old ones age out);
        // else (history cleared, trimmed past it) send the whole array. A list the worker has none of (an old state) = whole too.
        appendDiff(out, state, "track", snap.trkLast, snap.trkFrom, old.trkLast, old.trkFrom)
        appendDiff(out, state, "batLog", snap.batLast, snap.batFrom, old.batLast, old.batFrom)
        return out to snap
    }

    private fun appendDiff(out: JSONObject, state: JSONObject, k: String, last: Long, from: Long, oldLast: Long, oldFrom: Long) {
        if (last == oldLast && from == oldFrom) return
        val a = state.optJSONArray(k) ?: JSONArray(); val n = a.length()
        var at = -1
        if (oldLast > 0) for (i in n - 1 downTo 0) { val t = a.optJSONArray(i)?.optLong(0) ?: continue; if (t == oldLast) { at = i; break }; if (t < oldLast) break }
        if (at < 0) { out.put(k, a); return }
        val add = JSONArray(); for (i in at + 1 until n) add.put(a.opt(i))
        out.put(LISTS.getValue(k), JSONObject().put("from", from).put("add", add))
    }
}
