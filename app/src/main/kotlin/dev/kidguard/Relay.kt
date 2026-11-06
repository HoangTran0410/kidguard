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

    fun start() { h.post(::loop) }
    fun stop() { h.looper.quitSafely() }
    fun kick() { h.removeCallbacksAndMessages(null); h.post(::loop) }

    private fun loop() {
        val url = store.relayUrl; val token = store.relayToken; val rid = store.relayId
        var busy = false
        if (url.isNotBlank() && token.isNotBlank() && rid.isNotBlank()) runCatching { busy = sync(url, rid, token) }.onFailure {
            lastError = it.message ?: it.javaClass.simpleName; Log.w("KidGuard", "relay", it)
        }
        val on = g.getSystemService(PowerManager::class.java)?.isInteractive == true
        // right after a command: report back soon, so the parent sees the result
        h.postDelayed(::loop, if (busy) 3_000 else if (on) 20_000 else 60_000)
    }

    private fun sync(base: String, rid: String, token: String): Boolean {
        val body = JSONObject().put("state", g.currentState()).put("acks", acks).toString().toByteArray()
        val c = (URL("$base/c/$rid/sync").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 10_000; readTimeout = 15_000; doOutput = true
            setRequestProperty("Content-Type", "application/json"); setRequestProperty("X-KG-Token", token)
        }
        try {
            c.outputStream.use { it.write(body) }
            if (c.responseCode != 200) throw RuntimeException("HTTP ${c.responseCode}")
            val res = JSONObject(c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
            acks = JSONArray()
            res.optJSONObject("rules")?.let { r ->
                val rules = Rules.fromJson(r)
                if (rules.rev > store.rules.rev) g.setRules(rules)
            }
            val cmds = res.optJSONArray("cmds") ?: JSONArray()
            for (i in 0 until cmds.length()) {
                val cmd = cmds.getJSONObject(i)
                if (cmd.optString("type") == "relay") { acks.put(cmd.optString("id")); continue }   // not over the relay itself
                g.command(cmd)
                acks.put(cmd.optString("id"))
            }
            lastOk = System.currentTimeMillis(); lastError = ""
            return acks.length() > 0 || res.has("rules")
        } finally { c.disconnect() }
    }
}
