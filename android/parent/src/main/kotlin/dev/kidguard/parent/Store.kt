package dev.kidguard.parent

import android.content.Context
import java.io.File
import java.net.ServerSocket
import java.security.MessageDigest

/** Private prefs + snapshot files. Everything stays inside the app's private storage. */
object Store {
    private fun p(c: Context) = c.getSharedPreferences("kg_parent", Context.MODE_PRIVATE)

    fun kids(c: Context): String = p(c).getString("kids", "[]") ?: "[]"
    fun setKids(c: Context, json: String) { p(c).edit().putString("kids", json).apply() }
    fun notify(c: Context): Boolean = p(c).getBoolean("notify", true)
    fun setNotify(c: Context, v: Boolean) { p(c).edit().putBoolean("notify", v).apply() }
    /** History retention in days, 0 = unlimited (default). */
    fun tpl(c: Context): String = p(c).getString("tpl", "[]") ?: "[]"
    fun setTpl(c: Context, json: String) { if (json.length <= 50_000) p(c).edit().putString("tpl", json).apply() }
    fun historyDays(c: Context): Int = p(c).getInt("historyDays", 0)
    fun setHistoryDays(c: Context, n: Int) { p(c).edit().putInt("historyDays", n.coerceIn(0, 3650)).apply() }
    fun updateAt(c: Context): Long = p(c).getLong("updAt", 0)
    fun setUpdateAt(c: Context, t: Long) { p(c).edit().putLong("updAt", t).apply() }
    fun updateNotified(c: Context): String = p(c).getString("updTag", "") ?: ""
    fun setUpdateNotified(c: Context, tag: String) { p(c).edit().putString("updTag", tag).apply() }
    fun notifyState(c: Context, key: String): String? = p(c).getString("ns:" + sha(key), null)
    fun setNotifyState(c: Context, key: String, json: String) { p(c).edit().putString("ns:" + sha(key), json).apply() }
    /** A kid whose token was refused (401/403) is announced once; cleared when it answers again. */
    fun unpairedSeen(c: Context, key: String): Boolean = p(c).getBoolean("unp:" + sha(key), false)
    fun setUnpairedSeen(c: Context, key: String, v: Boolean) { p(c).edit().putBoolean("unp:" + sha(key), v).apply() }

    /** Requests answered from a notification button (kid hash + request id), newest last, max 100: a second tap is ignored. */
    fun takeAnswer(c: Context, kid: String, req: String): Boolean = synchronized(this) {
        val k = sha("$kid|$req").take(24)
        val a = org.json.JSONArray(p(c).getString("answered", "[]") ?: "[]")
        if ((0 until a.length()).any { a.optString(it) == k }) return false
        val out = org.json.JSONArray(); val from = maxOf(0, a.length() - 99)
        for (i in from until a.length()) out.put(a.optString(i))
        p(c).edit().putString("answered", out.put(k).toString()).commit()
        true
    }
    fun dropAnswer(c: Context, kid: String, req: String) = synchronized(this) {
        val k = sha("$kid|$req").take(24)
        val a = org.json.JSONArray(p(c).getString("answered", "[]") ?: "[]")
        val out = org.json.JSONArray(); for (i in 0 until a.length()) if (a.optString(i) != k) out.put(a.optString(i))
        p(c).edit().putString("answered", out.toString()).commit()
    }

    /** The page's localStorage lives per origin (= per port), so the port is chosen once and reused when free. */
    fun pickPort(c: Context): Int {
        val saved = p(c).getInt("port", 0)
        val port = listOf(saved).filter { it > 0 }.firstOrNull { free(it) } ?: ServerSocket(0).use { it.localPort }
        if (port != saved) p(c).edit().putInt("port", port).apply()
        return port
    }
    private fun free(port: Int) = try { ServerSocket(port, 0, java.net.InetAddress.getByName("127.0.0.1")).close(); true } catch (e: Exception) { false }

    fun sha(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    fun dir(c: Context, name: String) = File(c.filesDir, name).apply { mkdirs() }
}
