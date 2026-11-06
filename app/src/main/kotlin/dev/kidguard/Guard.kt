package dev.kidguard

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
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
    override fun onReceive(context: Context, intent: Intent) { Guard.start(context) }
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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        store = Store.of(this)
        foreground()
        work = Handler(HandlerThread("guard").apply { start() }.looper)
        running = this
        Policy.apply(this, store.rules)
        http = Http(this, Api(this)).also { it.start(PORT) }
        relay = Relay(this).also { it.start() }
        work.post(::loop)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int { kick(); return START_STICKY }

    override fun onDestroy() {
        running = null
        http?.stop(); relay?.stop()
        work.looper.quitSafely()
        super.onDestroy()
    }

    private fun loop() {
        runCatching { tick() }.onFailure { Log.e("KidGuard", "tick", it) }
        work.postDelayed(::loop, TICK_MS)
    }

    /** Runs the rules again now (rules changed, a command came). */
    fun kick() { if (::work.isInitialized) work.post { runCatching { tick() } } }

    private fun tick() {
        val now = System.currentTimeMillis()
        val r = store.rules
        val apps = Policy.launchable(this)
        val prot = Policy.protectedApps(this)
        val use = Usage.today(this, now)
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        val minute = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val today = day(now)
        val want = decide(r, use.ms, minute, now, today, apps.keys, prot)
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
        lastState = state(r, apps, prot, use, want, nowSusp, today, now)
    }

    private fun state(r: Rules, apps: Map<String, String>, prot: Set<String>, use: Usage.Today, want: Map<String, String>,
                      susp: Set<String>, today: String, now: Long): JSONObject {
        val list = JSONArray()
        apps.entries.sortedByDescending { use.ms[it.key] ?: 0 }.forEach { (p, label) ->
            list.put(JSONObject().put("pkg", p).put("label", label).put("ms", use.ms[p] ?: 0)
                .put("blocked", want[p] ?: JSONObject.NULL).put("suspended", p in susp).put("protected", p in prot))
        }
        val bm = getSystemService(BatteryManager::class.java)
        val pm = getSystemService(PowerManager::class.java)
        return JSONObject()
            .put("id", store.id).put("t", now).put("day", today)
            .put("model", "${Build.MANUFACTURER} ${Build.MODEL}").put("android", Build.VERSION.RELEASE).put("version", BuildConfigVersion)
            .put("owner", Policy.isOwner(this)).put("usageAccess", Usage.granted(this))
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
            .put("log", store.logs())
    }

    fun currentState(): JSONObject = lastState

    fun setRules(r: Rules) {
        val old = store.rules
        if (r.rev < old.rev) return
        store.rules = r
        Policy.apply(this, r)
        if (r.toJson().toString() != old.toJson().toString()) store.log("Bố mẹ đổi luật")
        kick()
    }

    /** A command from the parent. Returns a short result. */
    fun command(c: JSONObject): String {
        val t = c.optString("type")
        val res = when (t) {
            "lock" -> if (Policy.lockNow(this)) "ok" else "not owner"
            "locate" -> { locate(); "ok" }
            "message" -> { MessageActivity.show(this, c.optString("text").take(500)); "ok" }
            "ring" -> { ring(); "ok" }
            "relay" -> {
                store.relayUrl = c.optString("url")
                if (store.relayToken.isBlank() || store.relayId.isBlank()) { store.relayToken = store.addToken(); store.relayId = Store.randomHex(8) }
                relay?.kick(); "ok"
            }
            "openPairing" -> { store.openPairing(); "ok" }
            "unpairAll" -> { store.clearTokens(); "ok" }
            "release" -> { Policy.release(this, store); store.log("Đã gỡ quyền quản lý"); "ok" }
            else -> "unknown"
        }
        if (t != "relay" && t != "release") store.log("Lệnh: $t → $res")
        kick()
        return res
    }

    @SuppressLint("MissingPermission")
    private fun locate() {
        val lm = getSystemService(LocationManager::class.java) ?: return
        fun save(l: Location?) {
            if (l == null) return
            val old = store.location
            if (old != null && old.optLong("t") > l.time) return
            store.location = JSONObject().put("lat", l.latitude).put("lng", l.longitude).put("acc", l.accuracy.toDouble()).put("t", l.time).put("src", l.provider ?: "")
        }
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.FUSED_PROVIDER).filter {
            runCatching { lm.isProviderEnabled(it) }.getOrDefault(false)
        }
        for (p in providers) runCatching { save(lm.getLastKnownLocation(p)) }
        for (p in providers) runCatching {
            if (Build.VERSION.SDK_INT >= 30) lm.getCurrentLocation(p, null, mainExecutor) { save(it); kick() }
            else @Suppress("DEPRECATION") lm.requestSingleUpdate(p, { l: Location -> save(l); kick() }, mainLooper)
        }.onFailure { Log.w("KidGuard", "locate $p", it) }
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

    private fun goHome() = runCatching {
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun foreground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("guard", "KidGuard", NotificationManager.IMPORTANCE_MIN))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "guard").setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("KidGuard đang bật").setContentText("Bố mẹ quản lý thời gian dùng máy").setOngoing(true).setContentIntent(open).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(1, n)
    }

    companion object {
        const val PORT = 8770
        const val TICK_MS = 10_000L
        const val BuildConfigVersion = "0.1.0"
        @Volatile var running: Guard? = null

        fun start(ctx: Context) {
            runCatching { ctx.startForegroundService(Intent(ctx, Guard::class.java)) }.onFailure { Log.e("KidGuard", "start", it) }
        }

        fun day(ms: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ms))

        fun lanIp(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
                .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
                .flatMap { it.inetAddresses.toList() }.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
        }.getOrNull()

        fun lanUrl(): String? = lanIp()?.let { "http://$it:$PORT" }
    }
}
