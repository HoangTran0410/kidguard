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

class Req(val method: String, val path: String, val query: Map<String, String>, val headers: Map<String, String>, val body: ByteArray) {
    val text get() = String(body, Charsets.UTF_8)
    fun header(n: String) = headers[n.lowercase()]
}

class Res(val code: Int, val type: String, val body: ByteArray, val headers: Map<String, String> = emptyMap()) {
    companion object {
        fun json(s: String, code: Int = 200) = Res(code, "application/json; charset=utf-8", s.toByteArray())
        fun text(s: String, code: Int) = Res(code, "text/plain; charset=utf-8", s.toByteArray())
    }
}

/** A tiny HTTP/1.1 server (one request per connection), enough for the parent's page on the LAN. */
class Http(private val tag: Any, private val handler: (Req) -> Res) {
    private var server: ServerSocket? = null
    @Volatile private var stopped = false
    private val pool = ThreadPoolExecutor(4, 4, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(16))

    constructor(g: Guard, api: Api) : this(g, api::handle)

    fun start(port: Int) {
        Thread({
            while (!stopped) {
                try {
                    val s = ServerSocket(port).also { server = it }
                    while (!s.isClosed) {
                        val c = s.accept()
                        try { pool.execute { serve(c) } } catch (e: RejectedExecutionException) { runCatching { c.close() } }
                    }
                } catch (e: BindException) {
                    Log.w("KidGuard", "http bind $port failed, retry in 2s", e)
                    try { Thread.sleep(2000) } catch (_: InterruptedException) { return@Thread }
                } catch (e: SocketException) { /* stopped */ } catch (e: Exception) { Log.e("KidGuard", "http", e) }
            }
        }, "http").start()
    }

    fun stop() { stopped = true; runCatching { server?.close() }; pool.shutdownNow() }

    private fun serve(c: Socket) = c.use {
        it.soTimeout = 15_000
        // total deadline for receiving the request (slow-drip guard); soTimeout alone resets on every byte
        val killer = Thread({ try { Thread.sleep(10_000); runCatching { it.close() } } catch (_: InterruptedException) {} }, "http-deadline").apply { isDaemon = true; start() }
        val res = try {
            val req = try { read(BufferedInputStream(it.getInputStream())) } finally { killer.interrupt() } ?: return
            handler(req)
        } catch (e: TooBig) { Res.text("too big", 413) } catch (e: Exception) {
            Log.w("KidGuard", "req", e); Res.text("error", 500)
        }
        val head = "HTTP/1.1 ${res.code} ${reason(res.code)}\r\nContent-Type: ${res.type}\r\nContent-Length: ${res.body.size}\r\n" +
            "Cache-Control: ${if (res.type == "image/png") "private, max-age=86400" else "no-store"}\r\nConnection: close\r\nX-Content-Type-Options: nosniff\r\n" +
            (if (res.type.startsWith("text/html")) "X-Frame-Options: DENY\r\nContent-Security-Policy: frame-ancestors 'none'\r\n" else "") +
            res.headers.entries.joinToString("") { (k, v) -> "$k: $v\r\n" } + "\r\n"
        val out = it.getOutputStream()
        out.write(head.toByteArray()); out.write(res.body); out.flush()
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
        val len = headers["content-length"]?.toIntOrNull() ?: 0
        if (len > 512 * 1024) throw TooBig()
        val body = ByteArray(len)
        var got = 0
        while (got < len) { val r = i.read(body, got, len - got); if (r < 0) break; got += r }
        val target = parts[1]
        val path = target.substringBefore("?")
        val query = target.substringAfter("?", "").split("&").filter { it.contains("=") }.associate {
            URLDecoder.decode(it.substringBefore("="), "UTF-8") to URLDecoder.decode(it.substringAfter("="), "UTF-8")
        }
        return Req(parts[0].uppercase(), path, query, headers, body)
    }

    private fun reason(c: Int) = when (c) { 200 -> "OK"; 204 -> "No Content"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"; 413 -> "Payload Too Large"; 503 -> "Service Unavailable"; else -> "Error" }
}
