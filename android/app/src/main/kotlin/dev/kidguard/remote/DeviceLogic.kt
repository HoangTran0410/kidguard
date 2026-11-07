package dev.kidguard

import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure part of the parent page's "Máy" (device health) view: no Android classes, unit-tested in DeviceLogicTest.
 * DeviceInfo.kt reads the numbers from Android and hands them here; protocol: docs/protocol.md "Device health".
 */
object DeviceLogic {
    const val TTL_MS = 60_000L
    const val FRESH_MS = 5_000L
    const val TOP_N = 15

    data class AppSize(val pkg: String, val label: String, val app: Long, val data: Long, val cache: Long) {
        val total get() = app + data + cache
    }

    /** Biggest [n] apps by app + data + cache; empty ones dropped, ties by label so the list does not jump. */
    fun topBySize(all: List<AppSize>, n: Int = TOP_N): List<AppSize> =
        all.filter { it.total > 0 }.sortedWith(compareByDescending<AppSize> { it.total }.thenBy { it.label.lowercase() }).take(n)

    /** BatteryManager.BATTERY_HEALTH_* -> wire name. */
    fun healthName(h: Int): String = when (h) {
        2 -> "good"; 3 -> "overheat"; 4 -> "dead"; 5 -> "overVoltage"; 6 -> "failure"; 7 -> "cold"; else -> "unknown"
    }

    /** BatteryManager.BATTERY_PLUGGED_* (EXTRA_PLUGGED) -> wire name. */
    fun plugName(p: Int): String = when (p) { 1 -> "ac"; 2 -> "usb"; 4 -> "wireless"; 8 -> "dock"; else -> "none" }

    /** EXTRA_TEMPERATURE is tenths of a degree C; one decimal; null when the ROM reports nothing. */
    fun tempC(tenths: Int): Double? = if (tenths <= -1000 || tenths == Int.MIN_VALUE) null else Math.round(tenths.toDouble()) / 10.0

    /** Battery %, from EXTRA_LEVEL / EXTRA_SCALE; -1 when unknown. */
    fun pct(level: Int, scale: Int): Int = if (level < 0 || scale <= 0) -1 else (level * 100 + scale / 2) / scale

    /** RSSI dBm -> 0..4 bars (the old WifiManager.calculateSignalLevel formula, same on every API level). */
    fun wifiBars(rssi: Int?): Int? {
        if (rssi == null || rssi == Int.MIN_VALUE || rssi >= 0 || rssi < -127) return null
        return when { rssi <= -100 -> 0; rssi >= -55 -> 4; else -> (rssi + 100) * 4 / 45 }
    }

    /**
     * Screen-on time since [from] from usage events (type, time), API 28+: 15 = SCREEN_INTERACTIVE, 16 = SCREEN_NON_INTERACTIVE.
     * Before the first event the screen was in the opposite state of that event; no events at all: [onNow] decides.
     */
    fun screenOnMs(events: List<Pair<Int, Long>>, from: Long, now: Long, onNow: Boolean): Long {
        val ev = events.filter { (it.first == ON || it.first == OFF) && it.second in from..now }.sortedBy { it.second }
        if (ev.isEmpty()) return if (onNow) now - from else 0
        var on = ev.first().first == OFF
        var since = from; var total = 0L
        for ((type, t) in ev) {
            val nowOn = type == ON
            if (on && !nowOn) total += t - since
            if (!on && nowOn) since = t
            on = nowOn
        }
        if (on) total += now - since
        return total.coerceIn(0, now - from)
    }
    private const val ON = 15
    private const val OFF = 16

    data class Battery(val pct: Int, val charging: Boolean, val plug: String, val tempC: Double?, val health: String,
                       val voltageMv: Int, val screenOnMs: Long?)
    data class Ram(val total: Long, val avail: Long, val threshold: Long, val low: Boolean,
                   /** other apps' processes ActivityManager.getRunningAppProcesses shows us (0 since Android 5.1/7) */
                   val otherProcs: Int, /** /proc/stat readable (CPU %): false since Android 8 (SELinux) */ val procStat: Boolean)
    data class Net(val type: String, val rssi: Int?, val metered: Boolean)
    data class Snapshot(val t: Long, val sdk: Int, val android: String, val model: String, val version: String, val uptimeMs: Long,
                        val total: Long, val free: Long, val apps: List<AppSize>?, val ram: Ram, val battery: Battery, val net: Net,
                        val canClear: Boolean)

    fun json(s: Snapshot): JSONObject {
        val n = { v: Any? -> v ?: JSONObject.NULL }
        return JSONObject().put("t", s.t).put("sdk", s.sdk).put("android", s.android).put("model", s.model)
            .put("version", s.version).put("uptimeMs", s.uptimeMs)
            .put("storage", JSONObject().put("total", s.total).put("free", s.free).put("apps", s.apps?.let { l ->
                JSONArray().also { a -> l.forEach { x -> a.put(JSONObject().put("pkg", x.pkg).put("label", x.label.take(40))
                    .put("app", x.app).put("data", x.data).put("cache", x.cache)) } }
            } ?: JSONObject.NULL))
            .put("ram", JSONObject().put("total", s.ram.total).put("avail", s.ram.avail).put("threshold", s.ram.threshold)
                .put("low", s.ram.low).put("perApp", s.ram.otherProcs > 0 && s.ram.procStat)
                .put("otherProcs", s.ram.otherProcs).put("procStat", s.ram.procStat))
            .put("battery", JSONObject().put("pct", s.battery.pct).put("charging", s.battery.charging).put("plug", s.battery.plug)
                .put("tempC", n(s.battery.tempC)).put("health", s.battery.health).put("voltageMv", s.battery.voltageMv)
                .put("screenOnMs", n(s.battery.screenOnMs)))
            .put("net", JSONObject().put("type", s.net.type).put("rssi", n(s.net.rssi)).put("bars", n(wifiBars(s.net.rssi)))
                .put("metered", s.net.metered))
            .put("clearData", s.canClear)
    }
}
