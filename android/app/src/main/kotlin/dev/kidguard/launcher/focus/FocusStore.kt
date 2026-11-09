package dev.kidguard

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Persistence + DND side of study mode (see [FocusLogic]). One small prefs file "kg_focus" (device-protected), so the
 * session survives process death and a reboot. Launcher-local: nothing here goes to the parent page.
 * DND safety net: [expire] runs on every launcher render / resume, on every Guard tick (kid flavor) and from an
 * inexact alarm at the session end ([FocusEndReceiver]); [release] ends everything (Policy.release).
 */
object FocusStore {
    private fun p(ctx: Context) = ctx.applicationContext.let { (if (android.os.Build.VERSION.SDK_INT >= 24) it.createDeviceProtectedStorageContext() else it).getSharedPreferences("kg_focus", 0) }
    private fun nm(ctx: Context) = ctx.getSystemService(NotificationManager::class.java)

    @Volatile var mode: FocusLogic.Mode = FocusLogic.Mode.OFF   // last result of the launcher's sync, for the render key

    fun session(ctx: Context): FocusLogic.Session? = FocusLogic.parse(p(ctx).getString("s", null))
    fun study(ctx: Context): Set<String> = p(ctx).getStringSet("study", null)?.toSet() ?: emptySet()
    fun setStudy(ctx: Context, s: Set<String>) { p(ctx).edit().putStringSet("study", s).apply() }
    fun slots(ctx: Context): List<FocusLogic.Slot> = FocusLogic.slotsParse(p(ctx).getString("slots", ""))
    fun setSlots(ctx: Context, l: List<FocusLogic.Slot>) { p(ctx).edit().putString("slots", FocusLogic.slotsPref(l)).apply() }

    fun dndAccess(ctx: Context) = nm(ctx)?.isNotificationPolicyAccessGranted == true

    /** Starts a session of [minutes]; turns DND on (priority only) when allowed and currently off. Returns true when DND was turned on. */
    @Synchronized fun start(ctx: Context, minutes: Int, now: Long = System.currentTimeMillis()): Boolean {
        end(ctx)   // a running one first: its DND is put back before we read the "before" value
        var s = FocusLogic.begin(now, minutes)
        var dnd = false
        val n = nm(ctx)
        if (n != null && n.isNotificationPolicyAccessGranted) {
            val cur = n.currentInterruptionFilter
            if (cur == NotificationManager.INTERRUPTION_FILTER_ALL) {
                dnd = runCatching { n.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY); true }.getOrDefault(false)
                s = s.withDnd(FocusLogic.ALL, dnd)
            }
        }
        p(ctx).edit().putString("s", s.toPref()).commit()   // commit: DND is already on, the record must not be lost
        alarm(ctx, s.end)
        return dnd
    }

    /** Ends the session now (early or at its end): DND back to what it was, record and alarm gone. */
    @Synchronized fun end(ctx: Context) {
        val s = session(ctx)
        if (s != null) {
            val n = nm(ctx)
            val back = FocusLogic.restore(s, n?.currentInterruptionFilter)
            if (back != null && n?.isNotificationPolicyAccessGranted == true) runCatching { n.setInterruptionFilter(back) }
        }
        p(ctx).edit().remove("s").commit()
        alarm(ctx, 0)
    }

    /** Ends a session that is over (or absurd: clock set back). Cheap; safe to call from anywhere. Returns the live session or null. */
    fun expire(ctx: Context, now: Long = System.currentTimeMillis()): FocusLogic.Session? {
        val s = session(ctx) ?: return null
        if (FocusLogic.active(s, now)) return s
        end(ctx); return null
    }

    /** Policy.release: no session, no DND we set, no schedule. */
    fun release(ctx: Context) {
        end(ctx)
        p(ctx).edit().remove("slots").apply()
        mode = FocusLogic.Mode.OFF
    }

    private fun alarm(ctx: Context, at: Long) {
        runCatching {
            val am = ctx.getSystemService(AlarmManager::class.java) ?: return
            val pi = PendingIntent.getBroadcast(ctx, 7301, Intent(ctx, FocusEndReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            if (at <= 0) am.cancel(pi) else am.set(AlarmManager.RTC_WAKEUP, at + 2_000, pi)   // inexact: no extra permission
        }
    }
}

/** Alarm at the end of a study session: puts DND back even when the launcher process is gone. */
class FocusEndReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) { FocusStore.expire(ctx) }
}
