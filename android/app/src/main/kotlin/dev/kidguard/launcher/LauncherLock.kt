package dev.kidguard

import android.content.Context
import android.util.Log

/**
 * Rule `launcher.on` without Device Owner = "lock the home screen" as far as basic mode can:
 *  - Acc closes the Settings "Home app" page / the HOME chooser and bounces back from another launcher ([on], Acc.kt);
 *  - with self-adb (optional) `cmd package set-home-activity` makes KidGuard the default again whenever HOME changed (no flash).
 * Bypassable (Safe Mode, accessibility turned off): docs/support-matrix.md. [off] undoes the preferred-activity entry we wrote.
 */
object LauncherLock {
    private const val TAG = "KidGuard"
    @Volatile var on = false; private set
    @Volatile private var last = 0L
    private val busy = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun prefs(ctx: Context) = ctx.createDeviceProtectedStorageContext().getSharedPreferences("kg_lock", Context.MODE_PRIVATE)

    /** Guard tick (cheap: one HOME resolve). */
    fun tick(ctx: Context, r: Rules, now: Long) {
        val want = !Policy.isOwner(ctx) && !Flavor.HOME && r.launcher.on && LauncherSafe.wanted(ctx, r)
        if (!want) { if (on || prefs(ctx).getBoolean("set", false)) off(ctx); return }
        on = true
        if (!LauncherLockLogic.adbDue(true, LauncherSafe.homeIsOurs(ctx), SelfAdb.ready(ctx), last, now)) return
        if (!busy.compareAndSet(false, true)) return
        last = now
        Thread {
            try {
                val c = LauncherSafe.comp(ctx).flattenToShortString()
                val out = SelfAdb.run(ctx, "cmd package set-home-activity $c")
                Log.i(TAG, "home lock: set-home-activity -> $out")
                if (out != null) prefs(ctx).edit().putBoolean("set", true).apply()
            } catch (e: Exception) { Log.w(TAG, "home lock", e) } finally { busy.set(false) }
        }.apply { isDaemon = true }.start()
    }

    /** Rule off, safe mode, release, unpair: forget the lock and clear the HOME choice we wrote (the phone asks / uses the stock launcher). */
    fun off(ctx: Context) {
        on = false
        val p = prefs(ctx)
        if (!p.getBoolean("set", false)) return
        @Suppress("DEPRECATION")
        runCatching { ctx.packageManager.clearPackagePreferredActivities(ctx.packageName) }.onFailure { Log.w(TAG, "home lock clear", it) }
        p.edit().remove("set").apply()
    }
}
