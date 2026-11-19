package dev.kidguard

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * The system screens KidGuard sends people to, in one place (maintainability review 2026-11-17: the cap -> screen map was
 * written 3x and had drifted: FixPerms had no plain usage-access fallback for ROMs without the per-app page).
 */
object Intents {
    fun pkgUri(pkg: String): Uri = Uri.parse("package:$pkg")

    /** App info of [pkg] (KidGuard's own by default). */
    fun appDetails(ctx: Context, pkg: String = ctx.packageName): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri(pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Starts the first of [i] that the phone opens. False when none did. */
    fun startFirst(ctx: Context, vararg i: Intent): Boolean {
        // ADD_DEVICE_ADMIN finishes at once when started as a new task ("Cannot start ADD_DEVICE_ADMIN as a new task":
        // 2026-11-19 owner, Xiaomi: step 1 slid in and straight back): from an Activity it goes into our own task
        for (x in i) {
            val nt = newTask(ctx is android.app.Activity, x.action)
            if (runCatching { ctx.startActivity(if (nt) x.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) else x); true }.getOrDefault(false)) return true
        }
        return false
    }

    /** Whether to add NEW_TASK: always, except ADD_DEVICE_ADMIN from an Activity (it refuses a new task). Pure (tested). */
    fun newTask(fromActivity: Boolean, action: String?) = !(fromActivity && action == DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)

    /** The first of [i] that resolves (for a PendingIntent, which can't try the next one), else the last. */
    fun firstUsable(ctx: Context, i: List<Intent>): Intent? =
        i.firstOrNull { runCatching { ctx.packageManager.resolveActivity(it, 0) != null }.getOrDefault(false) } ?: i.lastOrNull()

    /** Settings actions for permission [cap], best first, with whether each carries our `package:` uri. Pure (tested). */
    fun capActions(cap: String): List<Pair<String, Boolean>> = when (cap) {
        "admin" -> listOf(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN to false)
        "acc" -> listOf(Settings.ACTION_ACCESSIBILITY_SETTINGS to false)
        "usage" -> listOf(Settings.ACTION_USAGE_ACCESS_SETTINGS to true, Settings.ACTION_USAGE_ACCESS_SETTINGS to false)
        "listener" -> listOf(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS to false)
        "battery" -> listOf(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS to true, Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS to false)
        "home" -> listOf(Settings.ACTION_HOME_SETTINGS to false, Settings.ACTION_SETTINGS to false)
        "install" -> listOf(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES to true, Settings.ACTION_APPLICATION_DETAILS_SETTINGS to true)
        else -> listOf(Settings.ACTION_APPLICATION_DETAILS_SETTINGS to true)   // location, notify, mic: the app's Permissions
    }

    /** The screens for [cap] (see [capActions]), admin with its explanation. */
    fun forCap(ctx: Context, cap: String): List<Intent> = capActions(cap).map { (action, withPkg) ->
        (if (withPkg) Intent(action, pkgUri(ctx.packageName)) else Intent(action)).also {
            if (cap == "admin") it.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, Policy.admin(ctx))
                .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, ctx.getString(R.string.basic_admin_expl))
        }
    }
}
