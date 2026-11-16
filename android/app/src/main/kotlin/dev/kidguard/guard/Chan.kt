package dev.kidguard

import android.app.NotificationChannel
import android.app.NotificationManager

/**
 * Notification channels that carry an escape hatch ("Show 3 buttons", "Turn on now") must not stay blocked: Android
 * never lets an app turn a blocked channel back on (and re-creating a deleted id restores the block), so when the user
 * (or a kid) blocked one we post on the next id instead: "nav", "nav.1", "nav.2"... Owner 2026-11-16.
 */
object Chan {
    /** First id from [base] that [blocked] says is usable; [base] again after [MAX] tries. */
    fun pick(base: String, blocked: (String) -> Boolean): String =
        (0 until MAX).map { if (it == 0) base else "$base.$it" }.firstOrNull { !blocked(it) } ?: base

    const val MAX = 30

    /** Creates (or reuses) a channel that is not blocked and returns its id. */
    fun open(nm: NotificationManager, base: String, name: String, importance: Int): String = pick(base) { id ->
        runCatching { nm.createNotificationChannel(NotificationChannel(id, name, importance)) }
        nm.getNotificationChannel(id)?.importance == NotificationManager.IMPORTANCE_NONE
    }
}
