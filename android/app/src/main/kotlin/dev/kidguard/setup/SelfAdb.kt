package dev.kidguard

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log

/**
 * A shell for non-Device-Owner phones: KidGuard talks to the phone's own adbd over Wireless debugging (Android 11+),
 * using the key paired once through [SelfSetup]. Used by "Dọn máy" (pm disable-user / pm enable / pm clear).
 * Wireless debugging must be on and the phone on Wi-Fi. With WRITE_SECURE_SETTINGS, [ensure] turns it on for the job and
 * off again afterwards (the "window"), so Developer options can stay off for banking apps.
 */
object SelfAdb {
    private const val TAG = AdbPortFinder.TAG
    private const val OK_MS = 60_000L        // how long a "ready" answer is trusted
    private const val NO_MS = 30_000L        // ... a "not ready" answer
    private const val CMD_TIMEOUT_MS = 25_000L

    private val lock = Any()
    private var mgr: SelfAdbManager? = null
    private var finder: AdbPortFinder? = null
    @Volatile private var readyAt = 0L
    @Volatile private var readyVal = false

    /** The one key/certificate manager of the process (SelfSetup uses it too). */
    fun manager(ctx: Context): SelfAdbManager = synchronized(lock) { mgr ?: SelfAdbManager(ctx.applicationContext).also { mgr = it } }

    /** A key was generated, i.e. the self-setup / [pair] has been started at least once on this phone. Cheap. */
    fun hasKey(ctx: Context): Boolean = java.io.File(ctx.filesDir, "adb_key.pk8").exists() && java.io.File(ctx.filesDir, "adb_cert.der").exists()

    /**
     * True when a paired key exists and the local adbd accepts a connection right now (Android 11+, Wireless debugging on).
     * Cached for a while; a stale check may block a few seconds, so call it off the main thread.
     */
    fun ready(ctx: Context): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 30 || !hasKey(ctx)) return false
        val now = System.currentTimeMillis()
        if (now - readyAt < (if (readyVal) OK_MS else NO_MS)) return readyVal
        if (SelfSetup.running) return readyVal   // the setup job owns the link
        val v = synchronized(lock) { runCatching { connect(ctx) }.getOrDefault(false) }
        readyVal = v; readyAt = System.currentTimeMillis()
        return v
    }

    // ---- the "window": Developer options stay OFF (banking apps refuse to run with them on); for a shell job KidGuard
    // turns Developer options + Wireless debugging on itself (WRITE_SECURE_SETTINGS), runs it, and puts both back
    // [IDLE_MS] after the last command. What the job changes (pm disable-user / suspend / clear) stays after adb is off.
    private const val IDLE_MS = 20_000L
    private const val ADB_WIFI = "adb_wifi_enabled"   // Settings.Global.ADB_WIFI_ENABLED is @hide
    private const val DEV = Settings.Global.DEVELOPMENT_SETTINGS_ENABLED
    @Volatile private var opened: Map<String, Int>? = null   // values before we switched them, restored by [close]
    @Volatile private var lastUse = 0L
    private val closer = android.os.Handler(android.os.Looper.getMainLooper())

    /** The window can be opened: paired key, Android 11+, WRITE_SECURE_SETTINGS, no debugging restriction, on Wi-Fi. */
    /**
     * Why KidGuard's own adb can't run now (state `adbWhy`, the page says what to do): "sdk" (< Android 11), "key" (never
     * paired: setup step), "wss" (no WRITE_SECURE_SETTINGS), "blocked" (debugging disallowed), "wifi" (Wireless debugging
     * needs Wi-Fi), null = it can.
     */
    fun why(ctx: Context): String? = when {
        android.os.Build.VERSION.SDK_INT < 30 -> "sdk"
        !hasKey(ctx) -> "key"
        ready(ctx) -> null
        !Sys.wss(ctx) -> "wss"
        runCatching { (ctx.getSystemService(Context.USER_SERVICE) as android.os.UserManager).hasUserRestriction(android.os.UserManager.DISALLOW_DEBUGGING_FEATURES) }.getOrDefault(false) -> "blocked"
        !canOpen(ctx) -> "wifi"
        else -> null
    }

    fun canOpen(ctx: Context): Boolean = android.os.Build.VERSION.SDK_INT >= 30 && hasKey(ctx) && Sys.wss(ctx) && runCatching {
        !(ctx.getSystemService(Context.USER_SERVICE) as android.os.UserManager).hasUserRestriction(android.os.UserManager.DISALLOW_DEBUGGING_FEATURES) &&
            ctx.getSystemService(android.net.ConnectivityManager::class.java).let { cm ->
                cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true }
    }.getOrDefault(false)

    /** Ready now, or after opening the window (waits up to ~10 s for adbd). Off the main thread. */
    fun ensure(ctx: Context): Boolean {
        if (ready(ctx)) { touch(ctx); return true }
        if (!canOpen(ctx)) return false
        synchronized(lock) {
            if (opened == null) {
                val cr = ctx.contentResolver
                val prev = listOf(DEV, ADB_WIFI).associateWith { runCatching { Settings.Global.getInt(cr, it, 0) }.getOrDefault(0) }
                if (prev.values.all { it == 1 }) return false   // already on and still not ready: nothing to open
                opened = prev
                for (k in listOf(DEV, ADB_WIFI)) runCatching { Settings.Global.putInt(cr, k, 1) }.onFailure { Log.w(TAG, "window open $k", it) }
                Log.i(TAG, "self adb window open")
            }
        }
        repeat(20) { invalidate(); if (ready(ctx)) { touch(ctx); return true }; Thread.sleep(500) }
        close(ctx)
        return false
    }

    private fun touch(ctx: Context) {
        if (opened == null) return
        lastUse = System.currentTimeMillis()
        closer.removeCallbacksAndMessages(null)
        closer.postDelayed({ Thread { if (System.currentTimeMillis() - lastUse >= IDLE_MS - 500) close(ctx) }.start() }, IDLE_MS)
    }

    /** Puts Wireless debugging, then Developer options back the way they were before [ensure] opened them. */
    fun close(ctx: Context) = synchronized(lock) {
        val prev = opened ?: return@synchronized
        runCatching { manager(ctx).disconnect() }
        val cr = ctx.contentResolver
        for (k in listOf(ADB_WIFI, DEV)) if (prev[k] == 0) runCatching { Settings.Global.putInt(cr, k, 0) }.onFailure { Log.w(TAG, "window close $k", it) }
        opened = null; invalidate()
        Log.i(TAG, "self adb window closed")
    }

    /** Forget the cached answer (after a failed command or when the user paired again). */
    fun invalidate() { readyAt = 0L }

    /** Under [lock]. */
    private fun connect(ctx: Context): Boolean {
        val m = manager(ctx)
        if (m.isConnected) return true
        val f = finder ?: AdbPortFinder(ctx, AdbPortFinder.CONNECT).also { finder = it }
        f.start()
        var left = 15
        while (f.port == 0 && left-- > 0) Thread.sleep(100)   // up to 1.5 s for mDNS
        val port = f.port
        f.stop()                                                // no discovery while idle
        if (port == 0) return false
        return runCatching { m.connect("127.0.0.1", port) }.onFailure { Log.w(TAG, "self connect", it) }.getOrDefault(false)
    }

    /** Runs a shell command and returns its trimmed output, or null when there is no shell / it timed out. Off the main thread. */
    fun run(ctx: Context, cmd: String): String? {
        if (!ensure(ctx)) return null
        synchronized(lock) {
            repeat(2) { attempt ->
                val m = manager(ctx)
                if (!m.isConnected && !runCatching { connect(ctx) }.getOrDefault(false)) { invalidate(); return null }
                try {
                    val s = m.openStream("shell:$cmd")
                    val finished = java.util.concurrent.atomic.AtomicBoolean(false)
                    Thread { Thread.sleep(CMD_TIMEOUT_MS); if (!finished.get()) runCatching { s.close() } }.apply { isDaemon = true }.start()
                    val input = s.openInputStream(); val bo = java.io.ByteArrayOutputStream(); val buf = ByteArray(8192)
                    try { while (true) { val n = input.read(buf); if (n < 0) break; bo.write(buf, 0, n) } } catch (_: java.io.IOException) { }   // "Stream closed" = end of output
                    finished.set(true); runCatching { s.close() }
                    return bo.toString("UTF-8").trim()
                } catch (e: Exception) {
                    Log.w(TAG, "self run (try ${attempt + 1}): $cmd", e)
                    runCatching { m.disconnect() }
                }
            }
        }
        invalidate()
        return null
    }

    /** Opens an adbd service other than shell (e.g. `tcpip:5555`, `usb:`); adbd restarts, so the link is dropped after. */
    fun service(ctx: Context, dest: String): String? {
        if (!ensure(ctx)) return null
        return synchronized(lock) {
            runCatching {
                val m = manager(ctx)
                if (!m.isConnected && !connect(ctx)) return@runCatching null
                val s = m.openStream(dest)
                val out = runCatching { s.openInputStream().readBytes().toString(Charsets.UTF_8).trim() }.getOrDefault("")
                runCatching { s.close() }; out
            }.onFailure { Log.w(TAG, "self service $dest", it) }.getOrNull().also { runCatching { manager(ctx).disconnect() }; invalidate() }
        }
    }

    /**
     * Pairing only (no Device Owner steps): shows the "enter the pairing code" notification and opens Developer options.
     * The code typed there runs [SelfSetup.start] in pair-only mode: pair, connect, done.
     */
    fun pair(ctx: Context) {
        val app = ctx.applicationContext
        SelfSetup.pairOnly = true
        SelfSetup.startDiscovery(app)
        SelfSetup.askCodeNotification(app)
        runCatching { app.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
