package dev.kidguard

import android.graphics.Bitmap
import android.graphics.Canvas
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/** The LAN API. The relay worker speaks the same /state, /rules, /cmd so the page works through both. */
class Api(private val g: Guard) {
    private val store = Store.of(g)

    fun handle(r: Req): Res {
        if (r.method == "GET" && (r.path == "/" || r.path == "/index.html")) {
            return Res(200, "text/html; charset=utf-8", g.assets.open("index.html").use { it.readBytes() })
        }
        if (!r.path.startsWith("/api/")) return Res.text("not found", 404)
        val p = r.path.removePrefix("/api")
        when {
            p == "/hello" -> return Res.json(JSONObject().put("id", store.id).put("pairing", store.pairCode() != null)
                .put("owner", Policy.isOwner(g)).toString())
            p == "/pair" && r.method == "POST" -> {
                val code = runCatching { JSONObject(r.text).optString("code") }.getOrDefault("")
                val t = store.tryPair(code) ?: return Res.json("""{"error":"wrong code"}""", 403)
                store.log("Đã ghép một thiết bị của bố mẹ")
                return Res.json(JSONObject().put("token", t).put("id", store.id).toString())
            }
        }
        val token = r.header("x-kg-token") ?: r.query["k"]
        if (!store.tokenOk(token)) return Res.json("""{"error":"not paired"}""", 401)
        return when {
            p == "/state" -> Res.json(g.currentState().toString())
            p == "/rules" && r.method == "PUT" -> {
                val incoming = Rules.fromJson(JSONObject(r.text))
                val rev = maxOf(store.rules.rev + 1, System.currentTimeMillis())
                g.setRules(incoming.copy(rev = rev))
                Res.json(store.rules.toJson().toString())
            }
            p == "/cmd" && r.method == "POST" -> {
                val c = JSONObject(r.text)
                if (c.optString("type") == "relay") {
                    val res = g.command(c)
                    Res.json(JSONObject().put("result", res).put("id", store.relayId).put("token", store.relayToken).put("url", store.relayUrl).toString())
                } else Res.json(JSONObject().put("result", g.command(c)).toString())
            }
            p == "/icon" -> icon(r.query["pkg"] ?: "")
            else -> Res.text("not found", 404)
        }
    }

    private fun icon(pkg: String): Res {
        val d = runCatching { g.packageManager.getApplicationIcon(pkg) }.getOrNull() ?: return Res.text("no icon", 404)
        val bmp = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, 96, 96); d.draw(Canvas(bmp))
        val out = ByteArrayOutputStream(); bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        return Res(200, "image/png", out.toByteArray())
    }
}
