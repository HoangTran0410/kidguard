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
    /** Failed tries in a row and when the first of them was (0 = working): the kid's status line shows both. */
    @Volatile var failN = 0; private set
    @Volatile var failSince = 0L; private set

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
    private var apps: JSONArray? = null
    private var fcmSent = ""
    @Volatile private var pvSent = ""   // "<relay> <pinHash> <pinSalt>" the worker has (sync `pv`)
    @Volatile private var pvRetryAt = 0L; @Volatile private var pvFails = 0; @Volatile private var pvRefused = ""   // refused pv: same key retried 10 min, 1 h, 6 h, then daily (each retry = a parent alert); a new PIN goes at once
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

    /** The CPU stays awake for one whole sync: with the screen off the phone sleeps between our Handler delays (uptime
     *  stops), so a sync that began could stall mid-request and the next one never came (Nam silent 0:09-4:27+, 2026-11-17). */
    private val cpu = runCatching { g.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "kidguard:relay").apply { setReferenceCounted(false) } }.getOrNull()

    /** From the [Watchdog] alarm (fires in Doze too): sync now even while asleep or backing off, and drop a socket that
     *  went quiet while the phone slept (NAT dropped it, the phone never noticed). The wakelock is taken here, on the
     *  alarm's thread, before the post: the CPU may sleep again before the relay thread runs. */
    fun wake() {
        runCatching { cpu?.acquire(WAKE_MS) }
        h.post {
            ws.ping()   // closes a socket that heard nothing for READ_TIMEOUT_MS
            wsRetryAt = 0L; wsFails = 0; forceSend = true
            if (inSync) kickAgain = true else schedule(0)
        }
    }

    /** Release (C8): one last whole-state sync on the relay thread (the `released` event goes out), waits at most [timeoutMs]. */
    fun finalSync(timeoutMs: Long) {
        val l = java.util.concurrent.CountDownLatch(1)
        h.post {
            try {
                val url = base(store.relayUrl); val token = store.relayToken; val rid = store.relayId
                if (url.startsWith("https://", ignoreCase = true) && token.isNotBlank() && rid.isNotBlank()) {
                    forceSend = true; lastHash = 0; lastFull = 0
                    runCatching { sync(url, rid, token) }.onFailure { Log.w("KidGuard", "final sync", it) }
                }
            } finally { l.countDown() }
        }
        l.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    private var netFails = 0

    private fun schedule(ms: Long) { h.removeCallbacks(loopRun); h.postDelayed(loopRun, ms) }

    private fun loop() {
        runCatching { cpu?.acquire(WAKE_MS) }
        try { loopAwake() } finally { runCatching { if (cpu?.isHeld == true) cpu.release() } }
    }

    private fun loopAwake() {
        inSync = true
        val url = base(store.relayUrl); val token = store.relayToken; val rid = store.relayId
        var busy = false
        var delay = 0L
        if (url.isNotBlank() && token.isNotBlank() && rid.isNotBlank()) {
            if (!url.startsWith("https://", ignoreCase = true)) {
                lastError = "relay needs https://"; delay = 60_000
            } else runCatching {
                // a LAN device was removed (relayShared): it may hold the kid token, so the token changes, the relay id stays
                val tk = if (store.relayShared) rekey(url, rid, token) else token
                ensureWs(url, rid, tk); busy = sync(url, rid, tk); fails = 0; netFails = 0; runCatching { uploadIcons(url, rid, tk) } }.onFailure {
                lastError = it.message ?: it.javaClass.simpleName; Log.w("KidGuard", "relay", it)
                // the shared relay has 2 names (workers.dev + relay.kidguard.asia, same worker): a network blocking one
                // (company Wi-Fi filters, DNS) is tried on the other after 2 network failures in a row
                if (it is java.io.IOException && other(store.relayUrl.trim().trimEnd('/')) != null && ++netFails >= 2) {
                    netFails = 0; useAlt = !useAlt; ws.close(); Log.i("KidGuard", "relay: trying ${base(store.relayUrl)}")
                }
                if (RelayTrim.isTooBig(lastError)) bigUntil = System.currentTimeMillis() + BIG_RETRY_MS
                fails++; if (failN++ == 0) failSince = System.currentTimeMillis()
                // 401 / 409 / 5xx / network: exponential backoff up to 5 min; the next success resets it
                delay = minOf(300_000L, 20_000L shl minOf(fails - 1, 4))
            }
        }
        if (url.isBlank() || token.isBlank() || rid.isBlank()) ws.close()
        inSync = false
        if (delay == 0L) {
            val on = g.getSystemService(PowerManager::class.java)?.isInteractive == true
            // right after a command: report back soon, so the parent sees the result
            // screen off + socket up: the relay kicks us through the socket and events kick() us, so this tick only looks for
            // a changed state: every 3 min, not every minute (Oppo 2026-11-18: 16 relay wakelocks in 16 min screen-off)
            delay = if (busy) 3_000 else if (on) 20_000 else if (ws.open) 180_000 else 60_000
        }
        if (kickAgain) { kickAgain = false; delay = 0 }
        schedule(delay)
    }

    /** POST rekey with a new kid token; returns the token to use now (the old one when the worker can't: an older worker = 404, the page still offers renew). */
    private var rekeyAt = 0L
    private fun rekey(base: String, rid: String, token: String): String {
        val now = System.currentTimeMillis()
        if (now - rekeyAt < 30 * 60_000L) return token   // an older worker answers 404 every time: don't add a request to each sync
        rekeyAt = now
        // kept before asking: a reply lost after the worker switched must not leave this phone with a dead token
        val t = store.relayNext.ifBlank { Store.randomHex(24).also { store.relayNext = it } }
        val body = JSONObject().put("token", t).put("drop", JSONArray(store.relayDrop()))
        val res = post(base, rid, token, "rekey", body)?.takeIf { it.optBoolean("ok") }
            ?: post(base, rid, t, "rekey", body)   // the worker took it last time (that reply was lost): the new token works
        if (res?.optBoolean("ok") != true) return token
        rekeyAt = 0L; store.rekeyRelay(t); ws.close(); store.log(R.string.log_relay_rotated)
        Log.i("KidGuard", "relay token changed (a device was removed)")
        return t
    }

    private fun open(url: String, token: String, timeout: Int = 15_000) =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 10_000; readTimeout = timeout; doOutput = true
            instanceFollowRedirects = false
            setRequestProperty("X-KG-Token", token)
        }

    private fun readCapped(c: HttpURLConnection): String {
        if (c.responseCode != 200) {
            // Cloudflare's own error page ("error code: 1101") = the relay worker itself is down (INCIDENT 2026-11-17)
            val head = runCatching { c.errorStream?.use { val b = ByteArray(200); val n = it.read(b); if (n > 0) String(b, 0, n, Charsets.UTF_8) else "" } }.getOrNull().orEmpty()
            val cf = Regex("error code: (\\d{3,4})", RegexOption.IGNORE_CASE).find(head)?.groupValues?.get(1)
            throw RuntimeException("HTTP ${c.responseCode}" + (cf?.let { " · Cloudflare $it" } ?: ""))
        }
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
        apps = state.optJSONArray("apps")
        val stateText by lazy(LazyThreadSafetyMode.NONE) { state.toString() }   // one serialisation for the coarse and the minute-hash copies
        val viaWs = ws.open
        if (viaWs) {
            // socket up: no message unless something the parent sees changed (time / battery / foreground app ignored),
            // the relay kicked, a command needs its ack, or the idle interval is over
            val coarse = JSONObject(stateText).apply {
                // relayOk = this phone's last good sync: it changes on every sync, so keeping it made each tick a sync (2026-11-17: 1 msg / 20 s)
                for (k in arrayOf("t", "usedMs", "leftMs", "current", "battery", "charging", "screenOn", "relayOk")) remove(k)
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
            remove("t"); remove("relayOk")   // the worker fills relayOk from when it last heard the phone
            for (k in arrayOf("usedMs", "leftMs")) if (has(k)) put(k, optLong(k) / 60_000)
            optJSONArray("apps")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { it.put("ms", it.optLong("ms") / 60_000) } }
        }.toString().hashCode()
        val full = hash != lastHash || now - lastFull >= 5 * 60_000L || (viaWs && forced)
        val req = JSONObject().put("acks", acks).put("ai", store.aiHash)   // the worker knows the AI token by this hash (AiScope)
        // push wake (KidPush): the worker keeps this phone's FCM token and wakes it when it goes quiet. Sent until one sync took it.
        val fcm = KidPush.token
        if (fcm.isNotEmpty() && "$base/$rid $fcm" != fcmSent) req.put("fcm", fcm)
        // the family PIN hash (docs/protocol.md "Parents"): the worker checks a new parent's PIN / a browser unlock with it. Sent when it changes
        val pr = store.rules; val pvKey = "$base/$rid ${pr.pinHash} ${pr.pinSalt}"
        if (pvKey != pvSent && (pvKey != pvRefused || now >= pvRetryAt)) {
            req.put("pv", if (pr.pinHash.isBlank()) "" else JSONObject().put("h", pr.pinHash).put("s", pr.pinSalt))
            // pvo: the hash the worker last said "ok" to = the proof that this is the phone (the kid token alone can't change it)
            RelayPv.proof(store.relayPvOk, rid)?.let { req.put("pvo", it) }
        }
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
        if (req.has("fcm")) fcmSent = "$base/$rid $fcm"
        if (req.has("pv")) {
            pvSent = pvKey
            when (res.optString("pv")) {
                "ok" -> { store.relayPvOk = RelayPv.stamp(rid, pr.pinHash); pvFails = 0; pvRefused = "" }
                "refused" -> {
                    if (pvRefused != pvKey) pvFails = 0
                    pvSent = ""; pvRefused = pvKey; pvRetryAt = now + RelayPv.retryMs(pvFails++)
                    Log.w("KidGuard", "relay refused the PIN hash (no proof): retry in ${RelayPv.retryMs(pvFails - 1) / 60_000} min")
                }
            }
        }
        run {
            if (full) { lastHash = hash; lastFull = now }
            canMerge = res.optInt("merge") == 1
            if (res.optBoolean("full") || !canMerge) { diff.reset(); if (res.optBoolean("full")) { lastHash = 0; pvSent = "" } }   // full = the worker lost its copy (pv too)   // nothing to merge on / can't merge
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
                if (rules.rev > store.rules.rev) { g.setRules(rules, by = r.optString("by")); if (rules.pinHash.isNotBlank()) store.relayPinAt = pinAt }
            } }.onFailure { Log.w("KidGuard", "relay rules", it) }
            val cmds = res.optJSONArray("cmds") ?: JSONArray()
            for (i in 0 until cmds.length()) {
                val cmd = Wire.decode(cmds.optJSONObject(i) ?: continue)   // short relay code -> command name
                // Guard de-dups by cmd "id"; we ack every command, even one that failed or throws
                // tgSet makes ~6 Telegram calls (Tg.command): off the sync thread, or every other command and the next sync wait for them
                if (cmd.optString("type") == "tgSet") slow.execute { runCatching { g.command(cmd) }.onFailure { Log.w("KidGuard", "cmd", it) } }
                else if (cmd.optString("type") != "relay") runCatching { g.command(cmd) }.onFailure { Log.w("KidGuard", "cmd", it) }
                acks.put(cmd.optString("id"))
            }
            if (res.optInt("files") > 0) Files.pull(g)   // parent -> kid files waiting on the relay (own thread)
            lastOk = System.currentTimeMillis(); lastError = ""; failN = 0; failSince = 0L
            if (lastOk - store.relayOkAt > 60_000) store.relayOkAt = lastOk
            if (res.optBoolean("live")) { liveWs = res.optInt("lws") == 1; startLive(base, rid, token) }
            if (res.optBoolean("device")) runCatching { DeviceInfo.upload(g, base, rid, token) }.onFailure { Log.w("KidGuard", "device", it) }
            res.optJSONObject("call")?.let { c -> runCatching { Call.fromRelay(g, c, base, rid, token) }.onFailure { Log.w("KidGuard", "call", it) } }
            res.optString("logday").takeIf { HistLogic.validDay(it) }?.let { d -> runCatching { HistDb.upload(g, base, rid, token, d) }.onFailure { Log.w("KidGuard", "logday", it) } }
            return acks.length() > 0 || res.has("rules") || res.optBoolean("full").also { if (it) forceSend = true }
        }
    }

    // ---- app icons: the relay keeps a 48 px WebP per app so the parent page can show them; each app sent once ----
    private fun uploadIcons(base: String, rid: String, token: String) {
        val apps = this.apps ?: return   // the list sync() just built: a second state build every tick cost CPU (review 2026-11-18)
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

    @Volatile private var liveWs = false        // the worker takes frames on the socket (sync reply `lws`)
    @Volatile private var liveOk = 0L           // last {"t":"live","live":true} on the socket (the page says it every 15 s)
    @Volatile private var liveStop = false
    private val inputRun = java.util.concurrent.Executors.newSingleThreadExecutor()

    private fun liveLoop(base: String, rid: String, token: String) {
        if (liveWs && ws.open && wsLiveLoop()) return
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
            if (!live) { Acc.stopViewing(g); return }   // parent pressed Stop / stopped asking: notice + projection off now
            val wait = maxOf(gap, if (gap == 150L) 0 else Acc.frameGapMs() - (System.currentTimeMillis() - t0))
            if (wait > 0) Thread.sleep(wait)
        }
    }

    /**
     * Frames over the socket (0.9.61): one binary message each, sent only when the picture changed (or every
     * [LiveLogic.RESEND_MS] so a new viewer gets one); taps come back on the socket at once. Ends when the worker says
     * live:false (the page left / Stop) or no live:true came for [LiveLogic.LIVE_OK_MS]. False = the socket dropped: HTTP loop.
     */
    private fun wsLiveLoop(): Boolean {
        liveOk = System.currentTimeMillis(); liveStop = false
        ws.onLive = { on -> if (on) liveOk = System.currentTimeMillis() else liveStop = true }
        ws.onInputs = { a -> inputRun.execute { for (i in 0 until minOf(a.length(), 50)) runCatching { Acc.input(a.getJSONObject(i)) } } }
        val crc = java.util.zip.CRC32()
        var lastSig = -1L; var lastAt = 0L; var lastErr = ""
        try {
            while (true) {
                val t0 = System.currentTimeMillis()
                if (liveStop || t0 - liveOk > LiveLogic.LIVE_OK_MS) { Acc.stopViewing(g); return true }
                if (!ws.open) return false
                val f = Acc.capture(720, 50)
                var gap = 0L
                if (f != null) {
                    crc.reset(); crc.update(f.jpeg); val sig = crc.value
                    if (LiveLogic.send(sig != lastSig, t0 - lastAt)) {
                        if (!ws.sendBinary(LiveLogic.wsFrame(f.w, f.h, "", f.jpeg))) return false
                        lastSig = sig; lastAt = t0; lastErr = ""
                    }
                } else {
                    val err = Acc.lastError
                    gap = if (err == "busy") 150 else 1_000
                    if (err != "busy" && LiveLogic.send(err != lastErr, t0 - lastAt)) {
                        if (!ws.sendBinary(LiveLogic.wsFrame(0, 0, err.take(60), null))) return false
                        lastErr = err; lastAt = t0; lastSig = -1
                    }
                }
                val wait = maxOf(gap, if (gap == 150L) 0 else Acc.frameGapMs() - (System.currentTimeMillis() - t0))
                if (wait > 0) Thread.sleep(wait)
            }
        } finally { ws.onLive = null; ws.onInputs = null }
    }

    companion object {
        /** Wakelock cap for one sync (HTTP 10 s connect + 15 s read, socket connect): released as soon as the sync ends. */
        private const val WAKE_MS = 60_000L
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

        /** [forget] with a second chance: the other name of the shared relay (some networks filter *.workers.dev), then the first again after 2 s. */
        fun forgetRetry(base: String, rid: String, token: String): Boolean {
            if (forget(base, rid, token)) return true
            val b = base.trim().trimEnd('/')
            if (b.equals(DEFAULT_URL, true) && forget(ALT_URL, rid, token)) return true
            runCatching { Thread.sleep(2_000) }
            return forget(base, rid, token)
        }

        /** POST /c/<rid>/<action> with the kid token; the JSON reply on 2xx, null otherwise. Blocking, short timeouts. */
        fun post(base: String, rid: String, token: String, action: String, body: JSONObject): JSONObject? = runCatching {
            val b = base.trim().trimEnd('/')
            if (!b.startsWith("https://", ignoreCase = true) || rid.isBlank() || token.isBlank()) return null
            val c = (URL("$b/c/$rid/$action").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; connectTimeout = 6_000; readTimeout = 8_000; doOutput = true; instanceFollowRedirects = false
                setRequestProperty("X-KG-Token", token); setRequestProperty("Content-Type", "application/json")
            }
            try {
                c.outputStream.use { it.write(body.toString().toByteArray()) }
                if (c.responseCode !in 200..299) null else JSONObject(c.inputStream.use { it.readBytes().toString(Charsets.UTF_8).take(4096) })
            } finally { c.disconnect() }
        }.getOrNull()

        /** A relay parent token of its own for a LAN page (`relay {mint:true}`, docs/protocol.md "Parents"): locked on the worker
         * until the family PIN opens it. [drop] = the id minted for the same LAN device before. -> (token, id) or null. */
        fun mint(base: String, rid: String, token: String, name: String, kind: String, drop: String): Pair<String, String>? {
            val j = post(base, rid, token, "mint", JSONObject().put("name", name.take(24)).put("kind", kind).apply { if (drop.isNotEmpty()) put("drop", drop) }) ?: return null
            val t = j.optString("token"); val id = j.optString("id")
            return if (Regex("^[0-9a-f]{48}$").matches(t) && Regex("^[0-9a-f]{12}$").matches(id)) t to id else null
        }

        private const val MAX_REPLY = 256 * 1024
        /** Socket up and nothing changed: a state anyway this often (usage minutes, battery). */
        private const val WHOLE_MS = 6 * 3600_000L
        private const val BIG_RETRY_MS = 30 * 60_000L   // then try the whole state again (apps may have been removed)
        private const val WS_IDLE_ON = 5 * 60_000L
        private const val WS_IDLE_OFF = 15 * 60_000L
        /** The author's shared relay, on by default once a parent has paired (Store.defaultRelay). Also in ui/assets/core.js DEFAULT_RELAY. */
        const val DEFAULT_URL = "https://kg-relay.99-hoangtran.workers.dev"
        /** the killed worker (docs/decisions/remote.md 2026-11-17): Store.defaultRelay moves a phone off it, same id/token */
        const val OLD_URL = "https://kidguard.99-hoangtran.workers.dev"
        /** The same worker on our own domain (2026-11-20): some networks filter *.workers.dev. Never stored: [base] swaps to it. */
        const val ALT_URL = "https://relay.kidguard.asia"
        /** True = the other name of the shared relay answers, this one did not (set by the sync loop, read by Files etc.). */
        @Volatile var useAlt = false
        fun other(u: String): String? = when (u.trim().trimEnd('/')) { DEFAULT_URL -> ALT_URL; ALT_URL -> DEFAULT_URL; else -> null }
        /** The address to call for a stored relay url: the other name of the shared relay while [useAlt]. */
        fun base(stored: String): String { val u = stored.trim().trimEnd('/'); return if (useAlt) other(u) ?: u else u }
    }
}

/** Pure: why the relay can't be reached, from [Relay.lastError], for the kid's status line (owner 2026-11-20). */
object RelayWhy {
    /** dns / net / tls / auth / "" (unknown: the raw error is shown). */
    fun kind(err: String): String {
        val e = err.lowercase()
        return when {
            "resolve host" in e || "no address associated" in e || "unknownhost" in e -> "dns"
            "ssl" in e || "certificate" in e || "handshake" in e || "trust anchor" in e -> "tls"
            Regex("http 40[13]|not paired|bad token|relay: (forbidden|unauthorized)").containsMatchIn(e) -> "auth"
            "timeout" in e || "timed out" in e || "failed to connect" in e || "unreachable" in e || "refused" in e ||
                "connection reset" in e || "econn" in e || "enetunreach" in e || "socket" in e -> "net"
            else -> ""
        }
    }
}

/** Pure: what to drop when the relay says the state is over its 120 KB cap (worker `state too big` / HTTP 413). */
object RelayTrim {
    fun isTooBig(msg: String) = msg.contains("state too big") || msg.contains("HTTP 413")

    /** A copy without the bulky lists; apps keep only what the page needs to list them. Flags `relayBig`. */
    fun trim(state: JSONObject): JSONObject {
        val o = JSONObject(state.toString())
        for (k in arrayOf("track", "calls")) o.remove(k)
        // the newest 10 events stay: they are what pushes "check-in" / chat / tamper alerts to the parent (review 2026-11-18)
        o.optJSONArray("events")?.let { e -> if (e.length() > 10) o.put("events", JSONArray().apply { for (i in e.length() - 10 until e.length()) put(e.get(i)) }) }
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

/** Pure: the `pvo` proof of a PIN-hash change (docs/protocol.md "Parents"). [ok] = "<relay id> <hash>" the worker said ok to. */
object RelayPv {
    fun stamp(rid: String, hash: String) = "$rid $hash"
    /** Wait before re-sending a refused pv (each try alerts every parent; the worker also throttles to 10 min). */
    fun retryMs(fails: Int): Long = when (fails) { 0 -> 10 * 60_000L; 1 -> 3600_000L; 2 -> 6 * 3600_000L; else -> 24 * 3600_000L }
    /** The hash to prove with, or null when the worker never said ok on this relay id (a first pv needs none). */
    fun proof(ok: String, rid: String): String? = ok.split(' ').takeIf { it.size == 2 && it[0] == rid && it[1].length == 64 }?.get(1)
}
