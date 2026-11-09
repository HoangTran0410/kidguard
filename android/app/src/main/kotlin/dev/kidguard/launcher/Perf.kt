package dev.kidguard

import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver

/**
 * Launcher timing probe (see docs/launcher-perf.md). Off by default and in every build; turn it on for a session with
 * `adb shell setprop log.tag.kgperf VERBOSE` (off again: `... setprop log.tag.kgperf ""`), then `adb logcat -s kgperf`.
 * Measure on a RELEASE build: debug is not JIT/AOT-compiled the same way (a 315 ms debug blur was 45 ms in release).
 */
object Perf {
    const val TAG = "kgperf"
    private var checkedAt = 0L
    private var on = false

    /** Is the probe on? Re-reads the log property at most every 2 s, so a guarded call costs nothing. */
    fun on(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - checkedAt > 2000) { checkedAt = now; on = Log.isLoggable(TAG, Log.VERBOSE) }
        return on
    }

    fun now(): Long = SystemClock.uptimeMillis()

    fun log(what: String, ms: Long) { if (on()) Log.v(TAG, "$what ${ms}ms") }

    /** Times [block] (main-thread build work, a parse, a save). */
    inline fun <T> time(what: String, block: () -> T): T {
        if (!on()) return block()
        val t0 = now()
        return block().also { log(what, now() - t0) }
    }

    /** Logs "[what] build Xms, first draw Yms" when [v] draws its first frame after [t0] (e.g. a sheet just opened). */
    fun firstDraw(v: View, what: String, t0: Long, built: Long = now()) {
        if (!on()) return
        val l = object : ViewTreeObserver.OnDrawListener {
            var done = false
            override fun onDraw() {
                if (done) return
                done = true
                val t = now()
                Log.v(TAG, "$what build ${built - t0}ms, first draw ${t - t0}ms")
                v.post { runCatching { v.viewTreeObserver.removeOnDrawListener(this) } }
            }
        }
        v.viewTreeObserver.addOnDrawListener(l)
    }
}
