package dev.kidguard

import android.Manifest
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.UserManager
import android.provider.Settings
import android.telecom.TelecomManager
import android.util.Log

class Admin : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) { Guard.start(context) }
}

/** Device Owner powers. Every call is guarded: off Device Owner it does nothing and says so. */
object Policy {
    private const val TAG = "KidGuard"

    fun admin(ctx: Context) = ComponentName(ctx, Admin::class.java)
    fun dpm(ctx: Context): DevicePolicyManager = ctx.getSystemService(DevicePolicyManager::class.java)
    fun isOwner(ctx: Context) = dpm(ctx).isDeviceOwnerApp(ctx.packageName)

    private val BASE = listOf(
        UserManager.DISALLOW_FACTORY_RESET, UserManager.DISALLOW_SAFE_BOOT,
        UserManager.DISALLOW_ADD_USER, UserManager.DISALLOW_CONFIG_DATE_TIME,
    )
    private val INSTALL = listOf(UserManager.DISALLOW_INSTALL_APPS, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)

    /** Restrictions + self-protection + the permissions the guard needs. Safe to call again and again. */
    fun apply(ctx: Context, r: Rules) {
        if (!isOwner(ctx)) return
        val d = dpm(ctx); val a = admin(ctx)
        runCatching {
            BASE.forEach { d.addUserRestriction(a, it) }
            INSTALL.forEach { if (r.noInstall) d.addUserRestriction(a, it) else d.clearUserRestriction(a, it) }
            if (r.noDebug) d.addUserRestriction(a, UserManager.DISALLOW_DEBUGGING_FEATURES)
            else d.clearUserRestriction(a, UserManager.DISALLOW_DEBUGGING_FEATURES)
        }.onFailure { Log.w(TAG, "restrictions", it) }
        runCatching { d.setUninstallBlocked(a, ctx.packageName, true) }.onFailure { Log.w(TAG, "uninstall block", it) }
        if (Build.VERSION.SDK_INT >= 30) {
            runCatching { d.setUserControlDisabledPackages(a, listOf(ctx.packageName)) }   // no Force stop / Clear data
            runCatching { d.setAutoTimeEnabled(a, true) }
        } else @Suppress("DEPRECATION") runCatching { d.setAutoTimeRequired(a, true) }
        runCatching { d.setShortSupportMessage(a, "Bố mẹ đang tạm khoá ứng dụng này.") }
        applyNet(ctx, d, a, r)
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 29) perms += Manifest.permission.ACCESS_BACKGROUND_LOCATION
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        for (p in perms) runCatching {
            d.setPermissionGrantState(a, ctx.packageName, p, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
        }.onFailure { Log.w(TAG, "grant $p", it) }
    }

    private fun restrict(d: DevicePolicyManager, a: ComponentName, key: String, on: Boolean) = runCatching {
        if (on) d.addUserRestriction(a, key) else d.clearUserRestriction(a, key)
    }.onFailure { Log.w(TAG, "restriction $key", it) }

    /** Location, airplane mode and Private DNS (the "safe DNS" filter). Each piece is separate so one failing doesn't stop the rest. */
    private fun applyNet(ctx: Context, d: DevicePolicyManager, a: ComponentName, r: Rules) {
        if (r.lockLocation) {
            if (Build.VERSION.SDK_INT >= 30) runCatching { d.setLocationEnabled(a, true) }.onFailure { Log.w(TAG, "location on", it) }
            restrict(d, a, UserManager.DISALLOW_CONFIG_LOCATION, true)
        } else restrict(d, a, UserManager.DISALLOW_CONFIG_LOCATION, false)
        if (Build.VERSION.SDK_INT >= 28) restrict(d, a, UserManager.DISALLOW_AIRPLANE_MODE, r.noAirplane)
        if (Build.VERSION.SDK_INT >= 29) {
            if (r.safeDns.isNotEmpty()) {
                runCatching { d.setGlobalPrivateDnsModeSpecifiedHost(a, r.safeDns) }.onFailure { Log.w(TAG, "private dns", it) }
                restrict(d, a, UserManager.DISALLOW_CONFIG_PRIVATE_DNS, true)
            } else {
                restrict(d, a, UserManager.DISALLOW_CONFIG_PRIVATE_DNS, false)
                runCatching { d.setGlobalPrivateDnsModeOpportunistic(a) }.onFailure { Log.w(TAG, "private dns off", it) }
            }
        }
    }

    /** Apps with a launcher icon, package -> label. */
    fun launchable(ctx: Context): Map<String, String> {
        val pm = ctx.packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val list = pm.queryIntentActivities(i, PackageManager.MATCH_DISABLED_COMPONENTS)
        return list.associate { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filterKeys { it != ctx.packageName }
    }

    /** Never suspended: this app, the home screens, phone, settings, the system UI. */
    fun protectedApps(ctx: Context): Set<String> {
        val pm = ctx.packageManager
        val out = mutableSetOf(ctx.packageName, "com.android.systemui", "com.android.settings", "com.android.phone",
            "com.android.emergency", "com.google.android.dialer",
            // the system's own plumbing, which some phones give a launcher icon: suspending it breaks the phone
            "com.google.android.gms", "com.google.android.gsf", "com.android.stk", "com.android.cellbroadcastreceiver",
            "com.google.android.cellbroadcastreceiver", "com.google.android.apps.safetyhub")
        // keyboards: a suspended keyboard leaves no way to type (not even the parent's PIN)
        runCatching {
            ctx.getSystemService(android.view.inputmethod.InputMethodManager::class.java)?.enabledInputMethodList?.forEach { out += it.packageName }
        }
        @Suppress("DEPRECATION")
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0).forEach { out += it.activityInfo.packageName }
        runCatching { ctx.getSystemService(TelecomManager::class.java)?.defaultDialerPackage?.let { out += it } }
        runCatching { Settings.Secure.getString(ctx.contentResolver, "sms_default_application")?.let { out += it } }
        return out
    }

    /** Suspends exactly [want] among the apps we suspended. Returns what is suspended by us now. */
    fun suspendExactly(ctx: Context, want: Set<String>, had: Set<String>): Set<String> {
        if (!isOwner(ctx)) return emptySet()
        val d = dpm(ctx); val a = admin(ctx)
        val add = (want - had).toTypedArray()
        val del = (had - want).toTypedArray()
        var now = had
        if (del.isNotEmpty()) {
            val failed = runCatching { d.setPackagesSuspended(a, del, false) }.getOrElse { Log.w(TAG, "unsuspend", it); del }.toSet()
            now = now - (del.toSet() - failed)
        }
        if (add.isNotEmpty()) {
            val failed = runCatching { d.setPackagesSuspended(a, add, true) }.getOrElse { Log.w(TAG, "suspend", it); add }.toSet()
            now = now + (add.toSet() - failed)
        }
        return now
    }

    fun lockNow(ctx: Context) = runCatching { dpm(ctx).lockNow() }.isSuccess

    /** Undo everything and stop being Device Owner. After this the app can be uninstalled like any other. */
    fun release(ctx: Context, s: Store) {
        if (!isOwner(ctx)) return
        val d = dpm(ctx); val a = admin(ctx)
        runCatching { d.setPackagesSuspended(a, s.suspendedByUs.toTypedArray(), false) }
        (BASE + INSTALL + UserManager.DISALLOW_DEBUGGING_FEATURES).forEach { runCatching { d.clearUserRestriction(a, it) } }
        runCatching { d.setUninstallBlocked(a, ctx.packageName, false) }
        applyNet(ctx, d, a, Rules(lockLocation = false, noAirplane = false, safeDns = ""))
        if (Build.VERSION.SDK_INT >= 30) runCatching { d.setUserControlDisabledPackages(a, emptyList()) }
        else @Suppress("DEPRECATION") runCatching { d.setAutoTimeRequired(a, false) }
        @Suppress("DEPRECATION") runCatching { d.clearDeviceOwnerApp(ctx.packageName) }
        s.suspendedByUs = emptySet()
    }
}
