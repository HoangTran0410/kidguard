package dev.kidguard

import android.graphics.Bitmap
import android.graphics.Canvas
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/** The LAN API. The relay worker speaks the same /state, /rules, /cmd so the page works through both. */
class Api(private val g: Guard) {
    private val store = Store.of(g)

    fun handle(r: Req): Res {
        if (!Safety.hostOk(r.header("host"))) return Res.text("bad host", 403)   // DNS rebinding
        if (r.method == "GET" && (r.path == "/" || r.path == "/index.html")) {
            return Res(200, "text/html; charset=utf-8", g.assets.open("index.html").use { it.readBytes() })
        }
        if (!r.path.startsWith("/api/")) return Res.text("not found", 404)
        val p = r.path.removePrefix("/api")
        when {
            p == "/hello" -> return Res.json(JSONObject().put("id", store.id).put("pairing", store.pairCode() != null)
                .put("pin", store.rules.pinHash.isNotBlank()).put("owner", Policy.isOwner(g)).toString())
            p == "/pair" && r.method == "POST" -> {
                val o = runCatching { JSONObject(r.text) }.getOrDefault(JSONObject())
                val t = store.tryPair(o.optString("code"), o.optString("pin").filter { it.isDigit() })
                    ?: return Res.json(JSONObject().put("error", store.lastPairError).put("wait", store.pinLockLeft()).toString(),
                        if (store.lastPairError == "wait" || store.lastPairError == "pin locked") 429 else 403)
                store.log("Đã ghép một thiết bị của bố mẹ")
                return Res.json(JSONObject().put("token", t).put("id", store.id).toString())
            }
        }
        val token = r.header("x-kg-token") ?: r.query["k"]
        if (!store.tokenOk(token)) return Res.json("""{"error":"not paired"}""", 401)
        return when {
            p == "/state" -> Res.json(g.currentState().toString())
            p == "/rules" && r.method == "PUT" -> {
                val body = JSONObject(r.text)
                var incoming = Rules.fromJson(body)
                if (!body.has("pin") && !body.has("pinHash")) incoming = incoming.copy(pinHash = store.rules.pinHash)
                val rev = maxOf(store.rules.rev + 1, System.currentTimeMillis())
                g.setRules(incoming.copy(rev = rev))
                Res.json(store.rules.toJson().also { it.put("pinSet", store.rules.pinHash.isNotEmpty()); it.remove("pinHash") }.toString())
            }
            p == "/cmd" && r.method == "POST" -> {
                val c = JSONObject(r.text)
                if (c.optString("type") == "relay") {
                    val res = g.command(c)
                    Res.json(JSONObject().put("result", res).put("id", store.relayId).put("token", store.relayToken).put("url", store.relayUrl).toString())
                } else Res.json(JSONObject().put("result", g.command(c)).toString())
            }
            p == "/screen" -> {
                val f = Acc.capture((r.query["max"]?.toIntOrNull() ?: 720).coerceIn(320, 1600), (r.query["q"]?.toIntOrNull() ?: 50).coerceIn(20, 90))
                if (f == null) Res.json(JSONObject().put("error", Acc.lastError).toString(), 503)
                else Res(200, "image/jpeg", f.jpeg, mapOf("X-KG-W" to f.w.toString(), "X-KG-H" to f.h.toString()))
            }
            p == "/input" && r.method == "POST" -> Res.json(JSONObject().put("result",
                runCatching { Acc.input(JSONObject(r.text)) }.getOrDefault("bad input")).toString())
            p == "/track" -> Res.json(JSONObject().put("track", track(((r.query["days"]?.toIntOrNull()) ?: 7).coerceIn(1, 7))).toString())
            p == "/icon" -> icon(r.query["pkg"] ?: "")
            else -> Res.text("not found", 404)
        }
    }

    private fun track(days: Int): org.json.JSONArray = store.track(days)

    private fun icon(pkg: String): Res {
        val d = runCatching { g.packageManager.getApplicationIcon(pkg) }.getOrNull() ?: return Res.text("no icon", 404)
        val bmp = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, 96, 96); d.draw(Canvas(bmp))
        val out = ByteArrayOutputStream(); bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        return Res(200, "image/png", out.toByteArray())
    }
}
