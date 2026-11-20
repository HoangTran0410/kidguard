package dev.kidguard

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/** Pure part of [NotifSync] (unit-tested): what one notification looks like on the wire, paging. */
object NotifSyncLogic {
    const val MAX_N = 50        // rows per pull
    const val DEF_N = 30
    const val TEXT = 300
    const val MSG = 160
    const val MSGS = 5

    fun count(raw: Int): Int = if (raw <= 0) DEF_N else raw.coerceAtMost(MAX_N)

    /** One row: app, title, text, time and the last chat messages (sender + text). No pictures, no icons. */
    fun item(id: Long, e: NotifEntry): JSONObject = JSONObject().put("id", id).put("t", e.time).put("pkg", e.pkg).put("app", e.app.take(60))
        .put("title", e.title.take(200)).put("text", e.text.take(TEXT))
        .put("msgs", JSONArray().also { a -> e.msgs.takeLast(MSGS).forEach { a.put(JSONObject().put("s", it.sender.take(60)).put("t", it.text.take(MSG))) } })

    /**
     * [rows] = newest first, up to n + 1 of them (the extra one only says there is more). [next] = the `before` value for the next
     * page (the time of the last row returned); the page sends it back.
     */
    fun page(rows: List<Pair<Long, NotifEntry>>, n: Int, on: Boolean, access: Boolean): JSONObject {
        val take = rows.take(n)
        return JSONObject().put("on", on).put("access", access)
            .put("items", JSONArray().also { a -> take.forEach { a.put(item(it.first, it.second)) } })
            .put("more", rows.size > n).put("next", take.lastOrNull()?.second?.time ?: 0L)
    }
}

/**
 * Rule `watch.notif`: the parent reads recent notifications on demand (never in state). LAN: `GET /api/notifs?before=&n=`;
 * relay: command `notifPull {fid, before, n}` -> the same JSON through the relay file channel (`Files.pushBytes`, one chunk).
 * Rows come from [NotifLog] (the kid's own history, captured by the notification listener); system rows are left out.
 */
object NotifSync {
    private const val TAG = "KidGuard"

    fun on(g: Context) = Store.of(g).baseRules.watch.notif.on

    // ---- automatic feed: the sync body (state "notifs") carries the newest rows of the notification log that pass the rule ----
    private val lastText = HashMap<String, String>()   // key -> title|text last offered (progress ticks and re-posts do not alert twice)

    /**
     * Called by [NotifLog.record] (listener thread, must be quick) for every notification worth logging (own app, ongoing,
     * progress, group summaries and media are already left out there): the keyword alert. Returns the keyword that hit, or null.
     * The feed itself is read from the log (sqlite) when the state is built: no copy here.
     */
    fun offer(g: Context, e: NotifEntry): String? {
        val w = runCatching { Store.of(g).baseRules.watch }.getOrNull() ?: return null
        if (w.words.isEmpty()) return null
        val sig = e.title + "|" + NotifFeedLogic.text(e)
        synchronized(lastText) {
            if (lastText[e.key] == sig) return null
            if (lastText.size > 300) lastText.clear()
            lastText[e.key] = sig
        }
        val hit = hitOf(w, e)
        if (hit != null) alert(g, e.app.ifEmpty { e.pkg }, e.pkg, hit, listOf(e.title, NotifFeedLogic.text(e)).filter { it.isNotEmpty() }.joinToString(": "))
        return hit
    }

    private fun hitOf(w: WatchRule, e: NotifEntry) = KeywordLogic.hit(w.words, listOf(e.title, e.text, e.subText, e.bigText) + e.msgs.map { it.text } + e.lines)

    // ---- parent-app mirror: a new row the feed sends -> a relay sync soon, so the worker can nudge the parent app (FCM "nf") ----
    private val gate = NudgeGate(NudgeGate.GAP)
    private val main by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    /**
     * Called by [NotifLog.record] (io thread) after a row was added or updated. Rows with a keyword hit are left out: their `word`
     * event syncs and pushes on its own. Coalesced to one relay kick per 30 s, the last of a burst on the trailing edge.
     */
    fun nudge(g: Context, e: NotifEntry) {
        val w = runCatching { Store.of(g).baseRules.watch }.getOrNull() ?: return
        val hit = hitOf(w, e)
        if (hit != null || !NotifFeedLogic.send(w.notif, null, e.pkg)) return
        val wait = gate.ask(System.currentTimeMillis())
        if (wait == 0L) Guard.running?.relayNudge()
        else if (wait > 0) main.postDelayed({ gate.fired(System.currentTimeMillis()); Guard.running?.relayNudge() }, wait)
    }

    private val throttle = WordThrottle()

    /** A keyword hit = an event with the app and a short piece of the text (every event is pushed to the parent app; never the whole message). */
    fun alert(g: Context, app: String, pkg: String, word: String, text: String) {
        if (!throttle.allow(pkg + "|" + KeywordLogic.key(word), System.currentTimeMillis())) return
        runCatching { Store.of(g).event("word", R.string.ev_word, app, KeywordLogic.snippet(text, word, 120), word) }
    }

    /**
     * Telegram `/notif`: the notification log's rows after the cursor ([t], [id]) that pass rule `watch.notif` (app filter,
     * words-only), read from the same sqlite as the kid's history (no copy). Returns the rows to send and the new cursor
     * (past the rows the rule left out too). Disk read: not on the main thread.
     */
    fun tgAfter(g: Context, t: Long, id: Long, n: Int): Pair<List<NotifFeedRow>, Pair<Long, Long>?> {
        val w = Store.of(g).baseRules.watch
        val rows = NotifLog.after(g, t, id, n)
        val out = rows.mapNotNull { (rid, e) ->
            val hit = hitOf(w, e)
            if (!NotifFeedLogic.send(w.notif, hit, e.pkg)) null
            else NotifFeedRow(rid, e.time, e.app.ifEmpty { e.pkg }, e.title, NotifFeedLogic.text(e), hit)
        }
        return out to rows.lastOrNull()?.let { it.second.time to it.first }
    }

    /** Rule turned off or released: forget the cached feed and the alert memory. */
    fun clear() { synchronized(lastText) { lastText.clear() }; cache = null }

    private class Cached(val ver: Long, val w: WatchRule, val json: JSONObject?)
    @Volatile private var cache: Cached? = null

    /**
     * State `notifs`: `{"n": [{id,t,pkg,app,title,text,hit?}] oldest first (<= 20), "more": true when older ones were left out}`;
     * null = nothing to say. The newest [NotifFeedLogic.SCAN] rows of the notification log, filtered by the rule; cached until the
     * log or the rule changes ([NotifLog.version]). On the main thread only the cached copy is used (no disk read there).
     */
    fun stateJson(g: Context): JSONObject? {
        val w = Store.of(g).baseRules.watch
        if (!w.notif.on) return null
        val ver = NotifLog.version
        cache?.let { if (it.ver == ver && it.w == w) return it.json }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return cache?.json
        val rows = NotifLog.before(g, Long.MAX_VALUE, NotifFeedLogic.SCAN)
            .mapNotNull { (rid, e) -> val hit = hitOf(w, e); if (NotifFeedLogic.send(w.notif, hit, e.pkg)) Triple(rid, e, hit) else null }
        val cut = rows.take(NotifFeedLogic.IN_STATE).reversed()
        val json = if (cut.isEmpty()) null else JSONObject().put("more", rows.size > cut.size).put("n", JSONArray().also { a ->
            cut.forEach { (rid, e, hit) ->
                a.put(JSONObject().put("id", NotifFeedLogic.feedId(e.time, rid)).put("t", e.time).put("pkg", e.pkg).put("app", e.app.take(40))
                    .put("title", e.title.take(NotifFeedLogic.STATE_TITLE)).put("text", NotifFeedLogic.text(e).take(NotifFeedLogic.STATE_TEXT))
                    .also { if (hit != null) it.put("hit", hit) })
            }
        })
        cache = Cached(ver, w, json)
        return json
    }

    /** One page, newest first, older than [before] ms (0 = from now). Blocks the calling thread (not the main one). */
    fun pull(g: Context, before: Long, n: Int): JSONObject {
        val on = on(g)
        val access = runCatching { NotifDots.granted(g) }.getOrDefault(false)
        if (!on) return NotifSyncLogic.page(emptyList(), n, false, access)
        val rows = NotifLog.before(g, if (before > 0) before else Long.MAX_VALUE, n + 1)
        return NotifSyncLogic.page(rows, n, true, access)
    }

    /** The child's own log says the parent looked (first page only: "load more" is the same look). */
    private fun told(g: Context, before: Long) { if (before <= 0 && on(g)) runCatching { Store.of(g).log(R.string.log_watch_notif) } }

    /** LAN route body. */
    fun api(g: Context, query: Map<String, String>): JSONObject {
        val before = query["before"]?.toLongOrNull() ?: 0L
        told(g, before)
        return pull(g, before, NotifSyncLogic.count(query["n"]?.toIntOrNull() ?: 0))
    }

    /** Relay command: answers "ok" = started; the page then finds the file `fid` on the relay. "off" when the rule is off. */
    fun command(g: Context, c: JSONObject): String {
        val fid = c.optString("fid")
        if (!FilesLogic.isId(fid)) return "bad fid"
        if (!on(g)) return "off"
        val before = c.optLong("before", 0); val n = NotifSyncLogic.count(c.optInt("n", 0))
        told(g, before)
        Thread({
            runCatching { Files.pushBytes(g, fid, "kidguard-notifs.json", pull(g, before, n).toString().toByteArray()) }
                .onFailure { Log.w(TAG, "notif push", it); Files.pushFailed(g, "kidguard-notifs.json") }
        }, "notif-push").start()
        return "ok"
    }
}

/**
 * Pure (unit-tested): at most one fire per [gap] ms, leading + trailing edge. [ask] = 0 fire now, > 0 schedule one trailing
 * fire in that many ms (then call [fired]), -1 = a trailing fire is already scheduled (it covers this one too).
 */
class NudgeGate(private val gap: Long) {
    private var last = Long.MIN_VALUE / 2
    private var pending = false

    @Synchronized fun ask(now: Long): Long {
        if (pending) return -1
        if (now - last >= gap || now < last) { last = now; return 0 }   // now < last: the clock went back
        pending = true
        return last + gap - now
    }

    @Synchronized fun fired(now: Long) { pending = false; last = now }

    companion object { const val GAP = 30_000L }
}

/** Pure part of the automatic feed (unit-tested): which notification goes to the parent, the ring of new ones. */
/** One forwarded notification (Telegram `/notif`): log row id, time, app label, title, the text the feed shows, the keyword that hit. */
data class NotifFeedRow(val id: Long, val t: Long, val app: String, val title: String, val text: String, val hit: String?)

object NotifFeedLogic {
    const val SCAN = 100        // newest log rows looked at for the state feed (the rule may leave many out)
    const val IN_STATE = 20     // newest of those in the sync body (state stays small: relay limits)
    const val STATE_TITLE = 80
    const val STATE_TEXT = 110

    /** The sync decision: [hit] = keyword found in this notification (or null). */
    fun send(w: NotifWatch, hit: String?, pkg: String) = w.on && w.appOk(pkg) && (!w.kwOnly || hit != null)

    /**
     * The page's feed id (it merges by id, sorted by id): row time ms x 1000 + row id mod 1000. Sorts by time; a chat thread
     * updated in place (new time) is a new item, like a new post. Same magnitude as the old in-memory ids (clock x 1000).
     */
    fun feedId(t: Long, rowId: Long): Long = t * 1000 + Math.floorMod(rowId, 1000L)

    fun text(e: NotifEntry): String =
        e.msgs.lastOrNull()?.let { (if (it.sender.isNotEmpty()) it.sender + ": " else "") + it.text } ?: e.text
}
