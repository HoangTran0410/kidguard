package dev.kidguard

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Telegram bot run BY THE CHILD PHONE (no relay, no LAN, no Tailscale): the family makes its own bot at @BotFather,
 * the token is given to the phone (command `tgSet`), the phone long-polls getUpdates and answers. A chat is let in
 * only with the 6-digit code shown on the parent page (max 5 chats). Alerts (SOS, requests with answer buttons,
 * events, wrong-PIN photos) are scanned from the store on every Guard tick. Destructive commands (release, unpair,
 * reboot, clear data) are not offered here. Texts / parsing / rule edits: TgLogic (unit-tested).
 */
object Tg {
    private const val TAG = "KidGuard"
    private const val API = "https://api.telegram.org"
    private val lock = Object()
    @Volatile private var thread: Thread? = null
    @Volatile private var gen = 0   // a poll thread runs while its generation is current (a stuck old one exits when it wakes)
    @Volatile private var guard: Guard? = null
    @Volatile private var conflictFixed = false
    @Volatile private var cmdsSet = false   // command lists / descriptions refreshed once per process (new texts after an update)
    // commands that wait (rec, locate ~30 s, screen) never block polling or each other; bounded: a flood is dropped, not queued forever
    private val work = java.util.concurrent.ThreadPoolExecutor(4, 4, 60, java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.LinkedBlockingQueue(64), java.util.concurrent.ThreadPoolExecutor.DiscardOldestPolicy())
    private val alerts = Executors.newSingleThreadExecutor()   // SOS / requests never wait behind a 60 s /rec
    private val sending = java.util.concurrent.ConcurrentHashMap<String, Long>()   // alert key -> retry not before (MAX = in flight)
    private val seenLock = Any(); private val rulesLock = Any()
    private val locBusy = java.util.concurrent.atomic.AtomicBoolean(); private val recBusy = java.util.concurrent.atomic.AtomicBoolean()
    private val strangerTries = java.util.concurrent.ConcurrentHashMap<Long, Int>()   // wrong link codes per chat (one stranger can't burn the code)
    private val fast = Executors.newSingleThreadExecutor()   // button acks: never queued behind a slow command
    private val askMsg = java.util.concurrent.ConcurrentHashMap<Long, Long>()   // chat -> when "💬 Message" asked for the text

    @Synchronized fun start(g: Guard) {
        guard = g
        if (thread?.isAlive == true) return
        val my = ++gen
        thread = Thread({ loop(my) }, "tg").apply { isDaemon = true; start() }
    }
    @Synchronized fun stop() { guard = null; gen++; thread?.interrupt(); thread = null }
    private fun redact(e: Throwable) = e.toString().replace(Regex("bot\\d+:[A-Za-z0-9_-]+"), "bot***")
    /** Token or chats changed: end the current wait at once. */
    fun wake() { synchronized(lock) { lock.notifyAll() }; thread?.interrupt() }

    /** For the state JSON (never the token). */
    fun state(s: Store): JSONObject {
        val chats = s.tgChats()
        return JSONObject().put("on", s.tgToken.isNotBlank()).put("bot", s.tgBot).put("err", s.tgErr)
            .put("chats", JSONArray().apply { for (i in 0 until chats.length()) chats.optJSONObject(i)?.let { put(JSONObject().put("id", it.optLong("id").toString()).put("name", it.optString("name")).put("at", it.optLong("at"))) } })
            .put("code", s.tgCode() ?: JSONObject.NULL).put("codeUntil", if (s.tgCode() != null) s.tgCodeUntil() else 0)
    }

    /**
     * Commands from the parent page: tgSet {token, lang} (checks getMe, makes a link code), tgCode (new code),
     * tgUnlink {chat}, tgOff (forget everything).
     */
    fun command(g: Guard, t: String, c: JSONObject): String {
        val s = Store.of(g)
        return when (t) {
            "tgSet" -> {
                val tok = c.optString("token").trim()
                if (tok.isNotEmpty() && !TgLogic.tokenOk(tok)) return "bad token"
                val oldLang = s.tgLang
                c.optString("lang").takeIf { it == "vi" || it == "en" }?.let { s.tgLang = it }
                val name = c.optString("name").replace(Regex("[\\p{Cntrl}<>]"), "").trim().take(30)
                if (tok.isNotEmpty() && tok != s.tgToken) {
                    val me = runCatching { call(tok, "getMe", JSONObject()) }.getOrNull()
                    if (me == null || !me.optBoolean("ok")) return if (me == null) "telegram unreachable" else "bad token"
                    s.tgClear(); s.tgToken = tok; s.tgBot = me.optJSONObject("result")?.optString("username").orEmpty()
                    runCatching { call(tok, "deleteWebhook", JSONObject()) }   // getUpdates refuses while a webhook is set
                    runCatching { setCommands(tok, s.tgLang) }
                }
                else if (s.tgLang != oldLang && s.tgToken.isNotBlank()) s.tgToken.let { t0 -> work.execute { runCatching { setCommands(t0, s.tgLang) } } }
                if (name.isNotEmpty()) s.tgName = name   // after tgClear (a new token clears the old name)
                if (s.tgToken.isBlank()) return "bad token"
                s.tgNewCode(); s.tgErr = ""; start(g); wake(); "ok"
            }
            "tgCode" -> if (s.tgToken.isBlank()) "off" else { s.tgNewCode(); "ok" }
            "tgUnlink" -> {
                val id = c.optString("chat").toLongOrNull() ?: return "no such chat"
                val l = s.tgChatLang(id)
                if (!s.tgRemoveChat(id)) return "no such chat"
                runCatching { work.execute { runCatching { send(s, id, TgLogic.s(l, "unlinked")) } } }; "ok"
            }
            "tgOff" -> {
                val tok = s.tgToken; val chats = s.tgChats()
                s.tgClear(); wake()
                if (tok.isNotBlank()) work.execute { for (i in 0 until chats.length()) runCatching { call(tok, "sendMessage", JSONObject().put("chat_id", chats.optJSONObject(i).optLong("id")).put("text", TgLogic.s(chats.optJSONObject(i).optString("lang").ifBlank { "vi" }, "unlinked"))) } }
                "ok"
            }
            else -> "unknown"
        }
    }

    // ---------------- polling ----------------
    private fun loop(my: Int) {
        var backoff = 5_000L
        while (guard != null && gen == my) {
            val g = guard ?: break
            val s = Store.of(g)
            val tok = s.tgToken
            if (tok.isBlank() || s.released) { idle(10 * 60_000L); continue }
            try {
                val q = JSONObject().put("offset", s.tgOffset).put("timeout", TgLogic.POLL_S).put("allowed_updates", JSONArray().put("message").put("callback_query"))
                val r = call(tok, "getUpdates", q, (TgLogic.POLL_S + 15) * 1000)
                if (!r.optBoolean("ok")) {
                    when (r.optInt("error_code")) {
                        401, 404 -> { s.tgErr = "bad token"; idle(30 * 60_000L) }
                        409 -> { s.tgErr = "conflict"; if (!conflictFixed) { conflictFixed = true; runCatching { call(tok, "deleteWebhook", JSONObject()) } }; idle(60_000L) }
                        else -> idle(backoff)
                    }
                    continue
                }
                if (s.tgErr.isNotEmpty()) s.tgErr = ""
                if (!cmdsSet) { cmdsSet = true; runCatching { work.execute { runCatching { setCommands(tok, s.tgLang) } } } }
                backoff = 5_000L
                val a = r.optJSONArray("result") ?: JSONArray()
                for (i in 0 until a.length()) {
                    val u = a.optJSONObject(i) ?: continue
                    if (gen != my || s.tgToken != tok) break   // token changed / tgOff while this poll was in flight: not its offset
                    s.tgOffset = u.optLong("update_id") + 1   // before handling: one bad update must not loop forever
                    // a tapped button: stop its spinner at once (+ "working…" toast for actions; panels need none)
                    u.optJSONObject("callback_query")?.let { q -> runCatching { fast.execute { runCatching {
                        val d = q.optString("data")
                        call(tok, "answerCallbackQuery", JSONObject().put("callback_query_id", q.optString("id")).apply { if (!d.startsWith("n:")) put("text", TgLogic.s(s.tgChatLang(q.optJSONObject("message")?.optJSONObject("chat")?.optLong("id") ?: 0), "busy")) }, 8_000)
                    } } } }
                    runCatching { work.execute { runCatching { update(g, s, u) }.onFailure { e ->
                        Log.w(TAG, "tg update: " + redact(e))
                        // never a silent dead end: the chat that asked hears it failed
                        val chat = (u.optJSONObject("message") ?: u.optJSONObject("callback_query")?.optJSONObject("message"))?.optJSONObject("chat")?.optLong("id") ?: 0
                        if (chat != 0L && s.tgChatOk(chat)) runCatching { send(s, chat, TgLogic.s(s.tgChatLang(chat), "fail", e.javaClass.simpleName)) }
                    } } }
                }
            } catch (e: InterruptedException) {
                // wake(): token / chats changed
            } catch (e: Exception) {
                if (Thread.interrupted()) continue
                Log.i(TAG, "tg poll: ${e.javaClass.simpleName}")
                idle(backoff); backoff = minOf(backoff * 2, 60_000L)   // capped: commands answer within a minute of the network coming back
            }
        }
    }

    private fun idle(ms: Long) { try { synchronized(lock) { lock.wait(ms) } } catch (_: InterruptedException) {} }

    // ---------------- Bot API ----------------
    private fun call(tok: String, method: String, body: JSONObject, readMs: Int = 30_000, retry: Boolean = true): JSONObject {
        val r = call1(tok, method, body, readMs)
        if (retry && r.optInt("error_code") == 429 && method != "getUpdates") {   // flood limit: wait as told (<= 30 s), once
            Thread.sleep(r.optJSONObject("parameters")?.optLong("retry_after", 3)?.coerceIn(1, 30)?.times(1000) ?: 3_000)
            return call1(tok, method, body, readMs)
        }
        return r
    }
    private fun call1(tok: String, method: String, body: JSONObject, readMs: Int): JSONObject {
        val h = (URL("$API/bot$tok/$method").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 15_000; readTimeout = readMs; doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        try {
            h.outputStream.use { it.write(body.toString().toByteArray()) }
            val ins = if (h.responseCode < 400) h.inputStream else h.errorStream ?: throw IOException("HTTP ${h.responseCode}")
            return JSONObject(ins.use { String(it.readBytes()) })
        } finally { h.disconnect() }
    }

    /** multipart/form-data upload: [field] = the file, plus plain text fields. */
    private fun upload(tok: String, method: String, fields: Map<String, String>, field: String, name: String, mime: String, data: java.io.InputStream, len: Long): JSONObject {
        val b = "kg" + Store.randomHex(12)
        val safe = name.replace(Regex("[\"\\r\\n]"), "_")
        val head = (fields.entries.joinToString("") { (k, v) -> "--$b\r\nContent-Disposition: form-data; name=\"$k\"\r\n\r\n$v\r\n" } +
            "--$b\r\nContent-Disposition: form-data; name=\"$field\"; filename=\"$safe\"\r\nContent-Type: $mime\r\n\r\n").toByteArray()
        val tail = "\r\n--$b--\r\n".toByteArray()
        val h = (URL("$API/bot$tok/$method").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 15_000; readTimeout = 120_000; doOutput = true
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$b")
            setFixedLengthStreamingMode(head.size + len + tail.size)   // streamed: a 50 MB file is never held twice in memory
        }
        try {
            DataOutputStream(h.outputStream).use { o -> o.write(head); data.use { it.copyTo(o, 64 * 1024) }; o.write(tail) }
            val ins = if (h.responseCode < 400) h.inputStream else h.errorStream ?: throw IOException("HTTP ${h.responseCode}")
            return JSONObject(ins.use { String(it.readBytes()) })
        } finally { h.disconnect() }
    }

    private fun send(s: Store, chat: Long, text: String, kb: JSONObject? = null): JSONObject? {
        val tok = s.tgToken.ifBlank { return null }
        val r = call(tok, "sendMessage", JSONObject().put("chat_id", chat).put("text", text.take(4000)).apply { if (kb != null) put("reply_markup", kb) })
        if (r.optInt("error_code") == 403) s.tgRemoveChat(chat)   // the parent blocked the bot
        return r
    }
    private fun sendFile(s: Store, chat: Long, method: String, field: String, name: String, mime: String, data: ByteArray, caption: String = "", extra: Map<String, String> = emptyMap()): Boolean =
        sendStream(s, chat, method, field, name, mime, data.inputStream(), data.size.toLong(), caption, extra)
    private fun sendStream(s: Store, chat: Long, method: String, field: String, name: String, mime: String, data: java.io.InputStream, len: Long, caption: String = "", extra: Map<String, String> = emptyMap()): Boolean {
        val tok = s.tgToken.ifBlank { return false }
        val r = runCatching { upload(tok, method, mapOf("chat_id" to chat.toString(), "caption" to caption.take(1000)) + extra, field, name, mime, data, len) }
            .getOrElse { Log.w(TAG, "tg $method: " + redact(it)); return false }
        if (!r.optBoolean("ok")) Log.w(TAG, "tg $method: ${r.optString("description")}")
        return r.optBoolean("ok")
    }
    private fun edit(s: Store, chat: Long, msgId: Long, text: String, kb: JSONObject?) {
        val tok = s.tgToken.ifBlank { return }
        val r = call(tok, "editMessageText", JSONObject().put("chat_id", chat).put("message_id", msgId).put("text", text.take(4000)).apply { if (kb != null) put("reply_markup", kb) })
        // "message is not modified" (same text) is fine; anything else (too old...) -> a new message
        if (!r.optBoolean("ok") && !r.optString("description").contains("not modified")) send(s, chat, text, kb)
    }
    private fun chatAction(s: Store, chat: Long, action: String) {
        val tok = s.tgToken.ifBlank { return }
        runCatching { call(tok, "sendChatAction", JSONObject().put("chat_id", chat).put("action", action), 8_000) }
    }
    /** Commands whose answer is a panel / picker: from a button they replace that message. */
    private val PANELS = setOf("bonus", "pause", "limit", "bed", "rec", "block", "unblock", "files", "lang")
    private fun sendLoc(s: Store, chat: Long, loc: JSONObject) {
        val tok = s.tgToken.ifBlank { return }
        call(tok, "sendLocation", JSONObject().put("chat_id", chat).put("latitude", loc.optDouble("lat")).put("longitude", loc.optDouble("lng"))
            .apply { if (loc.optDouble("acc", 0.0) in 1.0..1500.0) put("horizontal_accuracy", loc.optDouble("acc")) })
    }
    /** Command list + descriptions for Telegram's own language (vi, en) and the default (the bot's language). */
    private fun setCommands(tok: String, lang: String) { for (l in listOf<String?>(null, "vi", "en")) setCommandsFor(tok, l ?: lang, l) }
    private fun setCommandsFor(tok: String, lang: String, code: String?) {
        fun body() = JSONObject().apply { if (code != null) put("language_code", code) }
        val names = listOf("menu", "status", "apps", "locate", "events", "bonus", "pause", "resume", "lock", "ring", "msg", "block", "unblock", "limit", "bed", "screen", "rec", "files", "help", "lang", "unlink")
        val help = TgLogic.s(lang, "help", "").lines().drop(1)
        val desc = names.associateWith { n -> help.firstOrNull { Regex("(^|\\s)/$n(\\s|$)").containsMatchIn(it) && it.contains(" – ") }?.substringAfter("– ")?.substringBefore(" · ")?.take(60) ?: n }
        call(tok, "setMyCommands", body().put("commands", JSONArray().apply { names.forEach { put(JSONObject().put("command", it).put("description", desc[it])) } }))
        // what an empty chat shows before Start, and the profile line
        call(tok, "setMyDescription", body().put("description", TgLogic.s(lang, "botDesc")))
        call(tok, "setMyShortDescription", body().put("short_description", TgLogic.s(lang, "botShort")))
    }

    private fun hm(t: Long) = SimpleDateFormat("HH:mm", Locale.US).format(Date(t))
    private fun kidName(g: Guard) = Store.of(g).tgName.ifBlank { "KidGuard" }
    /** f(chat) for every linked chat; returns how many succeeded. */
    private fun broadcast(s: Store, f: (Long) -> Boolean): Int { val a = s.tgChats(); var ok = 0
        for (i in 0 until a.length()) a.optJSONObject(i)?.optLong("id")?.let { id -> if (runCatching { f(id) }.onFailure { Log.w(TAG, "tg send: " + redact(it)) }.getOrDefault(false)) ok++ }
        return ok }
    private fun ok(r: JSONObject?) = r?.optBoolean("ok") == true
    /** Rule edits from several workers / chats: read-modify-write one at a time. */
    private fun editRules(g: Guard, s: Store, f: (Rules) -> Rules): Rules = synchronized(rulesLock) { f(s.rules).also { g.setRules(it) } }

    // ---------------- updates ----------------
    private fun update(g: Guard, s: Store, u: JSONObject) {
        u.optJSONObject("callback_query")?.let { return callback(g, s, it) }
        val m = u.optJSONObject("message") ?: return
        val chat = m.optJSONObject("chat") ?: return
        if (chat.optString("type") != "private") return   // groups: never (anyone in the group could act)
        val id = chat.optLong("id")
        val lang = if (s.tgChatOk(id)) s.tgChatLang(id) else TgLogic.langOf(m.optJSONObject("from")?.optString("language_code"), s.tgLang)
        val text = m.optString("text")
        if (!s.tgChatOk(id)) {
            val code = (TgLogic.parse(text)?.takeIf { it.name == "start" }?.rest ?: text).trim()
            if (Regex("^\\d{6}$").matches(code)) {
                val n = strangerTries.merge(id, 1, Int::plus) ?: 1
                if (n > TgLogic.CHAT_TRIES || !s.tgTryCode(code)) { send(s, id, TgLogic.s(lang, "badCode")); return }
                strangerTries.remove(id)
                val who = listOf(chat.optString("first_name"), chat.optString("last_name")).filter { it.isNotBlank() }.joinToString(" ").ifBlank { chat.optString("username") }
                if (!s.tgAddChat(id, who, lang)) { send(s, id, TgLogic.s(lang, "full", TgLogic.MAX_CHATS)); return }
                s.log(R.string.log_tg_linked, who)
                // the others hear about it: a code seen on a shared screen must not give silent control
                alerts.execute { broadcast(s) { c -> c != id && ok(send(s, c, TgLogic.s(s.tgChatLang(c), "newLink", who))) } }
                tick(g, markOnly = true)   // the past is not news to a new chat
                send(s, id, TgLogic.s(lang, "linked", kidName(g)), TgLogic.replyKb(lang))
                send(s, id, TgLogic.s(lang, "menu", kidName(g)), TgLogic.menu(lang)); g.kick()
            } else send(s, id, TgLogic.s(lang, "linkAsk"))
            return
        }
        m.optJSONObject("document")?.let { return fileIn(g, s, id, it) }
        m.optJSONArray("photo")?.let { a -> a.optJSONObject(a.length() - 1)?.let { p ->   // a photo (not "as file"): the biggest size
            return fileIn(g, s, id, JSONObject().put("file_id", p.optString("file_id")).put("file_size", p.optLong("file_size"))
                .put("file_name", "telegram-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.jpg").put("mime_type", "image/jpeg")) } }
        val asked = askMsg.remove(id)?.let { System.currentTimeMillis() - it < 10 * 60_000L } == true
        val c = TgLogic.parse(text) ?: TgLogic.fromButton(text)
            ?: if (asked && text.isNotBlank()) TgLogic.Cmd("msg", emptyList(), text.trim()) else null
        if (c == null) { send(s, id, TgLogic.s(lang, "menu", kidName(g)), TgLogic.menu(lang)); return }
        runCmd(g, s, id, c)
    }

    /** [panel] = the message whose button was tapped (0 = typed): panels and pickers replace it instead of piling up. */
    private fun runCmd(g: Guard, s: Store, chat: Long, c: TgLogic.Cmd, panel: Long = 0) {
        val lang = s.tgChatLang(chat); val now = System.currentTimeMillis()
        // "bot is typing / sending a photo / ..." in the chat header while it works
        chatAction(s, chat, when (c.name) { "locate" -> "find_location"; "screen" -> "upload_photo"; "rec" -> "record_voice"; "files" -> "upload_document"; else -> "typing" })
        fun show(text: String, kb: JSONObject?) { if (panel > 0) edit(s, chat, panel, text, kb) else send(s, chat, text, kb) }
        fun say(k: String, vararg a: Any?) { send(s, chat, TgLogic.s(lang, k, *a)) }
        fun res(r: String, okKey: String, vararg a: Any?) { if (r == "ok") say(okKey, *a) else say("fail", TgLogic.err(lang, r)) }
        when (c.name) {
            "start", "help" -> send(s, chat, TgLogic.s(lang, "help", kidName(g)), TgLogic.replyKb(lang))
            "menu" -> send(s, chat, TgLogic.s(lang, "menu", kidName(g)), TgLogic.menu(lang))
            "lang" -> {   // /lang, /lang en, /lang vi: this chat only; the reply keyboard is resent in the new language
                val l = c.args.firstOrNull()?.lowercase()?.let { TgLogic.langOf(it, lang) } ?: TgLogic.other(lang)
                s.tgSetChatLang(chat, l)
                send(s, chat, TgLogic.s(l, "langDone"), TgLogic.replyKb(l))
                if (panel > 0) edit(s, chat, panel, TgLogic.s(l, "menu", kidName(g)), TgLogic.menu(l)) else send(s, chat, TgLogic.s(l, "menu", kidName(g)), TgLogic.menu(l))
            }
            "status" -> send(s, chat, TgLogic.status(g.currentState(), kidName(g), lang, now, ::hm), TgLogic.statusKb(lang))
            "apps" -> send(s, chat, TgLogic.apps(g.currentState(), lang))
            "events" -> {
                val a = s.events(); val rows = (maxOf(0, a.length() - 10) until a.length()).reversed().mapNotNull { a.optJSONObject(it) }
                send(s, chat, if (rows.isEmpty()) TgLogic.s(lang, "noEvents") else TgLogic.s(lang, "events") + "\n" + rows.joinToString("\n") { "• ${hm(it.optLong("t"))} ${it.optString("text")}" })
            }
            "locate" -> {
                if (!locBusy.compareAndSet(false, true)) return say("busyOne")
                try { locate(g, s, chat, lang, now) } finally { locBusy.set(false) }
            }

            "bonus", "pause", "limit", "bed", "rec" ->
                if (c.args.isEmpty()) show(subTitle(s, c.name, lang), TgLogic.sub(lang, c.name)) else runArg(g, s, chat, c, panel)
            "lock" -> res(g.command(JSONObject().put("type", "lock")), "locked")
            "ring" -> res(g.command(JSONObject().put("type", "ring")), "ringing")
            "msg" -> if (c.rest.isBlank()) {
                askMsg[chat] = now
                send(s, chat, TgLogic.s(lang, "msgAsk"), JSONObject().put("force_reply", true).put("input_field_placeholder", TgLogic.s(lang, "msgPh")))
            } else res(g.command(JSONObject().put("type", "message").put("text", c.rest.take(500))), "msgSent")
            else -> runArg(g, s, chat, c, panel)
        }
    }

    private fun locate(g: Guard, s: Store, chat: Long, lang: String, now: Long) {
        fun say(k: String, vararg a: Any?) { send(s, chat, TgLogic.s(lang, k, *a)) }
        val before = s.location?.optLong("t") ?: 0
        g.command(JSONObject().put("type", "locate"))
        // the last known fix at once, labelled old when it is (> 5 min), then a fresh one usually within 5-20 s
        s.location?.let { sendLoc(s, chat, it); say(if (now - it.optLong("t") > 5 * 60_000L) "locOld" else "locAt", hm(it.optLong("t")), it.optDouble("acc").toInt()) } ?: say("locWait")
        for (i in 0 until 8) { Thread.sleep(4_000); val l = s.location; if (l != null && l.optLong("t") > before) { sendLoc(s, chat, l); say("locAt", hm(l.optLong("t")), l.optDouble("acc").toInt()); return } }
        if (s.location == null) say("noLoc")
    }

    private fun subTitle(s: Store, name: String, lang: String) = when (name) {
        "bonus" -> TgLogic.s(lang, "subBonus"); "pause" -> TgLogic.s(lang, "subPause"); "rec" -> TgLogic.s(lang, "subRec")
        "limit" -> TgLogic.s(lang, "subLimit", s.rules.let { r -> val m = if (r.dailyByDay.size == 7) r.dailyByDay[(java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7] else r.dailyMin; if (m > 0) TgLogic.fmt(m * 60_000L, lang) else TgLogic.s(lang, "none") })
        else -> TgLogic.s(lang, "subBed", TgLogic.s(lang, if (s.rules.bedtimeOn) "on" else "off"))
    }

    /** The rest of the commands (all take their argument, or need none). */
    private fun runArg(g: Guard, s: Store, chat: Long, c: TgLogic.Cmd, panel: Long = 0) {
        val lang = s.tgChatLang(chat); val now = System.currentTimeMillis()
        fun show(text: String, kb: JSONObject?) { if (panel > 0) edit(s, chat, panel, text, kb) else send(s, chat, text, kb) }
        val back = listOf(TgLogic.s(lang, "b.back") to "n:main")
        fun say(k: String, vararg a: Any?) { send(s, chat, TgLogic.s(lang, k, *a)) }
        fun res(r: String, okKey: String, vararg a: Any?) { if (r == "ok") say(okKey, *a) else say("fail", TgLogic.err(lang, r)) }
        fun rules(f: (Rules) -> Rules) { editRules(g, s, f) }
        when (c.name) {
            "bonus" -> {
                val n = TgLogic.minutes(c.args.firstOrNull() ?: "30", 1, 600) ?: return say("usage", "/bonus 30")
                val today = Guard.day(now); val r = editRules(g, s) { TgLogic.bonus(it, today, n, now) }; say("bonusDone", n, r.bonusMin)
            }
            "pause" -> {
                val n = TgLogic.minutes(c.args.firstOrNull() ?: "60", 1, 24 * 60) ?: return say("usage", "/pause 60")
                rules { TgLogic.pause(it, n, now) }; say("pauseDone", hm(now + n * 60_000L))
            }
            "resume" -> { rules { TgLogic.resume(it, now) }; say("resumeDone") }
            "limit" -> {
                val n = TgLogic.minutes(c.args.firstOrNull(), 0, 24 * 60) ?: return say("usage", "/limit 120")
                rules { TgLogic.limit(it, n, now) }; if (n == 0) say("limitOff") else say("limitDone", TgLogic.fmt(n * 60_000L, lang))
            }
            "bed" -> {
                val on = when (c.args.firstOrNull()?.lowercase()) { "on", "bật", "bat", "1" -> true; "off", "tắt", "tat", "0" -> false; else -> return say("usage", "/bed on | /bed off") }
                rules { TgLogic.bed(it, on, now) }; say("bedDone", TgLogic.s(lang, if (on) "on" else "off"))
            }
            "block", "unblock" -> {
                val un = c.name == "unblock"
                val list = TgLogic.pickApps(g.currentState(), s.rules.blocked, un).filter { ("b:" + it.first).toByteArray().size <= 64 }
                if (list.isEmpty()) return show(TgLogic.s(lang, if (un) "noBlocked" else "noApps"), TgLogic.keyboard(listOf(back)))
                show(TgLogic.s(lang, if (un) "unblockPick" else "blockPick"),
                    TgLogic.keyboard(list.map { (p, l) -> (if (un) "✅ " else "⛔ ") + l to (if (un) "u:" else "b:") + p }.chunked(2) + listOf(back)))
            }
            "screen" -> {
                say("shotWait")
                val f = Acc.capture(1280, 80)
                if (f == null) say("fail", TgLogic.err(lang, Acc.lastError))
                else if (!sendFile(s, chat, "sendPhoto", "photo", "screen.jpg", "image/jpeg", f.jpeg, hm(now))) say("fail", TgLogic.err(lang, "upload"))
            }
            "rec" -> {
                if (!recBusy.compareAndSet(false, true)) return say("busyOne")
                try { rec(g, s, chat, c, lang, now) } finally { recBusy.set(false) }
            }
            "files" -> {
                val l = Files.all(g).filter { it.size <= TgLogic.TG_SEND_MAX }.sortedByDescending { it.at }.take(20)
                if (l.isEmpty()) return show(TgLogic.s(lang, "noFiles"), TgLogic.keyboard(listOf(back)))
                show(TgLogic.s(lang, "files"), TgLogic.keyboard(l.map { listOf(it.name.take(40) to "f:" + it.id) } + listOf(back)))
            }
            "unlink" -> { s.tgRemoveChat(chat); say("unlinked"); g.kick() }
            else -> say("unknown")
        }
    }

    private fun rec(g: Guard, s: Store, chat: Long, c: TgLogic.Cmd, lang: String, now: Long) {
        fun say(k: String, vararg a: Any?) { send(s, chat, TgLogic.s(lang, k, *a)) }
        val n = (c.args.firstOrNull()?.toIntOrNull() ?: TgLogic.REC_DEF).coerceIn(5, TgLogic.REC_MAX)
        val ses = Listen.open(g) ?: return say("fail", TgLogic.err(lang, Listen.lastError))
        say("recWait", n)
        val out = ByteArrayOutputStream()
        val t = Thread({ runCatching { ses.pump(out) } }, "tg-rec").apply { start() }
        try { Thread.sleep(n * 1000L) } finally { ses.close(""); t.join(5_000) }
        val raw = out.toByteArray()
        if (raw.size < 256) return say("fail", TgLogic.err(lang, "no audio"))   // mic taken by a call / another app
        val name = "kidguard-${SimpleDateFormat("HHmm", Locale.US).format(Date(now))}"; val cap = TgLogic.s(lang, "recCap", n, hm(now))
        // M4A = a voice message that plays inline; raw .aac only as a file to download (fallback)
        val m4a = Adts.toM4a(raw, g.cacheDir)
        // + a "download" button: a voice message can't be saved as a file on every Telegram app; the tap sends the same audio as a document (kept 24 h)
        val key = m4a?.let { keepRec(g, name, it.first) }
        val kb = key?.let { TgLogic.keyboard(listOf(listOf(TgLogic.s(lang, "b.recFile") to "a:$it"))).toString() }
        if (m4a == null || !sendFile(s, chat, "sendVoice", "voice", "$name.m4a", "audio/mp4", m4a.first, cap,
                mapOf("duration" to (m4a.second / 1000).toString()) + (kb?.let { mapOf("reply_markup" to it) } ?: emptyMap())))
            if (!sendFile(s, chat, "sendDocument", "document", "$name.aac", "audio/aac", raw, cap)) say("fail", TgLogic.err(lang, "upload"))
    }

    /** A recording kept for its "download" button: cache/tgrec/<key>__<name>.m4a, older than 24 h pruned. */
    private fun recDir(g: Guard) = java.io.File(g.cacheDir, "tgrec").apply { mkdirs() }
    private fun keepRec(g: Guard, name: String, b: ByteArray): String? = runCatching {
        val d = recDir(g); val cut = System.currentTimeMillis() - 24 * 3600_000L
        d.listFiles()?.forEach { if (it.lastModified() < cut) it.delete() }
        val k = Store.randomHex(8); java.io.File(d, "${k}__$name.m4a").writeBytes(b); k
    }.getOrNull()

    private fun callback(g: Guard, s: Store, q: JSONObject) {
        val chat = q.optJSONObject("message")?.optJSONObject("chat")?.optLong("id") ?: return
        val tok = s.tgToken.ifBlank { return }
        val d = q.optString("data"); val lang = s.tgChatLang(chat); val now = System.currentTimeMillis()
        if (!s.tgChatOk(chat)) return
        val msgId = q.optJSONObject("message")?.optLong("message_id") ?: 0
        fun done(text: String) {   // the buttons go away, the answer replaces them
            runCatching { call(tok, "editMessageReplyMarkup", JSONObject().put("chat_id", chat).put("message_id", msgId).put("reply_markup", JSONObject().put("inline_keyboard", JSONArray()))) }
            send(s, chat, text)
        }
        val label = { p: String -> runCatching { g.currentState().optJSONArray("apps") }.getOrNull()?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.firstOrNull { it.optString("pkg") == p }?.optString("label") } ?: p }
        when {
            d.startsWith("c:") -> TgLogic.fromData(d)?.let { runCmd(g, s, chat, it, if (it.name in PANELS) msgId else 0) }
            d == "n:main" -> edit(s, chat, msgId, TgLogic.s(lang, "menu", kidName(g)), TgLogic.menu(lang))
            d.startsWith("n:") -> { val n = d.removePrefix("n:"); TgLogic.sub(lang, n)?.let { edit(s, chat, msgId, subTitle(s, n, lang), it) } }
            d == "r:status" -> edit(s, chat, msgId, TgLogic.status(g.currentState(), kidName(g), lang, now, ::hm), TgLogic.statusKb(lang))
            d.startsWith("ry:") -> {
                val (id, min) = d.removePrefix("ry:").split(':').let { it[0] to (it.getOrNull(1)?.toIntOrNull() ?: 15).coerceIn(1, 600) }
                if ((0 until s.requests().length()).none { s.requests().optJSONObject(it)?.optString("id") == id }) return done(TgLogic.s(lang, "reqGone"))
                editRules(g, s) { TgLogic.bonus(it, Guard.day(now), min, now) }
                g.command(JSONObject().put("type", "answer").put("id", id).put("ok", true).put("min", min))
                done(TgLogic.s(lang, "reqYes", min))
            }
            d.startsWith("rn:") -> {
                val id = d.removePrefix("rn:")
                if (g.command(JSONObject().put("type", "answer").put("id", id).put("ok", false)) != "ok") return done(TgLogic.s(lang, "reqGone"))
                done(TgLogic.s(lang, "reqNo"))
            }
            d.startsWith("b:") || d.startsWith("u:") -> {   // the picker becomes the answer, with "another one" + back
                val on = d[0] == 'b'; val p = d.substring(2)
                chatAction(s, chat, "typing")
                editRules(g, s) { TgLogic.block(it, p, on, now) }
                edit(s, chat, msgId, TgLogic.s(lang, if (on) "blocked" else "unblockedOne", label(p)), TgLogic.keyboard(listOf(listOf(
                    TgLogic.s(lang, if (on) "b.blockMore" else "b.unblockMore") to (if (on) "c:block" else "c:unblock"), TgLogic.s(lang, "b.back") to "n:main"))))
            }
            d.startsWith("a:") -> {
                chatAction(s, chat, "upload_document")
                val k = d.removePrefix("a:").takeIf { Regex("^[0-9a-f]{16}$").matches(it) }
                val f = k?.let { kk -> recDir(g).listFiles()?.firstOrNull { it.name.startsWith(kk + "__") } }
                    ?: return send(s, chat, TgLogic.s(lang, "fail", TgLogic.err(lang, "gone"))).let {}
                if (!sendStream(s, chat, "sendDocument", "document", f.name.substringAfter("__"), "audio/mp4", f.inputStream(), f.length())) send(s, chat, TgLogic.s(lang, "fail", TgLogic.err(lang, "upload")))
            }
            d.startsWith("f:") -> {
                chatAction(s, chat, "upload_document")
                val e = Files.get(g, d.removePrefix("f:")) ?: return send(s, chat, TgLogic.s(lang, "fail", TgLogic.err(lang, "gone"))).let {}
                val ins = Files.openIn(g, e) ?: return send(s, chat, TgLogic.s(lang, "fail", TgLogic.err(lang, "gone"))).let {}
                if (!sendStream(s, chat, "sendDocument", "document", e.name, e.mime, ins, e.size)) send(s, chat, TgLogic.s(lang, "fail", TgLogic.err(lang, "upload")))
            }
        }
    }

    /** A document from a linked parent -> the phone's Download/KidGuard, like a LAN upload (Files.receive). */
    private fun fileIn(g: Guard, s: Store, chat: Long, doc: JSONObject) {
        val lang = s.tgChatLang(chat); val tok = s.tgToken
        if (doc.optLong("file_size") > TgLogic.TG_FILE_MAX) { send(s, chat, TgLogic.s(lang, "fileBig")); return }
        val f = call(tok, "getFile", JSONObject().put("file_id", doc.optString("file_id"))).optJSONObject("result") ?: run { send(s, chat, TgLogic.s(lang, "fileBig")); return }
        val h = URL("$API/file/bot$tok/${f.optString("file_path")}").openConnection() as HttpURLConnection
        h.connectTimeout = 15_000; h.readTimeout = 60_000
        try {
            if (h.responseCode != 200) { send(s, chat, TgLogic.s(lang, "fail", "HTTP ${h.responseCode}")); return }
            val name = doc.optString("file_name").ifBlank { "telegram-file" }
            val (e, err) = h.inputStream.use { Files.receive(g, name, h.contentLengthLong.takeIf { it >= 0 } ?: f.optLong("file_size"), doc.optString("mime_type").ifBlank { null }, it) }
            if (e != null) send(s, chat, TgLogic.s(lang, "fileIn", e.name)) else send(s, chat, TgLogic.s(lang, "fail", err))
            g.kick()
        } finally { h.disconnect() }
    }

    // ---------------- alerts ----------------
    /**
     * Every Guard tick: new SOS / requests / events -> every linked chat. [markOnly] = just remember them (a chat was
     * just linked, or none is). A wrong-PIN event waits up to 10 s for its photo.
     */
    fun tick(g: Guard, markOnly: Boolean = false) {
        val s = Store.of(g)
        if (s.tgToken.isBlank()) return
        val now = System.currentTimeMillis()
        val seen = s.tgSeen().toHashSet()
        val sos = s.sos(); val reqs = s.requests(); val evs = s.events()
        val fresh = ArrayList<Pair<String, JSONObject>>()
        for (i in 0 until sos.length()) sos.optJSONObject(i)?.let { if ("s:" + it.optString("id") !in seen) fresh += "s:" + it.optString("id") to it }
        for (i in 0 until reqs.length()) reqs.optJSONObject(i)?.let { if ("r:" + it.optString("id") !in seen) fresh += "r:" + it.optString("id") to it }
        for (i in 0 until evs.length()) evs.optJSONObject(i)?.let {
            val k = "e:" + it.optString("id")
            if (k !in seen && (markOnly || it.optString("kind") != "tamper" || it.has("shot") || now - it.optLong("t") > 10_000)) fresh += k to it
        }
        if (fresh.isEmpty()) return
        if (markOnly || s.tgChats().length() == 0) { synchronized(seenLock) { s.tgSetSeen(TgLogic.remember(s.tgSeen(), fresh.map { it.first })) }; return }
        // seen only once at least one chat got it; a failed one is retried on a later tick (30 s apart), never lost.
        // Items older than a day are dropped instead (a phone offline for days does not flood the chat).
        val todo = fresh.filter { (k, o) ->
            if (now - o.optLong("t", now) > 24 * 3600_000L) { synchronized(seenLock) { s.tgSetSeen(TgLogic.remember(s.tgSeen(), listOf(k))) }; false }
            else (sending[k] ?: 0L) <= now && sending.put(k, Long.MAX_VALUE).let { true }
        }
        if (todo.isEmpty()) return
        val name = kidName(g)
        alerts.execute {
            for ((k, o) in todo) {
                val n = runCatching { alert(g, s, name, k, o) }.getOrDefault(0)
                if (n > 0) { synchronized(seenLock) { s.tgSetSeen(TgLogic.remember(s.tgSeen(), listOf(k))) }; sending.remove(k) }
                else sending[k] = System.currentTimeMillis() + 30_000L
            }
        }
    }

    /** One alert to every linked chat; how many got it. */
    private fun alert(g: Guard, s: Store, name: String, k: String, o: JSONObject): Int = broadcast(s) { chat ->
        val lang = s.tgChatLang(chat)
        when (k[0]) {
            's' -> ok(send(s, chat, TgLogic.s(lang, "aSos", name), TgLogic.keyboard(listOf(listOf(TgLogic.s(lang, "b.locate") to "c:locate", TgLogic.s(lang, "b.status") to "r:status")))))
                .also { if (it) s.location?.let { l -> runCatching { sendLoc(s, chat, l) } } }
            'r' -> {
                val min = o.optInt("min"); val note = o.optString("note").takeIf { it.isNotBlank() }?.let { "\n“$it”" } ?: ""
                val opts = (if (min > 0) listOf(min) else listOf(15, 30)).distinct()
                ok(send(s, chat, TgLogic.s(lang, "aReq", name, if (min > 0) TgLogic.s(lang, "min", min) else TgLogic.s(lang, "time"), note),
                    TgLogic.keyboard(listOf(opts.map { TgLogic.s(lang, "yes", it) to "ry:${o.optString("id")}:$it" } + (TgLogic.s(lang, "no") to "rn:${o.optString("id")}")))))
            }
            else -> {
                val kind = o.optString("kind")
                val text = TgLogic.s(lang, when (kind) { "newApp" -> "aNewApp"; "tamper" -> "aTamper"; else -> "aEvent" }, o.optString("text"))
                val shot = o.optString("shot").takeIf { it.isNotBlank() }?.let { Shot.file(g, it) }?.takeIf { it.exists() }
                (shot != null && sendStream(s, chat, "sendPhoto", "photo", "kidguard-${shot.nameWithoutExtension}.jpg", "image/jpeg", shot.inputStream(), shot.length(), text))
                    || ok(send(s, chat, text))
            }
        }
    }

    /** Release: the bot goes quiet and forgets everything. */
    fun clear(s: Store) { s.tgClear(); wake() }
}
