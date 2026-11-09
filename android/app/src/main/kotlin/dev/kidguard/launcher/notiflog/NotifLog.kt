package dev.kidguard

import android.app.Notification
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.service.notification.StatusBarNotification
import java.io.File
import java.util.concurrent.Executors

/**
 * The kid's notification history ("I swiped it before reading"). Local only: one file in the app's private storage,
 * never in state JSON, never sent anywhere, the parent can't see it. 7 days / 300 entries (NotifLogic).
 * Fed by the notification listener (NotifDots.onNotificationPosted); disk work runs on one background thread.
 */
object NotifLog {
    /** One switch for the whole feature (no setting yet). */
    const val enabled = true

    private val io = Executors.newSingleThreadExecutor()
    private var cache: List<NotifEntry>? = null   // touched only on [io]

    private fun file(ctx: Context) = File(ctx.filesDir, "notiflog.json")
    private fun load(ctx: Context): List<NotifEntry> =
        cache ?: NotifLogic.fromJson(runCatching { file(ctx).readText() }.getOrNull()).also { cache = it }
    private fun save(ctx: Context, l: List<NotifEntry>) {
        cache = l
        runCatching { val f = file(ctx); val t = File(f.path + ".tmp"); t.writeText(NotifLogic.toJson(l)); t.renameTo(f) }
    }

    private fun wanted(s: StatusBarNotification): Boolean {
        val n = s.notification
        if (s.isOngoing || n.flags and (Notification.FLAG_GROUP_SUMMARY or Notification.FLAG_FOREGROUND_SERVICE or Notification.FLAG_ONGOING_EVENT) != 0) return false
        if (s.packageName.startsWith("dev.kidguard")) return false
        if (n.category == Notification.CATEGORY_TRANSPORT || n.extras.containsKey("android.mediaSession")) return false
        return n.extras.getString(Notification.EXTRA_TEMPLATE)?.contains("MediaStyle") != true
    }

    /** Called from the listener thread: reads the extras now, writes on the io thread. */
    fun record(ctx: Context, s: StatusBarNotification) {
        if (!enabled || !wanted(s)) return
        val x = s.notification.extras
        val title = x.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val big = x.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim().orEmpty()
        val text = big.ifEmpty { x.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty() }
        if (title.isEmpty() && text.isEmpty()) return
        val app = ctx.applicationContext
        val e = NotifEntry(s.key, System.currentTimeMillis(), s.packageName, IconCache.label(app, s.packageName), title.take(200), text.take(600))
        io.execute { save(app, NotifLogic.add(load(app), e)) }
    }

    /** Newest first; [done] runs on the main thread. */
    fun read(ctx: Context, done: (List<NotifEntry>) -> Unit) {
        val app = ctx.applicationContext
        io.execute {
            val l = NotifLogic.trim(load(app), System.currentTimeMillis()).asReversed()
            Handler(Looper.getMainLooper()).post { done(l) }
        }
    }

    fun clear(ctx: Context) { val app = ctx.applicationContext; io.execute { save(app, emptyList()) } }
}
