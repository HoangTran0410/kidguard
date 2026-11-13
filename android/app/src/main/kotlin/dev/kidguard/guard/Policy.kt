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
import android.os.Bundle
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

    // Android 14 / 15 profiles that would be a second, unmanaged space (clone app, private space): as string keys, guarded by SDK_INT
    private const val NO_CLONE_PROFILE = "no_add_clone_profile"      // DISALLOW_ADD_CLONE_PROFILE, API 34
    private const val NO_PRIVATE_PROFILE = "no_add_private_profile"  // DISALLOW_ADD_PRIVATE_PROFILE, API 35
    private val BASE: List<String> get() = listOf(
        UserManager.DISALLOW_FACTORY_RESET, UserManager.DISALLOW_SAFE_BOOT,
        UserManager.DISALLOW_ADD_USER, UserManager.DISALLOW_CONFIG_DATE_TIME,
    ) + (if (Build.VERSION.SDK_INT >= 34) listOf(NO_CLONE_PROFILE) else emptyList()) +
        (if (Build.VERSION.SDK_INT >= 35) listOf(NO_PRIVATE_PROFILE) else emptyList())
    private val INSTALL = listOf(UserManager.DISALLOW_INSTALL_APPS, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)

    /** Restrictions + self-protection + the permissions the guard needs. Safe to call again and again. */
    fun apply(ctx: Context, r: Rules) {
        if (!isOwner(ctx)) { runCatching { LauncherSafe.apply(ctx, r) }; return }   // non-owner: only the user's own HOME choice (local opt-in / parent rule)
        val d = dpm(ctx); val a = admin(ctx)
        runCatching { savePrior(ctx, r) }.onFailure { Log.w(TAG, "save prior", it) }
        runCatching {
            BASE.forEach { runCatching { d.addUserRestriction(a, it) } }   // one refusing key must not skip the rest
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
        if (r.pinShot) perms += Manifest.permission.CAMERA   // wrong-PIN photo only (Shot.kt), told on the PIN dialog
        for (p in perms) runCatching {
            d.setPermissionGrantState(a, ctx.packageName, p, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
        }.onFailure { Log.w(TAG, "grant $p", it) }
        runCatching { LauncherSafe.apply(ctx, r) }.onFailure { Log.w(TAG, "launcher", it) }   // v0.9.9 kid launcher (HOME)
    }

    /** The phone-wide reason the support message was last written for (Guard.tick feeds it through [noteNow]). */
    @Volatile private var nowKey = ""
    @Volatile private var nowState = NowWhy("", 0)

    /**
     * A3.1: the short support message ("disabled by admin" dialog) says why the phone is locked and when it opens again.
     * Called every tick; rewrites the DPM text only when the reason / end / "can ask" changed. Device Owner only.
     */
    fun noteNow(ctx: Context, r: Rules, nw: NowWhy) {
        val key = "${nw.why}|${nw.until}|${nw.name}|${r.lostOn}|${Store.of(ctx).everPaired}|${r.blockNote}"
        if (key == nowKey) return
        nowKey = key; nowState = nw
        if (!isOwner(ctx)) return
        runCatching { applyNotes(ctx, dpm(ctx), admin(ctx), r) }
    }

    private fun hhmm(ms: Long) = java.text.SimpleDateFormat("HH:mm", java.util.Locale.ROOT).format(java.util.Date(ms))

    /** The text for the current phone-wide reason; null = nothing special (the default sentence applies). */
    private fun reasonSupport(ctx: Context, r: Rules): String? {
        val nw = nowState
        val ask = SupportMsg.canAsk(nw.why, Store.of(ctx).everPaired)
        fun until() = hhmm(nw.until)
        return when {
            r.lostOn || nw.why == "lost" -> ctx.getString(R.string.support_lost)
            nw.why == "bedtime" && nw.until > 0 -> ctx.getString(if (ask) R.string.support_bed_ask else R.string.support_bed, until())
            nw.why == "schedule" && nw.until > 0 -> ctx.getString(if (ask) R.string.support_sched_ask else R.string.support_sched, nw.name.ifBlank { ctx.getString(R.string.support_break) }, until())
            nw.why == "paused" && nw.until > 0 -> ctx.getString(R.string.support_pause, until())
            nw.why == "daily" -> ctx.getString(if (ask) R.string.support_daily_ask else R.string.support_daily)
            else -> null
        }
    }

    /** v0.9.7: friendly support messages (+ the parent's note) and the lock-screen owner info ("" clears it). Lost mode (0.9.16) swaps both. */
    private fun applyNotes(ctx: Context, d: DevicePolicyManager, a: ComponentName, r: Rules) {
        val note = Notes.clean(r.blockNote, Notes.NOTE_MAX)
        val why = reasonSupport(ctx, r)
        val def = why ?: ctx.getString(R.string.policy_support)
        runCatching { d.setShortSupportMessage(a, if (r.lostOn) def else Notes.short(note, def)) }.onFailure { Log.w(TAG, "short support", it) }
        runCatching { d.setLongSupportMessage(a, Notes.long(ctx.getString(R.string.policy_support_long), note.takeIf { it.isNotEmpty() }?.let { ctx.getString(R.string.policy_note, it) })) }
            .onFailure { Log.w(TAG, "long support", it) }
        val info = if (r.lostOn) Notes.lostText(r.lostMsg.ifBlank { ctx.getString(R.string.lost_default_msg) }, r.lostPhone) else Notes.lockText(r.lockMsg, r.lockContact)
        runCatching { d.setDeviceOwnerLockScreenInfo(a, info.ifEmpty { null }) }.onFailure { Log.w(TAG, "lock screen info", it) }
    }

    private fun restrict(d: DevicePolicyManager, a: ComponentName, key: String, on: Boolean) = runCatching {
        if (on) d.addUserRestriction(a, key) else d.clearUserRestriction(a, key)
    }.onFailure { Log.w(TAG, "restriction $key", it) }

    /**
     * First apply only: remember the phone's own auto-time and Private DNS settings so [release] can put them back.
     * A Private DNS host equal to our current safe DNS is not saved (upgrade of a phone we already manage: it is ours, not the user's).
     */
    private fun savePrior(ctx: Context, r: Rules) {
        val s = Store.of(ctx)
        if (s.priorSaved) return
        val cr = ctx.contentResolver
        s.priorAutoTime = Settings.Global.getInt(cr, Settings.Global.AUTO_TIME, -1)
        val mode = Settings.Global.getString(cr, "private_dns_mode") ?: ""
        val host = Settings.Global.getString(cr, "private_dns_specifier") ?: ""
        if (mode == "hostname" && host.isNotEmpty() && host == r.safeDns) { s.priorDnsMode = ""; s.priorDnsHost = "" }
        else { s.priorDnsMode = mode; s.priorDnsHost = host }
        s.priorSaved = true
    }

    /** Release: back to what the owner had before we first applied (nothing saved: leave today's behaviour). */
    private fun restorePrior(ctx: Context, d: DevicePolicyManager, a: ComponentName) {
        val s = Store.of(ctx)
        if (!s.priorSaved) return
        if (Build.VERSION.SDK_INT >= 30 && s.priorAutoTime >= 0) runCatching { d.setAutoTimeEnabled(a, s.priorAutoTime == 1) }.onFailure { Log.w(TAG, "restore auto time", it) }
        if (Build.VERSION.SDK_INT < 29) return
        when (s.priorDnsMode) {
            "hostname" -> if (s.priorDnsHost.isNotEmpty()) runCatching { d.setGlobalPrivateDnsModeSpecifiedHost(a, s.priorDnsHost) }.onFailure { Log.w(TAG, "restore dns host", it) }
            "off" -> runCatching {   // DPM cannot set "off"; WRITE_SECURE_SETTINGS (granted to us as owner) can. Else it stays opportunistic.
                if (ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED)
                    Settings.Global.putString(ctx.contentResolver, "private_dns_mode", "off")
            }.onFailure { Log.w(TAG, "restore dns off", it) }
        }
    }

    /** Location, airplane mode and Private DNS (the "safe DNS" filter). Each piece is separate so one failing doesn't stop the rest. */
    private fun applyNet(ctx: Context, d: DevicePolicyManager, a: ComponentName, r: Rules) {
        if (r.lockLocation) {
            if (Build.VERSION.SDK_INT >= 30) runCatching { d.setLocationEnabled(a, true) }.onFailure { Log.w(TAG, "location on", it) }
            restrict(d, a, UserManager.DISALLOW_CONFIG_LOCATION, true)
        } else restrict(d, a, UserManager.DISALLOW_CONFIG_LOCATION, false)
        if (Build.VERSION.SDK_INT >= 28) restrict(d, a, UserManager.DISALLOW_AIRPLANE_MODE, r.noAirplane)
        applyDns(ctx, d, a, r)
        applyDoh(d, a, r.dnsHost().isNotEmpty())
        appliedDns = r.dnsHost()
        applyVpnLock(ctx, d, a, r.noVpn)
    }

    /** Chromium browsers that read the `DnsOverHttpsMode` managed policy on Android (Chrome; Edge 147+). Firefox/Brave/Opera: no managed DoH, see decisions. */
    private val DOH_BROWSERS = listOf("com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.chrome.canary", "com.microsoft.emmx")

    /** While safe DNS is on, browsers' own DoH would skip the filter: managed config DnsOverHttpsMode=off. Cleared (empty bundle) when it is off / on release. */
    private fun applyDoh(d: DevicePolicyManager, a: ComponentName, on: Boolean) {
        for (pkg in DOH_BROWSERS) runCatching { d.setApplicationRestrictions(a, pkg, Bundle().also { if (on) it.putString("DnsOverHttpsMode", "off") }) }
            .onFailure { Log.w(TAG, "doh $pkg", it) }
    }

    /**
     * Single owner of DISALLOW_CONFIG_VPN. Set when the rule `noVpn` is on OR our own Tailscale access wants it (Store.tsWanted);
     * cleared only when neither does. Ts.kt must call this instead of touching the restriction.
     */
    fun applyVpnLock(ctx: Context, d: DevicePolicyManager, a: ComponentName, noVpn: Boolean) {
        restrict(d, a, UserManager.DISALLOW_CONFIG_VPN, noVpn || Store.of(ctx).tsWanted)
    }

    /** "" while Private DNS is fine, else a short reason (the host was rejected; then the phone runs opportunistic DNS). */
    @Volatile var dnsError: String = ""
    private var dnsTriedAt = 0L
    /** Effective safe-DNS host last applied (null = never). Guard's tick compares it with [Rules.dnsHost] so a pause starts and ends by itself. */
    @Volatile var appliedDns: String? = null

    /** Called every tick: re-applies Private DNS / browser DoH when the effective host changed (pause started or expired). */
    fun syncDns(ctx: Context, r: Rules) {
        if (!isOwner(ctx)) { keepDnsWss(ctx, r); return }
        val want = r.dnsHost()
        if (appliedDns == null || appliedDns == want || !isOwner(ctx)) { if (appliedDns == null) appliedDns = want; return }
        val d = dpm(ctx); val a = admin(ctx)
        applyDns(ctx, d, a, r); applyDoh(d, a, want.isNotEmpty()); appliedDns = want
        runCatching { Store.of(ctx).log(if (want.isEmpty()) R.string.log_dns_paused else R.string.log_dns_resumed) }
    }

    /** Basic mode: the raw Private DNS writes work for a WRITE_SECURE_SETTINGS holder (no Device Owner, no live adb). */
    private fun dnsWssOk(ctx: Context) = Build.VERSION.SDK_INT >= 29 && !isOwner(ctx) && Sys.wss(ctx) && !Store.of(ctx).released

    /**
     * Basic mode with WRITE_SECURE_SETTINGS: keep Private DNS on [Rules.dnsHost] (tick + [Guard] observer on the two Settings.Global keys);
     * pause / off / release put back what the phone had ([DnsLogic.plan]). No-op for the owner (DPM does it) and without the permission.
     */
    @Synchronized fun keepDnsWss(ctx: Context, r: Rules) {
        if (!dnsWssOk(ctx)) return
        val s = Store.of(ctx); val cr = ctx.contentResolver
        val want = r.dnsHost()
        // fallen back to opportunistic (host unreachable): leave it until the retry window is over, see retryDns
        if (want.isNotEmpty() && dnsError.isNotEmpty() && System.currentTimeMillis() - dnsTriedAt < retryAfter) return
        val mode = Settings.Global.getString(cr, "private_dns_mode") ?: ""
        val host = Settings.Global.getString(cr, "private_dns_specifier") ?: ""
        if (want.isNotEmpty() && !s.dnsByWss) { s.priorSaved = false; savePrior(ctx, r) }
        val w = DnsLogic.plan(want, mode, host, s.dnsByWss, s.priorDnsMode, s.priorDnsHost)
        if (w != null) {
            dnsTriedAt = System.currentTimeMillis()
            runCatching { writeDns(ctx, w.first, w.second) }.onFailure { Log.w(TAG, "private dns (wss)", it) }
        }
        if (s.dnsByWss != want.isNotEmpty()) {
            s.dnsByWss = want.isNotEmpty()
            runCatching { s.log(if (want.isEmpty()) R.string.log_dns_paused else R.string.log_dns_resumed) }
        }
        if (want.isEmpty()) dnsError = ""
        appliedDns = want
    }

    /** Specifier first, then the mode: switching to "hostname" with no host set would be rejected by the system. */
    private fun writeDns(ctx: Context, mode: String, host: String) {
        val cr = ctx.contentResolver
        if (mode == "hostname") Settings.Global.putString(cr, "private_dns_specifier", host)
        Settings.Global.putString(cr, "private_dns_mode", mode)
    }

    private fun setOpportunistic(ctx: Context) {
        if (isOwner(ctx)) runCatching { dpm(ctx).setGlobalPrivateDnsModeOpportunistic(admin(ctx)) }
        else runCatching { writeDns(ctx, "opportunistic", "") }
    }

    private fun applyDns(ctx: Context, d: DevicePolicyManager, a: ComponentName, r: Rules) {
        if (Build.VERSION.SDK_INT < 29) return
        dnsTriedAt = System.currentTimeMillis()
        val host = r.dnsHost()
        if (host.isEmpty()) {
            restrict(d, a, UserManager.DISALLOW_CONFIG_PRIVATE_DNS, false)
            runCatching { d.setGlobalPrivateDnsModeOpportunistic(a) }.onFailure { Log.w(TAG, "private dns off", it) }
            dnsError = ""
            return
        }
        val code = runCatching { d.setGlobalPrivateDnsModeSpecifiedHost(a, host) }.getOrElse { Log.w(TAG, "private dns", it); -1 }
        if (code == DevicePolicyManager.PRIVATE_DNS_SET_NO_ERROR) {
            restrict(d, a, UserManager.DISALLOW_CONFIG_PRIVATE_DNS, true)
            dnsError = ""
        } else {
            // keep the child online: opportunistic DNS and let the system settings change it
            restrict(d, a, UserManager.DISALLOW_CONFIG_PRIVATE_DNS, false)
            runCatching { d.setGlobalPrivateDnsModeOpportunistic(a) }
            dnsError = ctx.getString(R.string.dns_refused, host, code)
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
        if (Build.VERSION.SDK_INT < 29 || r.dnsHost().isEmpty() || !(isOwner(ctx) || dnsWssOk(ctx))) { unvalidatedSince = 0; return }
        val now = System.currentTimeMillis()
        if (dnsError.isNotEmpty()) {
            if (now - dnsTriedAt >= retryAfter) {
                unvalidatedSince = 0
                if (isOwner(ctx)) applyDns(ctx, dpm(ctx), admin(ctx), r) else { dnsError = ""; keepDnsWss(ctx, r) }
            }
            return
        }
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java)
        val caps = runCatching { cm.getNetworkCapabilities(cm.activeNetwork) }.getOrNull()
        val online = caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val validated = caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        if (!online || validated) { unvalidatedSince = 0; retryAfter = 60_000L; return }
        if (unvalidatedSince == 0L) { unvalidatedSince = now; return }
        if (now - unvalidatedSince < 90_000) return
        setOpportunistic(ctx)
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
        val out = LinkedHashMap(list.associate { it.activityInfo.packageName to Labels.of(ctx, it, pm) })
        runCatching {
            @Suppress("DEPRECATION")
            for (ai in pm.getInstalledApplications(0)) {
                val pkg = ai.packageName
                if (pkg in out || !ai.enabled || ai.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) continue
                // a binder call per app (~120 ms for all on a POCO, cold): kept per apk path with the labels
                val hasActivity = Labels.memo(ctx, ai.sourceDir + "|acts") {
                    runCatching { if (pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES).activities?.isNotEmpty() == true) "1" else "0" }.getOrDefault("")
                } != "0"   // a failed look-up ("", not kept) counts the app: never a way out of the limits
                if (hasActivity) out[pkg] = Labels.of(ctx, ai, pm)
            }
        }
        return out.filterKeys { it != ctx.packageName && it != TsLogic.PKG }
    }

    /** Package of a non-system app that is the default HOME ("" = none). Set by [protectedApps]; Guard raises a tamper event. */
    @Volatile var foreignHome: String = ""

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
        // keyboards: a suspended keyboard leaves no way to type (not even the parent's PIN). System keyboards always;
        // a non-system keyboard only when it is the current default (else a third-party IME could escape the limits)
        runCatching {
            ctx.getSystemService(android.view.inputmethod.InputMethodManager::class.java)?.enabledInputMethodList?.forEach { if (isSystem(pm, it.packageName)) out += it.packageName }
            Settings.Secure.getString(ctx.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.substringBefore('/')?.takeIf { it.isNotEmpty() }?.let { out += it }
        }
        // home / dialer / alarm handlers: only system apps, or the one the user really picked as default
        // (an app that merely declares a HOME / DIAL filter must not escape the limits)
        fun sys(intent: Intent) = runCatching {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0).forEach { if (isSystem(pm, it.activityInfo.packageName)) out += it.activityInfo.packageName }
        }
        // only system apps (and ours) are exempt as default dialer / SMS; a non-system default HOME stays exempt too, because
        // suspending the only home screen is a dead end, but it is reported (foreignHome -> tamper event in Guard)
        var foreign = ""
        fun default(intent: Intent, home: Boolean = false) = runCatching {
            pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
                ?.takeIf { it != "android" && !it.contains("resolver") }?.let {
                    if (isSystem(pm, it) || it == ctx.packageName) out += it
                    else if (home) { out += it; foreign = it }
                }
        }
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        sys(home); default(home, true)
        sys(Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS))
        val dial = Intent(Intent.ACTION_DIAL); sys(dial); default(dial)
        runCatching { ctx.getSystemService(TelecomManager::class.java)?.defaultDialerPackage?.let { if (isSystem(pm, it)) out += it } }
        runCatching { Settings.Secure.getString(ctx.contentResolver, "sms_default_application")?.let { if (isSystem(pm, it)) out += it } }
        foreignHome = foreign
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
        if (!SelfAdb.ensure(ctx)) return had
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

    /** Disabled for the user (`pm disable-user`) or removed for the user with the APK kept (`pm uninstall -k --user 0`, see [Debloat.disableCmd]). */
    private fun disabledByUser(ctx: Context, pkg: String): Boolean = runCatching {
        @Suppress("DEPRECATION") val ai = ctx.packageManager.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES)
        ai.flags and android.content.pm.ApplicationInfo.FLAG_INSTALLED == 0 ||
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
        if (left.isNotEmpty() && SelfAdb.ensure(ctx)) for (p in left) {
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
        // bgLimited is only still set after a release when the undo failed (no Wireless debugging then): retried here too (audit M9)
        if ((s.shellDisabled.isEmpty() && s.bgLimited.isEmpty()) || !SelfAdb.hasKey(ctx) || !retrying.compareAndSet(false, true)) return
        Thread({ try {
            if (s.shellDisabled.isNotEmpty()) runCatching { enableShellDisabled(ctx, s) }
            if (s.bgLimited.isNotEmpty()) runCatching { Health.bgRestore(ctx, s) }
        } finally { retrying.set(false) } }, "shellenable").apply { isDaemon = true }.start()
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
    /**
     * Every app's cache (logins and data stay) through the self-adb shell, both modes: `pm trim-caches` asks the system to free
     * that much, which drops all caches. Per app is not possible: `pm clear --cache-only` needs INTERNAL_DELETE_CACHE_FILES,
     * shell (uid 2000) is "silently ignored" and the command hangs (measured API 34). "ok" | "no adb" | "failed".
     */
    fun clearCaches(ctx: Context): String {
        val free = { runCatching { android.os.StatFs(android.os.Environment.getDataDirectory().path).availableBytes }.getOrDefault(0L) }
        val before = free()
        val out = SelfAdb.run(ctx, "pm trim-caches 999G") ?: return "no adb"
        if (!(out.isBlank() || out.equals("Success", true))) return "failed"
        lastCacheClear = System.currentTimeMillis() to ((free() - before).coerceAtLeast(0L) / 1_048_576L).toInt()
        return "ok"
    }
    /** (when, MB freed) of the last clearCache, for the page's result line; memory only. */
    @Volatile var lastCacheClear: Pair<Long, Int>? = null

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
        if (!isOwner(ctx) && !SelfAdb.ready(ctx) && !SelfAdb.canOpen(ctx)) return JSONArray()
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
    /** [total MB, cache MB]. dataBytes already includes cacheBytes (StorageStats docs): adding both counted the cache twice. */
    fun sizesMb(ctx: Context, pkg: String): IntArray? = runCatching {
        val ss = ctx.getSystemService(android.app.usage.StorageStatsManager::class.java)
        val st = ss.queryStatsForPackage(android.os.storage.StorageManager.UUID_DEFAULT, pkg, android.os.Process.myUserHandle())
        intArrayOf(((st.appBytes + st.dataBytes) / 1_048_576L).toInt(), (st.cacheBytes / 1_048_576L).toInt())
    }.getOrNull()

    fun lockNow(ctx: Context) = runCatching { dpm(ctx).lockNow() }.isSuccess

    /**
     * Undo everything and stop being Device Owner. After this the app can be uninstalled like any other.
     * Call it on the Guard thread with the loop stopped. Returns "ok", "not owner" or "failed" (still Device Owner).
     */
    fun release(ctx: Context, s: Store): String {
        val res = releaseAll(ctx, s)
        // background limits (appops) that could not go back (no Wireless debugging): kept in Store.bgLimited, retried at the next start
        val bg = s.bgLimited.size
        if (bg > 0) s.log(ctx.resources.getQuantityString(R.plurals.release_bg_left, bg, bg))
        return res
    }

    private fun releaseAll(ctx: Context, s: Store): String {
        runCatching { Health.bgRestore(ctx, s) }.onFailure { Log.w(TAG, "bg restore", it) }   // background limits we set (appops) go back to allow, both modes
        runCatching { FocusStore.release(ctx) }   // study mode: session, DND we set, schedule
        if (!isOwner(ctx)) {
            val off = enableShellDisabled(ctx, s)
            val res = releaseBasic(ctx, s)   // always: the parent must be able to let the phone go
            if (off > 0 && res == "ok") s.log(ctx.resources.getQuantityString(R.plurals.release_shell_left, off, off))   // kept in Store.shellDisabled, see [retryShellEnable]
            return res
        }
        val d = dpm(ctx); val a = admin(ctx)
        // Ts.undo forgets the remote-access settings: if release fails (still Device Owner) they are put back below
        val tsBack = Triple(s.tsWanted, s.tsKey, s.tsNoExpiry)
        val tsRestore = { s.tsWanted = tsBack.first; s.tsKey = tsBack.second; s.tsNoExpiry = tsBack.third }
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
            tsRestore(); runCatching { apply(ctx, s.rules) }; Watchdog.schedule(ctx)
            return ctx.resources.getQuantityString(R.plurals.release_stuck, stuck.size, stuck.size)
        }
        Update.forget(ctx)
        (BASE + INSTALL + UserManager.DISALLOW_DEBUGGING_FEATURES + UserManager.DISALLOW_APPS_CONTROL).forEach { runCatching { d.clearUserRestriction(a, it) } }
        runCatching { d.setUninstallBlocked(a, ctx.packageName, false) }
        applyNet(ctx, d, a, Rules(lockLocation = false, noAirplane = false, safeDns = ""))
        if (Build.VERSION.SDK_INT >= 30) runCatching { d.setUserControlDisabledPackages(a, emptyList()) }
        else @Suppress("DEPRECATION") runCatching { d.setAutoTimeRequired(a, false) }
        restorePrior(ctx, d, a)
        runCatching { d.setShortSupportMessage(a, null) }
        runCatching { d.setLongSupportMessage(a, null) }
        runCatching { d.setDeviceOwnerLockScreenInfo(a, null) }
        removeAccEntry(ctx); AccPause.clear(ctx); VpnPause.clear(ctx); DevOpts.clearPause(ctx)   // a bank-app pause must not outlive us
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
        for (p in listOf(Manifest.permission.CAMERA)) runCatching {
            d.setPermissionGrantState(a, ctx.packageName, p, DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED)
            d.setPermissionGrantState(a, ctx.packageName, p, DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT)
        }.onFailure { Log.w(TAG, "camera revoke", it) }
        runCatching { Shot.clear(ctx) }; runCatching { Tg.clear(s, ctx) }
        s.released = true
        Watchdog.cancel(ctx)
        @Suppress("DEPRECATION") runCatching { d.clearDeviceOwnerApp(ctx.packageName) }
        s.suspendedByUs = emptySet(); s.hiddenByUs = emptySet(); s.refusedHide = emptySet()
        if (!isOwner(ctx)) {
            NotifDots.setOn(ctx, false)   // only once Device Owner is really gone (a failed release keeps the badge listener)
            if (shellLeft > 0) s.log(ctx.resources.getQuantityString(R.plurals.release_shell_left, shellLeft, shellLeft))
            clearLost(s); s.clearTokens(); Quick.setEnabled(ctx, false); return "ok"
        }
        // still Device Owner: put the protection back (the loop re-suspends apps) and keep the parents' tokens
        s.released = false
        tsRestore()   // remote access settings come back; apply() re-grants the mic / location permissions revoked above
        runCatching { apply(ctx, s.rules) }.onFailure { Log.w(TAG, "re-apply after failed release", it) }
        Watchdog.schedule(ctx)
        return "failed"
    }

    /** Release: lost mode must not outlive us (rules flag, runtime flags; the lock-screen text is cleared with the owner info; the ring was stopped). */
    private fun clearLost(s: Store) {
        s.lostApplied = false; s.lostRingStopped = false; s.lostRingAt = 0
        if (s.rules.lostOn || s.rules.passUntil > 0) s.rules = s.rules.copy(lostOn = false, passUntil = 0)
    }

    /** Basic mode: stop blocking, drop the device admin (the app can be uninstalled again), forget the parents. "not owner" when there is no admin. */
    private fun releaseBasic(ctx: Context, s: Store): String {
        val d = dpm(ctx); val a = admin(ctx)
        if (!runCatching { d.isAdminActive(a) }.getOrDefault(false)) return "not owner"
        s.released = true          // Acc and Guard go quiet first (Basic.enforcing checks it)
        runCatching { Shot.clear(ctx) }; runCatching { Tg.clear(s, ctx) }
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
        restoreDnsWss(ctx, s)   // Private DNS we wrote with WRITE_SECURE_SETTINGS goes back to what the phone had
        removeAccEntry(ctx); AccPause.clear(ctx); VpnPause.clear(ctx); DevOpts.clearPause(ctx)   // a bank-app pause must not outlive us
        NotifDots.setOn(ctx, false)
        runCatching { LauncherSafe.off(ctx, goHome = true) }   // only owner mode can turn it on, but never leave it behind
        runCatching { Acc.running?.disableSelf() }
        clearLost(s)
        s.clearTokens()
        Quick.setEnabled(ctx, false)   // lock tile / widget / shortcuts: nothing left to lock with
        return "ok"
    }

    private fun restoreDnsWss(ctx: Context, s: Store) {
        if (!s.dnsByWss || Build.VERSION.SDK_INT < 29 || !Sys.wss(ctx)) { s.dnsByWss = false; return }
        val cr = ctx.contentResolver
        DnsLogic.plan("", Settings.Global.getString(cr, "private_dns_mode") ?: "", Settings.Global.getString(cr, "private_dns_specifier") ?: "", true, s.priorDnsMode, s.priorDnsHost)
            ?.let { runCatching { writeDns(ctx, it.first, it.second) }.onFailure { e -> Log.w(TAG, "restore dns (wss)", e) } }
        s.dnsByWss = false; s.priorSaved = false
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
