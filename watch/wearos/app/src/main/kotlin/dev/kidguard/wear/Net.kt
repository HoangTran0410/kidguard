package dev.kidguard.wear

import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import org.json.JSONArray
import org.json.JSONObject

class ApiError(msg: String) : Exception(msg)

/** Parsed view of GET /c/<id>/state (same fields as Zepp's summary()). */
data class Req(val id: String, val kind: String, val min: Int, val app: String, val pkg: String, val note: String)
data class Loc(val lat: Double, val lng: Double, val acc: Int, val t: Long)
data class Zone(val name: String, val lat: Double, val lng: Double, val r: Double)
data class Summary(
    val seen: Long, val leftMs: Long, val limitMin: Int, val battery: Int, val charging: Boolean, val screenOn: Boolean,
    val current: String, val why: String, val until: Long, val whyName: String, val lost: Boolean,
    val loc: Loc?, val zones: List<Zone>, val req: List<Req>, val msgs: List<String>, val demo: Boolean = false,
) {
    companion object {
        fun demo(): Summary {
            val now = System.currentTimeMillis()
            return Summary(
                seen = now - 40_000, leftMs = 45 * 60_000L, limitMin = 120, battery = 62, charging = false, screenOn = true,
                current = "YouTube Kids", why = "", until = 0, whyName = "", lost = false,
                loc = Loc(21.0285, 105.8542, 18, now - 4 * 60_000), zones = listOf(Zone("Nhà", 21.0285, 105.8542, 150.0)),
                req = listOf(Req("demo1", "time", 30, "", "", "")), msgs = emptyList(), demo = true,
            )
        }
    }
}

/** Everything that talks to the relay. Blocking: call inside Dispatchers.IO. Never logs the token. */
object Net {
    const val DEFAULT_RELAY = "https://kidguard.99-hoangtran.workers.dev"
    const val OSM_UA = "KidGuardWear/0.1 (Wear OS parent app; https://kid-guard.pages.dev)"
    private const val NAME = "Wear OS watch"
    private val rnd = SecureRandom()

    fun validBase(s: String) = Regex("^https://[^/#?\\s]+$").matches(s)

    private class Res(val status: Int, val j: JSONObject)

    private fun call(method: String, url: String, headers: Map<String, String>, body: JSONObject? = null): Res {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 10_000
            c.readTimeout = 20_000
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val st = c.responseCode
            val txt = (if (st >= 400) c.errorStream else c.inputStream)?.bufferedReader()?.use { it.readText() } ?: ""
            val j = try { if (txt.startsWith("{")) JSONObject(txt) else JSONObject() } catch (e: Exception) { JSONObject() }
            return Res(st, j)
        } finally { c.disconnect() }
    }

    private fun kid(k: Kid, path: String, method: String = "GET", body: JSONObject? = null): JSONObject {
        val r = call(method, "${k.url}/c/${k.id}$path", mapOf("X-KG-Token" to k.token, "X-KG-Name" to NAME), body)
        if (r.status >= 400) throw ApiError(r.j.optString("error").ifEmpty { "HTTP ${r.status}" })
        return r.j
    }

    // ---------- connect by code (docs/protocol.md "Watch connect") ----------
    class Pending(val code: String, val exp: Long, val secret: String)

    fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    fun pairNew(relay: String): Pending {
        val b = ByteArray(32).also { rnd.nextBytes(it) }
        val secret = b.joinToString("") { "%02x".format(it) }
        val r = call("POST", "$relay/w/new", emptyMap(), JSONObject().put("sh", sha256(secret)))
        val code = r.j.optString("code")
        if (r.status >= 400 || code.isEmpty()) throw ApiError(r.j.optString("error").ifEmpty { "HTTP ${r.status}" })
        // the relay's clock may differ from the watch's: trust exp only when it is within the 10 min window
        val now = System.currentTimeMillis()
        val exp = r.j.optLong("exp").let { if (it - now in 1..12 * 60_000L) it else now + 10 * 60_000L }
        return Pending(code, exp, secret)
    }

    /** null = still waiting, Kid = done, throws "expired" on 404/403. */
    fun pairPoll(relay: String, p: Pending): Kid? {
        val r = call("GET", "$relay/w/${p.code}", mapOf("X-KG-Secret" to p.secret))
        if (r.status == 404 || r.status == 403) throw ApiError("expired")
        if (r.status >= 400) throw ApiError(r.j.optString("error").ifEmpty { "HTTP ${r.status}" })
        if (r.j.optBoolean("waiting")) return null
        val url = r.j.optString("url"); val id = r.j.optString("id"); val tok = r.j.optString("token")
        if (!validBase(url) || !Regex("[0-9a-f]{16}").matches(id) || !Regex("[0-9a-f]{48}").matches(tok)) throw ApiError("bad reply")
        return Kid(url, id, tok, r.j.optString("name").take(20).ifEmpty { "Kid" })
    }

    // ---------- read ----------
    class StateRes(val sum: Summary, val done: Map<String, String>, val expired: Set<String>)

    private fun newestRules(j: JSONObject): JSONObject {
        val a = j.optJSONObject("rules"); val b = j.optJSONObject("state")?.optJSONObject("rules")
        return if (a != null && b != null) (if (a.optLong("rev") >= b.optLong("rev")) a else b) else a ?: b ?: JSONObject()
    }

    fun state(k: Kid): StateRes {
        if (k.demo) return StateRes(Summary.demo(), emptyMap(), emptySet())
        val j = kid(k, "/state")
        val s = j.optJSONObject("state") ?: JSONObject()
        val r = newestRules(j)
        val now = s.optJSONObject("now")
        val l = s.optJSONObject("location")?.takeIf { it.has("lat") && !it.isNull("lat") }
        val apps = s.optJSONArray("apps")
        val cur = s.optString("current")
        var curLabel = ""
        if (apps != null) for (i in 0 until apps.length()) apps.getJSONObject(i).let { if (it.optString("pkg") == cur) curLabel = it.optString("label") }
        val reqs = s.optJSONArray("requests")
        val rl = ArrayList<Req>()
        if (reqs != null) for (i in maxOf(0, reqs.length() - 3) until reqs.length()) reqs.getJSONObject(i).let {
            rl.add(Req(it.optString("id"), if (it.optString("kind") == "app") "app" else "time", it.optInt("min"),
                it.optString("app").ifEmpty { it.optString("pkg") }.take(24), it.optString("pkg"), it.optString("note").take(60)))
        }
        val zl = ArrayList<Zone>()
        r.optJSONArray("zones")?.let { a -> for (i in 0 until minOf(10, a.length())) a.getJSONObject(i).let { zl.add(Zone(it.optString("name"), it.optDouble("lat"), it.optDouble("lng"), it.optDouble("r"))) } }
        val ml = ArrayList<String>()
        r.optJSONArray("msgs")?.let { a -> for (i in 0 until minOf(12, a.length())) a.optString(i).trim().takeIf { it.isNotEmpty() }?.let { ml.add(it.take(120)) } }
        val sum = Summary(
            seen = j.optLong("seen"), leftMs = if (s.isNull("leftMs") || !s.has("leftMs")) -1 else s.optLong("leftMs"),
            limitMin = s.optInt("limitMin"), battery = if (s.has("battery") && !s.isNull("battery")) s.optInt("battery") else -1,
            charging = s.optBoolean("charging"), screenOn = s.optBoolean("screenOn", true), current = if (s.optBoolean("screenOn", true)) curLabel else "",
            why = now?.optString("why") ?: "", until = now?.optLong("until") ?: 0, whyName = (now?.optString("name") ?: "").take(24),
            lost = (s.optJSONObject("lost")?.optBoolean("on") ?: false) || r.optBoolean("lostOn"),
            loc = l?.let { Loc(it.optDouble("lat"), it.optDouble("lng"), Math.round(it.optDouble("acc", 0.0)).toInt(), it.optLong("t")) },
            zones = zl, req = rl, msgs = ml,
        )
        val done = HashMap<String, String>()
        s.optJSONArray("done")?.let { a -> for (i in 0 until a.length()) a.getJSONObject(i).let { done[it.optString("id")] = it.optString("res") } }
        val exp = HashSet<String>()
        j.optJSONArray("expired")?.let { a -> for (i in 0 until a.length()) exp.add(a.getJSONObject(i).optString("id")) }
        return StateRes(sum, done, exp)
    }

    // ---------- commands ----------
    private fun newId() = "w" + System.currentTimeMillis().toString(36) + (rnd.nextInt(1 shl 30)).toString(36)

    /** Queues a command; returns our command id (matched later in state.done / expired). */
    fun cmd(k: Kid, type: String, extra: JSONObject.() -> Unit = {}): String {
        if (k.demo) return "demo"
        val id = newId()
        val body = JSONObject().put("type", type).put("id", id).apply(extra)
        val j = kid(k, "/cmd", "POST", body)
        val res = j.optString("result", "queued")
        if (res != "queued" && res != "ok") throw ApiError(res)
        return id
    }

    /** "ok" / "fail:<res>" / "expired" / "wait" */
    fun receipt(k: Kid, id: String): String {
        if (k.demo) return "ok"
        val st = state(k)
        st.done[id]?.let { return if (it == "ok") "ok" else "fail:" + it.take(40) }
        return if (id in st.expired) "expired" else "wait"
    }

    /** Approve (grant min / allow app) or deny a request: edit rules first, then tell the child (as the page does). */
    fun answer(k: Kid, r: Req, ok: Boolean, min: Int): String {
        if (k.demo) return "demo"
        if (ok) changeRules(k) { rules, st -> if (r.kind == "app") appOk(rules, r.pkg) else grant(rules, st, min) }
        return cmd(k, "answer") {
            put("req", r.id); put("ok", ok)
            if (ok && r.kind == "app") put("always", true)
            if (ok && r.kind != "app") put("min", min)
        }
    }

    private fun appOk(r: JSONObject, pkg: String) {
        val ap = r.optJSONArray("approved") ?: JSONArray()
        if ((0 until ap.length()).none { ap.optString(it) == pkg }) ap.put(pkg)
        r.put("approved", ap)
        val bl = r.optJSONArray("blocked") ?: JSONArray()
        val nb = JSONArray(); for (i in 0 until bl.length()) if (bl.optString(i) != pkg) nb.put(bl.optString(i))
        r.put("blocked", nb)
    }

    /** Same rule as the page's grantApply / Zepp grantApply: no lock or "daily" -> today's bonus, else a timed pass. */
    private fun grant(r: JSONObject, st: JSONObject?, min: Int) {
        val why = st?.optJSONObject("now")?.optString("why") ?: ""
        val now = System.currentTimeMillis()
        if (why == "" || why == "daily") {
            val day = st?.optString("day") ?: ""
            val b = r.optJSONObject("bonus")
            val c0 = if (b != null && b.optString("day") == day) b.optInt("min") else 0
            r.put("bonus", JSONObject().put("day", day).put("min", minOf(600, c0 + min)))
        } else r.put("passUntil", maxOf(now, r.optLong("passUntil")) + min * 60_000L)
    }

    /** GET state -> change the newest rules -> PUT the WHOLE object (full replace on the phone). */
    private fun changeRules(k: Kid, edit: (JSONObject, JSONObject?) -> Unit) {
        val j = kid(k, "/state")
        val r = JSONObject(newestRules(j).toString())
        edit(r, j.optJSONObject("state"))
        r.remove("pinHash"); r.remove("pinSet"); r.remove("pin")
        kid(k, "/rules", "PUT", r)
    }

    // ---------- map tiles: relay first, OSM with a real User-Agent as fallback ----------
    fun tile(k: Kid, z: Int, x: Int, y: Int): ByteArray? {
        if (!k.demo) try {
            return bytes("${k.url}/c/${k.id}/tile?z=$z&x=$x&y=$y", mapOf("X-KG-Token" to k.token, "X-KG-Name" to NAME))
        } catch (e: Exception) { /* fall through to OSM */ }
        return try { bytes("https://tile.openstreetmap.org/$z/$x/$y.png", mapOf("User-Agent" to OSM_UA)) } catch (e: Exception) { null }
    }

    private fun bytes(url: String, h: Map<String, String>): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000; c.readTimeout = 20_000
            h.forEach { (a, b) -> c.setRequestProperty(a, b) }
            if (c.responseCode != 200) throw ApiError("HTTP ${c.responseCode}")
            return c.inputStream.use { it.readBytes() }
        } finally { c.disconnect() }
    }
}
