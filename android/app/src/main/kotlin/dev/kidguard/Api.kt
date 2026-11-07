package dev.kidguard

import android.graphics.Bitmap
import android.graphics.Canvas
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/** The LAN API. The relay worker speaks the same /state, /rules, /cmd so the page works through both. */
class Api(private val g: Guard) {
    private val store = Store.of(g)

    /**
     * CORS for every /api/ path (docs/protocol.md "Several kids from one browser page"): the Origin is echoed only when
     * [Safety.corsOriginOk] (another KidGuard phone's page), never with Allow-Credentials. Auth stays the token.
     */
    fun handle(r: Req): Res {
        if (!r.path.startsWith("/api/")) return route(r)
        val origin = r.header("origin")?.takeIf { Safety.corsOriginOk(it) }
        if (r.method == "OPTIONS") {
            if (!Safety.hostOk(r.header("host"))) return Res.text("bad host", 403)
            val h = linkedMapOf("Vary" to "Origin")
            if (origin != null) {
                h["Access-Control-Allow-Origin"] = origin
                h["Access-Control-Allow-Methods"] = "GET, POST, PUT, DELETE"
                h["Access-Control-Allow-Headers"] = "X-KG-Token, X-KG-Name, Content-Type"
                h["Access-Control-Max-Age"] = "600"
                if (r.header("access-control-request-private-network") != null) h["Access-Control-Allow-Private-Network"] = "true"
            }
            return Res(204, "text/plain; charset=utf-8", ByteArray(0), h)
        }
        val res = route(r)
        val h = res.headers + ("Vary" to "Origin") + (if (origin == null) emptyMap() else
            mapOf("Access-Control-Allow-Origin" to origin, "Access-Control-Expose-Headers" to "X-KG-Seq, X-KG-Age, X-KG-Err, X-KG-W, X-KG-H"))
        return Res(res.code, res.type, res.body, h, res.stream)
    }

    private fun route(r: Req): Res {
        if (!Safety.hostOk(r.header("host"))) return Res.text("bad host", 403)   // DNS rebinding
        if (r.method == "GET" && (r.path == "/" || r.path == "/index.html")) {
            return Res(200, "text/html; charset=utf-8", g.assets.open("index.html").use { it.readBytes() })
        }
        // the page's scripts + style (web/ui/), public like the page itself
        if (r.method == "GET") Safety.uiAsset(r.path)?.let { (file, type) ->
            return try { Res(200, type, g.assets.open(file).use { it.readBytes() }) } catch (e: java.io.IOException) { Res.text("not found", 404) }
        }
        if (!r.path.startsWith("/api/")) return Res.text("not found", 404)
        val p = r.path.removePrefix("/api")
        when {
            // pairPin: this phone takes `newPin` at pairing (pairing without a PIN is refused while none is set)
            p == "/hello" -> return Res.json(JSONObject().put("id", store.id).put("pairing", store.pairCode() != null)
                .put("pin", store.rules.pinHash.isNotBlank()).put("pairPin", true).put("owner", Policy.isOwner(g)).put("mode", Basic.mode(g)).toString())
            p == "/pair" && r.method == "POST" -> {
                val o = runCatching { JSONObject(r.text) }.getOrDefault(JSONObject())
                val t = store.tryPair(o.optString("code"), o.optString("pin").filter { it.isDigit() }, o.optString("newPin").trim())
                    ?: return Res.json(JSONObject().put("error", store.lastPairError).put("wait", store.pinLockLeft()).toString(),
                        when (store.lastPairError) { "wait", "pin locked" -> 429; "bad pin" -> 400; else -> 403 })
                // pending until the page uses the token (Store.commitPair), so a closed page leaves nothing half-done
                return Res.json(JSONObject().put("token", t).put("id", store.id).put("pinSet", true).toString())
            }
        }
        val token = listOf(r.header("x-kg-token"), r.query["k"]).firstOrNull { !it.isNullOrBlank() }
        val session = ParentSession.fromCookie(r.header("cookie"))   // on-device parent page (AdminActivity), never a paired token
        if (!ParentSession.valid(session) && !store.tokenOk(token)) {
            if (!store.commitPair(token)) return Res.json("""{"error":"not paired"}""", 401)
            store.log(R.string.log_paired)
            g.kick()
        }
        if (token != null && !ParentSession.valid(session)) store.touchDevice(token, r.header("x-kg-name"))
        return when {
            // devices: the cached list, with the caller's own row marked self (a paired token only; the on-device session has none)
            p == "/state" -> Res.json(g.currentState().let { st -> if (token == null || ParentSession.valid(session)) st else JSONObject(st.toString()).put("devices", store.devices(token)) }.toString())
            p == "/unpair" && r.method == "POST" -> {
                val id = runCatching { JSONObject(r.text).optString("id") }.getOrDefault("")
                if (store.unpair(id)) { g.kick(); Res.json("""{"result":"ok"}""") } else Res.json("""{"result":"no such device"}""", 404)
            }
            p == "/files" || p.startsWith("/files/") -> Files.api(g, r, p.removePrefix("/files"))   // shared folder only, Files.kt
            p == "/rules" && r.method == "PUT" -> {
                val body = JSONObject(r.text)
                var incoming = Rules.fromJson(body)
                if (!body.has("pin") && !body.has("pinHash")) incoming = incoming.copy(pinHash = store.rules.pinHash, pinSalt = store.rules.pinSalt)   // both: a salted hash without its salt never matches
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
            p == "/device" -> Res.json(DeviceInfo.get(g, if (r.query["fresh"] == "1") DeviceLogic.FRESH_MS else DeviceLogic.TTL_MS).toString())   // "Máy" view, cached 60 s
            p == "/track" -> Res.json(JSONObject().put("track", track(((r.query["days"]?.toIntOrNull()) ?: 7).coerceIn(1, 7))).toString())
            p == "/icon" -> icon(r.query["pkg"] ?: "")
            // LAN / Tailscale only (the relay has no audio route): AAC stream of the mic; the child is always told
            p == "/listen" && r.method == "GET" -> {
                val s = Listen.open(g)
                if (s == null) Res.json(JSONObject().put("error", Listen.lastError).toString(), if (Listen.lastError == "busy") 409 else 503)
                else Res(200, "audio/aac", ByteArray(0), stream = s::pump)
            }
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
