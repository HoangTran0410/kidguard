package dev.kidguard

import android.app.usage.UsageStatsManager
import android.content.Context

/**
 * The drawer's "recently used" row (POCO / Pixel). With usage access (KidGuard has it once set up): the apps last in the
 * foreground over 3 days, opened from anywhere. Without it: the apps last opened from this launcher (a local list of
 * package names, never sent anywhere).
 */
object Recent {
    private const val KEY = "launcherRecent"

    fun note(ctx: Context, pkg: String) {
        val p = ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE)
        val l = (listOf(pkg) + (p.getString(KEY, "") ?: "").split(',').filter { it.isNotEmpty() && it != pkg }).take(12)
        p.edit().putString(KEY, l.joinToString(",")).apply()
    }

    /** Most recent first. A binder call when usage access is on: run it off the main thread. */
    fun load(ctx: Context): List<String> {
        if (Usage.granted(ctx)) runCatching {
            val now = System.currentTimeMillis()
            val um = ctx.getSystemService(UsageStatsManager::class.java)
            return um.queryUsageStats(UsageStatsManager.INTERVAL_BEST, now - 3 * 86_400_000L, now)
                .filter { it.lastTimeUsed > 0 && it.totalTimeInForeground > 0 && it.packageName != ctx.packageName }
                .sortedByDescending { it.lastTimeUsed }.map { it.packageName }.distinct()
        }
        return (ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE).getString(KEY, "") ?: "").split(',').filter { it.isNotEmpty() }
    }
}
