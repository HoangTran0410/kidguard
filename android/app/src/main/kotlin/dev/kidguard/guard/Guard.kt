package dev.kidguard

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class Boot : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {   // BOOT_COMPLETED, MY_PACKAGE_REPLACED
        Guard.start(context); Watchdog.schedule(context)
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) LauncherSafe.afterUpdate(context)
    }
}

/**
 * The always-on part on the child's phone: every [TICK_MS] it counts today's use and suspends what the rules say;
 * it serves the parent's page on the LAN and, when a relay is set, syncs with it.
 */
class Guard : Service() {
    override fun getResources(): android.content.res.Resources = Lang.res(baseContext) ?: super.getResources()   // in-app language (Android 8-12)
    private lateinit var store: Store
    private lateinit var work: Handler
    private var http: Http? = null
    private var relay: Relay? = null
    @Volatile private var lastState: JSONObject = JSONObject()
    private var lastLocateAt = 0L
    private var lastFullTick = 0L
    private var lastTrackTry = 0L
    private var trackBusy = false
    private var stopped = false
    private var released = false   // onCreate saw Store.released: nothing was started, onDestroy has little to undo
    private var locFixLogged = false
    private val loopRun = Runnable { loop() }
    private val pokeRun = Runnable { kick() }
    private var refWall = System.currentTimeMillis()
    private var refElapsed = android.os.SystemClock.elapsedRealtime()
    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val w = System.currentTimeMillis(); val e = android.os.SystemClock.elapsedRealtime()
            if (intent.action == Intent.ACTION_TIMEZONE_CHANGED) runCatching {
                val tz = java.util.TimeZone.getDefault().id
                store.event("clock", R.string.ev_tz, tz); store.log(R.string.log_tz, tz); relay?.kick()
            }
            else if (Events.clockJumped(refWall, refElapsed, w, e)) runCatching { store.event("clock", R.string.ev_clock); store.log(R.string.ev_clock); relay?.kick() }
            refWall = w; refElapsed = e
        }
    }
    private var prevAuto = -1; private var prevAutoTz = -1
    /** Basic mode: keep automatic time / time zone on (WRITE_SECURE_SETTINGS, else only report); stops with release (Basic.enforcing). */
    private fun clockGuard() {
        val can = checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        var tamper = false
        for (tz in listOf(false, true)) {
            val key = if (tz) android.provider.Settings.Global.AUTO_TIME_ZONE else android.provider.Settings.Global.AUTO_TIME
            val now = android.provider.Settings.Global.getInt(contentResolver, key, -1)
            val fix = BasicLogic.clockFix(now, if (tz) prevAutoTz else prevAuto, can)
            if (fix.write) runCatching { android.provider.Settings.Global.putInt(contentResolver, key, 1) }
            if (fix.log) tamper = true
            val seen = if (fix.write) 1 else now   // a rewrite means the next tick reads 1; a failed one logs only once (prev = 0)
            if (tz) prevAutoTz = seen else prevAuto = seen
        }
        if (tamper) { store.event("clock", R.string.ev_clock); store.log(R.string.ev_clock); relay?.kick() }
    }
    private val pkgReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_PACKAGE_REMOVED && !intent.getBooleanExtra(Intent.EXTRA_REPLACING, false))
                intent.data?.schemeSpecificPart?.let { p -> runCatching { store.knownApps?.let { if (p in it) store.knownApps = it - p } } }
            Policy.invalidate(); kick()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        store = Store.of(this)
        foreground()
        // released: a sticky restart / rebind in the 2 s before stopSelf (or after it) must not enforce again
        if (store.released) { released = true; stopSelf(); return }
        work = Handler(HandlerThread("guard").apply { start() }.looper)
        running = this
        Watchdog.schedule(this)
        runCatching { Ring.restoreAfterDeath(this) }
        work.post { runCatching { Policy.apply(this, store.rules) }; Quick.setEnabled(this, true) }   // off the main thread: DPM calls can block
        @Suppress("DEPRECATION") runCatching {   // location switched off: back on at once (rule lockLocation), not at the next tick
            contentResolver.registerContentObserver(Settings.Secure.getUriFor(Settings.Secure.LOCATION_MODE), false, locObserver)
            contentResolver.registerContentObserver(Settings.Secure.getUriFor(Settings.Secure.LOCATION_PROVIDERS_ALLOWED), false, locObserver)
        }
        runCatching {   // basic mode + WRITE_SECURE_SETTINGS: Private DNS changed by hand => back to the safe host at once
            contentResolver.registerContentObserver(Settings.Global.getUriFor("private_dns_mode"), false, dnsObserver)
            contentResolver.registerContentObserver(Settings.Global.getUriFor("private_dns_specifier"), false, dnsObserver)
        }
        runCatching {
            registerReceiver(pkgReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED); addDataScheme("package")
            })
        }
        runCatching { registerReceiver(timeReceiver, IntentFilter(Intent.ACTION_TIME_CHANGED).apply { addAction(Intent.ACTION_TIMEZONE_CHANGED) }) }
        wifiLock(true)
        http = Http(this, Api(this)).also { it.start(PORT) }
        relay = Relay(this).also { it.start() }
        work.post(loopRun)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (released || store.released) { stopSelf(); return START_NOT_STICKY }
        kick(); return START_STICKY
    }

    /** Swiped away from recents: come straight back. */
    override fun onTaskRemoved(rootIntent: Intent?) { start(this); super.onTaskRemoved(rootIntent) }

    override fun onDestroy() {
        if (released) { super.onDestroy(); return }
        running = null
        runCatching { unregisterReceiver(pkgReceiver) }
        runCatching { unregisterReceiver(timeReceiver) }
        runCatching { contentResolver.unregisterContentObserver(locObserver) }
        runCatching { contentResolver.unregisterContentObserver(dnsObserver) }
        Listen.stopAll(); Proj.stop(this); Ring.stop(this)
        wifiLock(false)
        http?.stop(); relay?.stop()
        work.looper.quitSafely()
        super.onDestroy()
    }

    private fun loop() {
        if (stopped) return
        runCatching { tick(false) }.onFailure { Log.e("KidGuard", "tick", it) }
        work.postDelayed(loopRun, TICK_MS)
    }

    /** Runs the rules again now (rules changed, a command came). */
    fun kick() { if (::work.isInitialized) work.post { if (!stopped) runCatching { tick(true) }.onFailure { Log.e("KidGuard", "kick", it) } } }

    /** Many pokes in a burst (accessibility events) become one tick 300 ms after the last. */
    fun pokeSoft() { if (::work.isInitialized) { work.removeCallbacks(pokeRun); work.postDelayed(pokeRun, 300) } }

    private val optLogAt = HashMap<String, Long>()
    private fun optFail(name: String, e: Throwable) {
        val n = System.currentTimeMillis()
        if (n - (optLogAt[name] ?: 0L) >= 60_000) { optLogAt[name] = n; Log.w("KidGuard", "tick step $name", e) }
    }
    /** An optional tick step: a failure here must never skip the suspend / hide enforcement below (logged at most once a minute per step). */
    private inline fun opt(name: String, f: () -> Unit) { try { f() } catch (e: Throwable) { optFail(name, e) } }

    private fun tick(force: Boolean) {
        runCatching { relabel() }
        val now = System.currentTimeMillis()
        // reference for the clock-jump check (see timeReceiver)
        refWall = now; refElapsed = android.os.SystemClock.elapsedRealtime()
        // screen off: nothing is being used, a full pass once a minute is enough
        if (!force && getSystemService(PowerManager::class.java)?.isInteractive == false && now - lastFullTick < 60_000) return
        lastFullTick = now
        val r = store.rules
        val (apps, prot) = Policy.cached(this)
        val use = runCatching { Usage.today(this, now) }.onFailure { optFail("usage", it) }.getOrNull() ?: Usage.Today(emptyMap(), null)
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        val minute = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val today = day(now)
        // bank apps refuse to run with any accessibility service on: off while one is used, back on 10 min after (AccPause)
        opt("focus") { FocusStore.expire(this, now) }   // a study session that ended: DND back (launcher may be gone)
        opt("accPause") { AccPause.tick(this, use.current, now) }
        opt("acc") { if (!AccPause.active(this)) Acc.ensureEnabled(this) }
        opt("dns") { Policy.syncDns(this, r); Policy.retryDns(this, r) }
        opt("ts") { Ts.poll(this, store) }
        opt("track") { if (r.trackMin > 0 && now - lastTrackTry >= r.trackMin * 60_000L - 5_000L) { lastTrackTry = now; trackFix(r.trackMin) } }
        opt("location") { keepLocation(r) }
        // usage access: reported once per revoke (persisted, so a process restart while revoked does not repeat it), the child is asked to turn it back on
        if (!Usage.granted(this)) {
            if (!store.usageOffRaised) {
                store.log(R.string.log_usage_off)
                if (store.knownApps != null) {   // before the first app scan (fresh setup) nothing is raised yet; retried next tick
                    store.usageOffRaised = true
                    runCatching { store.event("tamper", R.string.ev_usage_off); relay?.kick() }
                }
            }
            notifyUsageOff(true)
        } else { if (store.usageOffRaised) store.log(R.string.log_usage_on); store.usageOffRaised = false; notifyUsageOff(false) }
        // a non-system app became the default HOME: it is exempt from all limits (no dead end), so tell the parent
        Policy.foreignHome.let { fh ->
            if (fh.isNotEmpty() && fh != store.foreignHomeSeen) {
                store.foreignHomeSeen = fh
                runCatching { store.event("tamper", R.string.ev_foreign_home, apps[fh] ?: fh); store.log(R.string.log_foreign_home, fh); relay?.kick() }
            } else if (fh.isEmpty() && store.foreignHomeSeen.isNotEmpty()) store.foreignHomeSeen = ""
        }
        // first tick (with a real app list): everything installed now is "old"; while blockNew is off keep refreshing it
        val stored = store.knownApps
        val known = stored ?: emptySet<String>().also { if (apps.isNotEmpty()) store.knownApps = apps.keys }
        val fresh = if (stored == null) emptySet() else apps.keys - known - r.approved - r.allow - prot
        // apps we hid ("Dọn máy") leave the launcher list: keep them known, or un-hiding one reads as "Cài mới"
        val nowKnown = apps.keys + store.hiddenByUs
        if (!r.blockNew && apps.isNotEmpty() && known != nowKnown) store.knownApps = nowKnown
        (fresh - store.loggedNew).takeIf { it.isNotEmpty() }?.let { n ->
            n.forEach { store.log(R.string.log_new_app, apps[it] ?: it); store.event("newApp", R.string.ev_new_app, apps[it] ?: it) }
            store.loggedNew = (store.loggedNew + n).intersect(apps.keys)
        }
        opt("battery") { batteryCheck() }
        val newApps = if (r.blockNew) fresh else emptySet()
        val leftMs = dailyLeftMs(r, use.ms, today, apps.keys, prot)
        opt("warn") { if (!r.monitor) Warn.due(r, leftMs, minute, today, store.warned(today), store.tr)?.let { w ->
            store.markWarned(today, w.keys); notifyWarn(w.text)
        } }
        // v0.9.7 bedtime wind-down: grayscale (re-applied if the child switched it off, put back afterwards)
        windActive = WindDown.active(r, minute)
        runCatching { GrayFx.tick(this, store, windActive) }.onFailure { Log.w("KidGuard", "wind-down", it) }
        // monitor only: decide() still runs (pure) so the parent sees what strict mode would do, but nothing is enforced:
        // `want` (empty) feeds suspend / bounce / launcher / tile, `would` feeds state.wouldBlock + the log
        val would = decide(r, use.ms, minute, now, today, apps.keys, prot, newApps, store.unlockUntil)
        val want = enforced(r, would)
        logWould(if (r.monitor) would else emptyMap(), apps)
        // time-left tile / widget: pushed only when the text changes
        runCatching { Quick.publish(this, QuickLogic.timeLeftText(leftMs, QuickLogic.phoneBlock(want), store.tr)) }
        val had = store.suspendedByUs
        // persisted BEFORE the DPM call: a crash in between must not leave suspended apps untracked (the loop could never undo them)
        if ((want.keys - had).isNotEmpty()) store.suspendedByUs = had + want.keys
        val nowSusp = Policy.suspendExactly(this, want.keys, had)
        if (nowSusp != had) {
            (nowSusp - had).takeIf { it.isNotEmpty() }?.let { store.log(R.string.log_suspended, it.joinToString { p -> apps[p] ?: p }, want[it.first()] ?: "") }
            (had - nowSusp).takeIf { it.isNotEmpty() }?.let { store.log(R.string.log_unsuspended, it.joinToString { p -> apps[p] ?: p }) }
        }
        if (store.suspendedByUs != nowSusp) store.suspendedByUs = nowSusp
        applyHidden(r, prot, now)
        // basic mode: no suspend, so the guard bounces instead (window events do the same instantly, see Acc)
        val basic = Basic.enforcing(this)
        Acc.blocked = if (basic) want else emptyMap(); Acc.prot = prot
        if (basic) runCatching { clockGuard() }
        if (basic) use.current?.let { cur -> BasicLogic.bounceReason(cur, packageName, want, prot)?.let { Acc.bounceApp(this, cur, it) } }
        // owner mode: the system refused to suspend it (some OEMs protect their own apps): bounce it instead
        else use.current?.takeIf { it in want && it !in nowSusp }?.let { cur -> BasicLogic.bounceReason(cur, packageName, want, prot)?.let { Acc.bounceApp(this, cur, it) } }
        // the app in front just got suspended: send the child home (the system's own "paused" dialog shows when they tap it again)
        if (use.current != null && use.current in nowSusp && use.current !in had) goHome()
        opt("locate") { if (now - lastLocateAt > 15 * 60_000) { lastLocateAt = now; locate() } }
        opt("recover") { store.recoverAt.takeIf { it > 0 }?.let { at ->   // child's "lost the link" request (MainActivity): a PIN cancels it
            if (store.rules.pinHash.isNotBlank()) store.recoverAt = 0
            else if (now >= at) { store.recoverAt = 0; store.openPairing(); store.log(R.string.log_recover_open) }
        } }
        opt("history") { if (now - store.historyAt > 3_600_000) store.saveHistory(history(now, apps.keys, prot, apps), now) }
        opt("update") { Update.maybe(this, r, now) }
        opt("launcher") { launcherTick(r, now) }
        snap = Snap(r, want, use.ms, leftMs, minute, now, Rules.isoDow(today), apps.keys, prot)
        listeners.forEach { l -> main.post { runCatching { l() } } }
        lastState = state(r, apps, prot, use, want, if (r.monitor) would else emptyMap(), nowSusp, today, now, newApps)
    }

    private var wouldSeen: Set<String> = emptySet()
    /** Monitor only: one log line per app that newly WOULD be blocked (nothing is locked). */
    private fun logWould(would: Map<String, String>, apps: Map<String, String>) {
        val add = would.keys - wouldSeen
        if (add.isNotEmpty()) store.log(R.string.log_would_block, add.joinToString { apps[it] ?: it }, would[add.first()] ?: "")
        wouldSeen = would.keys
    }

    private var launcherAt = 0L

    /** Once a minute: HOME still ours (or back to stock after the 24 h safe mode), and the crash event. */
    private fun launcherTick(r: Rules, now: Long) {
        LauncherSafe.pendingEvent(this)?.let { why -> runCatching { store.event("launcher", R.string.ev_launcher_crash, why); store.log(R.string.ev_launcher_crash, why); relay?.kick() } }
        if (now - launcherAt < 60_000) return
        launcherAt = now
        if (LauncherSafe.wanted(this, r) != LauncherSafe.enabled(this) || (LauncherSafe.enabled(this) && (!LauncherSafe.homeIsOurs(this) || LauncherSafe.homeAppShown(this))))
            runCatching { LauncherSafe.apply(this, r) }.onFailure { Log.w("KidGuard", "launcher", it) }
    }

    private var hideKey = ""; private var hideAt = 0L
    private var preCache: JSONArray = JSONArray(); private var preAt = 0L
    private var mbCache = HashMap<String, Int>(); private var mbAt = 0L   // -1 = unknown

    private var shellOffLogged = false
    /** Hide / un-hide per rules.hidden when the rules or the never-hide set changed (or every 10 min, an app may have been installed).
     *  Device Owner: setApplicationHidden. Otherwise through the self-adb shell (SelfAdb) while Wireless debugging is on. */
    private fun applyHidden(r: Rules, prot: Set<String>, now: Long) {
        val owner = Policy.isOwner(this)
        val had = if (owner) store.hiddenByUs else store.shellDisabled
        val never = Policy.neverHide(this, prot)
        val key = r.hidden.sorted().joinToString(",") + "|" + had.sorted().joinToString(",") + "|" + never.hashCode()
        if (key == hideKey && now - hideAt < 10 * 60_000L) return
        hideKey = key; hideAt = now
        if (r.hidden.isEmpty() && had.isEmpty() && store.refusedHide.isEmpty()) return
        // no Device Owner: open the adb window (SelfAdb.ensure) only when something really has to change
        if (!owner && Debloat.diff(r.hidden - store.refusedHide, had, never).let { it.hide.isEmpty() && it.show.isEmpty() }) return
        if (!owner && !SelfAdb.ensure(this)) {   // no shell (or no window to open): changes wait (disabled apps stay disabled)
            hideKey = ""
            if (!shellOffLogged && Debloat.diff(r.hidden, had, Policy.neverHide(this, prot)).let { it.hide.isNotEmpty() || it.show.isNotEmpty() }) { shellOffLogged = true; store.log(R.string.app_shell_off) }
            return
        }
        shellOffLogged = false
        val failed = HashSet<String>()
        val nowHid = Policy.hideExactly(this, r.hidden, had, never, failed)
        val refused = Debloat.refusedAfter(store.refusedHide, failed, nowHid - had, had - nowHid, r.hidden)
        if (refused != store.refusedHide) { store.refusedHide = refused; Policy.invalidate(); preAt = 0 }
        if (nowHid != had) {
            (nowHid - had).takeIf { it.isNotEmpty() }?.let { store.log(R.string.app_hidden, it.joinToString { p -> Policy.labelOf(this, p) }) }
            (had - nowHid).takeIf { it.isNotEmpty() }?.let { store.log(R.string.app_unhidden, it.joinToString { p -> Policy.labelOf(this, p) }) }
            if (owner) store.hiddenByUs = nowHid else store.shellDisabled = nowHid
            hideKey = ""   // re-evaluate next tick with the new set (cheap), and pick up Policy.cached app list changes
            Policy.invalidate(); preAt = 0
        }
    }

    /** MB per package, refreshed hourly; null when unknown. */
    private fun sizeMb(pkg: String, now: Long): Int? {
        if (now - mbAt > 3_600_000L) { mbCache = HashMap(); mbAt = now }
        if (!Usage.granted(this)) return null   // not cached: sizes show as soon as usage access is granted
        return mbCache.getOrPut(pkg) { Policy.sizeMb(this, pkg) ?: -1 }.takeIf { it >= 0 }
    }

    private fun preinstalled(prot: Set<String>, now: Long): JSONArray {
        if (now - preAt > 10 * 60_000L) {
            preAt = now
            preCache = runCatching { Policy.preinstalled(this, store, Policy.neverHide(this, prot)) { sizeMb(it, now) } }
                .onFailure { Log.w("KidGuard", "preinstalled", it) }.getOrDefault(preCache)
        }
        return preCache
    }

    /** Battery <= 15 % unplugged: one event, armed again at >= 30 %. */
    private fun batteryCheck() {
        val bm = getSystemService(BatteryManager::class.java) ?: return
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val (armed, fire) = Events.batteryStep(store.batteryArmed, pct, bm.isCharging)
        if (armed != store.batteryArmed) store.batteryArmed = armed
        if (fire) { store.event("battery", R.string.ev_battery, pct); relay?.kick() }
    }

    /** A new location fix: zone in / out events. */
    private fun zoneFix(lat: Double, lng: Double, acc: Float) {
        val z = store.rules.zones
        if (z.isEmpty() || acc > 300f) return   // a cell-tower fix (km off) would fake "left Home"
        if (store.zoneFix(z, lat, lng).isNotEmpty()) { store.log(R.string.log_zone); relay?.kick() }
    }

    private fun state(r: Rules, apps: Map<String, String>, prot: Set<String>, use: Usage.Today, want: Map<String, String>, would: Map<String, String>,
                      susp: Set<String>, today: String, now: Long, newApps: Set<String>): JSONObject {
        val list = JSONArray()
        apps.entries.sortedByDescending { use.ms[it.key] ?: 0 }.forEach { (p, label) ->
            list.put(JSONObject().put("pkg", p).put("label", label.take(40)).put("ms", use.ms[p] ?: 0).put("mb", sizeMb(p, now) ?: JSONObject.NULL)
                .put("blocked", want[p] ?: JSONObject.NULL).put("suspended", p in susp).put("protected", p in prot).put("new", p in newApps))
        }
        val bm = getSystemService(BatteryManager::class.java)
        val pm = getSystemService(PowerManager::class.java)
        val fc = FeatureCaps.of(this)
        return JSONObject()
            .put("id", store.id).put("t", now).put("day", today)
            .put("model", "${Build.MANUFACTURER} ${Build.MODEL}").put("android", Build.VERSION.RELEASE).put("sdk", Build.VERSION.SDK_INT).put("version", BuildConfig.VERSION_NAME)
            .put("pairUntil", store.pairOpenUntil())
            .put("totpOn", store.totpSecret.isNotBlank()).put("unlockUntil", store.unlockUntil.takeIf { it > now } ?: 0)
            .put("acc", Acc.isOn(this)).put("accPaused", JSONArray(store.accPaused.sorted()))
            .put("accReEnable", checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED)
            .put("locationOn", locationOn())
            .put("dev", DevOpts.state(this))
            .put("limitMin", baseLimitMin(r, today).let { if (it > 0) it + if (r.bonusDay == today) r.bonusMin else 0 else 0 })
            .put("owner", Policy.isOwner(this)).put("mode", Basic.mode(this)).put("caps", FeatureCaps.caps(this, fc)).put("features", FeatureCaps.features(fc)).put("usageAccess", Usage.granted(this)).put("usageOff", !Usage.granted(this)).put("dnsPausedUntil", r.safeDnsPauseUntil.takeIf { r.safeDns.isNotEmpty() && it > now } ?: 0).put("dnsError", Policy.dnsError)
            .put("battery", bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1)
            .put("charging", bm?.isCharging == true).put("screenOn", pm?.isInteractive == true)
            .put("current", use.current?.let { apps[it] ?: it } ?: JSONObject.NULL)
            .put("usedMs", use.ms.filterKeys { it in apps && it !in prot && it !in r.allow }.values.sum())
            .put("leftMs", dailyLeftMs(r, use.ms, today, apps.keys, prot))
            .put("apps", list)
            .put("wouldBlock", JSONObject().apply { would.forEach { (p, why) -> put(p, why) } })
            .put("rules", r.toJson())
            .put("location", store.location ?: JSONObject.NULL)
            .put("lan", lanUrl() ?: JSONObject.NULL)
            .put("relay", store.relayUrl)
            .put("ts", Ts.stateJson(store))
            .put("devices", store.devices())
            .put("log", store.logs())
            .put("history", store.history)
            .put("track", store.track(2))
            .put("requests", store.requests())
            .put("sos", store.sos())
            .put("listen", Listen.state(this))
            .put("files", Files.state(this))
            .put("ring", Ring.state())
            .put("events", store.events())
            .put("windDown", GrayFx.state(this, r, windActive))
            .put("update", Update.stateJson(this))
            .put("launcher", runCatching { LauncherSafe.state(this, r, HomeFile.styleId(this), HomeFile.pages(this)) }.getOrDefault(JSONObject()))
            .put("notes", runCatching { KidNotes.stateJson(HomeFile.load(this)?.notes) }.getOrDefault(org.json.JSONArray()))   // the kid's notes shared with parents
            .put("sys", Sys.state(this))
            .also { if (!Policy.isOwner(this)) it.put("basic", Basic.status(this)); if (Policy.isOwner(this) || SelfAdb.ready(this) || SelfAdb.canOpen(this)) it.put("preinstalled", preinstalled(prot, now)) }
    }

    /**
     * Rule lockLocation: location switched off (quick settings tile) => straight back on. Android 11+: DPM
     * setLocationEnabled (DISALLOW_CONFIG_LOCATION, Android 9+, already hides the switch). Android 8: no restriction
     * exists, so the Device Owner writes LOCATION_MODE (setSecureSetting, allowed for it before Android 9), else
     * WRITE_SECURE_SETTINGS. Runs every tick and at once from [locObserver].
     */
    private fun keepLocation(r: Rules) {
        val owner = Policy.isOwner(this); val wss = Sys.wss(this)
        if (!r.lockLocation || (!owner && !wss) || store.released) return
        if (locationOn()) { locFixLogged = false; return }
        if (!owner) {   // basic mode: no DPM, so only the raw write (WRITE_SECURE_SETTINGS from setup)
            @Suppress("DEPRECATION") runCatching { Settings.Secure.putInt(contentResolver, Settings.Secure.LOCATION_MODE, Settings.Secure.LOCATION_MODE_HIGH_ACCURACY) }
                .onFailure { Log.w("KidGuard", "location mode (wss)", it) }
        } else if (Build.VERSION.SDK_INT >= 30) runCatching { Policy.dpm(this).setLocationEnabled(Policy.admin(this), true) }
        else {
            @Suppress("DEPRECATION") val mode = Settings.Secure.LOCATION_MODE_HIGH_ACCURACY
            @Suppress("DEPRECATION") runCatching { Policy.dpm(this).setSecureSetting(Policy.admin(this), Settings.Secure.LOCATION_MODE, mode.toString()) }
                .onFailure { Log.w("KidGuard", "location mode (owner)", it) }
            if (!locationOn() && checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED)
                @Suppress("DEPRECATION") runCatching { Settings.Secure.putInt(contentResolver, Settings.Secure.LOCATION_MODE, mode) }
                    .onFailure { Log.w("KidGuard", "location mode (wss)", it) }
        }
        if (!locFixLogged) { locFixLogged = true; store.log(R.string.log_loc_fixed) }
    }
    private val locObserver by lazy {
        object : android.database.ContentObserver(work) { override fun onChange(self: Boolean) { runCatching { keepLocation(store.rules) } } }
    }

    private val dnsObserver by lazy {
        object : android.database.ContentObserver(work) { override fun onChange(self: Boolean) { runCatching { Policy.keepDnsWss(this@Guard, store.rules) } } }
    }

    private fun locationOn(): Boolean {
        return Basic.locationOn(this)
    }

    /** Last 7 days before today, newest first: total of the countable apps and the top 5. */
    private fun history(now: Long, apps: Set<String>, prot: Set<String>, labels: Map<String, String>): JSONArray {
        val out = JSONArray()
        if (!Usage.granted(this)) return out
        for (k in 1..7) {
            val from = Usage.midnightAgo(k, now)
            val ms = Usage.range(this, from, Usage.midnightAgo(k - 1, now)).ms.filterKeys { it in apps && it !in prot }
            val top = JSONArray()
            ms.entries.sortedByDescending { it.value }.take(5).filter { it.value > 0 }.forEach {
                top.put(JSONObject().put("pkg", it.key).put("label", labels[it.key] ?: it.key).put("ms", it.value))
            }
            out.put(JSONObject().put("day", day(from)).put("ms", ms.values.sum()).put("top", top))
        }
        return out
    }

    private fun notifyChild(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("answer", getString(R.string.ch_answer), NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(2, Notification.Builder(this, "answer").setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("KidGuard").setContentText(text).setAutoCancel(true).setContentIntent(open).build())
    }

    /** Persistent notification (id 7) while usage access is off: tapping opens the system screen to turn it back on. */
    private fun notifyUsageOff(on: Boolean) = runCatching {
        val nm = getSystemService(NotificationManager::class.java)
        if (!on) { nm.cancel(7); return@runCatching }
        nm.createNotificationChannel(NotificationChannel("usage", getString(R.string.ch_usage), NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(this, 7, Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(7, Notification.Builder(this, "usage").setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("KidGuard").setContentText(getString(R.string.usage_off_note)).setOngoing(true).setContentIntent(open).build())
    }

    /** Heads-up before a lock (daily limit, bedtime, schedule): the child gets time to save what they are doing. */
    private fun notifyWarn(text: String) = runCatching {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("warn", getString(R.string.ch_warn), NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(6, Notification.Builder(this, "warn").setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("KidGuard").setContentText(text).setAutoCancel(true).setContentIntent(open).build())
    }

    fun currentState(): JSONObject = lastState

    fun setRules(incoming: Rules) {
        val old = store.rules
        if (incoming.rev < old.rev) return
        // a rules PUT without pin / pinHash keeps the old PIN
        val r = if (incoming.pinHash.isBlank()) incoming.copy(pinHash = old.pinHash, pinSalt = old.pinSalt) else incoming
        store.rules = r
        if (::work.isInitialized) work.post { runCatching { Policy.apply(this, r) } }
        if (r.noDebug && !old.noDebug) devOff()   // "no debugging" switched on: also turn Developer options off once
        if (r.copy(rev = 0).toJson(true).toString() != old.copy(rev = 0).toJson(true).toString()) store.log(Events.ruleDiff(old, r, store.tr), "rules")
        if (r.zones != old.zones) store.zoneBaseline = true
        kick()
    }

    /** A command from the parent. Never throws; returns a short result. A repeated relay command [id] is skipped but still answered "ok" (unless its first run failed: then the id is forgotten and it runs again). */
    fun command(c: JSONObject): String {
        val t = c.optString("type")
        val id = c.optString("id")
        // written with commit() before anything runs (a reboot / release must not run twice after a restart)
        if (id.isNotBlank() && !store.markCmd(id)) { Log.i("KidGuard", "duplicate command $id ($t)"); return "ok" }
        val res = runCatching { run(t, c) }.getOrElse { Log.w("KidGuard", "command $t", it); "failed" }
        // a failed run (e.g. release: "failed: N apps still hidden") may be re-sent with the same id and must run again, not answer "ok"
        if (id.isNotBlank() && res.startsWith("failed")) store.unmarkCmd(id)
        // a done `setting` writes its own log line (log_setting_*)
        if (t != "relay" && t != "release" && !(t == "setting" && res == "ok")) runCatching { store.log(R.string.log_cmd, t, res) }
        kick()
        return res
    }

    private fun run(t: String, c: JSONObject): String = when (t) {
        "lock" -> if (Policy.lockNow(this)) "ok" else "not owner"
        "locate" -> { locate(); "ok" }
        "message" -> { MessageActivity.show(this, c.optString("text").take(500)); "ok" }
        "ring" -> Ring.start(this)
        "ringStop" -> Ring.stop(this)
        "wake" -> Wake.now(this)
        "listenStop" -> { Listen.stopAll(); "ok" }
        "fileUp" -> Files.command(this, t, c)   // shared folder over the relay (Files.kt)
        "fileDel" -> Files.command(this, t, c)
        "relay" -> {
            store.relayUrl = c.optString("url")
            if (store.relayToken.isBlank() || store.relayId.isBlank()) { store.relayToken = store.addToken(); store.relayId = Store.randomHex(8); store.touchDevice(store.relayToken, "relay") }
            relay?.kick(); "ok"
        }
        // no PIN yet: the code shows on the child's screen and nothing else would stop the child pairing first
        "openPairing" -> if (store.everPaired && store.rules.pinHash.isBlank()) "set PIN first" else { store.openPairing(); "ok" }
        "unpairAll" -> { store.clearTokens(); "ok" }
        "unpair" -> if (store.unpair(c.optString("id"))) { relay?.kick(); kick(); "ok" } else "no such device"
        "answer" -> {
            val req = store.removeRequest(c.optString("req").ifBlank { c.optString("id") })   // relay: "req" = the request id (the command id may be a UUID)
            if (req != null) notifyChild(Events.answerText(c.optBoolean("ok"), c.optInt("min", req.optInt("min")), c.optString("note"), store.tr))
            if (req != null) "ok" else "no such request"
        }
        "sosAck" -> if (store.ackSos(c.optString("sos").ifBlank { "all" }) > 0) "ok" else "no such sos"   // not "id": the relay fills that in
        "reboot" -> if (Policy.isOwner(this)) runCatching { Policy.dpm(this).reboot(Policy.admin(this)); "ok" }.getOrElse { "failed" } else "not owner"
        "tailscale" -> Ts.enable(this, store, c.optString("authKey"), c.optString("hostname"))
        "tailscaleOff" -> Ts.disable(this, store)
        "clearData" -> {
            val pkg = c.optString("pkg")
            val res = Policy.clearData(this, pkg)
            if (res == "ok") store.log(R.string.log_cleared, Policy.labelOf(this, pkg))
            res
        }
        "update" -> if (Update.start(this, true)) "ok" else "busy"
        "devOff" -> devOff()
        "devOn" -> DevOpts.on(this).also { if (it == "ok") store.log(R.string.log_dev_on) }
        // the parent page made the secret and shows it as an Authenticator QR; "" turns codes off
        "totpSet" -> c.optString("secret").let { s ->
            if (s.isNotEmpty() && Totp.decode(s) == null) "bad secret"
            else { store.totpSecret = s.uppercase().filter { it in 'A'..'Z' || it in '2'..'7' }; store.unlockUntil = 0; store.log(if (s.isEmpty()) R.string.log_totp_off else R.string.log_totp_on); "ok" }
        }
        "setting" -> Sys.set(this, store, c)
        "release" -> release()
        else -> "unknown"
    }

    private fun devOff(): String = DevOpts.off(this).also { if (it == "ok") store.log(R.string.log_dev_off) }

    /** On the Guard thread, loop stopped first so no tick races with it. */
    private fun release(): String {
        if (!::work.isInitialized) return "failed"
        val res = java.util.concurrent.atomic.AtomicReference("failed")
        val done = java.util.concurrent.CountDownLatch(1)
        val state = java.util.concurrent.atomic.AtomicInteger(0)   // 0 running, 1 finished in time, 2 abandoned by the 20 s timeout
        work.postAtFrontOfQueue {
            stopped = true
            work.removeCallbacks(loopRun); work.removeCallbacks(pokeRun)
            Listen.stopAll(); Proj.stop(this); Ring.stop(this); wifiLock(false)
            runCatching { GrayFx.restore(this, store) }.onFailure { Log.w("KidGuard", "grayscale restore", it) }   // the loop puts it back if release fails
            val r = runCatching { Policy.release(this, store) }.getOrElse { Log.w("KidGuard", "release", it); "failed" }
            res.set(r)
            // anything but "ok" (e.g. "failed: N app còn bị ẩn") means we are still in charge: keep enforcing
            if (r != "ok") { stopped = false; wifiLock(true); work.post(loopRun); store.log(R.string.log_release_fail, r) }
            else { store.log(R.string.log_released); runCatching { Files.wipeOutbox(this) } }   // received files stay in Download
            // finished after the caller gave up ("failed" was answered): still go away, we are released
            if (!state.compareAndSet(0, 1) && r == "ok") Handler(mainLooper).postDelayed({ stopSelf() }, 2_000)
            done.countDown()
        }
        if (!done.await(20, java.util.concurrent.TimeUnit.SECONDS) && state.compareAndSet(0, 2)) return "failed"
        // let the HTTP answer go out, then go away
        if (res.get() == "ok") Handler(mainLooper).postDelayed({ stopSelf() }, 2_000)
        return res.get()
    }

    @SuppressLint("MissingPermission")
    private fun locate() {
        val lm = getSystemService(LocationManager::class.java) ?: return
        fun save(l: Location?) {
            if (l == null) return
            val old = store.location
            if (old != null && old.optLong("t") > l.time) return
            // network then gps answer within seconds: keep the finer one
            if (old != null && l.time - old.optLong("t") < 60_000 && l.hasAccuracy() && old.optDouble("acc", 1e9) < l.accuracy) return
            // an old cached fix (getLastKnownLocation after a long gap) must not fire zone in/out events
            if (System.currentTimeMillis() - l.time < 10 * 60_000) zoneFix(l.latitude, l.longitude, if (l.hasAccuracy()) l.accuracy else 0f)
            store.location = JSONObject().put("lat", l.latitude).put("lng", l.longitude).put("acc", l.accuracy.toDouble()).put("t", l.time).put("src", l.provider ?: "")
            if (store.fillCheckins(store.location!!)) relay?.kick()   // a check-in pressed before this fix gets it
        }
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.FUSED_PROVIDER))
            runCatching { if (lm.isProviderEnabled(p)) save(lm.getLastKnownLocation(p)) }
        oneFix(lm, 60_000, each = true) { l -> if (l != null) { save(l); kick() } }
    }

    /** Providers to ask for one fix. Fused alone when it exists; else network AND gps together, first answer wins:
     *  on Android 8-10 there is no fused provider, and gps alone never answers indoors, so the history got a point
     *  only when a cached fix happened to be fresh (the "sparse track" on the Oppo, 2026-11-07). */
    private fun fixProviders(lm: LocationManager): List<String> {
        val on = { p: String -> runCatching { lm.isProviderEnabled(p) }.getOrDefault(false) }
        if (Build.VERSION.SDK_INT >= 31 && on(LocationManager.FUSED_PROVIDER)) return listOf(LocationManager.FUSED_PROVIDER)
        return listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER).filter(on)
    }

    /** Asks every provider for one fix; [onFix] gets the first one (or null after [timeoutMs] / when all fail).
     *  [each]: [onFix] gets every fix as it comes (network first, then the finer gps one), never null.
     *  Every listener is removed when it ends, so gps never stays on (dumpsys showed hours of gps from leaked one-shots). */
    @SuppressLint("MissingPermission")
    private fun oneFix(lm: LocationManager, timeoutMs: Long, each: Boolean = false, onFix: (Location?) -> Unit) {
        val ps = fixProviders(lm)
        if (ps.isEmpty()) { if (!each) onFix(null); return }
        var done = false; var left = ps.size
        val cancel = android.os.CancellationSignal()
        val listeners = mutableListOf<LocationListener>()
        lateinit var timeout: Runnable
        fun end(l: Location?) {
            if (done) return
            done = true; work.removeCallbacks(timeout)
            runCatching { cancel.cancel() }
            listeners.forEach { runCatching { lm.removeUpdates(it) } }
            if (!each) onFix(l)
        }
        fun miss() { if (--left <= 0) end(null) }
        fun got(l: Location) { if (done) return; if (!each) end(l) else { onFix(l); miss() } }
        timeout = Runnable { end(null) }
        work.postDelayed(timeout, timeoutMs)
        for (p in ps) runCatching {
            if (Build.VERSION.SDK_INT >= 30) lm.getCurrentLocation(p, cancel, mainExecutor) { l -> work.post { if (l != null) got(l) else miss() } }
            else {
                val li = object : LocationListener {
                    override fun onLocationChanged(l: Location) { work.post { got(l) } }
                    @Deprecated("old API levels still call it") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                }
                listeners += li
                @Suppress("DEPRECATION") lm.requestSingleUpdate(p, li, mainLooper)
            }
        }.onFailure { Log.w("KidGuard", "fix $p", it); work.post { miss() } }
    }

    /** One location fix for the history: 30 s timeout, then the last known one if it is younger than 2 x [everyMin]. */
    @SuppressLint("MissingPermission")
    private fun trackFix(everyMin: Int) {
        if (trackBusy) return
        val lm = getSystemService(LocationManager::class.java) ?: return
        trackBusy = true
        fun fallback(): Location? = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.FUSED_PROVIDER)
            .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
            .filter { System.currentTimeMillis() - it.time < 2L * everyMin * 60_000 }.maxByOrNull { it.time }
        oneFix(lm, 30_000) { got ->
            trackBusy = false
            val l = got ?: fallback() ?: return@oneFix
            zoneFix(l.latitude, l.longitude, if (l.hasAccuracy()) l.accuracy else 0f)
            // a fix's own clock can lag (cached network fix); stamp it now when it is that recent, so same-spot points space out evenly
            val now = System.currentTimeMillis()
            val t = if (l.time <= 0 || now - l.time < 2 * 60_000) now else l.time
            store.addTrack(t, l.latitude, l.longitude, l.accuracy.toInt(), Track.gapFor(everyMin))
        }
    }

    private fun goHome() {
        // the accessibility service can press Home from the background; starting an activity may be blocked there
        if (Acc.running?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME) == true) return
        runCatching {
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    @Volatile private var windActive = false

    /**
     * v0.9.7 check-in ("arrived" / "pickup"): same transport as SOS (fresh fix, relay now and 15 s later) but a plain
     * event, no alarm. False = the same button was pressed less than a minute ago.
     */
    fun checkin(ck: String): Boolean {
        if (!checkinNow(this, store, ck)) return false
        runCatching { locate() }
        kick(); relay?.kick()
        if (::work.isInitialized) work.postDelayed({ relay?.kick() }, 15_000)
        return true
    }

    /** SOS from the child's screen: remember it, take a fresh location, tell the relay now. */
    fun sos(): Boolean {
        store.addSos() ?: return false
        store.log(R.string.log_sos)
        runCatching { locate() }
        kick(); relay?.kick()
        // once more when the location fix has had time to come in
        if (::work.isInitialized) work.postDelayed({ relay?.kick() }, 15_000)
        return true
    }

    private var wifi: android.net.wifi.WifiManager.WifiLock? = null

    /**
     * Held while the service runs: some ROMs (ColorOS on Android 8.1) put Wi-Fi to sleep with the screen off and the
     * LAN page / listen stream drop. Costs some standby battery (radio stays out of power save); released on stop / release.
     */
    @Suppress("DEPRECATION")
    private fun wifiLock(on: Boolean) = runCatching {
        if (!on) { wifi?.takeIf { it.isHeld }?.release(); wifi = null; return@runCatching }
        if (wifi?.isHeld == true) return@runCatching
        val wm = applicationContext.getSystemService(android.net.wifi.WifiManager::class.java) ?: return@runCatching
        // LOW_LATENCY (29+) only works while the app is in the foreground with the screen on: useless here
        wifi = wm.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "kidguard:lan").apply { setReferenceCounted(false); acquire() }
    }.onFailure { Log.w("KidGuard", "wifi lock", it) }

    /** Adds / drops the microphone type of our foreground service (Android 14 needs it before using the mic). */
    fun micForeground(on: Boolean): Boolean = runCatching { foreground(on); true }.getOrElse {
        Log.w("KidGuard", "mic foreground", it); if (on) runCatching { foreground(false) }; false
    }

    private var fgLabel = ""
    private fun fgNotification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val t = getString(R.string.notif_guard_title); val x = getString(R.string.notif_guard_text)
        fgLabel = t + "\n" + x
        return Notification.Builder(this, "guard").setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle(t).setContentText(x).setOngoing(true).setContentIntent(open).build()
    }

    /** The in-app language changed (Lang): re-post the foreground notification in it. Same id = same foreground slot. */
    private fun relabel() {
        if (fgLabel.isEmpty() || getString(R.string.notif_guard_title) + "\n" + getString(R.string.notif_guard_text) == fgLabel) return
        getSystemService(NotificationManager::class.java).notify(1, fgNotification())
    }

    private fun foreground(mic: Boolean = false) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("guard", "KidGuard", NotificationManager.IMPORTANCE_MIN))
        val n = fgNotification()
        val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or (if (mic) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, type)
        else if (Build.VERSION.SDK_INT >= 30 && mic) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(1, n)
    }

    companion object {
        const val PORT = 8770
        const val TICK_MS = 10_000L
        @Volatile var running: Guard? = null

        /** What the last tick decided, for the kid launcher (HomeActivity): it filters with the same decide() result. */
        class Snap(val r: Rules, val want: Map<String, String>, val usageMs: Map<String, Long>, val leftMs: Long, val minute: Int,
                   val now: Long, val dow: Int, val apps: Set<String>, val prot: Set<String>)
        @Volatile var snap: Snap? = null
        val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
        private val main by lazy { Handler(android.os.Looper.getMainLooper()) }

        /** Stores a check-in event (+ log line). The running Guard also locates and syncs; else starting it does. */
        fun checkinNow(ctx: Context, s: Store, ck: String): Boolean {
            if (ck !in Checkin.TYPES) return false
            val text = ctx.getString(if (ck == "arrived") R.string.ev_ci_arrived else R.string.ev_ci_pickup)
            s.addCheckin(ck, text, s.location) ?: return false
            s.log(R.string.log_checkin, ctx.getString(if (ck == "arrived") R.string.main_ci_arrived else R.string.main_ci_pickup))
            return true
        }

        fun start(ctx: Context) {
            if (Flavor.HOME) return   // KidGuard Home: no Guard service (removed from its manifest)
            if (Store.of(ctx).released) { Policy.retryShellEnable(ctx); return }   // after "release" nothing may bring us back (but apps we disabled via the shell still get enabled)
            runCatching { ctx.startForegroundService(Intent(ctx, Guard::class.java)) }.onFailure { Log.e("KidGuard", "start", it) }
        }

        /** Re-run the rules now (e.g. the accessibility service saw another app come to the front). */
        fun poke(ctx: Context) { if (!Flavor.HOME) running?.pokeSoft() ?: start(ctx) }

        fun day(ms: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ms))

        fun lanIp(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
                .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
                .flatMap { it.inetAddresses.toList() }.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
        }.getOrNull()

        fun lanUrl(): String? = lanIp()?.let { "http://$it:$PORT" }
    }
}
