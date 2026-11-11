package dev.kidguard

import org.json.JSONArray
import org.json.JSONObject

/**
 * Relay bandwidth (owner 2026-11-11: the Poco uploaded ~20 MB a day, whole states with 2 days of track and every app):
 * once the worker says it can merge (`merge: 1` in a sync reply), the phone sends only what changed since the last state
 * the worker took: changed top-level keys, `_drop` for removed keys, `_apps` {set, del} per package, `_trk` {from, add}
 * new track points. The worker applies it with `mergeState` (relay/src/index.js). Pure, unit-tested; one per Relay.
 */
class StateDiff {
    class Snap internal constructor(val keys: Map<String, Int>, val apps: Map<String, Int>, val trkLast: Long, val trkFrom: Long)

    private var sent: Snap? = null

    /** Forget what the worker has: the next [build] is a whole state. */
    fun reset() { sent = null }

    fun commit(s: Snap) { sent = s }

    /** (what to send, what the worker has after taking it). [whole] or nothing sent yet => the state itself. */
    fun build(state: JSONObject, whole: Boolean): Pair<JSONObject, Snap> {
        val keys = HashMap<String, Int>(); val apps = HashMap<String, Int>()
        val it = state.keys()
        while (it.hasNext()) { val k = it.next(); if (k != "apps" && k != "track") keys[k] = state.opt(k).toString().hashCode() }
        val appArr = state.optJSONArray("apps") ?: JSONArray()
        val appObj = LinkedHashMap<String, JSONObject>()
        for (i in 0 until appArr.length()) appArr.optJSONObject(i)?.let { a -> val p = a.optString("pkg"); appObj[p] = a; apps[p] = a.toString().hashCode() }
        val trk = state.optJSONArray("track") ?: JSONArray()
        val n = trk.length()
        val trkLast = if (n > 0) trk.optJSONArray(n - 1)?.optLong(0) ?: 0L else 0L
        val trkFrom = if (n > 0) trk.optJSONArray(0)?.optLong(0) ?: 0L else 0L
        val snap = Snap(keys, apps, trkLast, trkFrom)
        val old = sent
        if (whole || old == null) return state to snap

        val out = JSONObject().put("_part", 1)
        for ((k, h) in keys) if (old.keys[k] != h) out.put(k, state.opt(k))
        val drop = JSONArray(); for (k in old.keys.keys) if (k !in keys) drop.put(k)
        if (drop.length() > 0) out.put("_drop", drop)

        val set = JSONArray(); for ((p, h) in apps) if (old.apps[p] != h) set.put(appObj[p])
        val del = JSONArray(); for (p in old.apps.keys) if (p !in apps) del.put(p)
        if (set.length() > 0 || del.length() > 0) out.put("_apps", JSONObject().put("set", set).put("del", del))

        // track: append when the last point the worker has is still here (points only grow, old ones age out);
        // else (history cleared, trimmed past it) send the whole array
        if (trkLast != old.trkLast || trkFrom != old.trkFrom) {
            var at = -1
            if (old.trkLast > 0) for (i in n - 1 downTo 0) { val t = trk.optJSONArray(i)?.optLong(0) ?: continue; if (t == old.trkLast) { at = i; break }; if (t < old.trkLast) break }
            if (at < 0) out.put("track", trk)
            else {
                val add = JSONArray(); for (i in at + 1 until n) add.put(trk.opt(i))
                out.put("_trk", JSONObject().put("from", trkFrom).put("add", add))
            }
        }
        return out to snap
    }
}
