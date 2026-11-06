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
    fun historyDays(c: Context): Int = p(c).getInt("historyDays", 0)
    fun setHistoryDays(c: Context, n: Int) { p(c).edit().putInt("historyDays", n.coerceIn(0, 3650)).apply() }
    fun notifyState(c: Context, key: String): String? = p(c).getString("ns:" + sha(key), null)
    fun setNotifyState(c: Context, key: String, json: String) { p(c).edit().putString("ns:" + sha(key), json).apply() }

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
