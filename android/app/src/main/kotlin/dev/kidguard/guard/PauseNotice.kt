package dev.kidguard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/**
 * Bank-type app opened WITHOUT the kid home (Recents, notification, link): [AccPause.pauseLate] restarts it with
 * accessibility / debugging / adb port / VPN off. The home path asks first ([pauseAsk]); here there is no time to ask
 * (the service goes 150 ms later, an overlay window would die with it), so a heads-up NOTIFICATION says afterwards what
 * went off and how it comes back, with the same pa_* texts. It never covers the bank app, needs no Activity start, and
 * is one notification per app (same id: re-opening replaces it, no stacking, no loop). Skipped for apps in
 * Store.pauseNoAsk ("don't ask again") and when nothing was on to go off. [kinds] must be read BEFORE the pause.
 */
internal object PauseNotice {
    private const val CH = "pause"
    private const val TIMEOUT_MS = 90_000L

    fun show(ctx: Context, pkg: String, label: String, kinds: List<PauseAsk.Kind>) {
        if (kinds.isEmpty() || pkg in Store.of(ctx).pauseNoAsk) return
        runCatching {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CH, ctx.getString(R.string.ch_pause), NotificationManager.IMPORTANCE_HIGH))
            val rows = PauseAsk.lines(ctx, kinds)
            val big = rows.joinToString("\n\n") { (t, b) -> "• $t\n$b" }
            val short = rows.joinToString(", ") { it.first }
            val n = Notification.Builder(ctx, CH).setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(ctx.getString(R.string.bp_title, label))
                .setContentText(short)
                .setStyle(Notification.BigTextStyle().bigText(big))
                .setAutoCancel(true).setTimeoutAfter(TIMEOUT_MS).build()
            nm.notify(NOTIF_BASE + (pkg.hashCode() and 0xff), n)
        }
    }

    private const val NOTIF_BASE = 7300
}
