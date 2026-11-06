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
    override fun onReceive(context: Context, intent: Intent) { Guard.start(context); Watchdog.schedule(context) }   // BOOT_COMPLETED, MY_PACKAGE_REPLACED
}

/**
 * The always-on part on the child's phone: every [TICK_MS] it counts today's use and suspends what the rules say;
 * it serves the parent's page on the LAN and, when a relay is set, syncs with it.
 */
class Guard : Service() {
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
    private var locFixLogged = false
    private var usageLogged = false
    private val loopRun = Runnable { loop() }
    private val pokeRun = Runnable { kick() }
    private var refWall = System.currentTimeMillis()
    private var refElapsed = android.os.SystemClock.elapsedRealtime()
    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val w = System.currentTimeMillis(); val e = android.os.SystemClock.elapsedRealtime()
            if (Events.clockJumped(refWall, refElapsed, w, e)) runCatching { store.addEvent(Ev("clock", "Giờ máy bị đổi")); store.log("Giờ máy bị đổi"); relay?.kick() }
            refWall = w; refElapsed = e
        }
    }
    private val pkgReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { Policy.invalidate(); kick() }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        store = Store.of(this)
        foreground()
        work = Handler(HandlerThread("guard").apply { start() }.looper)
        running = this
        Watchdog.schedule(this)
        work.post { runCatching { Policy.apply(this, store.rules) } }   // off the main thread: DPM calls can block
        runCatching {
            registerReceiver(pkgReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED); addDataScheme("package")
            })
        }
        runCatching { registerReceiver(timeReceiver, IntentFilter(Intent.ACTION_TIME_CHANGED)) }
        http = Http(this, Api(this)).also { it.start(PORT) }
        relay = Relay(this).also { it.start() }
        work.post(loopRun)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int { kick(); return START_STICKY }

    /** Swiped away from recents: come straight back. */
    override fun onTaskRemoved(rootIntent: Intent?) { start(this); super.onTaskRemoved(rootIntent) }

    override fun onDestroy() {
        running = null
        runCatching { unregisterReceiver(pkgReceiver) }
        runCatching { unregisterReceiver(timeReceiver) }
        Listen.stopAll()
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

    private fun tick(force: Boolean) {
        val now = System.currentTimeMillis()
        // reference for the clock-jump check (see timeReceiver)
        refWall = now; refElapsed = android.os.SystemClock.elapsedRealtime()
        // screen off: nothing is being used, a full pass once a minute is enough
        if (!force && getSystemService(PowerManager::class.java)?.isInteractive == false && now - lastFullTick < 60_000) return
        lastFullTick = now
        val r = store.rules
        val (apps, prot) = Policy.cached(this)
        val use = Usage.today(this, now)
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        val minute = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val today = day(now)
        Acc.ensureEnabled(this)
        Policy.retryDns(this, r)
        Ts.poll(this, store)
        if (r.trackMin > 0 && now - lastTrackTry >= r.trackMin * 60_000L - 5_000L) { lastTrackTry = now; trackFix(r.trackMin) }
        if (r.lockLocation && Build.VERSION.SDK_INT >= 30 && Policy.isOwner(this)) {
            if (!locationOn()) {
                runCatching { Policy.dpm(this).setLocationEnabled(Policy.admin(this), true) }
                if (!locFixLogged) { locFixLogged = true; store.log("Vị trí bị tắt, đã bật lại") }
            } else locFixLogged = false
        }
        if (!Usage.granted(this)) {
            if (!usageLogged) { usageLogged = true; store.log("Quyền theo dõi sử dụng bị tắt") }
        } else usageLogged = false
        // first tick (with a real app list): everything installed now is "old"; while blockNew is off keep refreshing it
        val stored = store.knownApps
        val known = stored ?: emptySet<String>().also { if (apps.isNotEmpty()) store.knownApps = apps.keys }
        val fresh = if (stored == null) emptySet() else apps.keys - known - r.approved - r.allow - prot
        if (!r.blockNew && apps.isNotEmpty() && known != apps.keys) store.knownApps = apps.keys
        (fresh - store.loggedNew).takeIf { it.isNotEmpty() }?.let { n ->
            n.forEach { store.log("Cài mới: ${apps[it] ?: it}"); store.addEvent(Ev("newApp", "Đã cài ${apps[it] ?: it}")) }
            store.loggedNew = (store.loggedNew + n).intersect(apps.keys)
        }
        batteryCheck()
        val newApps = if (r.blockNew) fresh else emptySet()
        Warn.due(r, dailyLeftMs(r, use.ms, today, apps.keys, prot), minute, today, store.warned(today))?.let { w ->
            store.markWarned(today, w.keys); notifyWarn(w.text)
        }
        val want = decide(r, use.ms, minute, now, today, apps.keys, prot, newApps)
        val had = store.suspendedByUs
        val nowSusp = Policy.suspendExactly(this, want.keys, had)
        if (nowSusp != had) {
            (nowSusp - had).takeIf { it.isNotEmpty() }?.let { store.log("Tạm khoá: " + it.joinToString { p -> apps[p] ?: p } + " (" + want[it.first()] + ")") }
            (had - nowSusp).takeIf { it.isNotEmpty() }?.let { store.log("Mở lại: " + it.joinToString { p -> apps[p] ?: p }) }
            store.suspendedByUs = nowSusp
        }
        // the app in front just got suspended: send the child home (the system's own "paused" dialog shows when they tap it again)
        if (use.current != null && use.current in nowSusp && use.current !in had) goHome()
        if (now - lastLocateAt > 15 * 60_000) { lastLocateAt = now; locate() }
        if (now - store.historyAt > 3_600_000) store.saveHistory(history(now, apps.keys, prot, apps), now)
        lastState = state(r, apps, prot, use, want, nowSusp, today, now, newApps)
    }

    /** Battery <= 15 % unplugged: one event, armed again at >= 30 %. */
    private fun batteryCheck() {
        val bm = getSystemService(BatteryManager::class.java) ?: return
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val (armed, fire) = Events.batteryStep(store.batteryArmed, pct, bm.isCharging)
        if (armed != store.batteryArmed) store.batteryArmed = armed
        if (fire) { store.addEvent(Ev("battery", "Pin còn $pct%")); relay?.kick() }
    }

    /** A new location fix: zone in / out events. */
    private fun zoneFix(lat: Double, lng: Double, acc: Float) {
        val z = store.rules.zones
        if (z.isEmpty() || acc > 300f) return   // a cell-tower fix (km off) would fake "left Home"
        if (store.zoneFix(z, lat, lng).isNotEmpty()) { store.log("Vùng: sự kiện mới"); relay?.kick() }
    }

    private fun state(r: Rules, apps: Map<String, String>, prot: Set<String>, use: Usage.Today, want: Map<String, String>,
                      susp: Set<String>, today: String, now: Long, newApps: Set<String>): JSONObject {
        val list = JSONArray()
        apps.entries.sortedByDescending { use.ms[it.key] ?: 0 }.forEach { (p, label) ->
            list.put(JSONObject().put("pkg", p).put("label", label).put("ms", use.ms[p] ?: 0)
                .put("blocked", want[p] ?: JSONObject.NULL).put("suspended", p in susp).put("protected", p in prot).put("new", p in newApps))
        }
        val bm = getSystemService(BatteryManager::class.java)
        val pm = getSystemService(PowerManager::class.java)
        return JSONObject()
            .put("id", store.id).put("t", now).put("day", today)
            .put("model", "${Build.MANUFACTURER} ${Build.MODEL}").put("android", Build.VERSION.RELEASE).put("version", BuildConfig.VERSION_NAME)
            .put("acc", Acc.isOn(this))
            .put("accReEnable", checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED)
            .put("locationOn", locationOn())
            .put("limitMin", baseLimitMin(r, today).let { if (it > 0) it + if (r.bonusDay == today) r.bonusMin else 0 else 0 })
            .put("owner", Policy.isOwner(this)).put("usageAccess", Usage.granted(this)).put("dnsError", Policy.dnsError)
            .put("battery", bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1)
            .put("charging", bm?.isCharging == true).put("screenOn", pm?.isInteractive == true)
            .put("current", use.current?.let { apps[it] ?: it } ?: JSONObject.NULL)
            .put("usedMs", use.ms.filterKeys { it in apps && it !in prot && it !in r.allow }.values.sum())
            .put("leftMs", dailyLeftMs(r, use.ms, today, apps.keys, prot))
            .put("apps", list)
            .put("rules", r.toJson())
            .put("location", store.location ?: JSONObject.NULL)
            .put("lan", lanUrl() ?: JSONObject.NULL)
            .put("relay", store.relayUrl)
            .put("ts", Ts.stateJson(store.tsHost))
            .put("log", store.logs())
            .put("history", store.history)
            .put("track", store.track(2))
            .put("requests", store.requests())
            .put("sos", store.sos())
            .put("listen", Listen.state())
            .put("events", store.events())
    }

    private fun locationOn(): Boolean {
        val lm = getSystemService(LocationManager::class.java) ?: return false
        return runCatching { if (Build.VERSION.SDK_INT >= 28) lm.isLocationEnabled else true }.getOrDefault(false)
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
        nm.createNotificationChannel(NotificationChannel("answer", "Bố mẹ trả lời", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(2, Notification.Builder(this, "answer").setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("KidGuard").setContentText(text).setAutoCancel(true).setContentIntent(open).build())
    }

    /** Heads-up before a lock (daily limit, bedtime, schedule): the child gets time to save what they are doing. */
    private fun notifyWarn(text: String) = runCatching {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("warn", "Báo trước khi hết giờ", NotificationManager.IMPORTANCE_HIGH))
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
        if (r.copy(rev = 0).toJson(true).toString() != old.copy(rev = 0).toJson(true).toString()) store.log(Events.ruleDiff(old, r))
        if (r.zones != old.zones) store.zoneBaseline = true
        kick()
    }

    /** A command from the parent. Never throws; returns a short result. A repeated relay command [id] is skipped but still answered "ok". */
    fun command(c: JSONObject): String {
        val t = c.optString("type")
        val id = c.optString("id")
        // written with commit() before anything runs (a reboot / release must not run twice after a restart)
        if (id.isNotBlank() && !store.markCmd(id)) { Log.i("KidGuard", "duplicate command $id ($t)"); return "ok" }
        val res = runCatching { run(t, c) }.getOrElse { Log.w("KidGuard", "command $t", it); "failed" }
        if (t != "relay" && t != "release") runCatching { store.log("Lệnh: $t → $res") }
        kick()
        return res
    }

    private fun run(t: String, c: JSONObject): String = when (t) {
        "lock" -> if (Policy.lockNow(this)) "ok" else "not owner"
        "locate" -> { locate(); "ok" }
        "message" -> { MessageActivity.show(this, c.optString("text").take(500)); "ok" }
        "ring" -> { ring(); "ok" }
        "relay" -> {
            store.relayUrl = c.optString("url")
            if (store.relayToken.isBlank() || store.relayId.isBlank()) { store.relayToken = store.addToken(); store.relayId = Store.randomHex(8) }
            relay?.kick(); "ok"
        }
        // no PIN yet: the code shows on the child's screen and nothing else would stop the child pairing first
        "openPairing" -> if (store.everPaired && store.rules.pinHash.isBlank()) "set PIN first" else { store.openPairing(); "ok" }
        "unpairAll" -> { store.clearTokens(); "ok" }
        "answer" -> {
            val req = store.removeRequest(c.optString("id"))
            if (req != null) notifyChild(Events.answerText(c.optBoolean("ok"), c.optInt("min", req.optInt("min")), c.optString("note")))
            if (req != null) "ok" else "no such request"
        }
        "sosAck" -> if (store.ackSos(c.optString("id").ifBlank { "all" }) > 0) "ok" else "no such sos"
        "reboot" -> if (Policy.isOwner(this)) runCatching { Policy.dpm(this).reboot(Policy.admin(this)); "ok" }.getOrElse { "failed" } else "not owner"
        "tailscale" -> Ts.enable(this, store, c.optString("authKey"), c.optString("hostname"))
        "tailscaleOff" -> Ts.disable(this, store)
        "release" -> release()
        else -> "unknown"
    }

    /** On the Guard thread, loop stopped first so no tick races with it. */
    private fun release(): String {
        if (!::work.isInitialized) return "failed"
        var res = "failed"
        val done = java.util.concurrent.CountDownLatch(1)
        work.postAtFrontOfQueue {
            stopped = true
            work.removeCallbacks(loopRun); work.removeCallbacks(pokeRun)
            Listen.stopAll()
            res = runCatching { Policy.release(this, store) }.getOrElse { Log.w("KidGuard", "release", it); "failed" }
            if (res == "failed") { stopped = false; work.post(loopRun) }
            else store.log("Đã gỡ quyền quản lý")
            done.countDown()
        }
        if (!done.await(20, java.util.concurrent.TimeUnit.SECONDS)) return "failed"
        // let the HTTP answer go out, then go away
        if (res == "ok") Handler(mainLooper).postDelayed({ stopSelf() }, 2_000)
        return res
    }

    @SuppressLint("MissingPermission")
    private fun locate() {
        val lm = getSystemService(LocationManager::class.java) ?: return
        fun save(l: Location?) {
            if (l == null) return
            val old = store.location
            if (old != null && old.optLong("t") > l.time) return
            zoneFix(l.latitude, l.longitude, if (l.hasAccuracy()) l.accuracy else 0f)
            store.location = JSONObject().put("lat", l.latitude).put("lng", l.longitude).put("acc", l.accuracy.toDouble()).put("t", l.time).put("src", l.provider ?: "")
        }
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.FUSED_PROVIDER).filter {
            runCatching { lm.isProviderEnabled(it) }.getOrDefault(false)
        }
        for (p in providers) runCatching { save(lm.getLastKnownLocation(p)) }
        for (p in providers) runCatching {
            if (Build.VERSION.SDK_INT >= 30) lm.getCurrentLocation(p, null, mainExecutor) { save(it); kick() }
            else @Suppress("DEPRECATION") lm.requestSingleUpdate(p, object : LocationListener {
                override fun onLocationChanged(l: Location) { save(l); kick() }
                @Deprecated("old API levels still call it") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }, mainLooper)
        }.onFailure { Log.w("KidGuard", "locate $p", it) }
    }

    /** One location fix for the history: 30 s timeout, then the last known one if it is younger than 2 x [everyMin]. */
    @SuppressLint("MissingPermission")
    private fun trackFix(everyMin: Int) {
        if (trackBusy) return
        val lm = getSystemService(LocationManager::class.java) ?: return
        trackBusy = true
        var done = false
        fun finish(l: Location?) {
            if (done) return
            done = true; trackBusy = false
            if (l != null) zoneFix(l.latitude, l.longitude, if (l.hasAccuracy()) l.accuracy else 0f)
            if (l != null) store.addTrack(l.time.takeIf { it > 0 } ?: System.currentTimeMillis(), l.latitude, l.longitude, l.accuracy.toInt())
        }
        fun fallback(): Location? = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.FUSED_PROVIDER)
            .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
            .filter { System.currentTimeMillis() - it.time < 2L * everyMin * 60_000 }.maxByOrNull { it.time }
        val providers = listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter {
            runCatching { lm.isProviderEnabled(it) }.getOrDefault(false)
        }
        val p = providers.firstOrNull()
        if (p == null) { finish(fallback()); return }
        val cancel = android.os.CancellationSignal()
        val listener = object : LocationListener {
            override fun onLocationChanged(l: Location) { work.post { finish(l) } }
            @Deprecated("old API levels still call it") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        val timeout = Runnable {
            cancel.cancel()
            if (Build.VERSION.SDK_INT < 30) runCatching { lm.removeUpdates(listener) }
            finish(fallback())
        }
        work.postDelayed(timeout, 30_000)
        runCatching {
            if (Build.VERSION.SDK_INT >= 30) lm.getCurrentLocation(p, cancel, mainExecutor) { l -> work.post { work.removeCallbacks(timeout); finish(l ?: fallback()) } }
            else @Suppress("DEPRECATION") lm.requestSingleUpdate(p, listener, mainLooper)
        }.onFailure { Log.w("KidGuard", "track $p", it); work.removeCallbacks(timeout); finish(fallback()) }
    }

    private fun ring() {
        val am = getSystemService(AudioManager::class.java)
        runCatching { am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0) }
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        val rt = RingtoneManager.getRingtone(this, uri) ?: return
        rt.audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
        rt.play()
        work.postDelayed({ runCatching { rt.stop() } }, 20_000)
    }

    private fun goHome() {
        // the accessibility service can press Home from the background; starting an activity may be blocked there
        if (Acc.running?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME) == true) return
        runCatching {
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** SOS from the child's screen: remember it, take a fresh location, tell the relay now. */
    fun sos(): Boolean {
        store.addSos() ?: return false
        store.log("Con bấm SOS")
        runCatching { locate() }
        kick(); relay?.kick()
        // once more when the location fix has had time to come in
        if (::work.isInitialized) work.postDelayed({ relay?.kick() }, 15_000)
        return true
    }

    /** Adds / drops the microphone type of our foreground service (Android 14 needs it before using the mic). */
    fun micForeground(on: Boolean): Boolean = runCatching { foreground(on); true }.getOrElse {
        Log.w("KidGuard", "mic foreground", it); if (on) runCatching { foreground(false) }; false
    }

    private fun foreground(mic: Boolean = false) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("guard", "KidGuard", NotificationManager.IMPORTANCE_MIN))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "guard").setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("KidGuard đang bật").setContentText("Bố mẹ quản lý thời gian dùng máy").setOngoing(true).setContentIntent(open).build()
        val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or (if (mic) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, type)
        else if (Build.VERSION.SDK_INT >= 30 && mic) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(1, n)
    }

    companion object {
        const val PORT = 8770
        const val TICK_MS = 10_000L
        @Volatile var running: Guard? = null

        fun start(ctx: Context) {
            if (Store.of(ctx).released) return   // after "release" nothing may bring us back
            runCatching { ctx.startForegroundService(Intent(ctx, Guard::class.java)) }.onFailure { Log.e("KidGuard", "start", it) }
        }

        /** Re-run the rules now (e.g. the accessibility service saw another app come to the front). */
        fun poke(ctx: Context) { running?.pokeSoft() ?: start(ctx) }

        fun day(ms: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ms))

        fun lanIp(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
                .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
                .flatMap { it.inetAddresses.toList() }.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
        }.getOrNull()

        fun lanUrl(): String? = lanIp()?.let { "http://$it:$PORT" }
    }
}
