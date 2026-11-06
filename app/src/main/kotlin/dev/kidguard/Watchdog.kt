package dev.kidguard

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log

/** An alarm every ~15 min that starts Guard again if it was killed, and re-arms itself. */
class Watchdog : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) { Guard.start(context); schedule(context) }

    companion object {
        private const val EVERY = 15 * 60_000L

        fun schedule(ctx: Context) {
            runCatching {
                val pi = PendingIntent.getBroadcast(ctx, 7, Intent(ctx, Watchdog::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                ctx.getSystemService(AlarmManager::class.java)
                    .setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + EVERY, pi)
            }.onFailure { Log.w("KidGuard", "watchdog", it) }
        }
    }
}
