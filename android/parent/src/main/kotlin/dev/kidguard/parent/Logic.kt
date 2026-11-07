package dev.kidguard.parent

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.util.Base64

// Pure logic of the parent app. No Android classes (org.json is available on the JVM via the test dependency).

object Logic {
    const val LOST_MS = 30 * 60_000L
    const val SOS_FRESH_MS = 6 * 3600_000L
    const val EV_FRESH_MS = 6 * 3600_000L

    fun hhmm(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(ms).atZone(zone).toLocalTime().let { "%02d:%02d".format(it.hour, it.minute) }
    const val MIN_FREE = 200L * 1024 * 1024
    const val MIN_KEEP_DAYS = 7

    /** base64url (no padding) of a target such as http://192.168.1.5:8770 or https://relay.example.workers.dev */
    fun encodeTarget(target: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(target.toByteArray(Charsets.UTF_8))

    private val HOST = Regex("^[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?$")

    /** Decodes + validates a target: only http/https, a plain host (no userinfo/path/query), optional port. Null if invalid. */
    fun decodeTarget(t: String): String? {
        if (t.isEmpty() || t.length > 300) return null
        val s = try { String(Base64.getUrlDecoder().decode(t), Charsets.UTF_8) } catch (e: IllegalArgumentException) { return null }
        val scheme = when {
            s.startsWith("http://") -> "http"
            s.startsWith("https://") -> "https"
            else -> return null
        }
        val rest = s.substring(scheme.length + 3)
        if (rest.isEmpty() || rest.any { it == '/' || it == '?' || it == '#' || it == '@' || it == '\\' || it.isWhitespace() }) return null
        val host = rest.substringBefore(":")
        val port = if (rest.contains(":")) rest.substringAfter(":") else null
        if (!HOST.matches(host) || host.length > 253) return null
        if (port != null) {
            if (port.isEmpty() || port.length > 5 || !port.all { it.isDigit() }) return null
            if (port.toInt() !in 1..65535) return null
        }
        return s
    }

    /** Only a target saved in the kid list (LAN address or relay host) may be forwarded to. */
    fun allowedTarget(kidsJson: String, target: String) = parseKids(kidsJson).any { it.target == target }

    /** Pairing needs a not-yet-saved LAN target: only its hello probe and the pair call, nothing else. */
    fun allowedRequest(kidsJson: String, target: String, method: String, rest: String) =
        allowedTarget(kidsJson, target) || (target.startsWith("http://") && ((method == "GET" && rest == "/api/hello") || (method == "POST" && rest == "/api/pair")))

    /** Body cap of the shared-files routes (LAN 200 MB + slack); every other route keeps 512 KB. */
    const val MAX_FILE_BODY = 210L * 1024 * 1024
    private val RELAY_FILE = Regex("^/c/[^/]+/file$")
    /** LAN `/api/files…` and relay `/c/<id>/file` (rest = path after the target, query stripped). */
    fun isFilesPath(rest: String): Boolean { val p = rest.substringBefore("?"); return p == "/api/files" || p.startsWith("/api/files/") || RELAY_FILE.matches(p) }

    /** "/k/<t>/<rest>" -> (target, rest-with-leading-slash-and-query-stripped-by-caller). Null when malformed/invalid target. */
    private val UI = Regex("""/ui/([a-z0-9][a-z0-9-]{0,40})\.(js|css)""")

    /** `/ui/<name>.js|css` (the page's code, ui/assets/) -> (asset path, Content-Type); anything else -> null. Same rule as the child's Safety.uiAsset. */
    fun uiAsset(path: String): Pair<String, String>? = UI.matchEntire(path)?.let { m ->
        "assets/${m.groupValues[1]}.${m.groupValues[2]}" to (if (m.groupValues[2] == "js") "text/javascript; charset=utf-8" else "text/css; charset=utf-8")
    }

    fun parseKidPath(path: String): Pair<String, String>? {
        if (!path.startsWith("/k/")) return null
        val body = path.substring(3)
        val slash = body.indexOf('/')
        if (slash <= 0) return null
        val target = decodeTarget(body.substring(0, slash)) ?: return null
        return target to body.substring(slash)   // rest starts with "/"
    }

    private val STATE_PATHS = Regex("^/(api/state|c/[0-9a-f]{16}/state)$")
    /** Only the state snapshot of a kid is cached (LAN /api/state, relay /c/<id>/state). */
    fun isStatePath(method: String, rest: String) = method == "GET" && STATE_PATHS.matches(rest)

    /** Serve the stored snapshot when the live request failed: network error / timeout / 5xx. */
    fun useCache(status: Int?, networkError: Boolean) = networkError || status == null || status >= 500

    /** Local calendar day of an epoch ms, "2026-11-06". */
    fun dayKey(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().toString()

    /** Retention setting (0 = unlimited): days older than [keepDays] before [today] (inclusive window of keepDays days). */
    fun retentionDrop(days: Collection<String>, keepDays: Int, today: String): List<String> {
        if (keepDays <= 0) return emptyList()
        val oldest = java.time.LocalDate.parse(today).minusDays(keepDays.toLong() - 1).toString()
        return days.filter { it < oldest }
    }

    /**
     * Storage guard. [days] = (day, bytes that day uses across all kids). While free space < [minFree], drop the oldest
     * day, until enough is freed or only [minKeep] days remain. Returns the days to delete (oldest first).
     */
    fun storageDrop(days: List<Pair<String, Long>>, freeBytes: Long, minFree: Long = MIN_FREE, minKeep: Int = MIN_KEEP_DAYS): List<String> {
        val asc = days.sortedBy { it.first }
        val out = ArrayList<String>()
        var free = freeBytes
        var left = asc.size
        for ((d, bytes) in asc) {
            if (free >= minFree || left <= minKeep) break
            out.add(d); free += bytes; left--
        }
        return out
    }

    /** The page sends the state path with or without the leading slash. */
    fun normRest(p: String) = if (p.startsWith("/")) p else "/$p"

    // ---- background notifications ----

    class KidCfg(val name: String, val target: String, val via: String, val id: String, val token: String) {
        val key get() = "$target|$id"
    }

    fun parseKids(json: String): List<KidCfg> = try {
        val a = JSONArray(json)
        (0 until a.length()).mapNotNull {
            val o = a.optJSONObject(it) ?: return@mapNotNull null
            val target = decodeTarget(encodeTarget(o.optString("target"))) ?: return@mapNotNull null
            val via = o.optString("via")
            if (via != "lan" && via != "relay") return@mapNotNull null
            KidCfg(o.optString("name", "").ifEmpty { "con" }, target, via, o.optString("id"), o.optString("token"))
        }
    } catch (e: Exception) { emptyList() }

    /** Per kid, persisted between runs. */
    class NotifyState(val seenIds: Set<String> = emptySet(), val lost: Boolean = false, val failSince: Long = 0L, val init: Boolean = false, val evInit: Boolean = false, val accOff: Boolean = false) {
        fun toJson() = JSONObject().put("ids", JSONArray(seenIds.toList())).put("lost", lost).put("fail", failSince).put("init", init).put("ei", evInit).put("acc", accOff).toString()
        companion object {
            fun fromJson(s: String?): NotifyState = try {
                val o = JSONObject(s ?: "")
                val a = o.optJSONArray("ids") ?: JSONArray()
                NotifyState((0 until a.length()).map { a.getString(it) }.toSet(), o.optBoolean("lost"), o.optLong("fail"), o.optBoolean("init"), o.optBoolean("ei"), o.optBoolean("acc"))
            } catch (e: Exception) { NotifyState() }
        }
    }

    /** What one check saw for a kid. activityT = state.t (LAN) or relay `seen` (ms of the child's last sync), 0 = unknown. */
    class Ev(val id: String, val text: String, val t: Long, val kind: String = "", val loc: Loc? = null)   // one state.events entry (v0.6); loc: v0.9.7 check-in
    class Loc(val lat: Double, val lng: Double, val t: Long)   // state.location (last fix)
    class Seen(val fetchOk: Boolean, val requests: List<Pair<String, Int>>, val activityT: Long, val sos: List<Pair<String, Long>> = emptyList(), val events: List<Ev> = emptyList(),
               val accOff: Boolean = false,   // v0.7 basic mode: the child turned KidGuard's accessibility off (no blocking)
               val loc: Loc? = null)
    /** key: unique per event, used as notification id. [min]: a request's minutes; [loc]: the kid's last fix (SOS "show location"). */
    class Event(val key: String, val text: String, val min: Int = 0, val loc: Loc? = null)
    class Outcome(val events: List<Event>, val state: NotifyState)

    /** A failed fetch of a LAN kid says nothing while this phone is off Wi-Fi (mobile data cannot reach 192.168.x.x). */
    fun failCounts(via: String, onWifi: Boolean) = via != "lan" || onWifi

    /** Notify once per event: a new child request id, and the transition to "lost > 30 min". */
    fun evaluate(name: String, seen: Seen, st: NotifyState, now: Long, failCounts: Boolean = true, tr: Tr): Outcome {
        if (!seen.fetchOk && !failCounts) return Outcome(emptyList(), st)   // unreachable for a reason that says nothing about the kid
        val events = ArrayList<Event>()
        var ids = st.seenIds
        var lost = st.lost
        var failSince = st.failSince
        if (seen.fetchOk) {
            failSince = 0L
            // the first look at a kid only records what is already there (no burst of old requests)
            if (st.init) for ((id, min) in seen.requests) if (id !in ids) events.add(Event("req:$id", tr.q(T.ev_request, min, name, min), min))
            // SOS: also on the first look, unless it is old (the child may have pressed it before the app was set up)
            for ((id, t) in seen.sos) if ("s:$id" !in ids && now - t < SOS_FRESH_MS) events.add(Event("sos:$id", tr.s(T.ev_sos, name, hhmm(t)), loc = seen.loc))
            // events (zones, battery, new app, clock): same rule as requests, plus a one-time silent look per kid so an app
            // update does not replay the 50 events a phone already holds; stale ones are recorded but not shown
            if (st.evInit) for (e in seen.events) if ("e:${e.id}" !in ids && now - e.t < EV_FRESH_MS) events.add(if (e.kind == "checkin") Event("ci:${e.id}", "$name: ${e.text}", loc = e.loc ?: seen.loc) else Event("ev:${e.id}", "$name: ${e.text}"))
            val current = seen.sos.map { "s:" + it.first } + seen.requests.map { it.first } + seen.events.map { "e:" + it.id }
            ids = (ids + current).let { all -> if (all.size > 300) current.toSet() else all }
            val isLost = seen.activityT > 0 && now - seen.activityT > LOST_MS
            if (isLost && !lost) events.add(Event("lost", tr.s(T.ev_lost, name)))
            lost = isLost
            if (seen.accOff && !st.accOff) events.add(Event("acc", tr.s(T.ev_acc_off, name)))
        } else {
            if (failSince == 0L) failSince = now
            val isLost = now - failSince > LOST_MS
            if (isLost && !lost) events.add(Event("lost", tr.s(T.ev_lost, name)))
            if (isLost) lost = true
        }
        return Outcome(events, NotifyState(ids, lost, failSince, true, st.evInit || seen.fetchOk, if (seen.fetchOk) seen.accOff else st.accOff))
    }

    /**
     * FCM data message from the relay ({kind: sos|req, kid, eid: "s:<id>"|"r:<id>", t, min}) -> the same event the
     * background check would post (same key, so the same notification id), or null if unknown / already notified.
     */
    fun pushEvent(kids: List<KidCfg>, data: Map<String, String>, st: (KidCfg) -> NotifyState, now: Long = System.currentTimeMillis(), tr: Tr): Pair<KidCfg, Event>? {
        val k = kids.firstOrNull { it.via == "relay" && it.id == data["kid"] } ?: return null
        val eid = data["eid"] ?: return null
        val seenId = seenIdOf(eid) ?: return null
        if (seenId in st(k).seenIds) return null
        val t = data["t"]?.toLongOrNull()?.takeIf { it > 0 } ?: now
        return when (data["kind"]) {
            "sos" -> k to Event("sos:" + eid.removePrefix("s:"), tr.s(T.ev_sos, k.name, hhmm(t)))
            "req" -> k to (data["min"]?.toIntOrNull() ?: 0).let { m -> Event("req:" + eid.removePrefix("r:"), tr.q(T.ev_request, m, k.name, m), m) }
            else -> null
        }
    }

    /** The id the background check stores in [NotifyState.seenIds] for a push eid ("s:x" stays, "r:x" -> "x"). */
    fun seenIdOf(eid: String): String? = when {
        eid.startsWith("s:") && eid.length > 2 -> eid
        eid.startsWith("r:") && eid.length > 2 -> eid.substring(2)
        else -> null
    }

    fun withSeen(st: NotifyState, id: String) = NotifyState(st.seenIds + id, st.lost, st.failSince, st.init, st.evInit, st.accOff)

    /** FCM {kind: "ev", kid, ...} means "check this kid now" (the check itself notifies "<name>: <text>" once per event id). */
    fun pushCheckKid(kids: List<KidCfg>, data: Map<String, String>): KidCfg? =
        if (data["kind"] == "ev") kids.firstOrNull { it.via == "relay" && it.id == data["kid"] } else null

    // --- app update (same rules as the child's UpdateLogic) ---
    const val RELEASES = "https://github.com/HoangTran0410/kidguard/releases/latest"
    /** The versioned asset, so the browser saves "kidguard-parent-0.9.1.apk", not a second "kidguard-parent (1).apk". */
    fun parentApk(tag: String) = "https://github.com/HoangTran0410/kidguard/releases/download/v$tag/kidguard-parent-$tag.apk"

    /** "0.9.0" from a `Location: …/releases/tag/v0.9.0` redirect, or null. */
    fun tagFromLocation(loc: String?): String? =
        loc?.substringAfterLast("/tag/", "")?.takeIf { Regex("v?\\d{1,4}(\\.\\d{1,4}){1,3}").matches(it) }?.removePrefix("v")

    /** True when [a] is a higher dotted version than [b]. */
    fun newer(a: String, b: String): Boolean {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }; val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) { val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }; if (d != 0) return d > 0 }
        return false
    }
}
