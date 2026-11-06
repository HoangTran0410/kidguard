package dev.kidguard

import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * When the parent is away from home Wi-Fi: the child posts its state to the relay (a Cloudflare Worker the parent
 * owns) and takes back new rules and queued commands. Every 20 s with the screen on, 60 s off.
 */
class Relay(private val g: Guard) {
    private val store = Store.of(g)
    private val h = Handler(HandlerThread("relay").apply { start() }.looper)
    private var acks = JSONArray()
    @Volatile var lastOk = 0L; private set
    @Volatile var lastError = ""; private set

    // everything below is touched only on the relay thread
    private var fails = 0
    private var inSync = false
    private var kickAgain = false
    private var lastHash = 0
    private var lastFull = 0L
    private val loopRun = Runnable { loop() }

    fun start() { schedule(0) }
    fun stop() { h.looper.quitSafely() }
    /** Sync now. Never starts a second chain: while a sync runs it only asks the running one to go again at once. */
    fun kick() { h.post { if (inSync) kickAgain = true else schedule(0) } }

    private fun schedule(ms: Long) { h.removeCallbacks(loopRun); h.postDelayed(loopRun, ms) }

    private fun loop() {
        inSync = true
        val url = store.relayUrl.trim().trimEnd('/'); val token = store.relayToken; val rid = store.relayId
        var busy = false
        var delay = 0L
        if (url.isNotBlank() && token.isNotBlank() && rid.isNotBlank()) {
            if (!url.startsWith("https://", ignoreCase = true)) {
                lastError = "relay needs https://"; delay = 60_000
            } else runCatching { busy = sync(url, rid, token); fails = 0 }.onFailure {
                lastError = it.message ?: it.javaClass.simpleName; Log.w("KidGuard", "relay", it)
                fails++
                // 401 / 409 / 5xx / network: exponential backoff up to 5 min; the next success resets it
                delay = minOf(300_000L, 20_000L shl minOf(fails - 1, 4))
            }
        }
        inSync = false
        if (delay == 0L) {
            val on = g.getSystemService(PowerManager::class.java)?.isInteractive == true
            // right after a command: report back soon, so the parent sees the result
            delay = if (busy) 3_000 else if (on) 20_000 else 60_000
        }
        if (kickAgain) { kickAgain = false; delay = 0 }
        schedule(delay)
    }

    private fun open(url: String, token: String, timeout: Int = 15_000) =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 10_000; readTimeout = timeout; doOutput = true
            instanceFollowRedirects = false
            setRequestProperty("X-KG-Token", token)
        }

    private fun readCapped(c: HttpURLConnection): String {
        if (c.responseCode != 200) throw RuntimeException("HTTP ${c.responseCode}")
        c.inputStream.use { ins ->
            val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(8192)
            while (true) {
                val n = ins.read(buf); if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > MAX_REPLY) throw RuntimeException("relay reply too big")
            }
            return out.toByteArray().toString(Charsets.UTF_8)
        }
    }

    private fun sync(base: String, rid: String, token: String): Boolean {
        val now = System.currentTimeMillis()
        val state = g.currentState()
        // unchanged except `t` => omit the state; a full one at least every 5 min
        val hash = JSONObject(state.toString()).apply { remove("t") }.toString().hashCode()
        val full = hash != lastHash || now - lastFull >= 5 * 60_000L
        val req = JSONObject().put("acks", acks)
        if (full) req.put("state", state)
        val c = open("$base/c/$rid/sync", token).apply { setRequestProperty("Content-Type", "application/json") }
        try {
            c.outputStream.use { it.write(req.toString().toByteArray()) }
            val res = JSONObject(readCapped(c))
            if (full) { lastHash = hash; lastFull = now }
            acks = JSONArray()
            res.optJSONObject("rules")?.let { r ->
                val rules = Rules.fromJson(r)
                if (rules.rev > store.rules.rev) g.setRules(rules)
            }
            val cmds = res.optJSONArray("cmds") ?: JSONArray()
            for (i in 0 until cmds.length()) {
                val cmd = cmds.optJSONObject(i) ?: continue
                // Guard de-dups by cmd "id"; we ack every command, even one that failed or throws
                if (cmd.optString("type") != "relay") runCatching { g.command(cmd) }.onFailure { Log.w("KidGuard", "cmd", it) }
                acks.put(cmd.optString("id"))
            }
            lastOk = System.currentTimeMillis(); lastError = ""
            if (res.optBoolean("live")) startLive(base, rid, token)
            return acks.length() > 0 || res.has("rules")
        } finally { c.disconnect() }
    }

    // ---- live screen: one loop at a time, on its own thread, <= 3 fps, <= 10 min ----
    private val liveOn = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun startLive(base: String, rid: String, token: String) {
        if (!liveOn.compareAndSet(false, true)) return
        Thread({
            try { liveLoop(base, rid, token) } catch (e: Throwable) { Log.w("KidGuard", "live", e) }
            finally { liveOn.set(false) }
        }, "relay-live").start()
    }

    private fun liveLoop(base: String, rid: String, token: String) {
        val end = System.currentTimeMillis() + 10 * 60_000L
        var errors = 0
        while (System.currentTimeMillis() < end) {
            val t0 = System.currentTimeMillis()
            var live = false
            var gap = 0L
            try {
                val f = Acc.capture(720, 50)
                val c = open("$base/c/$rid/frame", token).apply {
                    if (f != null) {
                        setRequestProperty("Content-Type", "image/jpeg")
                        setRequestProperty("X-KG-W", f.w.toString()); setRequestProperty("X-KG-H", f.h.toString())
                    } else {
                        val err = Acc.lastError
                        setRequestProperty("X-KG-Err", err.take(60).filter { it.code in 32..126 })
                        // "busy" = another capture is running: try again at once; acc/screen off: long gap
                        gap = if (err == "busy") 150 else 1_000
                    }
                }
                try {
                    c.outputStream.use { if (f != null) it.write(f.jpeg) }
                    val res = JSONObject(readCapped(c))
                    live = res.optBoolean("live")
                    val ins = res.optJSONArray("inputs") ?: JSONArray()
                    for (i in 0 until minOf(ins.length(), 50)) runCatching { Acc.input(ins.getJSONObject(i)) }
                } finally { c.disconnect() }
                errors = 0
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
                if (++errors >= 3) return
                gap = 1_000
            }
            if (!live) return
            val wait = maxOf(gap, if (gap == 150L) 0 else 333 - (System.currentTimeMillis() - t0))
            if (wait > 0) Thread.sleep(wait)
        }
    }

    private companion object { const val MAX_REPLY = 256 * 1024 }
}
