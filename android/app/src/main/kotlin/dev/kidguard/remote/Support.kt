package dev.kidguard

import android.content.Context
import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * "Gửi lỗi cho nhà phát triển" (owner 2026-11-11, same census worker as tv-remote: dev/census/src/support.js, read with its
 * support.py). A press (parent page command `support`, or the child's admin screen) sends a report and gets an 8-digit code;
 * the developer's replies come back on a per-install thread, asked every [ASK_MS] while a code is younger than [KEEP_MS],
 * shown in state `support` and answered with `supportSay`.
 *
 * A child's phone: the report is an allow-list ([facts]), never location / track / Wi-Fi name / notifications / notes /
 * photos / app usage / app names, plus this process's own logcat and last crash, both through [SupportScrub]. One app
 * package goes only when the parent picked it. No live look at the phone (tv-remote has one; not here).
 */
object Support {
    const val URL = "https://tvr-census.99-hoangtran.workers.dev"
    const val KEEP_MS = 14 * 86_400_000L
    private const val ASK_MS = 6 * 3_600_000L
    private const val MAX_REPORT = 56 * 1024
    private const val MAX_SAY = 1000
    val KINDS = setOf("block", "time", "relay", "location", "launcher", "bank", "battery", "other")

    @Volatile private var busy = false
    @Volatile private var err = ""

    private fun prefs(ctx: Context) = ctx.applicationContext.createDeviceProtectedStorageContext().getSharedPreferences("support", 0)

    /** Starts a send in the background; the code (or error) shows in state `support`. */
    fun send(ctx: Context, kind: String, say: String, pkg: String?, from: String): String {
        if (busy) return "busy"
        val app = ctx.applicationContext
        busy = true; err = ""
        Thread {
            try {
                val p = prefs(app)
                val id = p.getString("id", null) ?: java.util.UUID.randomUUID().toString().also { p.edit().putString("id", it).apply() }
                val report = report(app, kind.takeIf { it in KINDS } ?: "other", say.take(MAX_SAY), pkg?.takeIf { Regex("[A-Za-z0-9_.]{3,120}").matches(it) }, from)
                val b = JSONObject().put("id", id).put("app", "kidguard").put("v", BuildConfig.VERSION_NAME)
                    .put("model", "${Build.MANUFACTURER} ${Build.MODEL}".take(64)).put("report", report)
                val (code, res) = post("/v1/support", b)
                when {
                    code == 200 && res.optString("code").length == 8 -> p.edit().putString("code", res.getString("code")).putString("key", res.getString("key"))
                        .putLong("made", System.currentTimeMillis()).putInt("left", res.optInt("left", -1)).putLong("asked", 0).apply()
                    code == 429 -> err = "limit"
                    else -> err = "http $code"
                }
                if (err.isEmpty()) thread(app)
            } catch (e: Exception) { err = "net"; Log.w("KidGuard", "support send", e) } finally { busy = false; Guard.running?.kick() }
        }.start()
        return "ok"
    }

    /** The parent writes back on the thread (in the background; a failure shows as state `support.err`). */
    fun say(ctx: Context, text: String): String {
        val app = ctx.applicationContext; val p = prefs(app); val code = p.getString("code", null) ?: return "no code"
        val t = text.trim().take(MAX_SAY); if (t.isEmpty()) return "empty"
        err = ""
        Thread {
            try {
                val (c, _) = post("/v1/support/say", JSONObject().put("code", code).put("key", p.getString("key", "")).put("text", SupportScrub.text(t, null)))
                if (c == 200) thread(app) else err = if (c == 429) "limit" else "http $c"
            } catch (e: Exception) { err = "net" } finally { Guard.running?.kick() }
        }.start()
        return "ok"
    }

    /** Guard tick: asks for replies every [ASK_MS] while the newest code is kept by the worker. */
    fun poll(ctx: Context) {
        val p = prefs(ctx); val now = System.currentTimeMillis()
        if (p.getString("code", null) == null || now - p.getLong("asked", 0) < ASK_MS) return
        if (now - p.getLong("made", 0) > KEEP_MS) { p.edit().remove("code").remove("key").remove("msgs").apply(); return }
        p.edit().putLong("asked", now).apply()
        Thread { runCatching { thread(ctx.applicationContext) }; Guard.running?.kick() }.start()
    }

    private fun thread(ctx: Context) {
        val p = prefs(ctx); val code = p.getString("code", null) ?: return
        val (c, res) = post("/v1/support/thread", JSONObject().put("code", code).put("key", p.getString("key", "")))
        if (c == 403) { p.edit().remove("code").remove("key").apply(); return }   // gone after the worker's 14 days
        if (c != 200) return
        val msgs = res.optJSONArray("msgs") ?: JSONArray()
        val out = JSONArray()
        for (i in maxOf(0, msgs.length() - 30) until msgs.length()) msgs.optJSONObject(i)?.let {
            out.put(JSONObject().put("who", if (it.optString("who") == "us") "dev" else "you").put("text", it.optString("text").take(4000)).put("at", it.optLong("at")))
        }
        p.edit().putString("msgs", out.toString()).putLong("asked", System.currentTimeMillis()).apply()
    }

    /** When the newest code was made (0 = none) and the last send's error ("" = none): the phone's own send dialog waits on these. */
    fun made(ctx: Context): Long = prefs(ctx).let { if (it.getString("code", null) == null) 0L else it.getLong("made", 0) }
    fun code(ctx: Context): String = prefs(ctx).getString("code", null) ?: ""
    fun lastErr(): String = err
    fun sending(): Boolean = busy

    /** State `support`: {busy, err, code, made, left, msgs:[{who: dev|you, text, at}]}. */
    fun state(ctx: Context): JSONObject {
        val p = prefs(ctx); val o = JSONObject()
        if (busy) o.put("busy", true)
        if (err.isNotEmpty()) o.put("err", err)
        p.getString("code", null)?.let { o.put("code", it).put("made", p.getLong("made", 0)).put("left", p.getInt("left", -1)) }
        p.getString("msgs", null)?.let { runCatching { o.put("msgs", JSONArray(it)) } }
        return o
    }

    private fun post(path: String, body: JSONObject): Pair<Int, JSONObject> {
        val c = URL(URL + path).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000; c.readTimeout = 20_000; c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            val txt = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            return code to (runCatching { JSONObject(txt) }.getOrNull() ?: JSONObject())
        } finally { c.disconnect() }
    }

    // ---- the report ----

    private fun report(ctx: Context, kind: String, say: String, pkg: String?, from: String): String {
        val st = Guard.running?.currentState() ?: JSONObject()
        val sb = StringBuilder()
        sb.append("KidGuard support report\nfrom: ").append(from).append("\nkind: ").append(kind).append('\n')
        if (say.isNotBlank()) sb.append("said: ").append(SupportScrub.text(say, pkg)).append('\n')
        if (pkg != null) sb.append("app: ").append(pkg).append(' ').append(appFacts(st, pkg)).append('\n')
        sb.append("\n--- facts ---\n").append(SupportScrub.text(facts(ctx, st).toString(1), pkg))
        File(ctx.filesDir, CRASH_FILE).takeIf { it.exists() }?.let { f ->
            sb.append("\n\n--- last crash (").append(java.util.Date(f.lastModified())).append(") ---\n").append(SupportScrub.text(f.readText().take(8000), pkg))
        }
        val head = sb.toString()
        val log = SupportScrub.text(logcat(), pkg)
        val room = MAX_REPORT - head.length - 40
        return head + "\n\n--- log (this process) ---\n" + (if (log.length > room) log.takeLast(maxOf(0, room)) else log)
    }

    /** Allow-list: what a fault is found by. Nested objects only by the keys named here. */
    fun facts(ctx: Context, st: JSONObject): JSONObject {
        val o = JSONObject()
        o.put("versionCode", BuildConfig.VERSION_CODE).put("flavor", if (Flavor.HOME) "home" else "kid")
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})").put("rom", Build.DISPLAY.take(80))
            .put("sdk", Build.VERSION.SDK_INT).put("release", Build.VERSION.RELEASE)
            .put("webview", runCatching { android.webkit.WebView.getCurrentWebViewPackage()?.versionName }.getOrNull() ?: "")
        for (k in listOf("version", "owner", "mode", "basic", "caps", "features", "adbWhy", "preinstalled", "acc", "accReEnable", "usageAccess",
            "usageOff", "locationOn", "dev", "vpnStuck", "dnsError", "battery", "charging", "screenOn", "update", "launcher", "sys", "limitMin"))
            if (st.has(k)) o.put(k, st.opt(k))
        for (k in listOf("accPaused", "vpnPaused", "devPaused")) st.optJSONArray(k)?.let { o.put(k + "N", it.length()) }
        st.optJSONObject("ts")?.let { o.put("ts", pick(it, "state", "err", "noExpiry")) }
        st.optJSONObject("tg")?.let { t -> o.put("tg", pick(t, "on", "err").put("chats", t.optJSONArray("chats")?.length() ?: 0)) }
        st.optJSONObject("net")?.let { o.put("net", pick(it, "type", "metered", "bars", "ghz")) }
        val relay = st.optString("relay")
        o.put("relay", when { relay.isBlank() -> "off"; relay.contains("kidguard.99-hoangtran.workers.dev") -> "default"; else -> "own" })
        Guard.running?.let { g -> o.put("relayOkAgoS", if (g.relayOkAt > 0) (System.currentTimeMillis() - g.relayOkAt) / 1000 else -1) }
        o.put("lan", st.opt("lan") != null && st.opt("lan") != JSONObject.NULL)
        o.put("apps", st.optJSONArray("apps")?.length() ?: 0)
        // rules: which ones are on (names only, never their apps / times / places)
        st.optJSONObject("rules")?.let { r ->
            val on = JSONArray(); val it = r.keys()
            while (it.hasNext()) { val k = it.next(); when (val v = r.opt(k)) {
                is Boolean -> if (v) on.put(k)
                is Number -> if (v.toDouble() != 0.0 && k != "rev") on.put("$k=$v")
                is JSONArray -> if (v.length() > 0) on.put("$k[${v.length()}]")
                is JSONObject -> if (v.length() > 0) on.put("$k{}")
            } }
            o.put("rules", on)
        }
        return o
    }

    private fun appFacts(st: JSONObject, pkg: String): String {
        val a = st.optJSONArray("apps") ?: return "(not installed?)"
        for (i in 0 until a.length()) a.optJSONObject(i)?.takeIf { it.optString("pkg") == pkg }?.let {
            return pick(it, "blocked", "suspended", "protected", "new").put("ms", it.optLong("ms")).toString()
        }
        return "(not in the list)"
    }

    private fun pick(src: JSONObject, vararg keys: String) = JSONObject().apply { keys.forEach { if (src.has(it)) put(it, src.opt(it)) } }

    private fun logcat(): String = runCatching {
        val p = ProcessBuilder("logcat", "-d", "-v", "time", "--pid", android.os.Process.myPid().toString(), "-t", "800").redirectErrorStream(true).start()
        val t = p.inputStream.bufferedReader().use { it.readText() }; p.waitFor(); t
    }.getOrElse { "(logcat: ${it.javaClass.simpleName})" }

    // ---- last crash, any thread (App.onCreate) ----

    const val CRASH_FILE = "last_crash.txt"

    fun watchCrashes(ctx: Context) {
        val app = ctx.applicationContext
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { File(app.filesDir, CRASH_FILE).writeText("thread ${t.name}, v${BuildConfig.VERSION_NAME}\n" + Log.getStackTraceString(e).take(16_000)) }
            old?.uncaughtException(t, e)
        }
    }
}

/**
 * What a support report must not carry, taken out on the phone before it leaves (pure, SupportScrubTest). Out: keys, tokens,
 * PINs, long hex ids, a Telegram bot token, emails, MACs, Wi-Fi names, coordinates, public IPs (LAN / Tailscale kept: network
 * faults are found by them), any word with non-ASCII letters (names, labels, notes are typed in Vietnamese), and every package
 * or host name that is not Android's / KidGuard's own (-> app#hash; [keep] = the one app the parent chose to send).
 */
object SupportScrub {
    private val SECRET = Regex("(\"(?:token|key|pin|pass|secret|hash|salt|pinHash|pinSalt|totp\\w*|relayToken)\"\\s*:\\s*\")[^\"]*(\")", RegexOption.IGNORE_CASE)
    private val QUERY = Regex("([?&](?:k|key|token|pin|pass|code)=)[^&\\s\"']*", RegexOption.IGNORE_CASE)
    private val HEADER = Regex("((?:X-KG-Token|Authorization|Bearer)[:=\\s]+)[A-Za-z0-9._~+/=-]+", RegexOption.IGNORE_CASE)
    private val BOT = Regex("\\d{6,}:[A-Za-z0-9_-]{30,}")
    private val HEX = Regex("\\b[0-9a-fA-F]{16,}\\b")
    private val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}")
    private val MAC = Regex("\\b[0-9A-Fa-f]{2}(?:[:-][0-9A-Fa-f]{2}){5}\\b")
    private val SSID = Regex("((?:\"?b?ssid\"?)\\s*[:=]\\s*\"?)[^\",}\\n]*", RegexOption.IGNORE_CASE)
    private val COORD = Regex("-?\\b\\d{1,3}\\.\\d{4,}\\b")
    private val IPV4 = Regex("\\b(\\d{1,3})\\.(\\d{1,3})\\.\\d{1,3}\\.\\d{1,3}\\b")
    private val NONASCII = Regex("[^\\s\"',:;=(){}\\[\\]]*[^\\x00-\\x7F][^\\s\"',:;=(){}\\[\\]]*")
    private val DOTTED = Regex("\\b[a-zA-Z][a-zA-Z0-9_]*(?:\\.[a-zA-Z0-9_]+)+\\b")
    private val OWN = listOf("dev.kidguard", "android", "androidx", "java", "javax", "kotlin", "kotlinx", "com.android", "com.google.android",
        "org.json", "dalvik", "libcore", "sun", "org.chromium", "com.google.android.gms", "okhttp3", "okio", "io.github", "workers.dev",
        "com.tailscale", "api.telegram.org", "pages.dev")

    fun text(s: String, keep: String?): String = s
        .let { SECRET.replace(it) { m -> m.groupValues[1] + "…" + m.groupValues[2] } }
        .let { QUERY.replace(it) { m -> m.groupValues[1] + "…" } }
        .let { HEADER.replace(it) { m -> m.groupValues[1] + "…" } }
        .let { BOT.replace(it, "bot…") }
        .let { EMAIL.replace(it, "email@x") }
        .let { MAC.replace(it, "xx:xx:xx:xx:xx:xx") }
        .let { SSID.replace(it) { m -> m.groupValues[1] + "…" } }
        .let { COORD.replace(it, "~") }
        .let { HEX.replace(it, "…") }
        .let { IPV4.replace(it) { m -> if (lan(m.groupValues[1].toInt(), m.groupValues[2].toInt())) m.value else "x.x.x.x" } }
        .let { NONASCII.replace(it, "…") }
        .let { DOTTED.replace(it) { m -> val v = m.value; if (v == keep || v == "x.x.x.x" || own(v) || v.endsWith(".kt") || v.endsWith(".java")) v else "app#" + Integer.toHexString(v.hashCode() and 0xffff) } }

    private fun own(v: String) = OWN.any { v == it || v.startsWith("$it.") || v.endsWith(".$it") } || v.matches(Regex("\\d+(\\.\\d+)*"))
    private fun lan(a: Int, b: Int) = a == 10 || a == 127 || (a == 192 && b == 168) || (a == 172 && b in 16..31) || (a == 100 && b in 64..127) || (a == 169 && b == 254)
}
