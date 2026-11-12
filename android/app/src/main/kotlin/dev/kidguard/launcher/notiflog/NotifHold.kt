package dev.kidguard

import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONObject
import java.util.Calendar

/**
 * Holding an app's notifications, chosen by the kid on its stats page (NotifStatsActivity): "bundle" = shown only at
 * [SLOTS] (8, 12, 18 h), "mute" = held until a time (1 h, tomorrow 7:00). The listener snoozes each new notification
 * of a held app until its delivery time (NotificationListenerService.snoozeNotification, API 26): the system brings
 * the very same notification back then, with its actions and reply, so nothing is copied or lost. Turning a hold off
 * cannot bring back the ones already snoozed (no listener API un-snoozes): they come at their time, the page says when.
 * Kid-local (prefs "nhold"), never sent; release unbinds the listener, so holds stop with it. Pure parts are unit-tested.
 */
object NotifHold {
    const val BUNDLE = "bundle"
    const val MUTE = "mute"
    val SLOTS = listOf(8, 12, 18)
    const val MORNING = 7
    /** A repost this close before its delivery time is the delivery (the alarm may fire a little early). */
    private const val EARLY_MS = 60_000L

    data class Hold(val mode: String, val until: Long = 0)

    // ---- pure ----

    /** The next slot hour strictly after [now] (+1 min, so a notification at 11:59:30 waits for 18:00, not 30 s). */
    fun nextSlot(now: Long, slots: List<Int> = SLOTS): Long {
        val c = Calendar.getInstance().apply { timeInMillis = now; set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        for (d in 0..1) for (h in slots.sorted()) {
            val t = (c.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, d); set(Calendar.HOUR_OF_DAY, h) }.timeInMillis
            if (t > now + EARLY_MS) return t
        }
        return now + 24 * 3600_000L   // no slots: a day
    }

    /** Next [hour]:00 after [now] (tomorrow when it is already past today). */
    fun nextAt(now: Long, hour: Int): Long {
        val c = Calendar.getInstance().apply { timeInMillis = now; set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        if (c.timeInMillis <= now + EARLY_MS) c.add(Calendar.DAY_OF_MONTH, 1)
        return c.timeInMillis
    }

    /** When a notification posted at [now] under [h] is shown, 0 = show it now (no hold, or a mute that ended). */
    fun deliverAt(h: Hold?, now: Long): Long = when (h?.mode) {
        BUNDLE -> nextSlot(now)
        MUTE -> if (h.until > now + EARLY_MS) h.until else 0
        else -> 0
    }

    /** False for what must never wait: calls, alarms, timers, navigation, media, ongoing / foreground, full-screen, ours. */
    fun holdable(pkg: String, category: String?, flags: Int, fullScreen: Boolean, media: Boolean): Boolean {
        if (pkg.startsWith("dev.kidguard") || fullScreen || media) return false
        if (flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE or Notification.FLAG_NO_CLEAR) != 0) return false
        return category !in setOf(Notification.CATEGORY_CALL, Notification.CATEGORY_ALARM, Notification.CATEGORY_REMINDER, Notification.CATEGORY_NAVIGATION,
            Notification.CATEGORY_TRANSPORT, Notification.CATEGORY_STOPWATCH, Notification.CATEGORY_PROGRESS, Notification.CATEGORY_SYSTEM, Notification.CATEGORY_SERVICE)
    }

    /**
     * A post of [key] at [now]: the delivery of a snoozed one (its time came) passes; else the time to hold it until
     * ([deliverAt]), 0 = pass. [snoozed] = key -> delivery time of the ones we snoozed.
     */
    fun decide(key: String, now: Long, h: Hold?, snoozed: Map<String, Long>): Long {
        snoozed[key]?.let { at -> if (now >= at - EARLY_MS) return 0 }
        return deliverAt(h, now)
    }

    fun toJson(m: Map<String, Hold>): String = JSONObject().apply {
        for ((p, h) in m) put(p, JSONObject().put("m", h.mode).apply { if (h.until > 0) put("u", h.until) })
    }.toString()

    fun fromJson(s: String?): Map<String, Hold> = runCatching {
        val o = JSONObject(s ?: return emptyMap())
        o.keys().asSequence().mapNotNull { p ->
            val x = o.optJSONObject(p) ?: return@mapNotNull null
            val m = x.optString("m")
            if (m != BUNDLE && m != MUTE) null else p to Hold(m, x.optLong("u"))
        }.toMap()
    }.getOrDefault(emptyMap())

    /** Mutes that ended are dropped (they hold nothing any more). */
    fun live(m: Map<String, Hold>, now: Long): Map<String, Hold> = m.filterValues { it.mode != MUTE || it.until > now }

    // ---- store (prefs "nhold": holds + the keys we snoozed) ----

    @Volatile private var cache: Map<String, Hold>? = null
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("nhold", Context.MODE_PRIVATE)

    fun all(ctx: Context): Map<String, Hold> {
        val now = System.currentTimeMillis()
        val m = cache ?: fromJson(prefs(ctx).getString("h", null)).also { cache = it }
        return live(m, now)
    }

    fun get(ctx: Context, pkg: String): Hold? = all(ctx)[pkg]
    fun any(ctx: Context): Boolean = all(ctx).isNotEmpty()

    /** Sets (null = clears) [pkg]'s hold; the listener is bound for it even with the dots off. */
    fun set(ctx: Context, pkg: String, h: Hold?) {
        val m = all(ctx).toMutableMap()
        if (h == null) m.remove(pkg) else m[pkg] = h
        cache = m
        prefs(ctx).edit().putString("h", toJson(m)).apply()
        if (h != null) NotifDots.setOn(ctx, true)
    }

    private fun snoozed(ctx: Context): MutableMap<String, Long> = runCatching {
        val o = JSONObject(prefs(ctx).getString("k", null) ?: return mutableMapOf())
        o.keys().asSequence().associateWith { o.optLong(it) }.toMutableMap()
    }.getOrDefault(mutableMapOf())

    private fun saveSnoozed(ctx: Context, m: Map<String, Long>, now: Long) {
        // a delivered one is dropped when it comes back; this only bounds keys whose app cancelled them meanwhile
        val keep = m.filterValues { it > now - 24 * 3600_000L }
        prefs(ctx).edit().putString("k", JSONObject(keep).toString()).apply()
    }

    /** Waiting now per package: key -> delivery time, for the "N đang chờ, hiện lúc 18:00" note. */
    fun waiting(ctx: Context, pkg: String, now: Long = System.currentTimeMillis()): List<Long> =
        snoozed(ctx).filter { (k, at) -> at > now && k.split('|').getOrNull(1) == pkg }.values.sorted()

    /** From the listener's onNotificationPosted: snoozes [s] when its app is held. */
    @Synchronized fun onPosted(svc: NotificationListenerService, s: StatusBarNotification) {
        val m = all(svc)
        val n = s.notification
        val sn = snoozed(svc)
        val now = System.currentTimeMillis()
        if (sn.isEmpty() && m[s.packageName] == null) return
        val media = n.extras.containsKey("android.mediaSession") || n.extras.getString(Notification.EXTRA_TEMPLATE)?.contains("MediaStyle") == true
        if (!holdable(s.packageName, n.category, n.flags, n.fullScreenIntent != null, media) || s.isOngoing) return
        val at = decide(s.key, now, m[s.packageName], sn)
        if (at == 0L) { if (sn.remove(s.key) != null) saveSnoozed(svc, sn, now); return }
        if (runCatching { svc.snoozeNotification(s.key, at - now) }.isSuccess) { sn[s.key] = at; saveSnoozed(svc, sn, now) }
    }
}
