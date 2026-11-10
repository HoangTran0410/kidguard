package dev.kidguard

import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * The phone's push channel to the relay (`wss://<relay>/c/<id>/ws`, worker `Kid.webSocketMessage`): a minimal RFC 6455
 * client (no library). The relay Durable Object hibernates while the socket is idle, so an open socket costs nothing;
 * "ping" every [PING_MS] is answered by the runtime itself ("pong", not billed). The server sends {"t":"kick"} when a
 * parent queued something ([onKick]); [request] sends {"t":"sync",...} and waits for the matching reply.
 * Any failure closes the socket; Relay.kt then falls back to HTTP polling and reconnects later.
 */
class RelayWs(private val onKick: () -> Unit) {
    @Volatile private var sock: Socket? = null
    @Volatile private var out: OutputStream? = null
    @Volatile var open = false; private set
    @Volatile private var lastIn = 0L
    private val replies = SynchronousQueue<JSONObject>()
    @Volatile private var waiting = -1
    private var n = 0

    /** Blocking connect + handshake (<= ~15 s). Throws on failure. */
    fun connect(base: String, rid: String, token: String) {
        close()
        val u = URL(base)
        val host = u.host; val port = if (u.port > 0) u.port else 443
        val raw = Socket().apply { connect(InetSocketAddress(host, port), 10_000); soTimeout = 15_000 }
        val s = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, host, port, true) as SSLSocket
        try {
            s.startHandshake()
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, s.session)) throw RuntimeException("relay host mismatch")
            val key = Base64.encodeToString(ByteArray(16).also { SecureRandom().nextBytes(it) }, Base64.NO_WRAP)
            val o = s.outputStream
            o.write(("GET /c/$rid/ws HTTP/1.1\r\nHost: $host\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\nX-KG-Token: $token\r\nUser-Agent: KidGuard\r\n\r\n").toByteArray())
            o.flush()
            val ins = s.inputStream
            val status = readLine(ins)
            val headers = generateSequence { readLine(ins).takeIf { it.isNotEmpty() } }.toList()
            if (!status.startsWith("HTTP/1.1 101")) throw RuntimeException("relay ws: $status")
            val accept = headers.firstOrNull { it.startsWith("sec-websocket-accept:", ignoreCase = true) }?.substringAfter(':')?.trim()
            if (accept != Ws.accept(key)) throw RuntimeException("relay ws: bad accept")
            s.soTimeout = READ_TIMEOUT_MS
            sock = s; out = o; open = true; lastIn = System.currentTimeMillis()
            Thread({ readLoop(s, ins) }, "relay-ws").apply { isDaemon = true }.start()
        } catch (e: Exception) { runCatching { s.close() }; throw e }
    }

    /** Sends one sync message, waits for its reply (null = timeout / closed: the caller falls back to HTTP). */
    fun request(body: JSONObject, timeoutMs: Long = 15_000): JSONObject? {
        if (!open) return null
        val id = ++n
        waiting = id
        replies.poll()   // drop a stale reply
        if (!send(body.put("t", "sync").put("n", id).toString())) return null
        val r = replies.poll(timeoutMs, TimeUnit.MILLISECONDS)
        waiting = -1
        if (r == null) { close(); return null }
        return r
    }

    /** Keep-alive; true if the socket still looks alive (heard something within [READ_TIMEOUT_MS]). */
    fun ping(): Boolean {
        if (!open) return false
        if (System.currentTimeMillis() - lastIn > READ_TIMEOUT_MS) { close(); return false }
        return send("ping")
    }

    @Synchronized private fun send(text: String): Boolean = runCatching {
        val o = out ?: return false
        o.write(Ws.frame(Ws.TEXT, text.toByteArray())); o.flush(); true
    }.getOrElse { close(); false }

    @Synchronized private fun sendRaw(op: Int, payload: ByteArray) = runCatching { out?.let { it.write(Ws.frame(op, payload)); it.flush() } }

    fun close() {
        open = false
        val s = sock; sock = null; out = null
        runCatching { s?.close() }
    }

    private fun readLoop(s: Socket, ins: InputStream) {
        try {
            while (open && sock === s) {
                val m = Ws.read(ins, MAX_MSG) ?: break
                lastIn = System.currentTimeMillis()
                when (m.first) {
                    Ws.CLOSE -> break
                    Ws.PING -> sendRaw(Ws.PONG, m.second)
                    Ws.TEXT -> {
                        val t = m.second.toString(Charsets.UTF_8)
                        if (t == "pong") continue
                        val j = runCatching { JSONObject(t) }.getOrNull() ?: continue
                        when (j.optString("t")) {
                            "kick" -> onKick()
                            "sync" -> if (j.optInt("n") == waiting) replies.offer(j)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (open) Log.i("KidGuard", "relay ws: ${e.message}")
        } finally { if (sock === s) close() }
    }

    private fun readLine(ins: InputStream): String {
        val b = ByteArrayOutputStream()
        while (true) {
            val c = ins.read(); if (c < 0) throw RuntimeException("relay ws: eof")
            if (c == '\n'.code) break
            if (c != '\r'.code) b.write(c)
            if (b.size() > 8192) throw RuntimeException("relay ws: header too long")
        }
        return b.toString("UTF-8")
    }

    companion object {
        const val PING_MS = 45_000L
        /** No byte (pong / kick / reply) for this long = dead socket (NAT dropped it while the phone slept). */
        const val READ_TIMEOUT_MS = 120_000
        private const val MAX_MSG = 300 * 1024
    }
}

/** RFC 6455 framing, pure (unit-tested in WsTest). Client frames are always masked. */
object Ws {
    const val TEXT = 1; const val CLOSE = 8; const val PING = 9; const val PONG = 10
    private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

    fun accept(key: String): String = java.util.Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key + GUID).toByteArray()))

    fun frame(op: Int, payload: ByteArray, mask: ByteArray = ByteArray(4).also { SecureRandom().nextBytes(it) }): ByteArray {
        val o = ByteArrayOutputStream(payload.size + 14)
        o.write(0x80 or op)
        val n = payload.size
        when {
            n < 126 -> o.write(0x80 or n)
            n < 65536 -> { o.write(0x80 or 126); o.write(n ushr 8); o.write(n and 0xff) }
            else -> { o.write(0x80 or 127); for (i in 7 downTo 0) o.write(((n.toLong() ushr (8 * i)) and 0xff).toInt()) }
        }
        o.write(mask)
        for (i in payload.indices) o.write((payload[i].toInt() xor mask[i and 3].toInt()) and 0xff)
        return o.toByteArray()
    }

    /** Next whole message (continuation frames joined): opcode to payload; null at end of stream. */
    fun read(ins: InputStream, max: Int): Pair<Int, ByteArray>? {
        var op = -1
        val buf = ByteArrayOutputStream()
        while (true) {
            val b0 = ins.read(); if (b0 < 0) return null
            val b1 = ins.read(); if (b1 < 0) return null
            val fin = b0 and 0x80 != 0; val fop = b0 and 0x0f
            var len = (b1 and 0x7f).toLong()
            if (len == 126L) len = ((need(ins) shl 8) or need(ins)).toLong()
            else if (len == 127L) { len = 0; repeat(8) { len = (len shl 8) or need(ins).toLong() } }
            val mask = if (b1 and 0x80 != 0) ByteArray(4) { need(ins).toByte() } else null
            if (len > max || buf.size() + len > max) throw RuntimeException("relay ws: message too big")
            val p = ByteArray(len.toInt())
            var got = 0
            while (got < p.size) { val r = ins.read(p, got, p.size - got); if (r < 0) return null; got += r }
            if (mask != null) for (i in p.indices) p[i] = (p[i].toInt() xor mask[i and 3].toInt()).toByte()
            if (fop >= 8) return fop to p   // control frames are never fragmented (may come between fragments)
            if (fop != 0) op = fop
            buf.write(p)
            if (fin) return op to buf.toByteArray()
        }
    }

    private fun need(ins: InputStream): Int { val c = ins.read(); if (c < 0) throw RuntimeException("relay ws: eof"); return c }
}
