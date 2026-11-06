package dev.kidguard.parent

import java.net.HttpURLConnection
import java.net.URL

/** Result of one outbound call. status null = network error / timeout. */
class NetRes(val status: Int?, val type: String, val body: ByteArray, val headers: Map<String, String>)

object Net {
    /** Plain HttpURLConnection call: no redirects, connect 6 s, read [readMs]. Never throws. */
    fun call(method: String, url: String, headers: Map<String, String>, body: ByteArray?, readMs: Int = 40_000): NetRes {
        var c: HttpURLConnection? = null
        return try {
            c = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 6_000
                readTimeout = readMs
                instanceFollowRedirects = false
                useCaches = false
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
                if (body != null && body.isNotEmpty() && method != "GET" && method != "HEAD") {
                    doOutput = true
                    setFixedLengthStreamingMode(body.size)
                    outputStream.use { it.write(body) }
                }
            }
            val code = c.responseCode
            val bytes = (if (code >= 400) c.errorStream else c.inputStream)?.use { it.readBytes() } ?: ByteArray(0)
            val out = HashMap<String, String>()
            for ((k, v) in c.headerFields) if (k != null && k.lowercase().startsWith("x-kg-") && v.isNotEmpty()) out[k] = v.last()
            NetRes(code, c.contentType ?: "application/octet-stream", bytes, out)
        } catch (e: Exception) {
            NetRes(null, "text/plain", ByteArray(0), emptyMap())
        } finally { runCatching { c?.disconnect() } }
    }
}
