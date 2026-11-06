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
    override fun onEnabled(context: Context, intent: Intent) { Store.of(context).released = false; Guard.start(context) }

    /** After QR provisioning: start the guard and open the app so the "Bước còn lại" checklist shows. */
    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        Store.of(context).released = false
        Guard.start(context)
        runCatching { context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
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
        perms += Manifest.permission.RECORD_AUDIO   // "listen around"; the child is always told when it is used (Listen.kt)
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
        applyDns(d, a, r)
    }

    /** "" while Private DNS is fine, else a short reason (the host was rejected; then the phone runs opportunistic DNS). */
    @Volatile var dnsError: String = ""
    private var dnsTriedAt = 0L

    private fun applyDns(d: DevicePolicyManager, a: ComponentName, r: Rules) {
        if (Build.VERSION.SDK_INT < 29) return
        dnsTriedAt = System.currentTimeMillis()
        if (r.safeDns.isEmpty()) {
            restrict(d, a, UserManager.DISALLOW_CONFIG_PRIVATE_DNS, false)
            runCatching { d.setGlobalPrivateDnsModeOpportunistic(a) }.onFailure { Log.w(TAG, "private dns off", it) }
            dnsError = ""
            return
        }
        val code = runCatching { d.setGlobalPrivateDnsModeSpecifiedHost(a, r.safeDns) }.getOrElse { Log.w(TAG, "private dns", it); -1 }
        if (code == DevicePolicyManager.PRIVATE_DNS_SET_NO_ERROR) {
            restrict(d, a, UserManager.DISALLOW_CONFIG_PRIVATE_DNS, true)
            dnsError = ""
        } else {
            // keep the child online: opportunistic DNS and let the system settings change it
            restrict(d, a, UserManager.DISALLOW_CONFIG_PRIVATE_DNS, false)
            runCatching { d.setGlobalPrivateDnsModeOpportunistic(a) }
            dnsError = "DNS ${r.safeDns} bị từ chối (mã $code)"
        }
    }

    private var unvalidatedSince = 0L
    private var retryAfter = 60_000L

    /**
     * Called every tick. (1) Tries a rejected / fallen-back Private DNS host again later. (2) Keeps the child online:
     * if strict Private DNS is on but the current network has not validated for 90 s (e.g. a Wi-Fi that blocks
     * DNS-over-TLS port 853, which leaves the phone with no DNS at all), falls back to opportunistic DNS for 30 min.
     */
    fun retryDns(ctx: Context, r: Rules) {
        if (Build.VERSION.SDK_INT < 29 || r.safeDns.isEmpty() || !isOwner(ctx)) { unvalidatedSince = 0; return }
        val now = System.currentTimeMillis()
        if (dnsError.isNotEmpty()) {
            if (now - dnsTriedAt >= retryAfter) { unvalidatedSince = 0; applyDns(dpm(ctx), admin(ctx), r) }
            return
        }
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java)
        val caps = runCatching { cm.getNetworkCapabilities(cm.activeNetwork) }.getOrNull()
        val online = caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val validated = caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        if (!online || validated) { unvalidatedSince = 0; retryAfter = 60_000L; return }
        if (unvalidatedSince == 0L) { unvalidatedSince = now; return }
        if (now - unvalidatedSince < 90_000) return
        val d = dpm(ctx); val a = admin(ctx)
        restrict(d, a, UserManager.DISALLOW_CONFIG_PRIVATE_DNS, false)
        runCatching { d.setGlobalPrivateDnsModeOpportunistic(a) }
        dnsError = "Mạng hiện tại không dùng được DNS an toàn, tạm tắt 30 phút để con không mất mạng"
        dnsTriedAt = now; retryAfter = 30 * 60_000L; unvalidatedSince = 0
        runCatching { Store.of(ctx).log("DNS an toàn làm mất mạng, tạm tắt 30 phút") }
    }

    private var cacheAt = 0L
    private var cacheApps: Map<String, String> = emptyMap()
    private var cacheProt: Set<String> = emptySet()
    /** The package list changed (install / uninstall): list again on the next call. */
    fun invalidate() { cacheAt = 0 }

    /** [launchable] and [protectedApps] kept for 60 s (they ask the package manager, which is not free every 10 s). */
    @Synchronized fun cached(ctx: Context): Pair<Map<String, String>, Set<String>> {
        val now = System.currentTimeMillis()
        if (now - cacheAt > 60_000 || cacheApps.isEmpty()) {
            cacheApps = launchable(ctx); cacheProt = protectedApps(ctx); cacheAt = now
        }
        return cacheApps to cacheProt
    }

    /** Apps with a launcher icon, package -> label. */
    fun launchable(ctx: Context): Map<String, String> {
        val pm = ctx.packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val list = pm.queryIntentActivities(i, PackageManager.MATCH_DISABLED_COMPONENTS)
        return list.associate { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filterKeys { it != ctx.packageName && it != TsLogic.PKG }
    }

    /** Never suspended: this app, the home screens, phone, settings, the system UI. */
    fun protectedApps(ctx: Context): Set<String> {
        val pm = ctx.packageManager
        val out = mutableSetOf(ctx.packageName, "com.android.systemui", "com.android.settings", "com.android.phone",
            "com.android.emergency", "com.google.android.dialer", "com.android.dialer", "com.samsung.android.dialer", "com.samsung.android.incallui",
            "com.android.server.telecom", "com.android.contacts", "com.google.android.contacts",
            // the alarm clock must always ring and be openable
            "com.google.android.deskclock", "com.android.deskclock", "com.sec.android.app.clockpackage",
            // the system's own plumbing, which some phones give a launcher icon: suspending it breaks the phone
            "com.google.android.gms", "com.google.android.gsf", "com.android.stk", "com.android.cellbroadcastreceiver",
            "com.google.android.cellbroadcastreceiver", "com.google.android.apps.safetyhub")
        // keyboards: a suspended keyboard leaves no way to type (not even the parent's PIN)
        runCatching {
            ctx.getSystemService(android.view.inputmethod.InputMethodManager::class.java)?.enabledInputMethodList?.forEach { out += it.packageName }
        }
        @Suppress("DEPRECATION")
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0).forEach { out += it.activityInfo.packageName }
        @Suppress("DEPRECATION")
        runCatching { pm.queryIntentActivities(Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS), 0).forEach { out += it.activityInfo.packageName } }
        @Suppress("DEPRECATION")
        runCatching { pm.queryIntentActivities(Intent(Intent.ACTION_DIAL), 0).forEach { out += it.activityInfo.packageName } }
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

    /**
     * Undo everything and stop being Device Owner. After this the app can be uninstalled like any other.
     * Call it on the Guard thread with the loop stopped. Returns "ok", "not owner" or "failed" (still Device Owner).
     */
    fun release(ctx: Context, s: Store): String {
        if (!isOwner(ctx)) return "not owner"
        val d = dpm(ctx); val a = admin(ctx)
        runCatching { Ts.undo(ctx, s) }.onFailure { Log.w(TAG, "remote access undo", it) }
        // every launcher app, not just what we remember: the stored set may be stale
        val all = (launchable(ctx).keys + s.suspendedByUs).toTypedArray()
        runCatching { d.setPackagesSuspended(a, all, false) }.onFailure { Log.w(TAG, "unsuspend all", it) }
        s.suspendedByUs.forEach { runCatching { d.setPackagesSuspended(a, arrayOf(it), false) } }
        (BASE + INSTALL + UserManager.DISALLOW_DEBUGGING_FEATURES).forEach { runCatching { d.clearUserRestriction(a, it) } }
        runCatching { d.setUninstallBlocked(a, ctx.packageName, false) }
        applyNet(ctx, d, a, Rules(lockLocation = false, noAirplane = false, safeDns = ""))
        if (Build.VERSION.SDK_INT >= 30) runCatching { d.setUserControlDisabledPackages(a, emptyList()) }
        else @Suppress("DEPRECATION") runCatching { d.setAutoTimeRequired(a, false) }
        runCatching { d.setShortSupportMessage(a, null) }
        removeAccEntry(ctx)
        // the mic was granted by us, not by the child: take it back and hand the switch to the user
        runCatching {
            d.setPermissionGrantState(a, ctx.packageName, Manifest.permission.RECORD_AUDIO, DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED)
            d.setPermissionGrantState(a, ctx.packageName, Manifest.permission.RECORD_AUDIO, DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT)
        }.onFailure { Log.w(TAG, "mic revoke", it) }
        s.released = true
        Watchdog.cancel(ctx)
        @Suppress("DEPRECATION") runCatching { d.clearDeviceOwnerApp(ctx.packageName) }
        s.suspendedByUs = emptySet()
        if (!isOwner(ctx)) { s.clearTokens(); return "ok" }
        // still Device Owner: put the protection back (the loop re-suspends apps) and keep the parents' tokens
        s.released = false
        runCatching { apply(ctx, s.rules) }.onFailure { Log.w(TAG, "re-apply after failed release", it) }
        Watchdog.schedule(ctx)
        return "failed"
    }

    /** Takes our accessibility service out of enabled_accessibility_services (needs WRITE_SECURE_SETTINGS). */
    private fun removeAccEntry(ctx: Context) = runCatching {
        val cr = ctx.contentResolver
        val me = "${ctx.packageName}/${Acc::class.java.name}"
        val cur = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        val left = cur.split(':').filter { it.isNotEmpty() && !it.equals(me, true) && !it.equals("${ctx.packageName}/.Acc", true) }
        Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, left.joinToString(":"))
        if (left.isEmpty()) Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
    }.onFailure { Log.w(TAG, "acc entry", it) }
}
