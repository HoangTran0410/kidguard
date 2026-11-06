package dev.kidguard

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import java.util.Calendar

/** Foreground time today per app, counted from the system's usage events (resumed -> paused / screen off). */
object Usage {
    data class Today(val ms: Map<String, Long>, val current: String?)

    fun granted(ctx: Context): Boolean {
        val ops = ctx.getSystemService(AppOpsManager::class.java)
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= 29) ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        else ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun midnight(now: Long = System.currentTimeMillis()): Long = Calendar.getInstance().apply {
        timeInMillis = now; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun today(ctx: Context, now: Long = System.currentTimeMillis()): Today {
        val um = ctx.getSystemService(UsageStatsManager::class.java) ?: return Today(emptyMap(), null)
        val from = midnight(now)
        val ev = um.queryEvents(from, now) ?: return Today(emptyMap(), null)
        val total = HashMap<String, Long>()
        val open = HashMap<String, Long>()          // package -> resumed at
        var last: String? = null
        val e = UsageEvents.Event()
        fun close(pkg: String, at: Long) { open.remove(pkg)?.let { total[pkg] = (total[pkg] ?: 0) + (at - it).coerceAtLeast(0) } }
        fun closeAll(at: Long) { open.keys.toList().forEach { close(it, at) } }
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e)
            val t = e.timeStamp
            @Suppress("DEPRECATION")
            when (e.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND -> {           // == ACTIVITY_RESUMED
                    // one app in front at a time on a phone: a new one in front ends the others
                    open.keys.filter { it != e.packageName }.forEach { close(it, t) }
                    if (e.packageName !in open) open[e.packageName] = t
                    last = e.packageName
                }
                UsageEvents.Event.MOVE_TO_BACKGROUND -> { close(e.packageName, t); if (last == e.packageName) last = null }
                SCREEN_NON_INTERACTIVE, KEYGUARD_SHOWN, DEVICE_SHUTDOWN -> { closeAll(t); last = null }
            }
        }
        closeAll(now)
        // an app still open stays "current" (screen on)
        return Today(total, last)
    }

    private const val SCREEN_NON_INTERACTIVE = 16
    private const val KEYGUARD_SHOWN = 17
    private const val DEVICE_SHUTDOWN = 26
}
