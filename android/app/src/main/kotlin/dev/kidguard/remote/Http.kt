package dev.kidguard

import android.util.Log
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.BindException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** [input] set (PUT /api/files/..., see Files.kt): the body is NOT read; [length] bytes wait on [input] for the handler. */
class Req(val method: String, val path: String, val query: Map<String, String>, val headers: Map<String, String>, val body: ByteArray,
          val input: InputStream? = null, val length: Long = -1) {
    val text get() = String(body, Charsets.UTF_8)
    fun header(n: String) = headers[n.lowercase()]
}

/** [stream] set: no Content-Length, the body is whatever [stream] writes until it returns (the connection then closes). */
class Res(val code: Int, val type: String, val body: ByteArray, val headers: Map<String, String> = emptyMap(),
          val stream: ((java.io.OutputStream) -> Unit)? = null) {
    companion object {
        fun json(s: String, code: Int = 200) = Res(code, "application/json; charset=utf-8", s.toByteArray())
        fun text(s: String, code: Int) = Res(code, "text/plain; charset=utf-8", s.toByteArray())
    }
}

/** A tiny HTTP/1.1 server (one request per connection), enough for the parent's page on the LAN. */
class Http(private val tag: Any, private val handler: (Req) -> Res) {
    private companion object { const val LONG_SLOTS = 6; const val MAX_PER_HOST = 14 }
    private var server: ServerSocket? = null
    @Volatile private var stopped = false
    // 16 workers (idle ones time out) + 32 queued. Long-running routes (listen, screen, file bodies) may hold at most
    // LONG_SLOTS of them, so /state and the other short calls always find a free thread; an over-limit long call gets 503 busy.
    private val pool = ThreadPoolExecutor(16, 16, 20L, TimeUnit.SECONDS, ArrayBlockingQueue(32)).apply { allowCoreThreadTimeOut(true) }
    private val longSlots = java.util.concurrent.Semaphore(LONG_SLOTS)
    private fun isLong(r: Req) = r.input != null || r.path == "/api/listen" || r.path == "/api/screen" ||
        (r.method == "GET" && r.path.startsWith("/api/files/"))

    constructor(g: Guard, api: Api) : this(g, api::handle)

    // Open connections per remote address: one silent host opening sockets must not take all 16 workers + the queue
    // from the parent's page (audit L10). The page itself uses a handful (6 long slots + short calls).
    private val perHost = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()
    private fun release(host: String) { perHost[host]?.let { if (it.decrementAndGet() <= 0) perHost.remove(host, it) } }

    fun start(port: Int) {
        Thread({
            while (!stopped) {
                var s: ServerSocket? = null
                try {
                    s = ServerSocket(port).also { server = it }
                    while (!s.isClosed) {
                        val c = s.accept()
                        c.soTimeout = 15_000   // also for a connection still waiting in the queue
                        val host = c.inetAddress?.hostAddress ?: "?"
                        if (perHost.getOrPut(host) { java.util.concurrent.atomic.AtomicInteger() }.incrementAndGet() > MAX_PER_HOST) { release(host); busy(c); continue }
                        try { pool.execute { try { safe(c) } finally { release(host) } } } catch (e: RejectedExecutionException) { release(host); busy(c) }
                    }
                } catch (e: BindException) {
                    Log.w("KidGuard", "http bind $port failed, retry in 2s", e)
                    try { Thread.sleep(2000) } catch (_: InterruptedException) { return@Thread }
                } catch (e: SocketException) { /* stopped, or an accept error: re-bind below */ } catch (e: Exception) { Log.e("KidGuard", "http", e) }
                finally {
                    // close before binding again: a still-bound old socket made every new bind fail for good (audit M4)
                    runCatching { s?.close() }
                    if (!stopped) try { Thread.sleep(500) } catch (_: InterruptedException) { return@Thread }
                }
            }
        }, "http").start()
    }

    /** Pool and queue full: answer 503 instead of closing the socket silently (the page then retries instead of "network error"). */
    private fun busy(c: Socket) {
        runCatching { c.soTimeout = 1000; c.getOutputStream().apply { write("HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nRetry-After: 2\r\nConnection: close\r\n\r\n".toByteArray()); flush() } }
        runCatching { c.close() }
    }

    fun stop() { stopped = true; runCatching { server?.close() }; pool.shutdownNow() }

    /** A client gone mid-write (page closed, Wi-Fi dropped) throws "Broken pipe" here: uncaught on a pool thread it
     *  killed the whole app (Oppo dropbox, 0.9.5), which then looked like a crash + restart to the child. */
    private fun safe(c: Socket) {
        try { serve(c) } catch (e: java.io.IOException) { Log.d("KidGuard", "http client gone: ${e.message}") }
        catch (e: Exception) { Log.w("KidGuard", "http serve", e) }
    }

    private fun serve(c: Socket) = c.use {
        var held = false
        try {
        it.soTimeout = 15_000
        // total deadline for receiving the request (slow-drip guard); soTimeout alone resets on every byte
        val killer = Thread({ try { Thread.sleep(10_000); runCatching { it.close() } } catch (_: InterruptedException) {} }, "http-deadline").apply { isDaemon = true; start() }
        val res = try {
            val req = try { read(BufferedInputStream(it.getInputStream())) } finally { killer.interrupt() } ?: return
            val long = isLong(req)
            if (long && !longSlots.tryAcquire()) Res.json("{\"error\":\"busy\"}", 503) else { held = long; handler(req) }
        } catch (e: TooBig) { Res.text("too big", 413) } catch (e: Exception) {
            Log.w("KidGuard", "req", e); Res.text("error", 500)
        }
        val head = "HTTP/1.1 ${res.code} ${reason(res.code)}\r\nContent-Type: ${res.type}\r\n" +
            (if (res.stream == null) "Content-Length: ${res.body.size}\r\n" else "") +
            "Cache-Control: ${if (res.type == "image/png") "private, max-age=86400" else "no-store"}\r\nConnection: close\r\nX-Content-Type-Options: nosniff\r\n" +
            (if (res.type.startsWith("text/html")) "X-Frame-Options: DENY\r\nContent-Security-Policy: frame-ancestors 'none'; connect-src 'self' http://*:8770; img-src 'self' data: blob: https://tile.openstreetmap.de https://tile.openstreetmap.org http://*:8770\r\n" else "") +
            res.headers.entries.joinToString("") { (k, v) -> "$k: $v\r\n" } + "\r\n"
        val out = it.getOutputStream()
        val s = res.stream
        if (s == null) { out.write(head.toByteArray()); out.write(res.body); out.flush(); return }
        // a stream owns something (the mic): it must run even if the client is already gone, so it can clean up
        val sink = try { it.tcpNoDelay = true; out.write(head.toByteArray()); out.flush(); out }
            catch (e: java.io.IOException) { object : java.io.OutputStream() { override fun write(b: Int) = throw java.io.IOException("closed") } }
        s(sink)
        } finally { if (held) longSlots.release() }
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
        val target = parts[1]
        val path = target.substringBefore("?")
        val query = target.substringAfter("?", "").split("&").filter { it.contains("=") }.associate {
            URLDecoder.decode(it.substringBefore("="), "UTF-8") to URLDecoder.decode(it.substringAfter("="), "UTF-8")
        }
        val method = parts[0].uppercase()
        // shared-file upload: streamed to storage by Files.kt (<= 200 MB), never held in memory
        if (method == "PUT" && path.startsWith("/api/files/")) {
            val n = headers["content-length"]?.toLongOrNull() ?: -1L
            if (n > FilesLogic.MAX_LAN) throw TooBig()
            return Req(method, path, query, headers, ByteArray(0), i, n)
        }
        val len = headers["content-length"]?.toIntOrNull() ?: 0
        if (len > 512 * 1024) throw TooBig()
        val body = ByteArray(len)
        var got = 0
        while (got < len) { val r = i.read(body, got, len - got); if (r < 0) break; got += r }
        return Req(method, path, query, headers, body)
    }

    private fun reason(c: Int) = when (c) { 200 -> "OK"; 204 -> "No Content"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"; 409 -> "Conflict"; 413 -> "Payload Too Large"; 507 -> "Insufficient Storage"; 503 -> "Service Unavailable"; else -> "Error" }
}
