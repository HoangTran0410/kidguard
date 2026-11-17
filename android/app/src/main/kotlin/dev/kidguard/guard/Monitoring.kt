package dev.kidguard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject

/**
 * What the parent may read about the child (rule `watch`). Every such switch is a [Monitoring.Cat] in [Monitoring.ALL]:
 * that registry is the ONE source of what the child is told (the lasting line in the KidGuard notification, the heads-up
 * when a parent turns one on, the "What your parent can see" screen). There is no setting that hides it; a unit test
 * (MonitoringTest) fails when a key of [WatchRule.toJson] has no entry or an entry has no text in both languages.
 * Not in the KidGuard Home flavor (it has no parent).
 */
data class NotifWatch(
    val on: Boolean = false,                 // new notifications go to the parent with the normal sync (+ older ones on demand)
    val kwOnly: Boolean = false,             // only those that hit a word of [WatchRule.words]
    val apps: String = "all",                // "all" | "only" (just [list]) | "except" (everything but [list])
    val list: List<String> = emptyList(),    // package names
) {
    fun toJson(): JSONObject = JSONObject().put("on", on).put("kw", kwOnly).put("apps", apps).put("list", JSONArray(list))

    /** Does a notification of [pkg] pass the app filter? */
    fun appOk(pkg: String): Boolean = when (apps) { "only" -> pkg in list; "except" -> pkg !in list; else -> true }

    companion object {
        const val MAX_APPS = 200
        fun fromJson(o: JSONObject?): NotifWatch {
            if (o == null) return NotifWatch()
            val mode = o.optString("apps", "all").let { if (it == "only" || it == "except") it else "all" }
            val l = o.optJSONArray("list")
            val list = (0 until (l?.length() ?: 0)).map { l!!.optString(it).trim() }.filter { it.isNotEmpty() && it.length <= 200 }.distinct().take(MAX_APPS)
            return NotifWatch(o.optBoolean("on", false), o.optBoolean("kw", false), mode, list)
        }
    }
}

data class WatchRule(
    val notif: NotifWatch = NotifWatch(),
    val words: List<String> = emptyList(),   // keywords: any notification / SMS with one is an alert to the parent (see KeywordLogic)
    val cam: Boolean = false,                // the parent may take a photo on request (Snap.kt); the child is told each time
) {
    fun toJson(): JSONObject = JSONObject().put("notif", notif.toJson()).put("words", JSONArray(words)).put("cam", cam)

    /** Is the category [id] (a [Monitoring.Cat.id], same as the JSON key) switched on? */
    fun isOn(id: String): Boolean = when (id) {
        "notif" -> notif.on
        "words" -> words.isNotEmpty()
        "cam" -> cam
        else -> false
    }

    /** How much the parent gets in category [id] (0 = the narrow form): a step up is told to the child again. */
    fun level(id: String): Int = if (id == "notif" && notif.on && !notif.kwOnly) 1 else 0

    companion object {
        fun fromJson(o: JSONObject?): WatchRule {
            if (o == null) return WatchRule()
            val l = o.optJSONArray("words")
            return WatchRule(NotifWatch.fromJson(o.optJSONObject("notif")), KeywordLogic.clean((0 until (l?.length() ?: 0)).map { l!!.optString(it) }), o.optBoolean("cam", false))
        }
    }
}

object Monitoring {
    /**
     * [id] = the key under `watch` in the rules; [title] = short name (the lasting line lists these); [kid] = what the child
     * reads about it ("your parent can ..."); [sees] = exactly what the parent gets (kept short).
     */
    class Cat(val id: String, val title: Int, val kid: Int, val sees: Int, val kwTitle: Int = title, val kwKid: Int = kid, val kwSees: Int = sees) {
        val rule get() = "watch.$id"
        private fun kw(w: WatchRule) = w.level(id) == 0
        fun title(w: WatchRule) = if (kw(w)) kwTitle else title
        fun kid(w: WatchRule) = if (kw(w)) kwKid else kid
        fun sees(w: WatchRule) = if (kw(w)) kwSees else sees
    }

    val ALL: List<Cat> = listOf(
        Cat("notif", R.string.watch_notif_title, R.string.watch_notif_kid, R.string.watch_notif_sees,
            R.string.watch_notif_kw_title, R.string.watch_notif_kw_kid, R.string.watch_notif_kw_sees),
        Cat("words", R.string.watch_words_title, R.string.watch_words_kid, R.string.watch_words_sees),
        Cat("cam", R.string.watch_cam_title, R.string.watch_cam_kid, R.string.watch_cam_sees),
    )

    fun byId(id: String): Cat? = ALL.firstOrNull { it.id == id }

    /** The categories that are on, registry order. */
    fun active(w: WatchRule): List<Cat> = ALL.filter { w.isOn(it.id) }

    /** Categories on in [new] that were off in [old], or that now give the parent more: the child gets a heads-up for each. */
    fun turnedOn(old: WatchRule, new: WatchRule): List<Cat> =
        ALL.filter { new.isOn(it.id) && (!old.isOn(it.id) || new.level(it.id) > old.level(it.id)) }

    private const val CH = "kg_watch"
    private const val NID = 12

    /** "Your parent can see: notifications, SMS" or "" when nothing is on. */
    fun line(ctx: Context, w: WatchRule): String {
        val on = active(w)
        return if (on.isEmpty()) "" else ctx.getString(R.string.watch_line, on.joinToString(", ") { ctx.getString(it.title(w)) })
    }

    /** The "What your parent can see" screen. */
    fun openIntent(ctx: Context): PendingIntent =
        PendingIntent.getActivity(ctx, 12, Intent(ctx, WatchActivity::class.java), PendingIntent.FLAG_IMMUTABLE)

    /** One heads-up for the categories [cats] the parent just turned on. Always shown; there is no rule that skips it. */
    fun tellOn(ctx: Context, cats: List<Cat>, w: WatchRule = WatchRule()) {
        if (cats.isEmpty() || Flavor.HOME) return
        runCatching {
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(NotificationChannel(CH, ctx.getString(R.string.ch_watch), NotificationManager.IMPORTANCE_HIGH))
            val what = cats.joinToString(", ") { ctx.getString(it.title(w)) }
            nm.notify(NID, Notification.Builder(ctx, CH).setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle(ctx.getString(R.string.watch_on_title, what))
                .setContentText(ctx.getString(R.string.watch_on_text))
                .setStyle(Notification.BigTextStyle().bigText(cats.joinToString("\n") { ctx.getString(it.kid(w)) }))
                .setContentIntent(openIntent(ctx)).setAutoCancel(true).build())
        }
    }

    /** Release: nothing is watched any more. */
    fun release(ctx: Context) {
        runCatching { ctx.getSystemService(NotificationManager::class.java)?.cancel(NID) }
    }
}
