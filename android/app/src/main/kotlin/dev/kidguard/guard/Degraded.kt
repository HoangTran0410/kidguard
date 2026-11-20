package dev.kidguard

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/** Pure throttle: one notice per key per [GAP_MS]. */
object DegradedLogic {
    const val GAP_MS = 30 * 60_000L
    fun due(last: Long?, now: Long, gap: Long = GAP_MS): Boolean = last == null || now - last >= gap || now < last
    /**
     * Notify + log now? Only when the key is not in `Store.degradedTold` (persisted: a process restart or a condition that
     * stays true for days, e.g. location permission denied, says nothing more) and not within [GAP_MS] of the last time
     * (a key that flaps clear / report). It used to re-alert every 30 min while stuck (heads-up + sound, and a log line).
     */
    fun tell(known: Boolean, last: Long?, now: Long): Boolean = !known && due(last, now)
    /** Stable notification id per key (own range, away from the fixed ids). */
    fun notifId(key: String): Int = 6000 + Math.floorMod(key.hashCode(), 900)
}

/**
 * "Never silent": a protection was skipped because KidGuard lacks a privilege (WRITE_SECURE_SETTINGS, Device Owner,
 * overlay, location, shell). The kid gets one notification (button to the right Settings page), the parent gets a log
 * line, both once per state change ([DegradedLogic.tell]), and the key stays in `Store.degraded` (state JSON `degraded`) until [clear] says it works again.
 * Key = "<area>" or "<area>.<detail>" (the page maps the area to a label).
 */
object Degraded {
    private val last = HashMap<String, Long>()
    private const val CH = "degraded"

    @Synchronized fun report(ctx: Context, key: String, title: String, body: String, settingsIntent: Intent?, logText: String) {
        if (Flavor.HOME) return
        val app = ctx.applicationContext
        val s = Store.of(app)
        // the state always lists the key (the parent sees it at once); only the notice + log line wait for [DegradedLogic.tell]
        if (key !in s.degraded) s.degraded = s.degraded + key
        val known = key in s.degradedTold
        val now = System.currentTimeMillis()
        // told only once per state change; a flapped key skipped by the throttle stays untold, so a later report tells it
        if (!DegradedLogic.tell(known, last[key], now)) return
        s.degradedTold = s.degradedTold + key
        last[key] = now
        runCatching {
            val nm = app.getSystemService(NotificationManager::class.java)
            val id = DegradedLogic.notifId(key)
            val ch = Chan.open(nm, CH, app.getString(R.string.ch_degraded), NotificationManager.IMPORTANCE_HIGH)
            val b = Notification.Builder(app, ch).setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle(title).setContentText(body).setStyle(Notification.BigTextStyle().bigText(body)).setAutoCancel(true).setOnlyAlertOnce(true)
            if (settingsIntent != null) {
                val pi = PendingIntent.getActivity(app, id, Intent(settingsIntent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                b.addAction(Notification.Action.Builder(null, app.getString(R.string.degraded_open), pi).build())
            }
            nm.notify(id, b.build())
        }
        runCatching { s.log(logText, "degraded") }
    }

    /** The protection works again: forget the key and drop its notification. */
    @Synchronized fun clear(ctx: Context, key: String) {
        val app = ctx.applicationContext
        val s = Store.of(app)
        // `last` is kept: a key that flaps (works, fails again) stays quiet for GAP_MS
        if (key in s.degradedTold) s.degradedTold = s.degradedTold - key
        if (key !in s.degraded) return
        s.degraded = s.degraded - key
        runCatching { app.getSystemService(NotificationManager::class.java).cancel(DegradedLogic.notifId(key)) }
    }
}
