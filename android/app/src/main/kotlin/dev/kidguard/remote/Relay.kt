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
 * With the WebSocket up ([RelayWs]): the same sync goes over the socket, only when something the parent cares about
 * changed, the relay kicks (a parent queued something / is looking), or every [WS_IDLE_ON] / [WS_IDLE_OFF]; HTTP stays
 * the fallback whenever the socket is down.
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
    private val slow = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "relay-slow").apply { isDaemon = true } }
    private val ws = RelayWs { h.post { forceSend = true; if (inSync) kickAgain = true else schedule(0) } }
    private var wsRetryAt = 0L
    private var wsFails = 0
    private var wsUpAt = 0L   // when the socket last connected: one that dies within 5 min counts as a failure (flapping network)
    private var forceSend = false
    private var lastCoarse = 0
    private var lastSent = 0L
    private val diff = StateDiff()      // partial states once the worker says `merge` (bandwidth)
    private var canMerge = false
    private var lastWhole = 0L
    private var bigUntil = 0L           // the relay refused the whole state (413): until then send a trimmed one (events / tracks / app details dropped)
    private var diffFor = ""            // the relay the diff is about: a new one (re-pointed) starts from a whole state
    private val pingRun = object : Runnable { override fun run() { if (ws.ping()) h.postDelayed(this, RelayWs.PING_MS) } }
    /** For the state screen / logs: "ws" while the socket is up, else "http". */
    val transport get() = if (ws.open) "ws" else "http"

    fun start() { schedule(0) }
    fun stop() { ws.close(); slow.shutdown(); h.looper.quitSafely() }
    /** Sync now. Never starts a second chain: while a sync runs it only asks the running one to go again at once. */
    fun kick() { h.post {
        // after failures the backoff stays: a kick (every rules change, event, battery step) must not hammer a relay that is down / refusing
        if (fails > 0) return@post
        wsRetryAt = 0L; wsFails = 0; if (inSync) kickAgain = true else schedule(0)
    } }

    /** Release (C8): one last whole-state sync on the relay thread (the `released` event goes out), waits at most [timeoutMs]. */
    fun finalSync(timeoutMs: Long) {
        val l = java.util.concurrent.CountDownLatch(1)
        h.post {
            try {
                val url = store.relayUrl.trim().trimEnd('/'); val token = store.relayToken; val rid = store.relayId
                if (url.startsWith("https://", ignoreCase = true) && token.isNotBlank() && rid.isNotBlank()) {
                    forceSend = true; lastHash = 0; lastFull = 0
                    runCatching { sync(url, rid, token) }.onFailure { Log.w("KidGuard", "final sync", it) }
                }
            } finally { l.countDown() }
        }
        l.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    private fun schedule(ms: Long) { h.removeCallbacks(loopRun); h.postDelayed(loopRun, ms) }

    private fun loop() {
        inSync = true
        val url = store.relayUrl.trim().trimEnd('/'); val token = store.relayToken; val rid = store.relayId
        var busy = false
        var delay = 0L
        if (url.isNotBlank() && token.isNotBlank() && rid.isNotBlank()) {
            if (!url.startsWith("https://", ignoreCase = true)) {
                lastError = "relay needs https://"; delay = 60_000
            } else runCatching { ensureWs(url, rid, token); busy = sync(url, rid, token); fails = 0; runCatching { uploadIcons(url, rid, token) } }.onFailure {
                lastError = it.message ?: it.javaClass.simpleName; Log.w("KidGuard", "relay", it)
                if (RelayTrim.isTooBig(lastError)) bigUntil = System.currentTimeMillis() + BIG_RETRY_MS
                fails++
                // 401 / 409 / 5xx / network: exponential backoff up to 5 min; the next success resets it
                delay = minOf(300_000L, 20_000L shl minOf(fails - 1, 4))
            }
        }
        if (url.isBlank() || token.isBlank() || rid.isBlank()) ws.close()
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

    /** (Re)connects the socket after the first HTTP sync bound the token; backoff 1 min .. 30 min on failures. */
    private fun ensureWs(base: String, rid: String, token: String) {
        val now = System.currentTimeMillis()
        if (ws.open || lastOk == 0L) return
        if (wsUpAt > 0) {   // it was up and is gone now
            if (now - wsUpAt >= 5 * 60_000L) wsFails = 0 else { wsFails++; wsRetryAt = now + minOf(30 * 60_000L, 60_000L shl minOf(wsFails - 1, 5)) }
            wsUpAt = 0
        }
        if (now < wsRetryAt) return
        runCatching { ws.connect(base, rid, token) }.onSuccess {
            wsUpAt = now; forceSend = true
            h.removeCallbacks(pingRun); h.postDelayed(pingRun, RelayWs.PING_MS)
            Log.i("KidGuard", "relay ws up")
        }.onFailure {
            wsFails++; wsRetryAt = now + minOf(30 * 60_000L, 60_000L shl minOf(wsFails - 1, 5))
            Log.i("KidGuard", "relay ws: ${it.message} (retry in ${(wsRetryAt - now) / 1000} s)")
        }
    }

    private fun sync(base: String, rid: String, token: String): Boolean {
        val now = System.currentTimeMillis()
        val big = now < bigUntil
        // state `relayBig: true` = this one was trimmed to fit the relay's 120 KB cap (the page may say so)
        val state = g.currentState().let { if (big) RelayTrim.trim(it) else it }
        val stateText by lazy(LazyThreadSafetyMode.NONE) { state.toString() }   // one serialisation for the coarse and the minute-hash copies
        val viaWs = ws.open
        if (viaWs) {
            // socket up: no message unless something the parent sees changed (time / battery / foreground app ignored),
            // the relay kicked, a command needs its ack, or the idle interval is over
            val coarse = JSONObject(stateText).apply {
                for (k in arrayOf("t", "usedMs", "leftMs", "current", "battery", "charging", "screenOn")) remove(k)
                optJSONObject("net")?.let { for (k in arrayOf("rssi", "bars", "mbps")) it.remove(k) }   // signal wobbles: not a reason to sync
                optJSONArray("apps")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { it.remove("ms"); it.remove("mb") } }
            }.toString().hashCode()
            val on = g.getSystemService(PowerManager::class.java)?.isInteractive == true
            val due = now - lastSent >= if (on) WS_IDLE_ON else WS_IDLE_OFF
            if (!forceSend && acks.length() == 0 && coarse == lastCoarse && !due) return false
            lastCoarse = coarse
        }
        val forced = forceSend; forceSend = false
        // unchanged except time counters => omit the state; a full one at least every 5 min.
        // Per-app ms and usedMs/leftMs tick every sync while an app is open: hash them per minute (one state a minute, not 3).
        val hash = JSONObject(stateText).apply {
            remove("t")
            for (k in arrayOf("usedMs", "leftMs")) if (has(k)) put(k, optLong(k) / 60_000)
            optJSONArray("apps")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { it.put("ms", it.optLong("ms") / 60_000) } }
        }.toString().hashCode()
        val full = hash != lastHash || now - lastFull >= 5 * 60_000L || (viaWs && forced)
        val req = JSONObject().put("acks", acks)
        if ("$base/$rid" != diffFor) { diffFor = "$base/$rid"; diff.reset(); canMerge = false }
        // a whole state until the worker can merge, and every 6 h anyway (a hash clash must not hide a change for long)
        val whole = !canMerge || big || now - lastWhole >= WHOLE_MS
        val sent = if (full) diff.build(state, whole).also { req.put("state", it.first) } else null
        val res = (if (viaWs) ws.request(JSONObject(req.toString())) else null) ?: run {
            val c = open("$base/c/$rid/sync", token).apply { setRequestProperty("Content-Type", "application/json") }
            try { c.outputStream.use { it.write(req.toString().toByteArray()) }; JSONObject(readCapped(c)) } finally { c.disconnect() }
        }
        res.optString("error").takeIf { it.isNotEmpty() }?.let { throw RuntimeException("relay: $it") }   // socket reply = HTTP error body
        lastSent = now
        run {
            if (full) { lastHash = hash; lastFull = now }
            canMerge = res.optInt("merge") == 1
            if (res.optBoolean("full") || !canMerge) { diff.reset(); if (res.optBoolean("full")) lastHash = 0 }   // nothing to merge on / can't merge
            else if (sent != null) { diff.commit(sent.second); if (sent.first === state) lastWhole = now }
            acks = JSONArray()
            // rules first, but a bad rules reply must never stop the commands below (a release/reboot would be lost)
            runCatching { res.optJSONObject("rules")?.let { r ->
                // the worker re-sends the PIN hash it once got with every rules reply: take it only when it is newer
                // (pinAt) than the last one taken, else a later PIN set on LAN / on this phone would be reverted
                val pinAt = r.optLong("pinAt", 0)
                val rules = Rules.fromJson(r).let { x ->
                    if (x.pinHash.isNotBlank() && pinAt > store.relayPinAt) x else x.copy(pinHash = "", pinSalt = "")
                }
                if (rules.rev > store.rules.rev) { g.setRules(rules); if (rules.pinHash.isNotBlank()) store.relayPinAt = pinAt }
            } }.onFailure { Log.w("KidGuard", "relay rules", it) }
            val cmds = res.optJSONArray("cmds") ?: JSONArray()
            for (i in 0 until cmds.length()) {
                val cmd = cmds.optJSONObject(i) ?: continue
                // Guard de-dups by cmd "id"; we ack every command, even one that failed or throws
                // tgSet makes ~6 Telegram calls (Tg.command): off the sync thread, or every other command and the next sync wait for them
                if (cmd.optString("type") == "tgSet") slow.execute { runCatching { g.command(cmd) }.onFailure { Log.w("KidGuard", "cmd", it) } }
                else if (cmd.optString("type") != "relay") runCatching { g.command(cmd) }.onFailure { Log.w("KidGuard", "cmd", it) }
                acks.put(cmd.optString("id"))
            }
            if (res.optInt("files") > 0) Files.pull(g)   // parent -> kid files waiting on the relay (own thread)
            lastOk = System.currentTimeMillis(); lastError = ""
            if (res.optBoolean("live")) startLive(base, rid, token)
            if (res.optBoolean("device")) runCatching { DeviceInfo.upload(g, base, rid, token) }.onFailure { Log.w("KidGuard", "device", it) }
            res.optString("logday").takeIf { HistLogic.validDay(it) }?.let { d -> runCatching { HistDb.upload(g, base, rid, token, d) }.onFailure { Log.w("KidGuard", "logday", it) } }
            return acks.length() > 0 || res.has("rules") || res.optBoolean("full").also { if (it) forceSend = true }
        }
    }

    // ---- app icons: the relay keeps a 48 px WebP per app so the parent page can show them; each app sent once ----
    private fun uploadIcons(base: String, rid: String, token: String) {
        val apps = g.currentState().optJSONArray("apps") ?: return
        val p = g.getSharedPreferences("relayIcons", 0)
        val key = "$base|$rid"; val sent = p.getStringSet(key, emptySet())!!.toMutableSet()
        val here = (0 until apps.length()).mapNotNull { apps.optJSONObject(it)?.optString("pkg")?.takeIf { s -> s.isNotEmpty() } }
        // a removed app is forgotten (the worker drops its icon when its list is full): installed again, it is sent again
        if (here.isNotEmpty() && sent.retainAll(here.toSet())) p.edit().putStringSet(key, sent.toSet()).apply()
        val todo = here.filter { it !in sent }.take(30)
        if (todo.isEmpty()) return
        val body = JSONObject()
        for (pkg in todo) iconWebp(pkg)?.let { body.put(pkg, android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP)) }
        val c = open("$base/c/$rid/icons", token).apply { setRequestProperty("Content-Type", "application/json") }
        try {
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            // 4xx (old worker without /icons, too big) won't get better by retrying every sync; 429 / 5xx will
            val code = c.responseCode
            if (code == 200 || (code in 400..499 && code != 429)) { sent.addAll(todo); p.edit().putStringSet(key, sent).apply() }
        } finally { c.disconnect() }
    }

    private fun iconWebp(pkg: String): ByteArray? {
        val d = runCatching { g.packageManager.getApplicationIcon(pkg) }.getOrNull() ?: return null
        val bmp = android.graphics.Bitmap.createBitmap(48, 48, android.graphics.Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, 48, 48); d.draw(android.graphics.Canvas(bmp))
        @Suppress("DEPRECATION")
        val fmt = if (android.os.Build.VERSION.SDK_INT >= 30) android.graphics.Bitmap.CompressFormat.WEBP_LOSSY else android.graphics.Bitmap.CompressFormat.WEBP
        return java.io.ByteArrayOutputStream().also { bmp.compress(fmt, 80, it) }.toByteArray()
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
            val wait = maxOf(gap, if (gap == 150L) 0 else Acc.frameGapMs() - (System.currentTimeMillis() - t0))
            if (wait > 0) Thread.sleep(wait)
        }
    }

    companion object {
        /** Release (C8): asks the worker to delete everything it keeps for [rid] (`POST /c/<id>/forget`, kid token). Blocking, short timeouts; false = not done. */
        fun forget(base: String, rid: String, token: String): Boolean = runCatching {
            val b = base.trim().trimEnd('/')
            if (!b.startsWith("https://", ignoreCase = true) || rid.isBlank() || token.isBlank()) return false
            val c = (URL("$b/c/$rid/forget").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; connectTimeout = 5_000; readTimeout = 5_000; doOutput = true; instanceFollowRedirects = false
                setRequestProperty("X-KG-Token", token); setRequestProperty("Content-Type", "application/json")
            }
            try { c.outputStream.use { it.write("{}".toByteArray()) }; c.responseCode in 200..299 } finally { c.disconnect() }
        }.getOrDefault(false)

        private const val MAX_REPLY = 256 * 1024
        /** Socket up and nothing changed: a state anyway this often (usage minutes, battery). */
        private const val WHOLE_MS = 6 * 3600_000L
        private const val BIG_RETRY_MS = 30 * 60_000L   // then try the whole state again (apps may have been removed)
        private const val WS_IDLE_ON = 5 * 60_000L
        private const val WS_IDLE_OFF = 15 * 60_000L
        /** The author's shared relay, on by default once a parent has paired (Store.defaultRelay). Also in ui/assets/core.js DEFAULT_RELAY. */
        const val DEFAULT_URL = "https://kidguard.99-hoangtran.workers.dev"
    }
}

/** Pure: what to drop when the relay says the state is over its 120 KB cap (worker `state too big` / HTTP 413). */
object RelayTrim {
    fun isTooBig(msg: String) = msg.contains("state too big") || msg.contains("HTTP 413")

    /** A copy without the bulky lists; apps keep only what the page needs to list them. Flags `relayBig`. */
    fun trim(state: JSONObject): JSONObject {
        val o = JSONObject(state.toString())
        for (k in arrayOf("events", "track")) o.remove(k)
        o.optJSONArray("apps")?.let { a ->
            val slim = JSONArray()
            for (i in 0 until a.length()) a.optJSONObject(i)?.let { x ->
                slim.put(JSONObject().apply { for (k in arrayOf("pkg", "label", "ms", "blocked", "suspended", "protected", "new")) if (x.has(k)) put(k, x.get(k)) })
            }
            o.put("apps", slim)
        }
        return o.put("relayBig", true)
    }
}
