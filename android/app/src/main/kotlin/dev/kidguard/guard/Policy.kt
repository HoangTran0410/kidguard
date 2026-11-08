package dev.kidguard

import android.Manifest
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.UserManager
import android.provider.Settings
import android.telecom.TelecomManager
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

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
        if (!isOwner(ctx)) { runCatching { LauncherSafe.apply(ctx, r) }; return }   // non-owner: only the user's own HOME choice (local opt-in / parent rule)
        val d = dpm(ctx); val a = admin(ctx)
        runCatching {
            BASE.forEach { d.addUserRestriction(a, it) }
            // a rules save during a self-update session must not put "no installs" back under it (Update.installing)
            INSTALL.forEach { if (r.noInstall && !Update.installing) d.addUserRestriction(a, it) else d.clearUserRestriction(a, it) }
            if (r.noDebug) d.addUserRestriction(a, UserManager.DISALLOW_DEBUGGING_FEATURES)
            else d.clearUserRestriction(a, UserManager.DISALLOW_DEBUGGING_FEATURES)
        }.onFailure { Log.w(TAG, "restrictions", it) }
        runCatching { d.setUninstallBlocked(a, ctx.packageName, true) }.onFailure { Log.w(TAG, "uninstall block", it) }
        if (Build.VERSION.SDK_INT >= 30) {
            runCatching { d.setUserControlDisabledPackages(a, listOf(ctx.packageName)) }   // no Force stop / Clear data
            runCatching { d.setAutoTimeEnabled(a, true) }
        } else {
            // no setUserControlDisabledPackages before 11: block Force stop / Clear data for every app (undone in release)
            restrict(d, a, UserManager.DISALLOW_APPS_CONTROL, true)
            @Suppress("DEPRECATION") runCatching { d.setAutoTimeRequired(a, true) }
        }
        applyNotes(ctx, d, a, r)
        applyNet(ctx, d, a, r)
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 29) perms += Manifest.permission.ACCESS_BACKGROUND_LOCATION
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        if (Build.VERSION.SDK_INT < 29) perms += Manifest.permission.WRITE_EXTERNAL_STORAGE   // parent's files -> Download/KidGuard (Files.kt); 29+ uses MediaStore
        perms += Manifest.permission.RECORD_AUDIO   // "listen around"; the child is always told when it is used (Listen.kt)
        for (p in perms) runCatching {
            d.setPermissionGrantState(a, ctx.packageName, p, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
        }.onFailure { Log.w(TAG, "grant $p", it) }
        runCatching { LauncherSafe.apply(ctx, r) }.onFailure { Log.w(TAG, "launcher", it) }   // v0.9.9 kid launcher (HOME)
    }

    /** v0.9.7: friendly support messages (+ the parent's note) and the lock-screen owner info ("" clears it). */
    private fun applyNotes(ctx: Context, d: DevicePolicyManager, a: ComponentName, r: Rules) {
        val note = Notes.clean(r.blockNote, Notes.NOTE_MAX)
        runCatching { d.setShortSupportMessage(a, Notes.short(note, ctx.getString(R.string.policy_support))) }.onFailure { Log.w(TAG, "short support", it) }
        runCatching { d.setLongSupportMessage(a, Notes.long(ctx.getString(R.string.policy_support_long), note.takeIf { it.isNotEmpty() }?.let { ctx.getString(R.string.policy_note, it) })) }
            .onFailure { Log.w(TAG, "long support", it) }
        val info = Notes.lockText(r.lockMsg, r.lockContact)
        runCatching { d.setDeviceOwnerLockScreenInfo(a, info.ifEmpty { null }) }.onFailure { Log.w(TAG, "lock screen info", it) }
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
        applyDns(ctx, d, a, r)
    }

    /** "" while Private DNS is fine, else a short reason (the host was rejected; then the phone runs opportunistic DNS). */
    @Volatile var dnsError: String = ""
    private var dnsTriedAt = 0L

    private fun applyDns(ctx: Context, d: DevicePolicyManager, a: ComponentName, r: Rules) {
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
            dnsError = ctx.getString(R.string.dns_refused, r.safeDns, code)
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
            if (now - dnsTriedAt >= retryAfter) { unvalidatedSince = 0; applyDns(ctx, dpm(ctx), admin(ctx), r) }
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
        runCatching { d.setGlobalPrivateDnsModeOpportunistic(a) }
        dnsError = ctx.getString(R.string.dns_fallback)
        dnsTriedAt = now; retryAfter = 30 * 60_000L; unvalidatedSince = 0
        runCatching { Store.of(ctx).log(R.string.log_dns_fallback) }
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

    private fun isSystem(pm: PackageManager, pkg: String): Boolean = runCatching {
        pm.getApplicationInfo(pkg, 0).flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
    }.getOrDefault(true)

    /**
     * Apps we count and limit, package -> label: every app with a launcher icon, plus every installed non-system app
     * that has any activity (an app can hide its icon and still be opened by a shortcut or another app).
     * Never system apps without an icon, and never what [protectedApps] lists (the caller filters those).
     */
    fun launchable(ctx: Context): Map<String, String> {
        val pm = ctx.packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val list = pm.queryIntentActivities(i, PackageManager.MATCH_DISABLED_COMPONENTS)
        val out = LinkedHashMap(list.associate { it.activityInfo.packageName to it.loadLabel(pm).toString() })
        runCatching {
            @Suppress("DEPRECATION")
            for (ai in pm.getInstalledApplications(0)) {
                val pkg = ai.packageName
                if (pkg in out || !ai.enabled || ai.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) continue
                val hasActivity = runCatching { pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES).activities?.isNotEmpty() == true }.getOrDefault(false)
                if (hasActivity) out[pkg] = ai.loadLabel(pm).toString()
            }
        }
        return out.filterKeys { it != ctx.packageName && it != TsLogic.PKG }
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
        // home / dialer / alarm handlers: only system apps, or the one the user really picked as default
        // (an app that merely declares a HOME / DIAL filter must not escape the limits)
        fun sys(intent: Intent) = runCatching {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0).forEach { if (isSystem(pm, it.activityInfo.packageName)) out += it.activityInfo.packageName }
        }
        fun default(intent: Intent) = runCatching {
            pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
                ?.takeIf { it != "android" && !it.contains("resolver") }?.let { out += it }
        }
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        sys(home); default(home)
        sys(Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS))
        val dial = Intent(Intent.ACTION_DIAL); sys(dial); default(dial)
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

    /** Never hidden / cleared here: Debloat's static set plus [protectedApps] (launcher, keyboards, dialer, self…). */
    fun neverHide(ctx: Context, prot: Set<String> = protectedApps(ctx)): Set<String> = prot + ctx.packageName + TsLogic.PKG   // remote access

    /**
     * Hides exactly [want] among the apps we hid (setApplicationHidden). Owner only; never touches the never-hide set.
     * Returns what is hidden by us now ([had] changed by what worked).
     */
    fun hideExactly(ctx: Context, want: Set<String>, had: Set<String>, never: Set<String> = neverHide(ctx), failed: MutableSet<String>? = null): Set<String> {
        if (!isOwner(ctx)) return hideViaShell(ctx, want, had, never, failed)
        val d = dpm(ctx); val a = admin(ctx)
        val df = Debloat.diff(want, had, never)
        var now = had
        for (p in df.show) if (runCatching { d.setApplicationHidden(a, p, false) }.getOrElse { Log.w(TAG, "unhide $p", it); false } || !installed(ctx, p)) now = now - p
        for (p in df.hide) if (runCatching { d.setApplicationHidden(a, p, true) }.getOrElse { Log.w(TAG, "hide $p", it); false }) now = now + p else failed?.add(p)   // false = the OS refused
        return now
    }

    /**
     * No Device Owner: the same through the phone's own adb shell ([SelfAdb]): `pm disable-user` / `pm enable`. [had] = apps WE disabled
     * (Store.shellDisabled); we only ever re-enable those. At most [Debloat.SHELL_BATCH] packages per call; blocks, so off the main thread.
     * No shell mid-way (Wireless debugging went off) stops the pass without marking anything as refused.
     */
    private fun hideViaShell(ctx: Context, want: Set<String>, had: Set<String>, never: Set<String>, failed: MutableSet<String>?): Set<String> {
        if (!SelfAdb.ready(ctx)) return had
        val df = Debloat.shellBatch(Debloat.diff(want, had, never))
        var now = had
        for (p in df.show) {
            if (!installed(ctx, p)) { now = now - p; continue }
            val out = SelfAdb.run(ctx, Debloat.enableCmd(p) ?: continue) ?: break
            if (Debloat.enableOk(out)) now = now - p else Log.w(TAG, "shell enable $p: $out")
        }
        for (p in df.hide) {
            val out = SelfAdb.run(ctx, Debloat.disableCmd(p) ?: continue) ?: break
            if (Debloat.disableOk(out)) now = now + p else { Log.w(TAG, "shell disable $p: $out"); failed?.add(p) }
        }
        return now
    }

    /** Disabled for the user (by us or anyone): what `pm disable-user` sets. */
    private fun disabledByUser(ctx: Context, pkg: String): Boolean = runCatching {
        ctx.packageManager.getApplicationEnabledSetting(pkg) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
    }.getOrDefault(false)

    /**
     * Release: re-enables what we disabled through the shell. Keeps only what is still disabled in Store.shellDisabled and returns
     * how many (0 = done). Without a shell they stay listed and release reports it.
     */
    private fun enableShellDisabled(ctx: Context, s: Store): Int {
        val ours = s.shellDisabled
        if (ours.isEmpty()) return 0
        var left = Debloat.toEnableOnRelease(ours) { disabledByUser(ctx, it) }
        if (left.isNotEmpty() && SelfAdb.ready(ctx)) for (p in left) {
            val out = SelfAdb.run(ctx, Debloat.enableCmd(p) ?: continue) ?: break
            if (Debloat.enableOk(out)) left = left - p
        }
        s.shellDisabled = left
        return left.size
    }

    private val retrying = java.util.concurrent.atomic.AtomicBoolean(false)
    /** After release: apps we could not re-enable (no Wireless debugging then) are retried at every start while the shell works. */
    fun retryShellEnable(ctx: Context) {
        val s = Store.of(ctx)
        if (s.shellDisabled.isEmpty() || !SelfAdb.hasKey(ctx) || !retrying.compareAndSet(false, true)) return
        Thread({ try { runCatching { enableShellDisabled(ctx, s) } } finally { retrying.set(false) } }, "shellenable").apply { isDaemon = true }.start()
    }

    /** True when [pkg] is present, hidden apps included. */
    fun installed(ctx: Context, pkg: String): Boolean = runCatching {
        @Suppress("DEPRECATION") ctx.packageManager.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES); true
    }.getOrDefault(false)

    private fun isHidden(ctx: Context, pkg: String): Boolean =
        if (isOwner(ctx)) runCatching { dpm(ctx).isApplicationHidden(admin(ctx), pkg) }.getOrDefault(false) else disabledByUser(ctx, pkg)

    /** Un-hides everything we hid plus anything still hidden that the rules list. Part of release. */
    private fun unhideAll(ctx: Context, s: Store, d: DevicePolicyManager, a: ComponentName) {
        val rulesHidden = s.rules.hidden
        val now = rulesHidden.filter { isHidden(ctx, it) }.toSet()
        for (p in Debloat.toUnhideOnRelease(s.hiddenByUs, rulesHidden, now)) runCatching { d.setApplicationHidden(a, p, false) }.onFailure { Log.w(TAG, "unhide $p", it) }
    }

    /**
     * Clears an app's data (DevicePolicyManager.clearApplicationUserData, Android 9+). "ok" | "not owner" | "unsupported" | "protected" | "failed".
     * Blocks up to 5 s for the system's answer, so call it off the main thread.
     */
    fun clearData(ctx: Context, pkg: String): String {
        if (!isOwner(ctx)) {   // no Device Owner: `pm clear` through the self-adb shell
            val cmd = Debloat.clearCmd(pkg)
            if (cmd == null || !Debloat.canClear(pkg, ctx.packageName, neverHide(ctx))) return "protected"
            val out = SelfAdb.run(ctx, cmd) ?: return "not owner"
            return if (Debloat.clearOk(out)) "ok" else "failed"
        }
        if (Build.VERSION.SDK_INT < 28) return "unsupported"
        if (!Debloat.validPkg(pkg) || !Debloat.canClear(pkg, ctx.packageName, neverHide(ctx))) return "protected"
        val latch = java.util.concurrent.CountDownLatch(1)
        var ok = false
        val started = runCatching {
            dpm(ctx).clearApplicationUserData(admin(ctx), pkg, { it.run() }) { _, success -> ok = success; latch.countDown() }
        }.onFailure { Log.w(TAG, "clearData $pkg", it) }.isSuccess
        if (!started) return "failed"
        return if (latch.await(5, java.util.concurrent.TimeUnit.SECONDS) && ok) "ok" else "failed"
    }

    /** Label of any installed package, hidden ones included; the package name when unknown. */
    fun labelOf(ctx: Context, pkg: String): String = runCatching {
        val pm = ctx.packageManager
        @Suppress("DEPRECATION") pm.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES).loadLabel(pm).toString()
    }.getOrDefault(pkg)

    /**
     * Rows for state.preinstalled: system apps with a launcher icon, curated suggestions that are installed, and anything hidden.
     * Never lists the never-hide set. Sorted suggest first, then label.
     */
    fun preinstalled(ctx: Context, s: Store, never: Set<String>, mb: (String) -> Int?): JSONArray {
        if (!isOwner(ctx) && !SelfAdb.ready(ctx)) return JSONArray()
        val pm = ctx.packageManager
        val flags = PackageManager.MATCH_UNINSTALLED_PACKAGES or PackageManager.MATCH_DISABLED_COMPONENTS
        @Suppress("DEPRECATION")
        val withIcon = runCatching {
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), flags).map { it.activityInfo.packageName }.toSet()
        }.getOrDefault(emptySet())
        val ours = s.hiddenByUs + s.shellDisabled + s.rules.hidden
        val refused = s.refusedHide
        data class C(val ai: ApplicationInfo, val hidden: Boolean, val row: Debloat.Row)
        val out = mutableListOf<C>()
        @Suppress("DEPRECATION")
        for (ai in pm.getInstalledApplications(PackageManager.MATCH_UNINSTALLED_PACKAGES)) {
            val p = ai.packageName
            if (!Debloat.canHide(p, never)) continue
            val sys = ai.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            val sug = Debloat.suggested(sys, p)
            if (!Debloat.listed(sys, p in withIcon, p, p in ours)) continue
            val hidden = isHidden(ctx, p)
            val label = runCatching { ai.loadLabel(pm).toString() }.getOrDefault(p).take(40)
            out += C(ai, hidden, Debloat.Row(p, label, sug))
        }
        val order = Debloat.sortRows(out.map { it.row }).map { it.pkg }
        val by = out.associateBy { it.row.pkg }
        return JSONArray().also { arr ->
            order.forEach { p ->
                val c = by.getValue(p)
                arr.put(JSONObject().put("pkg", p).put("label", c.row.label).put("hidden", c.hidden).put("suggest", c.row.suggest)
                    .put("mb", mb(p) ?: JSONObject.NULL).also { o -> if (p in refused) o.put("refused", true) })
            }
        }
    }

    /** app + data + cache in MB, or null (no usage access, hidden app, old Android…). */
    fun sizeMb(ctx: Context, pkg: String): Int? = runCatching {
        val ss = ctx.getSystemService(android.app.usage.StorageStatsManager::class.java)
        val st = ss.queryStatsForPackage(android.os.storage.StorageManager.UUID_DEFAULT, pkg, android.os.Process.myUserHandle())
        ((st.appBytes + st.dataBytes + st.cacheBytes) / 1_048_576L).toInt()
    }.getOrNull()

    fun lockNow(ctx: Context) = runCatching { dpm(ctx).lockNow() }.isSuccess

    /**
     * Undo everything and stop being Device Owner. After this the app can be uninstalled like any other.
     * Call it on the Guard thread with the loop stopped. Returns "ok", "not owner" or "failed" (still Device Owner).
     */
    fun release(ctx: Context, s: Store): String {
        if (!isOwner(ctx)) {
            val off = enableShellDisabled(ctx, s)
            val res = releaseBasic(ctx, s)   // always: the parent must be able to let the phone go
            if (off > 0 && res == "ok") s.log(ctx.resources.getQuantityString(R.plurals.release_shell_left, off, off))   // kept in Store.shellDisabled, see [retryShellEnable]
            return res
        }
        val d = dpm(ctx); val a = admin(ctx)
        runCatching { Ts.undo(ctx, s) }.onFailure { Log.w(TAG, "remote access undo", it) }
        // every launcher app, not just what we remember: the stored set may be stale
        val all = (launchable(ctx).keys + s.suspendedByUs).toTypedArray()
        runCatching { d.setPackagesSuspended(a, all, false) }.onFailure { Log.w(TAG, "unsuspend all", it) }
        s.suspendedByUs.forEach { runCatching { d.setPackagesSuspended(a, arrayOf(it), false) } }
        runCatching { unhideAll(ctx, s, d, a) }.onFailure { Log.w(TAG, "unhide all", it) }
        // setApplicationHidden(false) can refuse without throwing: once Device Owner is gone nobody can un-hide, so stop here
        val stuck = (s.hiddenByUs + s.rules.hidden).filter { isHidden(ctx, it) }
        val shellLeft = enableShellDisabled(ctx, s)   // apps disabled before this phone became Device Owner
        if (stuck.isNotEmpty()) {
            Log.w(TAG, "release: still hidden $stuck")
            runCatching { apply(ctx, s.rules) }; Watchdog.schedule(ctx)
            return ctx.resources.getQuantityString(R.plurals.release_stuck, stuck.size, stuck.size)
        }
        Update.forget(ctx)
        (BASE + INSTALL + UserManager.DISALLOW_DEBUGGING_FEATURES + UserManager.DISALLOW_APPS_CONTROL).forEach { runCatching { d.clearUserRestriction(a, it) } }
        runCatching { d.setUninstallBlocked(a, ctx.packageName, false) }
        applyNet(ctx, d, a, Rules(lockLocation = false, noAirplane = false, safeDns = ""))
        if (Build.VERSION.SDK_INT >= 30) runCatching { d.setUserControlDisabledPackages(a, emptyList()) }
        else @Suppress("DEPRECATION") runCatching { d.setAutoTimeRequired(a, false) }
        runCatching { d.setShortSupportMessage(a, null) }
        runCatching { d.setLongSupportMessage(a, null) }
        runCatching { d.setDeviceOwnerLockScreenInfo(a, null) }
        removeAccEntry(ctx)
        // kid launcher: our persistent HOME goes and the component is disabled before Device Owner does (else HOME stays ours)
        runCatching { LauncherSafe.off(ctx, goHome = true) }.onFailure { Log.w(TAG, "launcher off", it) }
        // the mic was granted by us, not by the child: take it back and hand the switch to the user
        // the other grants (location, notifications) stay granted but the user can change them again
        for (p in listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, "android.permission.ACCESS_BACKGROUND_LOCATION",
            "android.permission.POST_NOTIFICATIONS", Manifest.permission.WRITE_EXTERNAL_STORAGE)) runCatching { d.setPermissionGrantState(a, ctx.packageName, p, DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT) }
        runCatching {
            d.setPermissionGrantState(a, ctx.packageName, Manifest.permission.RECORD_AUDIO, DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED)
            d.setPermissionGrantState(a, ctx.packageName, Manifest.permission.RECORD_AUDIO, DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT)
        }.onFailure { Log.w(TAG, "mic revoke", it) }
        s.released = true
        Watchdog.cancel(ctx)
        @Suppress("DEPRECATION") runCatching { d.clearDeviceOwnerApp(ctx.packageName) }
        s.suspendedByUs = emptySet(); s.hiddenByUs = emptySet(); s.refusedHide = emptySet()
        if (!isOwner(ctx)) {
            if (shellLeft > 0) s.log(ctx.resources.getQuantityString(R.plurals.release_shell_left, shellLeft, shellLeft))
            s.clearTokens(); Quick.setEnabled(ctx, false); return "ok"
        }
        // still Device Owner: put the protection back (the loop re-suspends apps) and keep the parents' tokens
        s.released = false
        runCatching { apply(ctx, s.rules) }.onFailure { Log.w(TAG, "re-apply after failed release", it) }
        Watchdog.schedule(ctx)
        return "failed"
    }

    /** Basic mode: stop blocking, drop the device admin (the app can be uninstalled again), forget the parents. "not owner" when there is no admin. */
    private fun releaseBasic(ctx: Context, s: Store): String {
        val d = dpm(ctx); val a = admin(ctx)
        if (!runCatching { d.isAdminActive(a) }.getOrDefault(false)) return "not owner"
        s.released = true          // Acc and Guard go quiet first (Basic.enforcing checks it)
        Acc.blocked = emptyMap()
        Watchdog.cancel(ctx)
        runCatching { d.removeActiveAdmin(a) }.onFailure { Log.w(TAG, "removeActiveAdmin", it) }
        // removeActiveAdmin returns before the system has finished: wait up to 3 s for it
        var left = 15
        while (left-- > 0 && runCatching { d.isAdminActive(a) }.getOrDefault(false)) Thread.sleep(200)
        if (runCatching { d.isAdminActive(a) }.getOrDefault(false)) {
            s.released = false; Watchdog.schedule(ctx); return "failed"
        }
        s.suspendedByUs = emptySet()
        removeAccEntry(ctx)
        runCatching { LauncherSafe.off(ctx, goHome = true) }   // only owner mode can turn it on, but never leave it behind
        runCatching { Acc.running?.disableSelf() }
        s.clearTokens()
        Quick.setEnabled(ctx, false)   // lock tile / widget / shortcuts: nothing left to lock with
        return "ok"
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
