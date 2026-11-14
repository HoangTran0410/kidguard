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
    private fun <V> capped(n: Int): MutableMap<Long, V> = java.util.Collections.synchronizedMap(object : LinkedHashMap<Long, V>() {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<Long, V>?) = size > n })   // oldest entries drop first
    private val strangerTries = capped<Int>(500)   // wrong link codes per chat (one stranger can't burn the code)
    private val fast = Executors.newSingleThreadExecutor()   // button acks: never queued behind a slow command
    private val askMsg = java.util.concurrent.ConcurrentHashMap<Long, Long>()   // chat -> when "💬 Message" asked for the text
    /** One alert as sent to one chat: edited when anyone answers (kept in memory; after a restart old buttons just say "answered"). */
    private class Copy(val chat: Long, val msg: Long, val text: String, val at: Long = System.currentTimeMillis())
    private val copies = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.CopyOnWriteArrayList<Copy>>()   // "r:<req id>" / "s:<sos id>" -> every chat's message
    private val tapSeen = java.util.concurrent.ConcurrentHashMap<String, Long>()    // chat:message:data -> last tap (a double tap runs once)
    private class Undo(val before: Rules, val after: Rules, val at: Long)
    private val undos = java.util.concurrent.ConcurrentHashMap<String, Undo>()
    private val lostPending = capped<Pair<String, Long>>(20)   // chat -> (message typed with /lost, when)
    private val groupReplied = capped<Long>(200)               // group chat -> last "open a private chat" reply

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
                clear(s, g)
                if (tok.isNotBlank()) work.execute { for (i in 0 until chats.length()) runCatching { call(tok, "sendMessage", JSONObject().put("chat_id", chats.optJSONObject(i).optLong("id")).put("text", TgLogic.s(chats.optJSONObject(i).optString("lang").ifBlank { "vi" }, "unlinked"))) } }
                "ok"
            }
            else -> "unknown"
        }
    }

    // ---------------- polling ----------------
    private fun loop(my: Int) {
        var backoff = 5_000L; var notOk = 0
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
                        401, 404 -> { if (s.tgErr != "bad token") runCatching { s.event("tg", R.string.ev_tg_bad) }; s.tgErr = "bad token"; idle(30 * 60_000L) }
                        409 -> { s.tgErr = "conflict"; if (!conflictFixed) { conflictFixed = true; runCatching { call(tok, "deleteWebhook", JSONObject()) } }; idle(60_000L) }
                        // flood limit / Telegram's own trouble: wait as told, or longer each time (was a fixed 5 s for ever)
                        else -> idle(TgLogic.pollWaitMs(r.optInt("error_code"), r.optJSONObject("parameters")?.optLong("retry_after", 5) ?: 5, notOk++))
                    }
                    continue
                }
                notOk = 0
                val gap = TgLogic.gapMin(s.tgLastOk, System.currentTimeMillis()); s.tgLastOk = System.currentTimeMillis()
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
                    runCatching { work.execute { runCatching { update(g, s, u, gap) }.onFailure { e ->
                        Log.w(TAG, "tg update: " + redact(e))
                        // never a silent dead end: the chat that asked hears it failed
                        val chat = (u.optJSONObject("message") ?: u.optJSONObject("callback_query")?.optJSONObject("message"))?.optJSONObject("chat")?.optLong("id") ?: 0
                        if (chat != 0L && s.tgChatOk(chat)) runCatching { send(s, chat, TgLogic.s(s.tgChatLang(chat), "fail", e.javaClass.simpleName)) }
                    } } }
                }
            } catch (e: InterruptedException) {
                // wake(): token / chats changed
            } catch (e: Throwable) {   // an Error must not end the thread for good (audit L27); tick() also restarts a dead one
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

    private fun mid(r: JSONObject?) = r?.optJSONObject("result")?.optLong("message_id") ?: 0L
    private fun send(s: Store, chat: Long, text: String, kb: JSONObject? = null, silent: Boolean = false): JSONObject? {
        val tok = s.tgToken.ifBlank { return null }
        // a long text goes out whole, as several messages (the buttons under the last one); the reply of the last part is returned
        val parts = TgLogic.split(text)
        var r: JSONObject? = null
        for ((i, part) in parts.withIndex()) {
            val last = i == parts.size - 1
            r = call(tok, "sendMessage", JSONObject().put("chat_id", chat).put("text", part).apply { if (kb != null && last) put("reply_markup", kb); if (silent) put("disable_notification", true) })
            if (r.optInt("error_code") == 403) { s.tgRemoveChat(chat); break }   // the parent blocked the bot
            if (!r.optBoolean("ok")) break
        }
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
        if (text.length > TgLogic.MSG_MAX) { send(s, chat, text, kb); return }   // an edit cannot become several messages
        val r = call(tok, "editMessageText", JSONObject().put("chat_id", chat).put("message_id", msgId).put("text", text).apply { if (kb != null) put("reply_markup", kb) })
        // "message is not modified" (same text) is fine; anything else (too old...) -> a new message
        if (!r.optBoolean("ok") && !r.optString("description").contains("not modified")) send(s, chat, text, kb)
    }
    private fun track(key: String, chat: Long, r: JSONObject?, text: String) {
        val m = mid(r); if (m > 0) copies.getOrPut(key) { java.util.concurrent.CopyOnWriteArrayList() }.add(Copy(chat, m, text))
    }
    /** Every copy of an alert becomes "<alert text>\n<answer>" (buttons gone), in each chat's language. */
    private fun resolve(s: Store, list: List<Copy>, text: (String) -> String) {
        for (c in list) runCatching { edit(s, c.chat, c.msg, (if (c.text.isNotEmpty()) c.text + "\n" else "") + text(s.tgChatLang(c.chat)), null) }
    }
    private fun chatName(s: Store, chat: Long): String = s.tgChats().let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }
        .firstOrNull { it.optLong("id") == chat }?.optString("name") }?.takeIf { it.isNotBlank() } ?: "?"
    private fun chatAction(s: Store, chat: Long, action: String) {
        val tok = s.tgToken.ifBlank { return }
        runCatching { call(tok, "sendChatAction", JSONObject().put("chat_id", chat).put("action", action), 8_000) }
    }
    /** Commands whose answer is a panel / picker: from a button they replace that message. */
    private val PANELS = setOf("bonus", "pause", "limit", "bed", "rec", "block", "unblock", "files", "lang", "quiet", "lost")
    private fun sendLoc(s: Store, chat: Long, loc: JSONObject) {
        val tok = s.tgToken.ifBlank { return }
        call(tok, "sendLocation", JSONObject().put("chat_id", chat).put("latitude", loc.optDouble("lat")).put("longitude", loc.optDouble("lng"))
            .apply { if (loc.optDouble("acc", 0.0) in 1.0..1500.0) put("horizontal_accuracy", loc.optDouble("acc")) })
    }
    /** Command list + descriptions for Telegram's own language (vi, en) and the default (the bot's language). */
    private fun setCommands(tok: String, lang: String) { for (l in listOf<String?>(null, "vi", "en")) setCommandsFor(tok, l ?: lang, l) }
    private fun setCommandsFor(tok: String, lang: String, code: String?) {
        fun body() = JSONObject().apply { if (code != null) put("language_code", code) }
        val names = listOf("menu", "status", "apps", "locate", "events", "bonus", "pause", "resume", "lock", "ring", "msg", "block", "unblock", "limit", "bed", "screen", "rec", "files", "code", "requests", "quiet", "digest", "lost", "help", "lang", "unlink")
        val help = TgLogic.s(lang, "help", "").lines().drop(1)
        val desc = names.associateWith { n -> help.firstOrNull { Regex("(^|\\s)/$n(\\s|$)").containsMatchIn(it) && it.contains(" – ") }?.substringAfter("– ")?.substringBefore(" · ")?.take(60) ?: n }
        call(tok, "setMyCommands", body().put("commands", JSONArray().apply { names.forEach { put(JSONObject().put("command", it).put("description", desc[it])) } }))
        // what an empty chat shows before Start, and the profile line
        call(tok, "setMyDescription", body().put("description", TgLogic.s(lang, "botDesc")))
        call(tok, "setMyShortDescription", body().put("short_description", TgLogic.s(lang, "botShort")))
    }

    private fun hm(t: Long) = SimpleDateFormat("HH:mm", Locale.US).format(Date(t))
    /** The child's name plus the phone model: a family with several phones (or a test emulator) sees which one spoke. */
    private fun kidName(g: Guard) = Store.of(g).tgName.ifBlank { "KidGuard" } + " · " + android.os.Build.MODEL
    /** f(chat) for every linked chat; returns how many succeeded. */
    private fun broadcast(s: Store, f: (Long) -> Boolean): Int { val a = s.tgChats(); var ok = 0
        for (i in 0 until a.length()) a.optJSONObject(i)?.optLong("id")?.let { id -> if (runCatching { f(id) }.onFailure { Log.w(TAG, "tg send: " + redact(it)) }.getOrDefault(false)) ok++ }
        return ok }
    private fun ok(r: JSONObject?) = r?.optBoolean("ok") == true
    /** Rule edits from several workers / chats: read-modify-write one at a time. */
    private fun editRules(g: Guard, s: Store, f: (Rules) -> Rules): Rules = synchronized(rulesLock) { f(s.baseRules).also { g.setRules(it) } }
    /** Same, returning (before, after) for an undo. */
    private fun editRules2(g: Guard, s: Store, f: (Rules) -> Rules): Pair<Rules, Rules> = synchronized(rulesLock) { val b = s.baseRules; val a = f(b); g.setRules(a); b to a }

    private val strangerReplied = capped<Long>(500)   // chat -> last reply time: at most one reply per 5 s to an unlinked chat
    private fun strangerReply(s: Store, id: Long, text: String) {
        val now = System.currentTimeMillis()
        if (now - (strangerReplied[id] ?: 0L) < 5_000L) return   // a parent typing a wrong code must still see "wrong code"
        strangerReplied[id] = now; send(s, id, text)
    }

    // ---------------- updates ----------------
    private fun update(g: Guard, s: Store, u: JSONObject, gap: Int = 0) {
        u.optJSONObject("callback_query")?.let { return callback(g, s, it, gap) }
        val m = u.optJSONObject("message") ?: return
        val chat = m.optJSONObject("chat") ?: return
        if (chat.optString("type") != "private") {   // groups: never act (anyone in the group could); one reply per 10 min says why
            val gid = chat.optLong("id"); val now = System.currentTimeMillis()
            if (m.optString("text").startsWith("/") && now - (groupReplied[gid] ?: 0L) > 10 * 60_000L) {
                groupReplied[gid] = now
                send(s, gid, TgLogic.s(TgLogic.langOf(m.optJSONObject("from")?.optString("language_code"), s.tgLang), "groupHint"))
            }
            return
        }
        val id = chat.optLong("id")
        val lang = if (s.tgChatOk(id)) s.tgChatLang(id) else TgLogic.langOf(m.optJSONObject("from")?.optString("language_code"), s.tgLang)
        val text = m.optString("text")
        if (!s.tgChatOk(id)) {
            val code = (TgLogic.parse(text)?.takeIf { it.name == "start" }?.rest ?: text).trim()
            if (Regex("^\\d{6}$").matches(code)) {
                val n = strangerTries.merge(id, 1, Int::plus) ?: 1
                if (n > TgLogic.CHAT_TRIES || !s.tgTryCode(code)) { strangerReply(s, id, TgLogic.s(lang, "badCode")); return }
                strangerTries.remove(id)
                val who = listOf(chat.optString("first_name"), chat.optString("last_name")).filter { it.isNotBlank() }.joinToString(" ").ifBlank { chat.optString("username") }
                if (!s.tgAddChat(id, who, lang)) { send(s, id, TgLogic.s(lang, "full", TgLogic.MAX_CHATS)); return }
                s.log(R.string.log_tg_linked, who)
                // the others hear about it: a code seen on a shared screen must not give silent control
                alerts.execute { broadcast(s) { c -> c != id && ok(send(s, c, TgLogic.s(s.tgChatLang(c), "newLink", who))) } }
                tick(g, markOnly = true)   // the past is not news to a new chat
                send(s, id, TgLogic.s(lang, "linked", kidName(g)), TgLogic.replyKb(lang))
                send(s, id, TgLogic.s(lang, "menu", kidName(g)), TgLogic.menu(lang)); g.kick()
            } else strangerReply(s, id, TgLogic.s(lang, "linkAsk"))
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
        // Telegram keeps undelivered messages ~24 h: a /lock typed while the phone was off must not fire the next morning (audit M8)
        val now = System.currentTimeMillis()
        if (TgLogic.isAction(c.name) && TgLogic.tooOld(m.optLong("date"), now)) { send(s, id, TgLogic.s(lang, "tooOld", (now - m.optLong("date") * 1000) / 60_000)); return }
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
            "requests" -> {
                val a = s.requests()
                if (a.length() == 0) return say("noReqs")
                say("reqsTitle", a.length())
                for (i in 0 until a.length()) a.optJSONObject(i)?.let { o -> sendReq(s, chat, lang, kidName(g), o) }
            }
            "quiet" -> {
                val l = c.args.firstOrNull()?.lowercase()?.takeIf { it == "all" || it == "important" || it == "sos" }
                if (l != null) { s.tgSetChatQuiet(chat, l); show(TgLogic.s(lang, "quietDone", TgLogic.s(lang, "q.$l")), TgLogic.quietKb(lang, l)) }
                else s.tgChatQuiet(chat).let { cur -> show(TgLogic.s(lang, "subQuiet", TgLogic.s(lang, "q.$cur")), TgLogic.quietKb(lang, cur)) }
            }
            "digest" -> {   // evening summary: /digest on|off|HH:mm, none = state + a preview now
                val a = c.args.firstOrNull()?.lowercase()
                val (on0, at0, _) = s.tgChatDigest(chat)
                when {
                    a == "on" -> { s.tgSetChatDigest(chat, on = true); say("digestOn", at0) }
                    a == "off" -> { s.tgSetChatDigest(chat, on = false); say("digestOff") }
                    a != null && Digest.minutes(a) != null -> { s.tgSetChatDigest(chat, on = true, at = "%02d:%02d".format(Digest.minutes(a)!! / 60, Digest.minutes(a)!! % 60)); say("digestOn", s.tgChatDigest(chat).second) }
                    else -> {
                        say(if (on0) "digestIs" else "digestIsOff", at0)
                        send(s, chat, Digest.daily(Digest.fromState(g.currentState(), kidName(g)), lang))
                    }
                }
            }
            "lost" -> {
                val first = c.args.firstOrNull()?.lowercase(); val on = s.rules.lostOn
                when (first) {
                    "off" -> if (!on) say("lostNone") else { editRules(g, s) { TgLogic.lost(it, false, null, now) }; show(TgLogic.s(lang, "lostOffDone"), null) }
                    "go" -> {   // the confirm button (or typed): the message typed with /lost is used
                        val msg = TgLogic.lostMsgOf(c) ?: lostPending.remove(chat)?.takeIf { now - it.second < 10 * 60_000L }?.first
                        editRules(g, s) { TgLogic.lost(it, true, msg, now) }; show(TgLogic.s(lang, "lostOnDone"), null)
                    }
                    else -> {
                        TgLogic.lostMsgOf(c)?.let { lostPending[chat] = it to now }
                        val msg = lostPending[chat]?.takeIf { now - it.second < 10 * 60_000L }?.first ?: s.rules.lostMsg
                        if (on) show(TgLogic.s(lang, "lostIs"), TgLogic.lostKb(lang, true))
                        else show(TgLogic.s(lang, "lostAsk", kidName(g), if (msg.isNotBlank()) TgLogic.s(lang, "lostMsgLine", msg) else ""), TgLogic.lostKb(lang, false))
                    }
                }
            }
            "code" -> TgLogic.codeNow(s.totpSecret, now)?.let { (c1, left, c2) -> say("code", c1, left, c2) } ?: say("codeOff")
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
        // from a panel button the panel becomes the result with an Undo (a double tap can't add twice); typed = a plain reply
        fun saved(before: Rules, after: Rules, text: String) {
            if (panel <= 0) return say2(s, chat, text)
            val k = Store.randomHex(4); undos.entries.removeIf { now - it.value.at > 3600_000L }; undos[k] = Undo(before, after, now)
            edit(s, chat, panel, text, TgLogic.undoKb(lang, k))
        }
        when (c.name) {
            "bonus" -> {
                val n = TgLogic.minutes(c.args.firstOrNull() ?: "30", 1, 600) ?: return say("usage", "/bonus 30")
                val today = Guard.day(now); val why = TgLogic.whyOf(g.currentState())
                val (b0, a0) = editRules2(g, s) { TgLogic.give(it, why, n, now, today).first }
                if (a0.passUntil != b0.passUntil) saved(b0, a0, TgLogic.s(lang, "passDone", n, hm(a0.passUntil), TgLogic.whyName(lang, why)))
                else saved(b0, a0, TgLogic.s(lang, if (panel > 0) "bonusPanel" else "bonusDone", n, a0.bonusMin))
            }
            "pause" -> {
                val n = TgLogic.minutes(c.args.firstOrNull() ?: "60", 1, 24 * 60) ?: return say("usage", "/pause 60")
                val (b0, a0) = editRules2(g, s) { TgLogic.pause(it, n, now) }; saved(b0, a0, TgLogic.s(lang, if (panel > 0) "pausePanel" else "pauseDone", hm(now + n * 60_000L)))
            }
            "resume" -> { rules { TgLogic.resume(it, now) }; say("resumeDone") }
            "limit" -> {
                val (scope, ms) = TgLogic.limitArgs(c.args)
                val n = TgLogic.minutes(ms, 0, 24 * 60) ?: return say("usage", "/limit 120")
                val label = if (n == 0) TgLogic.s(lang, "none") else TgLogic.fmt(n * 60_000L, lang)
                if (scope == "" && TgLogic.limitDiffers(s.rules))   // would flatten a per-weekday list: ask first
                    return show(TgLogic.s(lang, "limitAsk", TgLogic.limitSummary(s.rules, lang), label), TgLogic.limitAskKb(lang, n))
                val (b0, a0) = editRules2(g, s) { if (scope == "today") TgLogic.limitToday(it, n, Rules.isoDow(Guard.day(now)), now) else TgLogic.limit(it, n, now) }
                saved(b0, a0, if (scope == "today") TgLogic.s(lang, "limitTodayDone", label) else if (n == 0) TgLogic.s(lang, "limitOff") else TgLogic.s(lang, if (panel > 0) "limitPanel" else "limitDone", label))
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

    private fun say2(s: Store, chat: Long, text: String) { send(s, chat, text) }

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

    /**
     * A recording kept for its "download" button: files/tgrec/<key>__<name>.m4a. Kept 7 days, at most 100 MB, oldest first
     * dropped early when the phone has < 500 MB free (the voice itself stays in the Telegram chat for good).
     */
    private fun recDir(g: android.content.Context) = java.io.File(g.filesDir, "tgrec").apply { mkdirs() }
    private fun keepRec(g: Guard, name: String, b: ByteArray): String? = runCatching {
        val d = recDir(g); val cut = System.currentTimeMillis() - 7 * 86400_000L
        d.listFiles()?.forEach { if (it.lastModified() < cut) it.delete() }
        val left = (d.listFiles() ?: emptyArray()).sortedBy { it.lastModified() }.toMutableList()
        while (left.isNotEmpty() && (left.sumOf { it.length() } + b.size > 100L * 1048576 || d.usableSpace < 500L * 1048576)) left.removeAt(0).delete()
        val k = Store.randomHex(8); java.io.File(d, "${k}__$name.m4a").writeBytes(b); k
    }.getOrNull()

    /** An open request with its buttons; the message is remembered so every copy can be edited when anyone answers. */
    private fun sendReq(s: Store, chat: Long, lang: String, name: String, o: JSONObject, silent: Boolean = false): Boolean {
        val text = TgLogic.reqText(lang, name, o)
        val r = send(s, chat, text, TgLogic.reqKb(lang, o), silent)
        if (ok(r)) track("r:" + TgLogic.reqId(o), chat, r, text)
        return ok(r)
    }

    private fun callback(g: Guard, s: Store, q: JSONObject, gap: Int = 0) {
        val chat = q.optJSONObject("message")?.optJSONObject("chat")?.optLong("id") ?: return
        val tok = s.tgToken.ifBlank { return }
        val d = q.optString("data"); val lang = s.tgChatLang(chat); val now = System.currentTimeMillis()
        if (!s.tgChatOk(chat)) return
        val msgId = q.optJSONObject("message")?.optLong("message_id") ?: 0
        val baseText = q.optJSONObject("message")?.optString("text").orEmpty()
        if (TgLogic.isActionData(d)) {
            // the phone was offline: a tap queued then must not run now. And a double tap runs once.
            if (gap > 0) { send(s, chat, TgLogic.s(lang, "gapOld", gap)); return }
            val tk = "$chat:$msgId:$d"; tapSeen.entries.removeIf { now - it.value > 10_000L }
            if (tapSeen.put(tk, now)?.let { now - it < 4_000L } == true) return
        }
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
            d.startsWith("ry:") || d.startsWith("rn:") || d.startsWith("ra:") || d.startsWith("rb:") -> {
                val kind = d.substring(0, 2); val parts = d.substring(3).split(':'); val id = parts[0]
                val yes = kind == "ry" || kind == "ra"; val min = (parts.getOrNull(1)?.toIntOrNull() ?: 15).coerceIn(1, 600)
                val o = (0 until s.requests().length()).mapNotNull { s.requests().optJSONObject(it) }.firstOrNull { it.optString("id") == id }
                val app = kind == "ra" || kind == "rb"
                // claim the copies first: the tick must not word this answer as "from the page" while we are still working
                val list = (copies.remove("r:$id")?.toMutableList() ?: mutableListOf()).also { l -> if (l.none { it.chat == chat && it.msg == msgId }) l += Copy(chat, msgId, baseText) }
                // "req", not "id": a repeated command id is answered "ok" without running (Guard.command), which would let a second tap through
                val cmd = JSONObject().put("type", "answer").put("req", id).put("ok", yes).apply { if (app && yes) put("always", true); if (!app && yes) put("min", min) }
                if (g.command(cmd) != "ok") { copies["r:$id"] = java.util.concurrent.CopyOnWriteArrayList(list.filter { it.chat != chat || it.msg != msgId }); return done(TgLogic.s(lang, "reqGone")) }
                val who = chatName(s, chat)
                if (app) { o?.optString("pkg")?.takeIf { it.isNotBlank() }?.let { p -> editRules(g, s) { TgLogic.approve(it, p, yes, now) } } }
                else if (yes) { val why = TgLogic.whyOf(g.currentState()); editRules(g, s) { TgLogic.give(it, why, min, now, Guard.day(now)).first } }
                val appLabel = o?.let { it.optString("app").ifBlank { it.optString("pkg") } } ?: ""
                resolve(s, list) { l -> TgLogic.answered(l, who, yes, if (app) "app" else "time", min, appLabel, now, ::hm) }
            }
            d.startsWith("ay:") || d.startsWith("an:") -> {   // a new-app alert: allow / block
                val yes = d[1] == 'y'; val p = d.substring(3)
                editRules(g, s) { TgLogic.approve(it, p, yes, now) }
                edit(s, chat, msgId, baseText + "\n" + TgLogic.answered(lang, chatName(s, chat), yes, "app", 0, label(p), now, ::hm), null)
            }
            d.startsWith("k:") -> {   // SOS: "got it" (clears it on the phone and tells the child)
                val id = d.removePrefix("k:")
                val list = (copies.remove("s:$id")?.toMutableList() ?: mutableListOf()).also { l -> if (l.none { it.chat == chat && it.msg == msgId }) l += Copy(chat, msgId, baseText) }
                g.command(JSONObject().put("type", "sosAck").put("sos", id))
                val who = chatName(s, chat)
                resolve(s, list) { l -> TgLogic.s(l, "ansSos", who, hm(now)) }
            }
            d.startsWith("z:") -> {   // undo of a panel change
                val u = undos.remove(d.removePrefix("z:"))
                if (u == null) edit(s, chat, msgId, TgLogic.s(lang, "undoGone"), TgLogic.keyboard(listOf(listOf(TgLogic.s(lang, "b.back") to "n:main"))))
                else { editRules(g, s) { TgLogic.undo(it, u.before, u.after, now) }
                    edit(s, chat, msgId, TgLogic.s(lang, "undone"), TgLogic.keyboard(listOf(listOf(TgLogic.s(lang, "b.back") to "n:main")))) }
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
        start(g)   // no-op while the poll thread lives; brings a dead one back
        val now = System.currentTimeMillis()
        if (!markOnly) digestTick(g, s, now)
        if (copies.isNotEmpty()) alerts.execute { runCatching { resolveGone(s, now) } }
        val seen = s.tgSeen().toHashSet()
        val sos = s.sos(); val reqs = s.requests(); val evs = s.events()
        val fresh = ArrayList<Pair<String, JSONObject>>()
        for (i in 0 until sos.length()) sos.optJSONObject(i)?.let { if ("s:" + it.optString("id") !in seen) fresh += "s:" + it.optString("id") to it }
        for (i in 0 until reqs.length()) reqs.optJSONObject(i)?.let { val k = TgLogic.reqKey(it); if (k !in seen) fresh += k to it }
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
            // several plain events at once (the phone was offline): one message per chat instead of a ping each
            val plain = todo.filter { (k, o) -> TgLogic.digestable(k, o.optString("kind"), o.optString("shot").isNotBlank()) }.takeIf { it.size >= TgLogic.DIGEST_MIN } ?: emptyList()
            if (plain.isNotEmpty()) {
                val n = runCatching { digest(s, name, plain) }.getOrDefault(0)
                for ((k, _) in plain) if (n > 0) { synchronized(seenLock) { s.tgSetSeen(TgLogic.remember(s.tgSeen(), listOf(k))) }; sending.remove(k) } else sending[k] = System.currentTimeMillis() + 30_000L
            }
            for ((k, o) in todo) {
                if (plain.any { it.first == k }) continue
                val n = runCatching { alert(g, s, name, k, o) }.getOrDefault(0)
                if (n > 0) { synchronized(seenLock) { s.tgSetSeen(TgLogic.remember(s.tgSeen(), listOf(k))) }; sending.remove(k) }
                else sending[k] = System.currentTimeMillis() + 30_000L
            }
        }
    }

    /** Evening summary: every chat that turned it on (/digest) gets today's message once after its time; on Sunday the weekly one follows. */
    private fun digestTick(g: Guard, s: Store, now: Long) {
        val chats = s.tgChats(); val today = Digest.dayKey(now, java.util.TimeZone.getDefault())
        val due = (0 until chats.length()).mapNotNull { chats.optJSONObject(it)?.optLong("id") }.filter { id ->
            val (on, at, last) = s.tgChatDigest(id); Digest.due(on, at, last, now) && (digestBusy[id] ?: 0L) <= now }
        if (due.isEmpty()) return
        due.forEach { digestBusy[it] = now + 30_000L }
        alerts.execute {
            val inp = Digest.fromState(g.currentState(), kidName(g))
            for (id in due) {
                val lang = s.tgChatLang(id)
                val sent = runCatching { Digest.messages(inp, lang, now).map { ok(send(s, id, it, null, true)) }.all { it } }.getOrDefault(false)
                if (sent) { s.tgSetChatDigest(id, day = today); digestBusy.remove(id) }
            }
        }
    }
    private val digestBusy = capped<Long>(20)

    /** Alerts whose request / SOS is gone (answered on the page, in the parent app, by another chat, or expired): every copy says so. */
    private fun resolveGone(s: Store, now: Long) {
        val reqs = s.requests().let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("id") }.toSet() }
        val sos = s.sos().let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("id") }.toSet() }
        for (key in copies.keys.toList()) {
            val list = copies[key] ?: continue
            val id = key.substring(2)
            if (list.all { now - it.at > 36 * 3600_000L }) { copies.remove(key); continue }
            if (if (key[0] == 'r') id in reqs else id in sos) continue
            copies.remove(key) ?: continue
            if (key[0] == 's') { resolve(s, list) { l -> TgLogic.s(l, "ansSos", TgLogic.s(l, "who.app"), hm(now)) }; continue }
            val la = runCatching { s.lastAnswer() }.getOrNull()?.takeIf { it.optLong("at") >= (list.minOfOrNull { c -> c.at } ?: 0L) - 5_000L }
            resolve(s, list) { l ->
                if (la == null) TgLogic.s(l, "ansGone")
                else TgLogic.answered(l, TgLogic.s(l, "who.app"), la.optBoolean("ok"), la.optString("kind").ifBlank { "time" }, la.optInt("min"), la.optString("app"), la.optLong("at").takeIf { it > 0 } ?: now, ::hm)
            }
        }
    }

    /** [items] as one message per chat (each chat only what its alert level lets through; silent when all of it is "info"). */
    private fun digest(s: Store, name: String, items: List<Pair<String, JSONObject>>): Int = broadcast(s) { chat ->
        val lang = s.tgChatLang(chat); val level = s.tgChatQuiet(chat)
        val mine = items.map { it.second }.map { o -> o to TgLogic.importance(o.optString("kind"), runCatching { Events.important(o.optString("kind")) }.getOrDefault(false)) }
            .filter { TgLogic.quietOk(level, it.second) }
        if (mine.isEmpty()) return@broadcast true
        if (mine.size == 1) return@broadcast ok(send(s, chat, TgLogic.s(lang, "aEvent", mine[0].first.optString("text"), name), null, mine[0].second == "info"))
        ok(send(s, chat, TgLogic.digest(lang, name, mine.map { it.first.optLong("t") to it.first.optString("text") }, ::hm), null, mine.all { it.second == "info" }))
    }

    /** One alert to every linked chat; how many got it (a chat whose alert level skips it counts as served). */
    private fun alert(g: Guard, s: Store, name: String, k: String, o: JSONObject): Int {
        val kind = o.optString("kind")
        val imp = when (k[0]) { 's' -> "sos"; 'r' -> "important"; else -> TgLogic.importance(kind, runCatching { Events.important(kind) }.getOrDefault(false)) }
        val now = System.currentTimeMillis()
        val n = broadcast(s) { chat ->
            val lang = s.tgChatLang(chat)
            if (!TgLogic.quietOk(s.tgChatQuiet(chat), imp)) return@broadcast true
            when (k[0]) {
                's' -> {
                    val text = TgLogic.s(lang, "aSos", name)
                    val r = send(s, chat, text, TgLogic.sosKb(lang, o.optString("id")))
                    ok(r).also { if (it) {
                        track("s:" + o.optString("id"), chat, r, text)
                        // the last stored fix at once, labelled with its time and accuracy (old ones say so); the fresh one follows (sosFollow)
                        s.location?.let { l -> runCatching { sendLoc(s, chat, l); send(s, chat, TgLogic.pinLabel(lang, l, now, ::hm)) } }
                    } }
                }
                'r' -> sendReq(s, chat, lang, name, o)
                else -> {
                    val text = TgLogic.s(lang, when (kind) { "newApp" -> "aNewApp"; "tamper" -> "aTamper"; else -> "aEvent" }, o.optString("text"), name)
                    val silent = imp == "info"   // info alerts arrive without a ring
                    val shot = o.optString("shot").takeIf { it.isNotBlank() }?.let { Shot.file(g, it) }?.takeIf { it.exists() }
                    val pkg = if (kind == "newApp") runCatching { TgLogic.newAppPkg(g.currentState(), o.optString("text")) }.getOrNull() else null
                    (shot != null && sendStream(s, chat, "sendPhoto", "photo", "kidguard-${shot.nameWithoutExtension}.jpg", "image/jpeg", shot.inputStream(), shot.length(), text, if (silent) mapOf("disable_notification" to "true") else emptyMap()))
                        || ok(send(s, chat, text, pkg?.let { TgLogic.newAppKb(lang, it) }, silent))
                }
            }
        }
        if (k[0] == 's' && n > 0) work.execute { runCatching { sosFollow(s, o.optLong("t")) } }
        return n
    }

    /** After an SOS: when a location fix newer than the press arrives (the phone was told to locate), send it as a second labelled pin. */
    private fun sosFollow(s: Store, since: Long) {
        if ((s.location?.optLong("t") ?: 0L) >= since) return   // the first pin was already a fix taken after the press
        for (i in 0 until 14) {
            Thread.sleep(5_000)
            val l = s.location ?: continue
            if (l.optLong("t") < since) continue
            val now = System.currentTimeMillis()
            broadcast(s) { chat -> sendLoc(s, chat, l); ok(send(s, chat, TgLogic.pinLabel(s.tgChatLang(chat), l, now, ::hm))) }
            return
        }
    }

    /** Release: the bot goes quiet and forgets everything. */
    fun clear(s: Store, ctx: android.content.Context) { s.tgClear(); java.io.File(ctx.filesDir, "tgrec").deleteRecursively(); wake() }
}
