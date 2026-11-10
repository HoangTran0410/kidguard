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
 * The kid's notification history ("I swiped it before reading"). Local only: one file in the app's private storage
 * (+ small images in notiflog_img/), never in state JSON, never sent anywhere, the parent can't see it.
 * 7 days / 300 entries (NotifLogic). Fed by the notification listener (NotifDots); all disk / image work runs on one
 * background thread; the list lives in memory and is written at most once per [SAVE_DELAY_MS].
 */
object NotifLog {
    /** One switch for the whole feature (no setting yet). */
    const val enabled = true
    private const val SAVE_DELAY_MS = 3_000L
    private const val HELD_MAX = 50

    private val io = ScheduledThreadPoolExecutor(1)
    // everything below up to [held] is touched only on [io]
    private var cache: List<NotifEntry>? = null
    private var dirty = false
    private var pendingSave: ScheduledFuture<*>? = null
    private val gone = HashSet<String>()   // image files to delete after the next successful write

    private fun file(ctx: Context) = File(ctx.filesDir, "notiflog.json")
    private fun dir(ctx: Context) = File(ctx.filesDir, "notiflog_img")
    fun imageFile(ctx: Context, name: String): File = File(dir(ctx), name)

    private fun load(ctx: Context): List<NotifEntry> = cache ?: NotifLogic.fromJson(runCatching { file(ctx).readText() }.getOrNull()).also {
        cache = it
        // images left behind by a crash between the image write and the list write
        val used = NotifLogic.refs(it)
        runCatching { dir(ctx).listFiles()?.forEach { f -> if (f.name !in used) f.delete() } }
    }

    /** New list in memory now, on disk within [SAVE_DELAY_MS] (one write however many notifications arrive). */
    private fun mutate(ctx: Context, l: List<NotifEntry>) {
        gone += NotifLogic.orphans(cache ?: emptyList(), l)
        cache = l; dirty = true
        if (pendingSave == null) pendingSave = io.schedule({ writeNow(ctx) }, SAVE_DELAY_MS, TimeUnit.MILLISECONDS)
    }

    private fun writeNow(ctx: Context) {
        pendingSave = null
        val l = cache ?: return
        if (dirty) {
            val ok = runCatching { val f = file(ctx); val t = File(f.path + ".tmp"); t.writeText(NotifLogic.toJson(l)); t.renameTo(f) }.getOrDefault(false)
            if (!ok) return
            dirty = false
        }
        for (n in gone) runCatching { File(dir(ctx), n).delete() }
        gone.clear()
    }

    /** Writes now (service going away, so the last 3 s are not lost). */
    fun flush(ctx: Context) { val app = ctx.applicationContext; io.execute { pendingSave?.cancel(false); writeNow(app) } }

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
    fun record(ctx: Context, s: StatusBarNotification, importance: Int = 0) {
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
        )
        n.contentIntent?.let { pi -> synchronized(held) { held.remove(s.key); held[s.key] = Held(ClickRec(s.key, s.packageName, 0, ""), pi) } }
        // images: grab the references here, decode / scale / encode on io
        val largeIcon = runCatching { n.getLargeIcon() }.getOrNull()
        val largeBmp = x.get(Notification.EXTRA_LARGE_ICON) as? Bitmap
        val picBmp = x.get(Notification.EXTRA_PICTURE) as? Bitmap
        val picIcon = if (Build.VERSION.SDK_INT >= 31) x.get(Notification.EXTRA_PICTURE_ICON) as? Icon else null
        io.execute {
            val l = load(app)
            if (NotifLogic.isRepeat(l, e)) return@execute   // progress ticks etc.: no image work either
            val li = saveImg(app, NotifLogic.imgName(e.key, e.postTime, 'l'), bitmapOf(app, largeIcon, largeBmp), NotifLogic.ICON_PX, NotifLogic.ICON_PX, 80)
            val pic = saveImg(app, NotifLogic.imgName(e.key, e.postTime, 'p'), bitmapOf(app, picIcon, picBmp), NotifLogic.PIC_W, NotifLogic.PIC_H, 75)
            val added = NotifLogic.add(l, e.copy(largeIcon = li, picture = pic), now)
            mutate(app, added)
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
            val l = load(app)
            val n = NotifLogic.markRemoved(l, s.key, s.postTime, now, code)
            if (n !== l) mutate(app, n)
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

    // ---- reading ----

    /** Newest first; [done] runs on the main thread. */
    fun read(ctx: Context, done: (List<NotifEntry>) -> Unit) {
        val app = ctx.applicationContext
        io.execute {
            val all = load(app)
            val t = NotifLogic.trim(all, System.currentTimeMillis())
            if (t.size != all.size) mutate(app, t)
            val l = t.asReversed()
            Handler(Looper.getMainLooper()).post { done(l) }
        }
    }

    fun clear(ctx: Context) {
        val app = ctx.applicationContext
        synchronized(held) { held.clear() }
        io.execute {
            load(app)
            mutate(app, emptyList())
            runCatching { dir(app).listFiles()?.forEach { it.delete() } }
            pendingSave?.cancel(false); writeNow(app)
        }
    }
}
