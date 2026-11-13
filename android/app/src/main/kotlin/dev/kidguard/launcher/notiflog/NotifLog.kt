package dev.kidguard

import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.notification.StatusBarNotification
import java.io.File
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * The kid's notification history ("I swiped it before reading"). Local only: an SQLite file in the app's private
 * storage (+ small images in notiflog_img/), never in state JSON, never sent anywhere, the parent can't see it.
 * Kept 7 / 30 / 90 days (default 30, the screen's menu), images [NotifQ.IMG_DAYS]. Fed by the notification listener
 * (NotifDots); all disk / image work runs on one background thread. Was a JSON file of 7 days / 300 entries rewritten
 * every 3 s and drawn whole (owner 2026-11-11: too short): that file is imported once and deleted.
 */
object NotifLog {
    /** One switch for the whole feature (no setting yet). */
    const val enabled = true
    private const val SAVE_DELAY_MS = 3_000L
    private const val HELD_MAX = 50

    private val io = ScheduledThreadPoolExecutor(1)
    private var db: android.database.sqlite.SQLiteDatabase? = null   // opened and used only on [io]
    private var trimmedAt = 0L

    private fun dir(ctx: Context) = File(ctx.filesDir, "notiflog_img")
    fun imageFile(ctx: Context, name: String): File = File(dir(ctx), name)
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("notiflog", Context.MODE_PRIVATE)

    /** Days kept (7 / 30 / 90). */
    fun keepDays(ctx: Context): Int = prefs(ctx).getInt("days", NotifQ.KEEP_DEFAULT).let { if (it in NotifQ.KEEP_DAYS) it else NotifQ.KEEP_DEFAULT }
    fun setKeepDays(ctx: Context, d: Int) {
        val app = ctx.applicationContext
        prefs(app).edit().putInt("days", d).apply()
        io.execute { trimmedAt = 0; trim(app, System.currentTimeMillis()) }
    }

    // v2 (2026-11-12, stats screen): ch / cn channel id + name, rs removal reason, im importance, index on p
    private class Helper(ctx: Context) : android.database.sqlite.SQLiteOpenHelper(ctx, "notiflog.db", null, 2) {
        override fun onCreate(d: android.database.sqlite.SQLiteDatabase) {
            d.execSQL("CREATE TABLE n (id INTEGER PRIMARY KEY AUTOINCREMENT, k TEXT NOT NULL, t INTEGER NOT NULL, d INTEGER NOT NULL, m INTEGER NOT NULL, p TEXT NOT NULL, a TEXT NOT NULL, kd TEXT NOT NULL, live INTEGER NOT NULL, f TEXT NOT NULL, j TEXT NOT NULL)")
            d.execSQL("CREATE INDEX n_t ON n(t)"); d.execSQL("CREATE INDEX n_d ON n(d)"); d.execSQL("CREATE INDEX n_k ON n(k)")
            v2(d)
        }
        override fun onUpgrade(d: android.database.sqlite.SQLiteDatabase, o: Int, n: Int) {
            if (o < 2) {
                v2(d)
                // fill the new columns from each row's JSON, once (60k rows at most)
                d.rawQuery("SELECT id, j FROM n", null).use { c ->
                    while (c.moveToNext()) {
                        val e = entry(c.getString(1)) ?: continue
                        d.update("n", android.content.ContentValues().apply { cols(this, e) }, "id = ?", arrayOf(c.getLong(0).toString()))
                    }
                }
            }
        }
        private fun v2(d: android.database.sqlite.SQLiteDatabase) {
            for (c in listOf("ch", "cn", "rs")) d.execSQL("ALTER TABLE n ADD COLUMN $c TEXT NOT NULL DEFAULT ''")
            d.execSQL("ALTER TABLE n ADD COLUMN im INTEGER NOT NULL DEFAULT 0")
            d.execSQL("CREATE INDEX n_p ON n(p, d)")
        }
    }

    private fun cols(v: android.content.ContentValues, e: NotifEntry) {
        v.put("ch", e.channel); v.put("cn", e.channelName); v.put("rs", e.reason); v.put("im", e.importance)
    }

    private fun db(ctx: Context): android.database.sqlite.SQLiteDatabase = db ?: Helper(ctx).writableDatabase.also { d ->
        db = d
        // the old JSON history (7 days / 300): import once
        val old = File(ctx.filesDir, "notiflog.json")
        if (old.exists()) {
            val l = NotifLogic.fromJson(runCatching { old.readText() }.getOrNull())
            d.beginTransaction()
            try { for (e in l) d.insert("n", null, values(e)); d.setTransactionSuccessful() } finally { d.endTransaction() }
            old.delete(); File(old.path + ".tmp").delete()
        }
    }

    private fun values(e: NotifEntry): android.content.ContentValues {
        val c = java.util.Calendar.getInstance()
        return android.content.ContentValues().apply {
            put("k", e.key); put("t", e.time); put("d", NotifQ.day(e.time, c)); put("m", NotifQ.minute(e.time, c))
            put("p", e.pkg); put("a", e.app); put("kd", NotifQ.kind(e.category, e.msgs.isNotEmpty()))
            put("live", if (e.removedAt == 0L) 1 else 0); put("f", NotifQ.searchText(e)); put("j", NotifLogic.toObj(e).toString())
            cols(this, e)
        }
    }

    private fun entry(j: String): NotifEntry? = runCatching { NotifLogic.fromObj(org.json.JSONObject(j)) }.getOrNull()

    /** The newest row of [key]: (id, entry). */
    private fun last(d: android.database.sqlite.SQLiteDatabase, key: String): Pair<Long, NotifEntry>? =
        d.rawQuery("SELECT id, j FROM n WHERE k = ? ORDER BY id DESC LIMIT 1", arrayOf(key)).use { c ->
            if (c.moveToFirst()) entry(c.getString(1))?.let { c.getLong(0) to it } else null
        }

    /** Old rows out (days kept, [NotifQ.MAX_ROWS]); images older than [NotifQ.IMG_DAYS]. At most hourly. */
    private fun trim(ctx: Context, now: Long) {
        if (now - trimmedAt < 3_600_000L) return
        trimmedAt = now
        val d = db(ctx)
        runCatching {
            d.delete("n", "t < ?", arrayOf((now - keepDays(ctx) * NotifLogic.DAY_MS).toString()))
            d.execSQL("DELETE FROM n WHERE id <= (SELECT id FROM n ORDER BY id DESC LIMIT 1 OFFSET ${NotifQ.MAX_ROWS})")
        }
        val old = now - NotifQ.IMG_DAYS * NotifLogic.DAY_MS
        runCatching { dir(ctx).listFiles()?.forEach { f -> if (f.lastModified() < old) f.delete() } }
    }

    /** Nothing to buffer any more (each change is one row); kept for the listener's onDestroy. */
    fun flush(ctx: Context) {}

    // ---- PendingIntents (memory only: lost with the process, that is the point of "recent") ----

    private class Held(var rec: ClickRec, val pi: PendingIntent)
    private val held = object : LinkedHashMap<String, Held>(16, 0.75f, false) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Held>?) = size > HELD_MAX
    }

    /** The contentIntent of the notification of [pkg] that the kid tapped within [withinMs]; null if none / not held any more. */
    fun recentClick(pkg: String, withinMs: Long, now: Long = System.currentTimeMillis()): PendingIntent? = synchronized(held) {
        NotifLogic.pickClick(held.values.map { it.rec }, pkg, withinMs, now)?.let { held[it.key]?.pi }
    }

    fun pendingFor(key: String): PendingIntent? = synchronized(held) { held[key]?.pi }

    /** Sends [pi] (allowed to start an activity from the background where the OS has that switch). False if it was cancelled. */
    fun send(ctx: Context, pi: PendingIntent): Boolean = runCatching {
        val opts: Bundle? = if (Build.VERSION.SDK_INT >= 34)
            ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED).toBundle() else null
        pi.send(ctx, 0, null, null, null, null, opts)
    }.isSuccess

    // ---- recording ----

    private fun wanted(s: StatusBarNotification): Boolean {
        val n = s.notification
        if (s.isOngoing || n.flags and (Notification.FLAG_GROUP_SUMMARY or Notification.FLAG_FOREGROUND_SERVICE or Notification.FLAG_ONGOING_EVENT) != 0) return false
        if (s.packageName.startsWith("dev.kidguard")) return false
        if (n.category == Notification.CATEGORY_TRANSPORT || n.extras.containsKey("android.mediaSession")) return false
        return n.extras.getString(Notification.EXTRA_TEMPLATE)?.contains("MediaStyle") != true
    }

    @Suppress("DEPRECATION")
    private fun messages(x: Bundle): List<NotifMsg> = runCatching {
        (x.getParcelableArray(Notification.EXTRA_MESSAGES) ?: return emptyList()).mapNotNull { p ->
            val b = p as? Bundle ?: return@mapNotNull null
            val t = b.getCharSequence("text")?.toString()?.trim().orEmpty()
            if (t.isEmpty()) return@mapNotNull null
            val who = (if (Build.VERSION.SDK_INT >= 28) (b.getParcelable<android.os.Parcelable>("sender_person") as? android.app.Person)?.name else null)
                ?: b.getCharSequence("sender")
            NotifMsg(who?.toString().orEmpty().take(80), t.take(300))
        }.takeLast(NotifLogic.MAX_MSGS)
    }.getOrDefault(emptyList())

    /** Called from the listener thread: reads the extras now, writes (and encodes images) on the io thread. */
    @Suppress("DEPRECATION")
    fun record(ctx: Context, s: StatusBarNotification, importance: Int = 0, channelName: String = "") {
        if (!enabled || !wanted(s)) return
        val n = s.notification
        val x = n.extras
        fun cs(k: String) = x.getCharSequence(k)?.toString()?.trim().orEmpty()
        val title = cs(Notification.EXTRA_TITLE)
        val big = cs(Notification.EXTRA_BIG_TEXT)
        val text = big.ifEmpty { cs(Notification.EXTRA_TEXT) }
        if (title.isEmpty() && text.isEmpty()) return
        val app = ctx.applicationContext
        val now = System.currentTimeMillis()
        val shownText = text.take(600)
        val prog = x.getInt(Notification.EXTRA_PROGRESS_MAX).let { max ->
            if (max > 0) "${x.getInt(Notification.EXTRA_PROGRESS)}/$max" else if (x.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE)) "?" else ""
        }
        val e = NotifEntry(
            s.key, now, s.packageName, IconCache.label(app, s.packageName), title.take(200), shownText,
            postTime = s.postTime, whenMs = n.`when`, channel = n.channelId.orEmpty().take(80), category = n.category.orEmpty(),
            subText = cs(Notification.EXTRA_SUB_TEXT).take(200),
            summary = cs(Notification.EXTRA_SUMMARY_TEXT).ifEmpty { cs(Notification.EXTRA_INFO_TEXT) }.take(200),
            bigText = if (big.isNotEmpty() && big.take(2000) != shownText) big.take(2000) else "",
            lines = (x.getCharSequenceArray(Notification.EXTRA_TEXT_LINES) ?: emptyArray()).take(NotifLogic.MAX_LINES).map { it.toString().take(300) },
            msgs = messages(x),
            actions = (n.actions ?: emptyArray()).mapNotNull { it.title?.toString()?.take(40) }.take(NotifLogic.MAX_ACTIONS),
            progress = prog, group = n.group.orEmpty().take(80), priority = n.priority, importance = importance,
            channelName = channelName.take(80),
        )
        n.contentIntent?.let { pi -> synchronized(held) { held.remove(s.key); held[s.key] = Held(ClickRec(s.key, s.packageName, 0, ""), pi) } }
        // images: grab the references here, decode / scale / encode on io
        val largeIcon = runCatching { n.getLargeIcon() }.getOrNull()
        val largeBmp = x.get(Notification.EXTRA_LARGE_ICON) as? Bitmap
        val picBmp = x.get(Notification.EXTRA_PICTURE) as? Bitmap
        val picIcon = if (Build.VERSION.SDK_INT >= 31) x.get(Notification.EXTRA_PICTURE_ICON) as? Icon else null
        io.execute {
            runCatching {
                val d = db(app)
                val old = last(d, e.key)
                // a held one shown at its time is the same row again, keeping its arrival time (stats count it there)
                val back = old != null && old.second.reason == NotifLogic.R_HOLD
                val live = old != null && (old.second.removedAt == 0L || back)
                if (live && !back && NotifLogic.isRepeat(listOf(old!!.second), e)) return@execute   // progress ticks etc.: no image work either
                val li = saveImg(app, NotifLogic.imgName(e.key, e.postTime, 'l'), bitmapOf(app, largeIcon, largeBmp), NotifLogic.ICON_PX, NotifLogic.ICON_PX, 80)
                val pic = saveImg(app, NotifLogic.imgName(e.key, e.postTime, 'p'), bitmapOf(app, picIcon, picBmp), NotifLogic.PIC_W, NotifLogic.PIC_H, 75)
                val ne = e.copy(largeIcon = li, picture = pic).let { if (back) it.copy(time = old!!.second.time) else it }
                // an update of a notification still showing replaces its row (chat threads grow in place); after it was
                // dismissed, the same key is a new notification and gets a row of its own
                if (live) {
                    d.update("n", values(ne), "id = ?", arrayOf(old!!.first.toString()))
                    for (f in NotifLogic.refs(old.second) - NotifLogic.refs(ne).toSet()) runCatching { File(dir(app), f).delete() }
                } else d.insert("n", null, values(ne))
                trim(app, now)
            }
        }
    }

    /** The notification [s] went away: [reason] is NotificationListenerService.REASON_*. */
    fun removed(ctx: Context, s: StatusBarNotification, reason: Int) {
        if (!enabled) return
        val app = ctx.applicationContext
        val code = NotifLogic.reason(reason)
        val now = System.currentTimeMillis()
        synchronized(held) { held[s.key]?.let { it.rec = it.rec.copy(removedAt = now, reason = code) } }
        io.execute {
            runCatching {
                val d = db(app)
                val (id, e) = last(d, s.key) ?: return@execute
                val n = NotifLogic.markRemoved(listOf(e), s.key, s.postTime, now, code)
                if (n[0] !== e) d.update("n", values(n[0]), "id = ?", arrayOf(id.toString()))
            }
        }
    }

    // ---- images ----

    private fun bitmapOf(ctx: Context, icon: Icon?, bmp: Bitmap?): Bitmap? {
        if (bmp != null) return bmp
        val d = icon?.let { runCatching { it.loadDrawable(ctx) }.getOrNull() } ?: return null
        (d as? BitmapDrawable)?.bitmap?.let { return it }
        val w = d.intrinsicWidth; val h = d.intrinsicHeight
        if (w <= 0 || h <= 0) return null
        val (fw, fh) = NotifLogic.fit(w, h, 1024, 1024)
        return runCatching {
            Bitmap.createBitmap(fw, fh, Bitmap.Config.ARGB_8888).also { d.setBounds(0, 0, fw, fh); d.draw(Canvas(it)) }
        }.getOrNull()
    }

    /** Scales [src] down into maxW x maxH and writes it as WEBP; returns the file name, "" if there is no bitmap / it failed. */
    @Suppress("DEPRECATION")
    private fun saveImg(ctx: Context, name: String, src: Bitmap?, maxW: Int, maxH: Int, quality: Int): String {
        if (src == null || src.isRecycled) return ""
        var b: Bitmap? = null; var s: Bitmap? = null
        return try {
            b = if (src.config == Bitmap.Config.HARDWARE) src.copy(Bitmap.Config.ARGB_8888, false) ?: return "" else src
            val (w, h) = NotifLogic.fit(b.width, b.height, maxW, maxH)
            s = if (w != b.width || h != b.height) Bitmap.createScaledBitmap(b, w, h, true) else b
            dir(ctx).mkdirs()
            val f = File(dir(ctx), name)
            val fmt = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
            val ok = f.outputStream().use { s.compress(fmt, quality, it) }
            if (ok) name else { f.delete(); "" }
        } catch (_: Throwable) { "" } finally {
            // only recycle copies we made, never the bitmap the notification handed us
            if (s != null && s !== src) s.recycle()
            if (b != null && b !== src && b !== s) b.recycle()
        }
    }

    // ---- reading (all on io, results on the main thread) ----

    private fun <T> onIo(ctx: Context, work: (android.database.sqlite.SQLiteDatabase) -> T, done: (T) -> Unit) {
        val app = ctx.applicationContext
        io.execute {
            val r = runCatching { trim(app, System.currentTimeMillis()); work(db(app)) }.getOrNull() ?: return@execute
            Handler(Looper.getMainLooper()).post { done(r) }
        }
    }

    /** A page of rows matching [f], newest first: (row id, entry). */
    fun page(ctx: Context, f: NotifFilter, offset: Int, done: (List<Pair<Long, NotifEntry>>) -> Unit) = onIo(ctx, { d ->
        val (w, a) = NotifQ.where(f)
        d.rawQuery("SELECT id, j FROM n WHERE $w ORDER BY t DESC, id DESC LIMIT ${NotifQ.PAGE} OFFSET $offset", a).use { c ->
            val out = ArrayList<Pair<Long, NotifEntry>>(c.count)
            while (c.moveToNext()) entry(c.getString(1))?.let { out += c.getLong(0) to it }
            out
        }
    }, done)

    /** Everything the screen needs besides the rows: per-day counts of [month0]..[month1] (other filters applied),
     *  apps and kinds with counts (other filters applied), and the total. */
    class Overview(val days: Map<Int, Int>, val apps: List<Triple<String, String, Int>>, val kinds: Map<String, Int>, val total: Int, val oldest: Long)

    fun overview(ctx: Context, f: NotifFilter, month0: Int, month1: Int, done: (Overview) -> Unit) = onIo(ctx, { d ->
        val days = HashMap<Int, Int>()
        NotifQ.where(f, skipDays = true).let { (w, a) ->
            d.rawQuery("SELECT d, COUNT(*) FROM n WHERE $w AND d >= $month0 AND d <= $month1 GROUP BY d", a).use { c -> while (c.moveToNext()) days[c.getInt(0)] = c.getInt(1) }
        }
        val apps = ArrayList<Triple<String, String, Int>>()
        NotifQ.where(f, skipPkgs = true).let { (w, a) ->
            d.rawQuery("SELECT p, MAX(a), COUNT(*) AS c FROM n WHERE $w GROUP BY p ORDER BY c DESC LIMIT 200", a).use { c -> while (c.moveToNext()) apps += Triple(c.getString(0), c.getString(1) ?: "", c.getInt(2)) }
        }
        val kinds = HashMap<String, Int>()
        NotifQ.where(f, skipKinds = true).let { (w, a) ->
            d.rawQuery("SELECT kd, COUNT(*) FROM n WHERE $w GROUP BY kd", a).use { c -> while (c.moveToNext()) kinds[c.getString(0)] = c.getInt(1) }
        }
        val total = NotifQ.where(f).let { (w, a) -> d.rawQuery("SELECT COUNT(*) FROM n WHERE $w", a).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 } }
        val oldest = d.rawQuery("SELECT MIN(t) FROM n", null).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
        Overview(days, apps, kinds, total, oldest)
    }, done)

    /** Packages that posted a notification in the last [days] days (Health "idle" hint); blocks up to 3 s, so call it off the main thread. null = unknown (history kept for fewer days, or the read failed). */
    fun postersSince(ctx: Context, days: Int): Set<String>? {
        if (keepDays(ctx) < days) return null
        val out = java.util.concurrent.atomic.AtomicReference<Set<String>?>(null)
        val latch = java.util.concurrent.CountDownLatch(1)
        val app = ctx.applicationContext
        io.execute {
            try {
                val from = NotifQ.day(System.currentTimeMillis() - days * 86_400_000L)
                out.set(db(app).rawQuery("SELECT DISTINCT p FROM n WHERE d >= ?", arrayOf(from.toString())).use { c -> val r = HashSet<String>(); while (c.moveToNext()) r += c.getString(0); r })
            } catch (_: Exception) {} finally { latch.countDown() }
        }
        return if (latch.await(3, TimeUnit.SECONDS)) out.get() else null
    }

    /** Notifications [pkg] posted today (the long-press menu's line), on the main thread. */
    fun today(ctx: Context, pkg: String, done: (Int) -> Unit) = onIo(ctx, { d ->
        d.rawQuery("SELECT COUNT(*) FROM n WHERE p = ? AND d = ?", arrayOf(pkg, NotifQ.day(System.currentTimeMillis()).toString())).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
    }, done)

    /** Raw numbers for the stats screen, days [d0]..[d1] (yyyymmdd), one app if [pkg] is set; [p0]..[p1] = the period
     *  before, for the change. Shaped by [NotifStats]. */
    fun stats(ctx: Context, d0: Int, d1: Int, p0: Int, p1: Int, pkg: String?, done: (NotifStats.Raw) -> Unit) = onIo(ctx, { d ->
        val pw = if (pkg != null) " AND p = ?" else ""
        fun args(vararg x: Any) = (x.map { it.toString() } + listOfNotNull(pkg)).toTypedArray()
        val cells = ArrayList<NotifStats.Cell>()
        d.rawQuery("SELECT d, m / 60, COUNT(*) FROM n WHERE d >= ? AND d <= ?$pw GROUP BY 1, 2", args(d0, d1)).use { c ->
            while (c.moveToNext()) cells += NotifStats.Cell(c.getInt(0), c.getInt(1), c.getInt(2))
        }
        val prev = d.rawQuery("SELECT COUNT(*) FROM n WHERE d >= ? AND d <= ?$pw", args(p0, p1)).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        val opened = d.rawQuery("SELECT rs, COUNT(*) FROM n WHERE d >= ? AND d <= ?$pw GROUP BY rs", args(d0, d1)).use { c ->
            HashMap<String, Int>().also { m -> while (c.moveToNext()) m[c.getString(0)] = c.getInt(1) }
        }
        val apps = ArrayList<NotifStats.AppRaw>()
        if (pkg == null) {
            val hours = HashMap<String, IntArray>()
            d.rawQuery("SELECT p, m / 60, COUNT(*) FROM n WHERE d >= ? AND d <= ? GROUP BY 1, 2", args(d0, d1)).use { c ->
                while (c.moveToNext()) hours.getOrPut(c.getString(0)) { IntArray(24) }[c.getInt(1).coerceIn(0, 23)] += c.getInt(2)
            }
            d.rawQuery("SELECT p, MAX(a), COUNT(*), SUM(rs = 'click'), SUM(kd = 'promo') FROM n WHERE d >= ? AND d <= ? GROUP BY p ORDER BY 3 DESC", args(d0, d1)).use { c ->
                while (c.moveToNext()) apps += NotifStats.AppRaw(c.getString(0), c.getString(1) ?: "", c.getInt(2), c.getInt(3), c.getInt(4), hours[c.getString(0)] ?: IntArray(24))
            }
        }
        val chans = ArrayList<NotifStats.ChanRaw>()
        if (pkg != null) {
            // per channel: counts in the period, name + importance from its newest row (any time)
            d.rawQuery("SELECT ch, COUNT(*), SUM(rs = 'click'), (SELECT cn FROM n x WHERE x.p = n.p AND x.ch = n.ch AND x.cn <> '' ORDER BY x.id DESC LIMIT 1), " +
                "(SELECT im FROM n x WHERE x.p = n.p AND x.ch = n.ch ORDER BY x.id DESC LIMIT 1) FROM n WHERE d >= ? AND d <= ? AND p = ? GROUP BY ch ORDER BY 2 DESC", args(d0, d1)).use { c ->
                while (c.moveToNext()) chans += NotifStats.ChanRaw(c.getString(0) ?: "", c.getString(3) ?: "", c.getInt(1), c.getInt(2), c.getInt(4))
            }
        }
        val name = if (pkg != null) d.rawQuery("SELECT a FROM n WHERE p = ? ORDER BY id DESC LIMIT 1", arrayOf(pkg)).use { c -> if (c.moveToFirst()) c.getString(0) else "" } else ""
        NotifStats.Raw(cells, prev, opened, apps, chans, name)
    }, done)

    /** One row by id (detail screen); an empty entry if it is gone. */
    fun byId(ctx: Context, id: Long, done: (NotifEntry) -> Unit) = onIo(ctx, { d ->
        d.rawQuery("SELECT j FROM n WHERE id = ?", arrayOf(id.toString())).use { c -> if (c.moveToFirst()) entry(c.getString(0)) else null }
            ?: NotifEntry("", 0, "", "", "", "")
    }, done)

    fun clear(ctx: Context) {
        val app = ctx.applicationContext
        synchronized(held) { held.clear() }
        io.execute {
            runCatching { db(app).delete("n", null, null) }
            runCatching { dir(app).listFiles()?.forEach { it.delete() } }
        }
    }
}
