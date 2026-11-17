package dev.kidguard

import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * The child's "Dọn dẹp" run (rule kidClean), shared by the KidGuard screen tile and the launcher widget.
 * ONLY Policy.clearCaches, on its own thread, once per 10 min ([KidTilesLogic.CLEAN_GAP_MS]). Caches only: no app data, accounts or permissions.
 * Never bgLimit: that stays a parent command. The cooldown stamp lives in prefs "quick" key "cleanAt" (the key the tile always used).
 */
object KidClean {
    /** [status]: "ok" | "fail" | "off" (rule off) | "wait" (cooldown, [waitMin] left) | "busy". [mb] freed on "ok". */
    class Result(val status: String, val mb: Int = 0, val waitMin: Int = 0)

    @Volatile var running = false; private set
    private val ui = Handler(Looper.getMainLooper())
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("quick", Context.MODE_PRIVATE)

    /** Minutes until the next clean is allowed (0 = now). One prefs read. */
    fun waitMin(c: Context): Int = KidTilesLogic.cleanWaitMin(prefs(c).getLong("cleanAt", 0L), System.currentTimeMillis())

    /** Runs the clean; [onDone] on the main thread (also for "off" / "wait" / "busy", which do no work). */
    fun run(c: Context, onDone: (Result) -> Unit) {
        val app = c.applicationContext
        val s = Store.of(app)
        if (!s.rules.kidClean) return onDone(Result("off"))
        if (running) return onDone(Result("busy"))
        val wait = waitMin(app)
        if (wait > 0) return onDone(Result("wait", waitMin = wait))
        running = true
        Thread {
            val res = runCatching { Policy.clearCaches(app) }.getOrDefault("failed")
            val mb = Policy.lastCacheClear?.second ?: 0
            if (res == "ok") {
                prefs(app).edit().putLong("cleanAt", System.currentTimeMillis()).apply()
                s.log(R.string.log_kid_clean, mb)
            }
            ui.post { running = false; onDone(if (res == "ok") Result("ok", mb) else Result("fail")) }
        }.apply { isDaemon = true }.start()
    }

    /** Result line for the child (kc_* strings). */
    fun text(c: Context, r: Result): String = when (r.status) {
        "ok" -> if (r.mb > 0) c.getString(R.string.kc_done, KidTilesLogic.mbText(r.mb)) else c.getString(R.string.kc_done0)
        "wait" -> c.getString(R.string.kc_wait, r.waitMin)
        "fail" -> c.getString(R.string.kc_fail)
        else -> ""
    }
}
