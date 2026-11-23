package dev.kidguard

/**
 * Pure battery decisions of the Guard (unit-tested): when the Wi-Fi radio is kept out of power save, and how often a
 * phone that is lying still takes a location fix. See docs/flows/enforcement.md §1.6 / §1.8.
 */
object PowerLogic {
    /** The LAN page / a stream was used this recently: keep Wi-Fi out of power save so the next calls answer fast. */
    const val LAN_HOT_MS = 3 * 60_000L

    /**
     * Hold the high-performance Wi-Fi lock now? Android 8-9 (ColorOS 8.1 puts Wi-Fi to sleep with the screen off, the
     * LAN page then never reaches the phone): always, as before. Android 10+: the radio stays associated in sleep on
     * its own, so only while a parent is using the LAN page or a stream runs; idle hours keep Wi-Fi power save.
     */
    fun wifiPerf(sdk: Int, lanIdleMs: Long, streaming: Boolean): Boolean =
        sdk <= 28 || streaming || lanIdleMs in 0 until LAN_HOT_MS
}

/**
 * Location pace: a phone that has not moved (two fixes in a row at the same spot, screen off in between) takes fewer
 * fixes, as long as the significant-motion sensor is armed to wake it the moment it is carried away.
 */
object LocPace {
    const val STILL_M = 100.0
    const val STILL_FIXES = 2                    // same-spot fixes in a row before slowing down
    const val SLOW_TRACK_MIN = 15                // history step while still (the page notes a gap only above 30 min)
    const val LOCATE_MS = 15 * 60_000L           // "last location" refresh
    const val SLOW_LOCATE_MS = 60 * 60_000L
    const val GOOD_ACC_M = 100                   // a history fix this fine also serves as the "last location"

    fun same(lat1: Double, lng1: Double, acc1: Int, lat2: Double, lng2: Double, acc2: Int): Boolean =
        Track.meters(lat1, lng1, lat2, lng2) < maxOf(STILL_M, maxOf(acc1, acc2).toDouble())

    /**
     * The count of same-spot fixes after a new fix. A vague fix (cell tower, > [Track.BAD_ACC_M]) proves nothing either
     * way: unchanged. The screen was on since the last fix, or the spot changed: back to 0.
     */
    fun step(still: Int, hasPrev: Boolean, same: Boolean, screenWasOn: Boolean, acc: Int): Int = when {
        acc > Track.BAD_ACC_M -> still
        hasPrev && same && !screenWasOn -> minOf(still + 1, 100)
        else -> 0
    }

    /** Slow pace only with the motion sensor armed: without it a child leaving would be noticed late. */
    fun slow(still: Int, motionArmed: Boolean): Boolean = motionArmed && still >= STILL_FIXES

    /**
     * Providers for one fix. Android 12+: fused AND gps (owner 2026-11-23, Wi-Fi-only kid phone: home -> school 40 min, only
     * the 2 Wi-Fi ends in the history). getCurrentLocation(fused) asks with balanced quality = Wi-Fi / cell only, never gps,
     * and Wi-Fi location needs internet: on the road with no SIM nothing answered. Online, the fused answer comes first and
     * ends the gps one (same cost as before); offline gps is the only one that can answer.
     */
    fun providers(sdk: Int, fused: Boolean, network: Boolean, gps: Boolean): List<String> =
        if (sdk >= 31 && fused) listOfNotNull("fused", "gps".takeIf { gps })
        else listOfNotNull("network".takeIf { network }, "gps".takeIf { gps })

    /**
     * A first answer that should wait for gps (when gps is still asking): coarse (cell tower) or old. Offline, the Oppo's
     * MediaTek network provider hands back its cached Wi-Fi fix from home at once, which used to end the request: the
     * whole trip was stored as "still at home" (dumpsys 2026-11-23: no real gps fix in 5 h, "gps" last-known = a network one).
     */
    fun vague(hasAcc: Boolean, accM: Float, ageMs: Long): Boolean = !hasAcc || accM > Track.BAD_ACC_M || ageMs > STALE_MS
    const val STALE_MS = 120_000L

    /** History fix timeout: offline gps has no assistance data and needs longer than 30 s (worse in a school bag). */
    fun trackTimeoutMs(online: Boolean): Long = if (online) 30_000L else 75_000L

    fun trackEveryMs(trackMin: Int, slow: Boolean): Long = (if (slow) maxOf(trackMin, SLOW_TRACK_MIN) else trackMin) * 60_000L

    /**
     * The separate "last location" fix (up to 60 s of GPS) is due? Not while the history fixes keep the last location
     * fresh and fine ([fixAgeMs] within two history steps, accuracy <= [GOOD_ACC_M]): then it would only repeat them.
     */
    fun locateDue(sinceLocateMs: Long, fixAgeMs: Long, fixAcc: Int, trackMin: Int, slow: Boolean): Boolean {
        if (sinceLocateMs <= (if (slow) SLOW_LOCATE_MS else LOCATE_MS)) return false
        val step = trackEveryMs(trackMin, slow)
        return !(trackMin > 0 && fixAcc in 1..GOOD_ACC_M && fixAgeMs in 0 until 2 * step + 60_000L)
    }
}
