package dev.kidguard.parent

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.TimeUnit

class Req(val method: String, val path: String, val rawQuery: String, val query: Map<String, String>, val headers: Map<String, String>, val body: ByteArray,
          /** Set for a big `/api/files` / relay `/file` body that was NOT read yet: [bodyLen] bytes still on [bodyIn]. */
          val bodyIn: InputStream? = null, val bodyLen: Long = 0) {
    fun header(n: String) = headers[n.lowercase()]
}

/** [stream] set: no Content-Length; the body is what [stream] writes (the connection then closes). */
class Res(val code: Int, val type: String, val body: ByteArray, val headers: Map<String, String> = emptyMap(), val html: Boolean = false,
          val stream: ((java.io.OutputStream) -> Unit)? = null, val length: Long = -1) {
    companion object {
        fun json(s: String, code: Int = 200) = Res(code, "application/json; charset=utf-8", s.toByteArray())
        fun text(s: String, code: Int) = Res(code, "text/plain; charset=utf-8", s.toByteArray())
    }
}

/**
 * The parent app's local server on 127.0.0.1: serves ui/index.html (+ its ui/assets/ files) and forwards `/k/<t>/<rest>` to the child
 * (LAN `http://ip:8770` or relay `https://host`), caching state snapshots (see docs/protocol.md "Parent app").
 */
class Proxy(private val ctx: Context, val port: Int) {
    val secret: String = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private var server: ServerSocket? = null
    @Volatile private var stopped = false
    // long-polls (relay frames) hold a thread up to 40 s, so many threads, no queue
    private val pool = ThreadPoolExecutor(2, 32, 30, TimeUnit.SECONDS, SynchronousQueue())
    private val lastWrite = ConcurrentHashMap<String, Long>()
    private val cacheDir = Store.dir(ctx, "cache")
    private val histDir = Store.dir(ctx, "history")

    fun start(): Boolean {
        val s = try { ServerSocket(port, 50, InetAddress.getByName("127.0.0.1")) } catch (e: Exception) { Log.e("KGParent", "bind $port", e); return false }
        server = s
        Thread({
            while (!stopped) {
                try {
                    val c = s.accept()
                    try { pool.execute { serve(c) } } catch (e: Exception) { runCatching { c.close() } }
                } catch (e: SocketException) { break } catch (e: Exception) { Log.e("KGParent", "accept", e) }
            }
        }, "kg-proxy").start()
        return true
    }

    fun stop() { stopped = true; runCatching { server?.close() }; pool.shutdownNow() }

    // ---- routing ----

    private fun handle(r: Req): Res {
        val host = r.header("host") ?: ""
        if (host != "127.0.0.1:$port") return Res.text("bad host", 403)   // DNS-rebinding guard
        if (r.method == "GET" && r.path == "/s/$secret") {
            return Res(302, "text/plain", ByteArray(0), mapOf("Location" to "/", "Set-Cookie" to "kgs=$secret; Path=/; HttpOnly; SameSite=Strict"))
        }
        val cookie = (r.header("cookie") ?: "").split(";").map { it.trim() }
        if (!cookie.any { it == "kgs=$secret" }) return Res.text("forbidden", 403)
        return when {
            r.method == "GET" && (r.path == "/" || r.path == "/index.html") -> index()
            r.method == "GET" && Logic.uiAsset(r.path) != null -> ui(Logic.uiAsset(r.path)!!)
            r.method == "GET" && r.path == "/app/history/days" -> history(r, false)
            r.method == "GET" && r.path == "/app/history/day" -> history(r, true)
            r.path.startsWith("/k/") -> forward(r)
            else -> Res.text("not found", 404)
        }
    }

    private fun index(): Res = try {
        Res(200, "text/html; charset=utf-8", ctx.assets.open("index.html").use { it.readBytes() }, html = true)
    } catch (e: Exception) { Res.text("no page", 500) }

    // the page's scripts + style (ui/assets/)
    private fun ui(a: Pair<String, String>): Res = try {
        Res(200, a.second, ctx.assets.open(a.first).use { it.readBytes() })
    } catch (e: java.io.IOException) { Res.text("not found", 404) }

    private fun forward(r: Req): Res {
        val (target, rest) = Logic.parseKidPath(r.path) ?: return Res.json("{\"error\":\"bad target\"}", 400)
        if (!Logic.allowedRequest(Store.kids(ctx), target, r.method, rest)) return Res.json("{\"error\":\"unknown target\"}", 403)
        val url = target + rest + (if (r.rawQuery.isNotEmpty()) "?" + r.rawQuery else "")
        val fwd = HashMap<String, String>()
        for (h in listOf("X-KG-Token", "X-KG-W", "X-KG-H", "Content-Type")) r.header(h)?.let { fwd[h] = it }
        if (Logic.isFilesPath(rest)) return filesForward(r, url, fwd)
        if (r.method == "GET" && rest == "/api/listen") return listen(url, fwd)
        val cacheable = Logic.isStatePath(r.method, rest)
        // state answers in < 1 s; a hung phone or half-dead Wi-Fi accepts the TCP connect and then stalls,
        // so don't wait the long-poll timeout (40 s, for screen frames) before falling back to the snapshot
        val res = Net.call(r.method, url, fwd, r.body, if (cacheable) 8_000 else 40_000)
        val key = Store.sha(target + rest)
        if (cacheable && res.status == 200 && isJson(res.body)) store(key, target, rest, res.body)
        if (cacheable && Logic.useCache(res.status, res.status == null)) {
            val snap = load(key)
            if (snap != null) return Res(200, "application/json; charset=utf-8", snap.second, mapOf("X-KG-Cached" to snap.first.toString()))
            return Res.json("{\"error\":\"offline\"}", 502)
        }
        if (res.status == null) return Res.json("{\"error\":\"offline\"}", 502)
        val h = HashMap(res.headers)
        return Res(res.status, res.type, res.body, h)
    }

    /** Shared-files routes: request body streamed up, a LAN download streamed down (never buffered; docs/protocol.md "Shared files"). */
    private fun filesForward(r: Req, url: String, fwd: Map<String, String>): Res {
        var c: java.net.HttpURLConnection? = null
        try {
            c = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = r.method; connectTimeout = 6_000; readTimeout = 120_000; instanceFollowRedirects = false; useCaches = false
                fwd.forEach { (k, v) -> setRequestProperty(k, v) }
                if (r.method != "GET" && r.method != "HEAD" && (r.bodyIn != null || r.body.isNotEmpty())) {
                    doOutput = true
                    setFixedLengthStreamingMode(if (r.bodyIn != null) r.bodyLen else r.body.size.toLong())
                    outputStream.use { o ->
                        if (r.bodyIn == null) o.write(r.body) else {
                            val buf = ByteArray(32 * 1024); var left = r.bodyLen
                            while (left > 0) { val n = r.bodyIn.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (n < 0) throw java.io.IOException("short body"); o.write(buf, 0, n); left -= n }
                        }
                    }
                }
            }
            val code = c.responseCode
            val type = c.contentType ?: "application/octet-stream"
            if (code == 200 && r.method == "GET" && c.contentLengthLong >= 0 && rest0(r).startsWith("/api/files/")) {
                val conn = c; c = null
                return Res(200, type, ByteArray(0), stream = { out ->
                    try { conn.inputStream.use { ins -> val buf = ByteArray(32 * 1024); while (true) { val n = ins.read(buf); if (n < 0) break; out.write(buf, 0, n) }; out.flush() } }
                    catch (e: Exception) { /* either side closed */ } finally { runCatching { conn.disconnect() } }
                }, length = conn.contentLengthLong)
            }
            val bytes = (if (code >= 400) c.errorStream else c.inputStream)?.use { it.readBytes() } ?: ByteArray(0)
            return Res(code, type, bytes)
        } catch (e: Exception) {
            return Res.json("{\"error\":\"offline\"}", 502)
        } finally { runCatching { c?.disconnect() } }
    }
    private fun rest0(r: Req) = Logic.parseKidPath(r.path)?.second ?: ""

    /** The child's mic stream (LAN / Tailscale): passed through as it comes, never cached. */
    private fun listen(url: String, fwd: Map<String, String>): Res {
        val c = try {
            (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 6_000; readTimeout = 20_000; instanceFollowRedirects = false; useCaches = false
                fwd.forEach { (k, v) -> setRequestProperty(k, v) }
            }.also { it.responseCode }
        } catch (e: Exception) { return Res.json("{\"error\":\"offline\"}", 502) }
        if (c.responseCode != 200) {
            val b = runCatching { c.errorStream?.use { it.readBytes() } }.getOrNull() ?: ByteArray(0)
            c.disconnect()
            return Res(c.responseCode, c.contentType ?: "application/json", b)
        }
        return Res(200, c.contentType ?: "audio/aac", ByteArray(0), stream = { out ->
            try { c.inputStream.use { ins -> val buf = ByteArray(4096); while (true) { val n = ins.read(buf); if (n < 0) break; out.write(buf, 0, n); out.flush() } } }
            catch (e: Exception) { /* either side closed */ } finally { runCatching { c.disconnect() } }
        })
    }

    private fun isJson(b: ByteArray) = try { JSONObject(String(b, Charsets.UTF_8)); true } catch (e: Exception) { false }

    // ---- snapshots ----

    @Volatile private var lastGuard = 0L

    private fun store(key: String, target: String, rest: String, body: ByteArray) {
        val now = System.currentTimeMillis()
        if (now - (lastWrite[key] ?: 0L) < 10_000) return   // the page polls every few seconds: write at most every 10 s
        lastWrite[key] = now
        try {
            val o = JSONObject().put("t", now).put("body", JSONObject(String(body, Charsets.UTF_8)))
            File(cacheDir, "$key.json").writeText(o.toString())
            guard(now)
            val d = File(histDir, key).apply { mkdirs() }
            File(d, Logic.dayKey(now) + ".json").writeText(o.toString())   // last snapshot of the day wins
        } catch (e: Exception) { Log.w("KGParent", "store", e) }
    }

    /** Retention setting + free-space guard over the history of all kids (at most once a minute). */
    private fun guard(now: Long) {
        if (now - lastGuard < 60_000) return
        lastGuard = now
        val kids = histDir.listFiles()?.filter { it.isDirectory } ?: return
        fun files(day: String) = kids.map { File(it, "$day.json") }.filter { it.exists() }
        val all = kids.flatMap { it.list()?.map { n -> n.removeSuffix(".json") } ?: emptyList() }.toSortedSet().toList()
        val drop = LinkedHashSet(Logic.retentionDrop(all, Store.historyDays(ctx), Logic.dayKey(now)))
        val rest = all.filter { it !in drop }.map { d -> d to files(d).sumOf { it.length() } }
        drop.addAll(Logic.storageDrop(rest, histDir.usableSpace))
        for (d in drop) files(d).forEach { it.delete() }
    }

    /** (storedAtMs, body bytes) */
    private fun load(key: String): Pair<Long, ByteArray>? = try {
        val o = JSONObject(File(cacheDir, "$key.json").readText())
        o.getLong("t") to o.getJSONObject("body").toString().toByteArray()
    } catch (e: Exception) { null }

    private val DAY = Regex("^\\d{4}-\\d{2}-\\d{2}$")

    private fun history(r: Req, one: Boolean): Res {
        val target = Logic.decodeTarget(r.query["k"] ?: "") ?: return Res.json("{\"error\":\"bad target\"}", 400)
        if (!Logic.allowedTarget(Store.kids(ctx), target)) return Res.json("{\"error\":\"unknown target\"}", 403)
        val rest = Logic.normRest(r.query["p"] ?: "api/state")
        if (!Logic.isStatePath("GET", rest)) return Res.json("{\"error\":\"bad path\"}", 400)
        val d = File(histDir, Store.sha(target + rest))
        if (!one) {
            val days = (d.list() ?: emptyArray()).map { it.removeSuffix(".json") }.filter { DAY.matches(it) }.sortedDescending()
            return Res.json(JSONArray(days).toString())
        }
        val day = r.query["d"] ?: ""
        if (!DAY.matches(day)) return Res.json("{\"error\":\"bad day\"}", 400)
        val f = File(d, "$day.json")
        if (!f.exists()) return Res.json("{\"error\":\"none\"}", 404)
        return try {
            val o = JSONObject(f.readText())
            Res.json(JSONObject().put("day", day).put("t", o.getLong("t")).put("body", o.getJSONObject("body")).toString())
        } catch (e: Exception) { Res.json("{\"error\":\"none\"}", 404) }
    }

    // ---- tiny HTTP/1.1 server (one request per connection), style of the child's Http.kt ----

    private fun serve(c: Socket) = c.use {
        it.soTimeout = 60_000
        val killer = Thread({ try { Thread.sleep(10_000); runCatching { it.close() } } catch (_: InterruptedException) {} }, "kg-deadline").apply { isDaemon = true; start() }
        val res = try {
            val req = try { read(BufferedInputStream(it.getInputStream())) } finally { killer.interrupt() } ?: return
            handle(req)
        } catch (e: TooBig) { Res.text("too big", 413) } catch (e: Exception) {
            Log.w("KGParent", "req", e); Res.text("error", 500)
        }
        val head = "HTTP/1.1 ${res.code} ${reason(res.code)}\r\nContent-Type: ${res.type}\r\n" +
            (if (res.stream == null) "Content-Length: ${res.body.size}\r\n" else if (res.length >= 0) "Content-Length: ${res.length}\r\n" else "") +
            "Cache-Control: no-store\r\nConnection: close\r\nX-Content-Type-Options: nosniff\r\n" +
            (if (res.html) "X-Frame-Options: DENY\r\nContent-Security-Policy: frame-ancestors 'none'; connect-src 'self' blob:; img-src 'self' data: blob: https://tile.openstreetmap.de https://tile.openstreetmap.org\r\n" else "") +
            res.headers.entries.joinToString("") { (k, v) -> "$k: ${v.replace('\r', ' ').replace('\n', ' ')}\r\n" } + "\r\n"
        val out = it.getOutputStream()
        val s = res.stream
        if (s == null) { out.write(head.toByteArray()); out.write(res.body); out.flush(); return }
        // the stream holds an upstream connection: run it even if the WebView already left, so it closes that
        val sink = try { out.write(head.toByteArray()); out.flush(); out }
            catch (e: java.io.IOException) { object : java.io.OutputStream() { override fun write(b: Int) = throw java.io.IOException("closed") } }
        s(sink)
    }

    private class TooBig : Exception()

    private fun line(i: InputStream): String? {
        val b = ByteArrayOutputStream()
        while (true) {
            val x = i.read()
            if (x == -1) return if (b.size() == 0) null else b.toString("UTF-8")
            if (x == '\n'.code) return b.toString("UTF-8").trimEnd('\r')
            b.write(x)
            if (b.size() > 8192) throw TooBig()
        }
    }

    private fun read(i: InputStream): Req? {
        val first = line(i) ?: return null
        val parts = first.split(" ")
        if (parts.size < 2) return null
        val headers = HashMap<String, String>()
        var n = 0
        while (true) {
            val l = line(i) ?: break
            if (l.isEmpty()) break
            if (++n > 100) throw TooBig()
            val k = l.substringBefore(":", ""); if (k.isNotEmpty()) headers[k.trim().lowercase()] = l.substringAfter(":").trim()
        }
        val len = headers["content-length"]?.toLongOrNull() ?: 0
        val target = parts[1]
        val path = target.substringBefore("?")
        val big = len > 512 * 1024
        if (big && !(len <= Logic.MAX_FILE_BODY && Logic.parseKidPath(path)?.second?.let { Logic.isFilesPath(it) } == true)) throw TooBig()
        val body = if (big) ByteArray(0) else ByteArray(len.toInt())
        var got = 0
        while (!big && got < len) { val r = i.read(body, got, len.toInt() - got); if (r < 0) break; got += r }
        val raw = target.substringAfter("?", "")
        val query = raw.split("&").filter { it.contains("=") }.associate {
            URLDecoder.decode(it.substringBefore("="), "UTF-8") to URLDecoder.decode(it.substringAfter("="), "UTF-8")
        }
        return Req(parts[0].uppercase(), path, raw, query, headers, body, if (big) i else null, if (big) len else 0)
    }

    private fun reason(c: Int) = when (c) { 200 -> "OK"; 204 -> "No Content"; 302 -> "Found"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"; 413 -> "Payload Too Large"; 502 -> "Bad Gateway"; else -> "Error" }
}
