package dev.kidguard

import android.content.Context
import android.provider.Settings
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Remote adb shell (owner 2026-11-11). `adbShell {cmd, pin}` from the parent page (PIN-gated: [CmdGate]; not offered on
 * Telegram) runs as the shell user through KidGuard's own paired adbd ([SelfAdb]: Android 11+, WRITE_SECURE_SETTINGS,
 * Wi-Fi) and the output comes back in state `sh`, so LAN and relay work the same. Kept in memory only (last [KEEP]).
 *
 * `adbTcp {on, pin}`: classic `adb connect <phone ip>:5555` without a cable. USB debugging stays on and adbd gets
 * `tcpip:5555`; the property dies with a reboot, so the Guard tick ([tick]) opens it again. Paused while a bank app has
 * debugging off (DevOpts.pause); `devOff`, `adbTcp off` and release put Developer options back the way they were.
 * A computer still has to be accepted on the phone ("Allow USB debugging?") the first time.
 */
object Shell {
    private const val KEEP = 8
    private const val MAX_OUT = 6_000      // newest two entries in full
    private const val OLD_OUT = 300        // older ones: a head only, so state `sh` stays < ~14 KB (relay state size)
    const val PORT = 5555
    private const val RETRY_MS = 10 * 60_000L

    private val log = ArrayDeque<JSONObject>()
    @Volatile private var busy = false
    @Volatile private var tcpUp = false
    @Volatile private var lastTry = 0L
    /** Why the port is not open yet (state `sh.tcpWhy`): "bank" = a bank app's pause holds it, "link" = no adb link
     *  (port closed and Wireless debugging needs Wi-Fi), "" = trying / nothing known. */
    @Volatile private var why = ""
    @Volatile private var tcpBusy = false

    fun can(ctx: Context) = android.os.Build.VERSION.SDK_INT >= 30 && SelfAdb.hasKey(ctx) && (Sys.wss(ctx) || SelfAdb.viaTcp)   // port 5555 open: no WSS / Wi-Fi needed

    fun run(ctx: Context, cmd: String): String {
        val c = cmd.trim().take(2000)
        if (c.isEmpty()) return "empty"
        if (!can(ctx)) return "no adb"
        if (busy) return "busy"
        busy = true
        val app = ctx.applicationContext
        Thread({
            val t0 = System.currentTimeMillis()
            val e = JSONObject().put("at", t0).put("cmd", c)
            try {
                val why = runCatching { SelfAdb.why(app) }.getOrNull()
                val out = if (why != null) null else runCatching { SelfAdb.run(app, c) }.getOrNull()
                if (out == null) e.put("err", why ?: "adb") else e.put("out", if (out.length > MAX_OUT) out.take(MAX_OUT) + "\n…" else out)
            } finally {
                e.put("ms", System.currentTimeMillis() - t0)
                synchronized(log) { log.addLast(e); while (log.size > KEEP) log.removeFirst() }
                busy = false
                Guard.running?.kick()
            }
        }, "adb-shell").start()
        return "ok"
    }

    fun clear(): String { synchronized(log) { log.clear() }; return "ok" }

    /** State `sh` (docs/protocol.md). */
    fun state(ctx: Context): JSONObject = JSONObject().put("can", can(ctx)).put("busy", busy)
        .put("tcp", Store.of(ctx).adbTcp).put("tcpUp", tcpUp).put("tcpWhy", why).put("port", PORT)
        .put("log", JSONArray().also { a ->
            val all = synchronized(log) { log.toList() }
            all.forEachIndexed { i, e ->
                val o = e.optString("out", "")
                a.put(if (i >= all.size - 2 || o.length <= OLD_OUT) e else JSONObject(e.toString()).put("out", o.take(OLD_OUT) + "\n…"))
            }
        })

    private fun listening(): Boolean = runCatching {
        java.net.Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", PORT), 300); true }
    }.getOrDefault(false)

    private fun global(ctx: Context, k: String) = runCatching { Settings.Global.getInt(ctx.contentResolver, k, 0) == 1 }.getOrDefault(false)

    fun tcp(ctx: Context, on: Boolean): String {
        val s = Store.of(ctx)
        if (!on) { lastTry = 0L; tcpOff(ctx); return "ok" }
        if (!can(ctx)) return "no adb"
        if (!DevOpts.canOn(ctx)) return "blocked"
        if (s.adbTcpPrev < 0) s.adbTcpPrev = (if (global(ctx, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED)) 1 else 0) or
            (if (global(ctx, Settings.Global.ADB_ENABLED)) 2 else 0)
        s.adbTcp = true; s.adbTcpReset = 0L; lastTry = 0L
        s.adbTcpUntil = System.currentTimeMillis() + DevLogic.TCP_MAX_MS
        // asked now (switch, or "open now" while on): a debugging pause whose app is no longer in front ends early,
        // instead of holding the port for its 10-minute resume wait
        if (s.devApps.isNotEmpty()) {
            val cur = runCatching { Usage.today(ctx).current }.getOrNull()
            if (cur == null || cur !in s.devApps) DevOpts.clearPause(ctx)
        }
        tick(ctx, System.currentTimeMillis())
        return "ok"
    }

    /** Off: adbd back to USB only (best effort), then Developer options / USB debugging as they were. No blocking work. */
    fun tcpOff(ctx: Context, restore: Boolean = true) {
        val s = Store.of(ctx)
        val was = s.adbTcp; val prev = s.adbTcpPrev
        s.adbTcp = false; s.adbTcpPrev = -1; s.adbTcpUntil = 0L; tcpUp = false
        if (!was) return
        // service.adb.tcp.port outlives adbd restarts until a reboot: until `usb:` went through, any later adbd start
        // (devOn, a bank-pause resume) would listen on 5555 again. [tick] retries it while adbd runs.
        s.adbTcpReset = System.currentTimeMillis()
        if (prev >= 0 && prev and 2 == 0 && s.devPaused and 2 != 0) s.devPaused = s.devPaused and 2.inv()   // bank resume: don't turn USB debugging back on
        if (!restore) return
        val app = ctx.applicationContext
        Thread({
            resetPort(app)
            val cr = app.contentResolver
            if (prev >= 0 && prev and 2 == 0) runCatching { Settings.Global.putInt(cr, Settings.Global.ADB_ENABLED, 0) }
            if (prev >= 0 && prev and 1 == 0) runCatching { Settings.Global.putInt(cr, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) }
            Guard.running?.kick()
        }, "adb-tcp-off").start()
    }

    /** `usb:` if 5555 is still served after we turned it off; done = flag cleared (also by a reboot, which drops the property). */
    private fun resetPort(app: Context) {
        val s = Store.of(app)
        val at = s.adbTcpReset
        if (at == 0L || s.adbTcp) return
        if (System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime() > at) { s.adbTcpReset = 0L; return }   // rebooted since
        if (!listening()) return
        if (SelfAdb.service(app, "usb:") != null && !listening()) s.adbTcpReset = 0L
    }

    /** Guard tick: while `adbTcp` is on (and no bank app has debugging paused), keep port 5555 open; after off, close it. */
    fun tick(ctx: Context, now: Long) {
        val s = Store.of(ctx)
        if (tcpBusy) return
        if (!s.adbTcp) {
            if (s.adbTcpReset != 0L && now - lastTry >= RETRY_MS && listening()) {
                lastTry = now; tcpBusy = true
                val app = ctx.applicationContext
                Thread({ try { resetPort(app) } finally { tcpBusy = false } }, "adb-tcp-reset").start()
            }
            return
        }
        // the port closes by itself after DevLogic.TCP_MAX_MS: switching it on again is a parent's decision
        val until = DevLogic.tcpDeadline(s.adbTcpUntil, now)
        if (until != s.adbTcpUntil) s.adbTcpUntil = until
        if (DevLogic.tcpExpired(until, now)) { tcpOff(ctx); runCatching { s.log(R.string.log_adb_tcp_timeout) }; Guard.running?.kick(); return }
        if (s.devApps.isNotEmpty()) { tcpUp = false; why = "bank"; return }
        tcpUp = listening()
        if (tcpUp) why = ""   // loopback connect, sub-ms: no thread for the common case
        if (tcpUp || now - lastTry < RETRY_MS || !DevOpts.canOn(ctx)) return
        val app = ctx.applicationContext
        tcpBusy = true
        Thread({
            try {
                run {
                    lastTry = now
                    if (!global(app, Settings.Global.ADB_ENABLED)) DevOpts.on(app)
                    why = ""
                    val r = SelfAdb.service(app, "tcpip:$PORT")
                    Log.i("KidGuard", "adb tcpip $PORT: $r")
                    repeat(10) { if (!tcpUp) { Thread.sleep(500); tcpUp = listening() } }
                    why = if (tcpUp) "" else if (r == null) "link" else ""
                    Guard.running?.kick()
                }
            } finally { tcpBusy = false }
        }, "adb-tcp").start()
    }
}
