package dev.kidguard

import android.app.ActivityManager
import android.app.usage.StorageStatsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.Process
import android.os.StatFs
import android.os.SystemClock
import android.os.storage.StorageManager
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Device health for the parent page's "Máy" view: LAN `GET /api/device`, relay `PUT /c/<id>/device` when the sync reply
 * asks (`"device": true`). Built on demand, cached 60 s (sizing every app costs a binder call each).
 * Only what a normal app / Device Owner can read on API 26-34: no per-app RAM / CPU, no other apps' cache clearing.
 */
object DeviceInfo {
    @Volatile private var cache: JSONObject? = null
    @Volatile private var cacheAt = 0L

    /** [maxAgeMs]: 60 s by default; the page's "Làm mới" (`?fresh=1`) and a relay ask take one at most 5 s old. */
    @Synchronized
    fun get(ctx: Context, maxAgeMs: Long = DeviceLogic.TTL_MS, now: Long = System.currentTimeMillis()): JSONObject {
        cache?.let { if (now - cacheAt in 0 until maxAgeMs) return it }
        val j = DeviceLogic.json(snapshot(ctx.applicationContext, now))
        cache = j; cacheAt = now
        return j
    }

    /** Relay: the parent asked (sync reply `"device": true`); send a fresh-enough snapshot. Throws on HTTP errors. */
    fun upload(ctx: Context, base: String, rid: String, token: String) {
        val body = get(ctx, DeviceLogic.FRESH_MS).toString().toByteArray()
        val c = (URL("$base/c/$rid/device").openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"; connectTimeout = 10_000; readTimeout = 15_000; doOutput = true; instanceFollowRedirects = false
            setRequestProperty("X-KG-Token", token); setRequestProperty("Content-Type", "application/json")
        }
        try {
            c.outputStream.use { it.write(body) }
            if (c.responseCode != 200) throw RuntimeException("device HTTP ${c.responseCode}")
        } finally { c.disconnect() }
    }

    private fun snapshot(ctx: Context, now: Long): DeviceLogic.Snapshot {
        val (total, free) = storage(ctx)
        return DeviceLogic.Snapshot(
            t = now, sdk = Build.VERSION.SDK_INT, android = Build.VERSION.RELEASE, model = "${Build.MANUFACTURER} ${Build.MODEL}",
            version = BuildConfig.VERSION_NAME, uptimeMs = SystemClock.elapsedRealtime(),
            total = total, free = free,
            apps = if (Usage.granted(ctx)) runCatching { appSizes(ctx) }.onFailure { Log.w("KidGuard", "device sizes", it) }.getOrNull() else null,
            ram = ram(ctx), battery = battery(ctx, now), net = net(ctx),
            canClear = Build.VERSION.SDK_INT >= 28 && Policy.isOwner(ctx))
    }

    /** Total / free of internal storage. StorageStatsManager matches Settings (rounded total incl. system); StatFs if it fails. */
    private fun storage(ctx: Context): Pair<Long, Long> = runCatching {
        val ss = ctx.getSystemService(StorageStatsManager::class.java)
        ss.getTotalBytes(StorageManager.UUID_DEFAULT) to ss.getFreeBytes(StorageManager.UUID_DEFAULT)
    }.getOrElse {
        val f = StatFs(Environment.getDataDirectory().path)
        f.totalBytes to f.availableBytes
    }

    private fun appSizes(ctx: Context): List<DeviceLogic.AppSize> {
        val pm = ctx.packageManager
        val ss = ctx.getSystemService(StorageStatsManager::class.java)
        val me = Process.myUserHandle()
        val all = pm.getInstalledApplications(0).mapNotNull { ai ->
            runCatching {
                val s = ss.queryStatsForPackage(ai.storageUuid ?: StorageManager.UUID_DEFAULT, ai.packageName, me)
                DeviceLogic.AppSize(ai.packageName, ai.packageName, s.appBytes, s.dataBytes, s.cacheBytes)
            }.getOrNull()
        }
        // labels only for the few shown (loadLabel is slow)
        return DeviceLogic.topBySize(all).map { a ->
            a.copy(label = runCatching { pm.getApplicationInfo(a.pkg, 0).loadLabel(pm).toString() }.getOrDefault(a.pkg))
        }
    }

    private fun ram(ctx: Context): DeviceLogic.Ram {
        val am = ctx.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        // probes, so the page says "not readable" from what this phone really allows, not from a guess
        val others = runCatching { am.runningAppProcesses?.count { it.pid != Process.myPid() } ?: 0 }.getOrDefault(0)
        val procStat = runCatching { File("/proc/stat").readText().isNotEmpty() }.getOrDefault(false)
        return DeviceLogic.Ram(mi.totalMem, mi.availMem, mi.threshold, mi.lowMemory, others, procStat)
    }

    private fun battery(ctx: Context, now: Long): DeviceLogic.Battery {
        val i: Intent? = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))   // sticky: no receiver kept
        val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val pct = DeviceLogic.pct(i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1, i?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1)
        return DeviceLogic.Battery(
            pct = if (pct >= 0) pct else ctx.getSystemService(BatteryManager::class.java)?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1,
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
            plug = DeviceLogic.plugName(i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0),
            tempC = DeviceLogic.tempC(i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE),
            health = DeviceLogic.healthName(i?.getIntExtra(BatteryManager.EXTRA_HEALTH, 1) ?: 1),
            voltageMv = i?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1,
            screenOnMs = screenOn(ctx, now))
    }

    /** API 28+ only (SCREEN_INTERACTIVE events); null before or without usage access. */
    private fun screenOn(ctx: Context, now: Long): Long? {
        if (Build.VERSION.SDK_INT < 28 || !Usage.granted(ctx)) return null
        return runCatching {
            val from = Usage.midnight(now)
            val ev = ctx.getSystemService(UsageStatsManager::class.java).queryEvents(from, now)
            val out = ArrayList<Pair<Int, Long>>(); val e = UsageEvents.Event()
            while (ev.hasNextEvent()) { ev.getNextEvent(e); if (e.eventType == 15 || e.eventType == 16) out += e.eventType to e.timeStamp }
            DeviceLogic.screenOnMs(out, from, now, ctx.getSystemService(PowerManager::class.java)?.isInteractive == true)
        }.getOrNull()
    }

    private fun net(ctx: Context): DeviceLogic.Net {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = runCatching { cm.getNetworkCapabilities(cm.activeNetwork) }.getOrNull() ?: return DeviceLogic.Net("none", null, false)
        val wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        val type = when {
            wifi -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cell"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            else -> "other"
        }
        var rssi: Int? = null
        if (wifi) {
            if (Build.VERSION.SDK_INT >= 29) rssi = caps.signalStrength.takeIf { it != Int.MIN_VALUE }
            @Suppress("DEPRECATION")
            if (rssi == null) rssi = runCatching { ctx.getSystemService(WifiManager::class.java)?.connectionInfo?.rssi }.getOrNull()
        }
        return DeviceLogic.Net(type, rssi, !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
    }
}
