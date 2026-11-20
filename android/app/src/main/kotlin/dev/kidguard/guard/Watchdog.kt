package dev.kidguard

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log

/** An alarm every ~10 min (allowed in Doze) that starts Guard again if it was killed, makes the relay sync even while
 *  the phone sleeps ([Relay.wake]: before this the sync loop only ran while something else kept the CPU awake), and re-arms itself. */
class Watchdog : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Guard.running?.relayWake() ?: run {
            // process gone well after boot = something killed it: the "run in background" / Recents lock steps show from now on
            // a step ticked "Đã bật" by hand evidently isn't: shown again (owner 2026-11-20)
            if (android.os.SystemClock.elapsedRealtime() > 10 * 60_000L) Store.of(context).run { bgRecheck(); killSeen = System.currentTimeMillis() }
            Guard.start(context)
        }
        schedule(context)
    }

    companion object {
        private const val EVERY = 10 * 60_000L

        private fun pi(ctx: Context) = PendingIntent.getBroadcast(ctx, 7, Intent(ctx, Watchdog::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        fun cancel(ctx: Context) {
            runCatching { ctx.getSystemService(AlarmManager::class.java).cancel(pi(ctx)) }
        }

        fun schedule(ctx: Context) {
            if (Store.of(ctx).released) return
            runCatching {
                ctx.getSystemService(AlarmManager::class.java)
                    .setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + EVERY, pi(ctx))
            }.onFailure { Log.w("KidGuard", "watchdog", it) }
        }
    }
}
