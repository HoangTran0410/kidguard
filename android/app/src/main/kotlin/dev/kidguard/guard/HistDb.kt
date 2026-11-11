package dev.kidguard

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.TimeZone
import java.util.concurrent.Executors

/**
 * The parent's "Nhật ký" history: every log line and event, 90 days / 30,000 rows, in one SQLite table. The state JSON
 * still carries only the latest 50 of each (Store prefs, unchanged); the page asks for one day ([day]) over LAN
 * (`GET /api/logday?day=`) or the relay (sync reply `logday`, then PUT). Writes go through one background thread so the
 * Guard tick never waits on disk. The 50 + 50 rows already in prefs are imported once, when the file is created.
 * Wiped on a successful release ([wipe]).
 */
object HistDb {
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "KG-hist").apply { isDaemon = true } }
    @Volatile private var db: SQLiteDatabase? = null
    @Volatile private var trimmedAt = 0L

    private class Helper(val ctx: Context) : SQLiteOpenHelper(ctx, "kghist.db", null, 1) {
        override fun onCreate(d: SQLiteDatabase) {
            // s: 0 = log line (k kind, x message), 1 = event (k kind, x text, z zone id, e event id)
            d.execSQL("CREATE TABLE h (id INTEGER PRIMARY KEY AUTOINCREMENT, s INTEGER NOT NULL, t INTEGER NOT NULL, k TEXT NOT NULL, x TEXT NOT NULL, z TEXT NOT NULL, e TEXT NOT NULL)")
            d.execSQL("CREATE INDEX h_t ON h(t)")
            runCatching {   // the rows prefs still hold (50 + 50), once
                val st = Store.of(ctx); val lg = st.logs(); val ev = st.events()
                for (i in 0 until lg.length()) lg.optJSONObject(i)?.let { d.insert("h", null, row(0, it.optLong("t"), it.optString("k"), it.optString("m"), "", "")) }
                for (i in 0 until ev.length()) ev.optJSONObject(i)?.let { d.insert("h", null, row(1, it.optLong("t"), it.optString("kind"), it.optString("text"), it.optString("zone"), it.optString("id"))) }
            }.onFailure { Log.w("KidGuard", "hist import", it) }
        }
        override fun onUpgrade(d: SQLiteDatabase, o: Int, n: Int) {}
    }

    private fun row(s: Int, t: Long, k: String, x: String, z: String, e: String) = ContentValues().apply {
        put("s", s); put("t", t); put("k", k); put("x", x); put("z", z); put("e", e)
    }

    @Synchronized private fun db(ctx: Context): SQLiteDatabase = db ?: Helper(ctx.applicationContext).writableDatabase.also { db = it }

    /** A log line ([Store.log]); queued, returns at once. */
    fun addLog(ctx: Context, t: Long, kind: String, msg: String) = enqueue(ctx) { it.insert("h", null, row(0, t, kind, msg, "", "")) }
    /** An event ([Store.addEvent]); queued, returns at once. */
    fun addEvent(ctx: Context, t: Long, id: String, kind: String, text: String, zone: String?) = enqueue(ctx) { it.insert("h", null, row(1, t, kind, text, zone ?: "", id)) }

    private fun enqueue(ctx: Context, work: (SQLiteDatabase) -> Unit) {
        val app = ctx.applicationContext
        io.execute { runCatching { val d = db(app); work(d); trim(d, System.currentTimeMillis()) }.onFailure { Log.w("KidGuard", "hist write", it) } }
    }

    /** Old rows out (90 days, 30,000 rows); at most hourly. */
    private fun trim(d: SQLiteDatabase, now: Long) {
        if (now - trimmedAt < HistLogic.TRIM_EVERY_MS) return
        trimmedAt = now
        d.delete("h", "t < ?", arrayOf(HistLogic.cutoff(now).toString()))
        d.execSQL("DELETE FROM h WHERE id <= (SELECT id FROM h ORDER BY id DESC LIMIT 1 OFFSET ${HistLogic.MAX_ROWS})")
    }

    /** Everything gone (release): the file, not just the rows. Queued behind pending writes. */
    fun wipe(ctx: Context) {
        val app = ctx.applicationContext
        io.execute { runCatching { synchronized(this) { db?.close(); db = null }; app.deleteDatabase("kghist.db") } }
    }

    /**
     * One day for the parent page: `{"day", "days": {"yyyy-MM-dd": rows, last 90 days}, "ev": [...], "lg": [...], "cut": bool}`,
     * ev / lg shaped like the state's `events` / `log`, oldest first. At most [HistLogic.DAY_ROWS] rows and
     * [HistLogic.DAY_BYTES] bytes (the newest kept, `cut` = true). Null when [day] is not a date.
     */
    fun day(ctx: Context, day: String, now: Long = System.currentTimeMillis(), tz: TimeZone = TimeZone.getDefault()): JSONObject? {
        val (from, to) = HistLogic.dayRange(day, tz) ?: return null
        val d = db(ctx)
        val ts = d.rawQuery("SELECT t FROM h WHERE t >= ?", arrayOf(HistLogic.cutoff(now).toString())).use { c ->
            val a = LongArray(c.count); var i = 0; while (c.moveToNext()) a[i++] = c.getLong(0); a
        }
        val ev = ArrayList<JSONObject>(); val lg = ArrayList<JSONObject>(); var cut = false; var bytes = 0
        d.rawQuery("SELECT s, t, k, x, z, e FROM h WHERE t >= ? AND t < ? ORDER BY t DESC, id DESC LIMIT ${HistLogic.DAY_ROWS + 1}", arrayOf(from.toString(), to.toString())).use { c ->
            var n = 0
            while (c.moveToNext()) {
                if (++n > HistLogic.DAY_ROWS) { cut = true; break }
                val o = if (c.getInt(0) == 1) JSONObject().put("id", c.getString(5)).put("t", c.getLong(1)).put("kind", c.getString(2)).put("text", c.getString(3)).apply { if (c.getString(4).isNotEmpty()) put("zone", c.getString(4)) }
                else JSONObject().put("t", c.getLong(1)).put("m", c.getString(3)).apply { if (c.getString(2).isNotEmpty()) put("k", c.getString(2)) }
                bytes += o.toString().length + 1
                if (bytes > HistLogic.DAY_BYTES) { cut = true; break }
                (if (c.getInt(0) == 1) ev else lg) += o
            }
        }
        val days = JSONObject(); HistLogic.counts(ts, tz, now).forEach { (k, v) -> days.put(k, v) }
        return JSONObject().put("day", day).put("days", days).put("cut", cut)
            .put("ev", JSONArray(ev.reversed())).put("lg", JSONArray(lg.reversed()))
    }

    /** Relay: the parent asked for [day] (sync reply `"logday"`); PUT it. Throws on HTTP errors. */
    fun upload(ctx: Context, base: String, rid: String, token: String, day: String) {
        val body = (day(ctx, day) ?: return).toString().toByteArray()
        val c = (URL("$base/c/$rid/logday").openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"; connectTimeout = 10_000; readTimeout = 15_000; doOutput = true; instanceFollowRedirects = false
            setRequestProperty("X-KG-Token", token); setRequestProperty("Content-Type", "application/json")
        }
        try {
            c.outputStream.use { it.write(body) }
            if (c.responseCode != 200) throw RuntimeException("logday HTTP ${c.responseCode}")
        } finally { c.disconnect() }
    }
}
