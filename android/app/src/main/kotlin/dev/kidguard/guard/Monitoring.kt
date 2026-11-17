package dev.kidguard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import org.json.JSONObject

/**
 * What the parent may read about the child (rule `watch`). Every such switch is a [Monitoring.Cat] in [Monitoring.ALL]:
 * that registry is the ONE source of what the child is told (the lasting line in the KidGuard notification, the heads-up
 * when a parent turns one on, the "What your parent can see" screen). There is no setting that hides it; a unit test
 * (MonitoringTest) fails when a key of [WatchRule.toJson] has no entry or an entry has no text in both languages.
 * Not in the KidGuard Home flavor (it has no parent).
 */
data class WatchRule(
    val notif: Boolean = false,   // the parent can read recent notifications (pull on demand)
) {
    fun toJson(): JSONObject = JSONObject().put("notif", notif)

    /** Is the category [id] (a [Monitoring.Cat.id], same as the JSON key) switched on? */
    fun isOn(id: String): Boolean = when (id) {
        "notif" -> notif
        else -> false
    }

    companion object {
        fun fromJson(o: JSONObject?): WatchRule = if (o == null) WatchRule() else WatchRule(notif = o.optBoolean("notif", false))
    }
}

object Monitoring {
    /**
     * [id] = the key under `watch` in the rules; [title] = short name (the lasting line lists these); [kid] = what the child
     * reads about it ("your parent can ..."); [sees] = exactly what the parent gets (kept short).
     */
    class Cat(val id: String, val title: Int, val kid: Int, val sees: Int) {
        val rule get() = "watch.$id"
    }

    val ALL: List<Cat> = listOf(
        Cat("notif", R.string.watch_notif_title, R.string.watch_notif_kid, R.string.watch_notif_sees),
    )

    fun byId(id: String): Cat? = ALL.firstOrNull { it.id == id }

    /** The categories that are on, registry order. */
    fun active(w: WatchRule): List<Cat> = ALL.filter { w.isOn(it.id) }

    /** Categories on in [new] that were off in [old]: the child gets a heads-up for each. */
    fun turnedOn(old: WatchRule, new: WatchRule): List<Cat> = ALL.filter { new.isOn(it.id) && !old.isOn(it.id) }

    private const val CH = "kg_watch"
    private const val NID = 12

    /** "Your parent can see: notifications, SMS" or "" when nothing is on. */
    fun line(ctx: Context, w: WatchRule): String {
        val on = active(w)
        return if (on.isEmpty()) "" else ctx.getString(R.string.watch_line, on.joinToString(", ") { ctx.getString(it.title) })
    }

    /** The "What your parent can see" screen. */
    fun openIntent(ctx: Context): PendingIntent =
        PendingIntent.getActivity(ctx, 12, Intent(ctx, WatchActivity::class.java), PendingIntent.FLAG_IMMUTABLE)

    /** One heads-up for the categories [cats] the parent just turned on. Always shown; there is no rule that skips it. */
    fun tellOn(ctx: Context, cats: List<Cat>) {
        if (cats.isEmpty() || Flavor.HOME) return
        runCatching {
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(NotificationChannel(CH, ctx.getString(R.string.ch_watch), NotificationManager.IMPORTANCE_HIGH))
            val what = cats.joinToString(", ") { ctx.getString(it.title) }
            nm.notify(NID, Notification.Builder(ctx, CH).setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle(ctx.getString(R.string.watch_on_title, what))
                .setContentText(ctx.getString(R.string.watch_on_text))
                .setStyle(Notification.BigTextStyle().bigText(cats.joinToString("\n") { ctx.getString(it.kid) }))
                .setContentIntent(openIntent(ctx)).setAutoCancel(true).build())
        }
    }

    /** Release: nothing is watched any more. */
    fun release(ctx: Context) {
        runCatching { ctx.getSystemService(NotificationManager::class.java)?.cancel(NID) }
    }
}
