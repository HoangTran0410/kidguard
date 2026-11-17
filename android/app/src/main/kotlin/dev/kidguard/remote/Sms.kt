package dev.kidguard

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/** One text message as the page sees it. [out] = sent by the child. */
data class SmsRow(val id: Long, val t: Long, val who: String, val text: String, val out: Boolean)

/** Pure part of [Sms] (unit-tested). */
object SmsLogic {
    const val MAX_PAGE = 50
    const val MAX_TEXT = 600
    fun count(n: Int) = if (n <= 0) 30 else minOf(n, MAX_PAGE)

    /** Rows newest first, one more than [n] was asked: that extra one only says "there is more". */
    fun page(rows: List<SmsRow>, n: Int, on: Boolean, access: Boolean): JSONObject {
        val a = JSONArray()
        rows.take(n).forEach { a.put(JSONObject().put("id", it.id).put("t", it.t).put("from", it.who).put("text", it.text.take(MAX_TEXT)).put("out", it.out)) }
        return JSONObject().put("on", on).put("access", access).put("sms", a).put("more", rows.size > n)
    }

    /** New inbox rows after [last] that contain a watched word: (row, word), oldest first. */
    fun hits(rows: List<SmsRow>, last: Long, words: List<String>): List<Pair<SmsRow, String>> =
        rows.filter { !it.out && it.t > last }.sortedBy { it.t }.mapNotNull { r -> KeywordLogic.hit(words, listOf(r.text, r.who))?.let { r to it } }
}

/**
 * Text messages for the parent (rule `watch.sms`, default off): READ_SMS (Device Owner grants it; basic mode through our own adb, best effort).
 * Two uses: older/recent messages on demand (LAN `GET /sms?before=&n=`, relay `smsPull` + file channel) and keyword alerts from [tick].
 * Without the permission the parent still gets the text of an SMS notification through the notification feed (docs/flows 1.17).
 */
object Sms {
    private const val TAG = "KidGuard"
    private val URI = Uri.parse("content://sms")
    private var lastGrantTry = 0L

    fun on(g: Context) = Store.of(g).baseRules.watch.sms
    fun granted(g: Context) = g.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    /** Newest first, older than [before] ms, [n] rows (inbox + sent). */
    fun query(g: Context, before: Long, n: Int): List<SmsRow> = runCatching {
        val out = ArrayList<SmsRow>()
        g.contentResolver.query(URI, arrayOf("_id", "date", "address", "body", "type"), "date < ? AND type IN (1,2)", arrayOf((if (before > 0) before else Long.MAX_VALUE).toString()), "date DESC LIMIT $n")?.use { c ->
            while (c.moveToNext()) out += SmsRow(c.getLong(0), c.getLong(1), c.getString(2).orEmpty(), c.getString(3).orEmpty(), c.getInt(4) == 2)
        }
        out
    }.onFailure { Log.w(TAG, "sms query", it) }.getOrDefault(emptyList())

    fun pull(g: Context, before: Long, n: Int): JSONObject {
        val on = on(g); val ok = granted(g)
        if (!on || !ok) return SmsLogic.page(emptyList(), n, on, ok)
        return SmsLogic.page(query(g, before, n + 1), n, true, true)
    }

    private fun told(g: Context, before: Long) { if (before <= 0 && on(g)) runCatching { Store.of(g).log(R.string.log_watch_sms) } }

    fun api(g: Context, query: Map<String, String>): JSONObject {
        val before = query["before"]?.toLongOrNull() ?: 0L
        told(g, before)
        return pull(g, before, SmsLogic.count(query["n"]?.toIntOrNull() ?: 0))
    }

    /** Relay command smsPull {fid, before, n}: "ok" = started (the page finds the file `fid`), "off" when the rule is off. */
    fun command(g: Context, c: JSONObject): String {
        val fid = c.optString("fid")
        if (!FilesLogic.isId(fid)) return "bad fid"
        if (!on(g)) return "off"
        val before = c.optLong("before", 0); val n = SmsLogic.count(c.optInt("n", 0))
        told(g, before)
        Thread({
            runCatching { Files.pushBytes(g, fid, "kidguard-sms.json", pull(g, before, n).toString().toByteArray()) }
                .onFailure { Log.w(TAG, "sms push", it); Files.pushFailed(g, "kidguard-sms.json") }
        }, "sms-push").start()
        return "ok"
    }

    /**
     * Guard tick: keyword alerts for new incoming messages. The first run only remembers "now" (old messages never alert).
     * Basic mode with no permission asks our own adb for it at most every 10 minutes.
     */
    fun tick(g: Context) {
        if (Flavor.HOME || !on(g)) return
        val sp = g.getSharedPreferences("kg_sms", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!granted(g)) {
            if (now - lastGrantTry > 10 * 60_000L && !Policy.isOwner(g) && SelfAdb.hasKey(g)) {
                lastGrantTry = now
                Thread({ runCatching { SelfAdb.run(g.applicationContext, "pm grant ${g.packageName} android.permission.READ_SMS") } }, "sms-grant").start()
            }
            return
        }
        val words = Store.of(g).baseRules.watch.words
        val last = sp.getLong("last", 0L)
        if (last == 0L) { sp.edit().putLong("last", now).apply(); return }
        if (words.isEmpty()) { sp.edit().putLong("last", now).apply(); return }
        val rows = query(g, 0, 20).filter { it.t > last }
        if (rows.isEmpty()) return
        SmsLogic.hits(rows, last, words).forEach { (r, w) -> NotifSync.alert(g, g.getString(R.string.sms_app), "sms", w, r.text) }
        sp.edit().putLong("last", maxOf(last, rows.maxOf { it.t })).apply()
    }

    /** Release / rule off: forget the position. */
    fun clear(g: Context) { runCatching { g.getSharedPreferences("kg_sms", Context.MODE_PRIVATE).edit().clear().apply() } }
}
