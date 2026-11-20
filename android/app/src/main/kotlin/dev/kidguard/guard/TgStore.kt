package dev.kidguard

import android.content.SharedPreferences
import java.security.SecureRandom
import org.json.JSONArray
import org.json.JSONObject

/**
 * Telegram bot state on this phone (Tg.kt), in the same prefs file as [Store] (keys "tg*", unchanged). The token never
 * leaves the phone (not in state, not in rules). Split out of Store (maintainability review 2026-11-17); reached as `store.tg`.
 * Its own lock is enough: nothing outside this class writes a "tg*" key (Store.wipe clears the whole file).
 */
class TgStore internal constructor(private val p: SharedPreferences) {
    var token: String
        get() = p.getString("token", "") ?: ""
        set(v) { p.edit().putString("token", v).commit() }
    var bot: String
        get() = p.getString("bot", "") ?: ""
        set(v) { p.edit().putString("bot", v).apply() }
    var name: String   // the child's name as the parent page calls it (texts only)
        get() = p.getString("name", "") ?: ""
        set(v) { p.edit().putString("name", v).apply() }
    var lang: String
        get() = p.getString("lang", "vi") ?: "vi"
        set(v) { p.edit().putString("lang", v).apply() }
    var err: String
        get() = p.getString("err", "") ?: ""
        set(v) { p.edit().putString("err", v).apply() }
    var offset: Long
        get() = p.getLong("offset", 0)
        set(v) { p.edit().putLong("offset", v).apply() }
    /** Linked chats [{id, name, at}], max TgLogic.MAX_CHATS: only these get alerts and may send commands. */
    @Synchronized fun chats(): JSONArray = runCatching { JSONArray(p.getString("chats", "[]")) }.getOrDefault(JSONArray())
    @Synchronized fun chatOk(id: Long): Boolean = chats().let { a -> (0 until a.length()).any { a.optJSONObject(it)?.optLong("id") == id } }
    @Synchronized fun addChat(id: Long, name: String, lang: String = this.lang): Boolean {
        val a = chats(); if (chatOk(id)) return true
        if (a.length() >= TgLogic.MAX_CHATS) return false
        a.put(JSONObject().put("id", id).put("name", name.take(40)).put("at", System.currentTimeMillis()).put("lang", lang))
        p.edit().putString("chats", a.toString()).commit(); return true
    }
    /** The chat's own language ("vi" / "en"); the bot default (page language at tgSet) for an unknown chat. */
    @Synchronized fun chatLang(id: Long): String = chats().let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }
        .firstOrNull { it.optLong("id") == id }?.optString("lang")?.takeIf { it == "vi" || it == "en" } } ?: lang
    @Synchronized fun setChatLang(id: Long, lang: String) {
        val a = chats(); for (i in 0 until a.length()) a.optJSONObject(i)?.takeIf { it.optLong("id") == id }?.put("lang", lang)
        p.edit().putString("chats", a.toString()).commit()
    }
    /** Alert level of a chat: "all" (default) / "important" / "sos" (TgLogic.quietOk). */
    @Synchronized fun chatQuiet(id: Long): String = chats().let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }
        .firstOrNull { it.optLong("id") == id }?.optString("quiet")?.takeIf { it == "important" || it == "sos" } } ?: "all"
    /** Evening summary of a chat (/digest): on, time "HH:mm", last day sent. Off by default. */
    @Synchronized fun chatDigest(id: Long): Triple<Boolean, String, String> = chats().let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }
        .firstOrNull { it.optLong("id") == id } }?.let { Triple(it.optBoolean("digest"), it.optString("digestAt").ifBlank { Digest.DEFAULT_AT }, it.optString("digestDay")) } ?: Triple(false, Digest.DEFAULT_AT, "")
    @Synchronized fun setChatDigest(id: Long, on: Boolean? = null, at: String? = null, day: String? = null) {
        val a = chats(); for (i in 0 until a.length()) a.optJSONObject(i)?.takeIf { it.optLong("id") == id }?.let { o ->
            if (on != null) o.put("digest", on); if (at != null) o.put("digestAt", at); if (day != null) o.put("digestDay", day) }
        p.edit().putString("chats", a.toString()).commit()
    }
    /** `/notif` of a chat: the child's new notifications (rule `watch.notif`) forwarded here. Off by default. */
    @Synchronized fun chatNotif(id: Long): Boolean = chats().let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }
        .firstOrNull { it.optLong("id") == id }?.optBoolean("notif") } ?: false
    @Synchronized fun setChatNotif(id: Long, on: Boolean) {
        val a = chats(); for (i in 0 until a.length()) a.optJSONObject(i)?.takeIf { it.optLong("id") == id }?.put("notif", on)
        p.edit().putString("chats", a.toString()).commit()
    }
    /** The last notification feed id forwarded (NotifSync ids grow with the clock, so it survives a restart). */
    var notifAfter: Long
        get() = p.getLong("notifAfter", 0)
        set(v) { p.edit().putLong("notifAfter", v).apply() }
    @Synchronized fun setChatQuiet(id: Long, level: String) {
        val a = chats(); for (i in 0 until a.length()) a.optJSONObject(i)?.takeIf { it.optLong("id") == id }?.put("quiet", level)
        p.edit().putString("chats", a.toString()).commit()
    }
    /** When the last getUpdates succeeded: a long gap = the phone was offline (old button taps are not run). */
    var lastOk: Long
        get() = p.getLong("lastOk", 0)
        set(v) { p.edit().putLong("lastOk", v).apply() }
    @Synchronized fun unlinkAll() { p.edit().remove("chats").commit() }
    @Synchronized fun removeChat(id: Long): Boolean {
        val a = chats(); val keep = JSONArray(); var hit = false
        for (i in 0 until a.length()) { val o = a.optJSONObject(i) ?: continue; if (o.optLong("id") == id) hit = true else keep.put(o) }
        p.edit().putString("chats", keep.toString()).commit(); return hit
    }
    /** A fresh 6-digit link code, valid TgLogic.CODE_MS, TgLogic.CODE_TRIES wrong tries. */
    @Synchronized fun newCode(now: Long = System.currentTimeMillis()): String {
        val c = (SecureRandom().nextInt(900_000) + 100_000).toString()
        p.edit().putString("code", c).putLong("codeUntil", now + TgLogic.CODE_MS).putInt("tgCodeTries", 0).commit(); return c
    }
    @Synchronized fun code(now: Long = System.currentTimeMillis()): String? =
        p.getString("code", null)?.takeIf { now < p.getLong("codeUntil", 0) && p.getInt("tgCodeTries", 0) < TgLogic.CODE_TRIES }
    @Synchronized fun codeUntil(): Long = p.getLong("codeUntil", 0)
    /** True = right code (then used up); a wrong one counts a try. */
    @Synchronized fun tryCode(given: String, now: Long = System.currentTimeMillis()): Boolean {
        val c = code(now) ?: return false
        if (TgLogic.codeOk(given, c, p.getLong("codeUntil", 0), now)) { p.edit().remove("code").commit(); return true }
        p.edit().putInt("tgCodeTries", p.getInt("tgCodeTries", 0) + 1).commit(); return false
    }
    @Synchronized fun seen(): List<String> = (p.getString("seen", "") ?: "").split(',').filter { it.isNotEmpty() }
    @Synchronized fun setSeen(l: List<String>) { p.edit().putString("seen", l.joinToString(",")).apply() }
    /** Bot off: token, chats, code, offset, seen all gone. */
    @Synchronized fun clear() {
        p.edit().remove("token").remove("bot").remove("chats").remove("code").remove("codeUntil").remove("tgCodeTries")
            .remove("offset").remove("seen").remove("err").remove("name").remove("notifAfter").commit()
    }
}
