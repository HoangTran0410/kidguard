package dev.kidguard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.os.PowerManager

/** Tells the child once per burst that location was put back on by the parent's rule (toast, only with the screen on). */
object LocHint {
    private const val GAP_MS = 5 * 60_000L
    private var last = 0L

    fun maybe(ctx: Context, now: Long = System.currentTimeMillis()) {
        if (now - last < GAP_MS) return
        if (!runCatching { ctx.getSystemService(PowerManager::class.java)?.isInteractive == true }.getOrDefault(false)) return
        last = now
        Toasts.show(ctx, ctx.getString(R.string.loc_kept))
    }
}

/**
 * Rule lockLocation without Device Owner and without WRITE_SECURE_SETTINGS (basic mode, no adb): nothing can switch
 * location back on, so the child gets a sticky "turn location back on" notification (tap = Location settings) and the
 * parent one tamper event per switch-off (push / Telegram like other tamper events). Owner 2026-11-19.
 */
object LocNagLogic {
    enum class Act { NONE, START, KEEP, END }
    /** [offAt] = when the nag started (0 = none). Returns the new offAt and what to do. Pure (tested). */
    fun step(offAt: Long, nag: Boolean, now: Long): Pair<Long, Act> = when {
        nag && offAt == 0L -> now to Act.START
        nag -> offAt to Act.KEEP
        offAt != 0L -> 0L to Act.END
        else -> 0L to Act.NONE
    }
}

object LocNag {
    private const val NOTIF = 7450

    /** [nag] = the rule is on, location is off and nothing can turn it back on. Every tick + location observer. */
    fun update(ctx: Context, nag: Boolean, store: Store, now: Long = System.currentTimeMillis()) {
        val prefs = ctx.createDeviceProtectedStorageContext().getSharedPreferences("locnag", Context.MODE_PRIVATE)
        val (at, act) = LocNagLogic.step(prefs.getLong("offAt", 0L), nag, now)
        if (act == LocNagLogic.Act.NONE) return
        if (act != LocNagLogic.Act.KEEP) prefs.edit().putLong("offAt", at).apply()
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        when (act) {
            LocNagLogic.Act.START -> {
                runCatching { store.eventKey("tamper", "locoff", R.string.ev_loc_off); store.log(R.string.log_loc_off) }
                show(ctx, nm)
            }
            LocNagLogic.Act.KEEP -> if (nm.activeNotifications.none { it.id == NOTIF }) show(ctx, nm)   // swiped away: back
            LocNagLogic.Act.END -> { nm.cancel(NOTIF); runCatching { store.log(R.string.log_loc_back) } }
            else -> {}
        }
    }

    private fun show(ctx: Context, nm: NotificationManager) = runCatching {
        nm.createNotificationChannel(NotificationChannel("loc", ctx.getString(R.string.ch_loc), NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(ctx, NOTIF, Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = ctx.getString(R.string.loc_nag)
        nm.notify(NOTIF, Notification.Builder(ctx, "loc").setSmallIcon(android.R.drawable.ic_dialog_map).setContentTitle("KidGuard")
            .setContentText(text).setStyle(Notification.BigTextStyle().bigText(text)).setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(open).build())
    }

    /** Release hook: our notification goes, nothing durable is set. */
    fun clear(ctx: Context) {
        runCatching { ctx.getSystemService(NotificationManager::class.java)?.cancel(NOTIF) }
        runCatching { ctx.createDeviceProtectedStorageContext().getSharedPreferences("locnag", Context.MODE_PRIVATE).edit().clear().apply() }
    }
}
