package dev.kidguard.wear

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** One paired kid. demo = the built-in sample, never stored. */
data class Kid(val url: String, val id: String, val token: String, val name: String, val demo: Boolean = false) {
    companion object { val DEMO = Kid("", "", "", "Demo", true) }
}

/** SharedPreferences "kg" (app-private): kids list (with tokens), pending connect secret/code, map zoom. */
object Store {
    private fun p(c: Context) = c.getSharedPreferences("kg", Context.MODE_PRIVATE)

    fun kids(c: Context): List<Kid> = try {
        val a = JSONArray(p(c).getString("kids", "[]"))
        (0 until a.length()).map { val o = a.getJSONObject(it); Kid(o.getString("url"), o.getString("id"), o.getString("token"), o.optString("name", "")) }
    } catch (e: Exception) { emptyList() }

    private fun save(c: Context, l: List<Kid>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("url", it.url).put("id", it.id).put("token", it.token).put("name", it.name)) }
        p(c).edit().putString("kids", a.toString()).apply()
    }

    /** Same id again = replace (re-connect after "renew relay link"). Returns the index. */
    fun addKid(c: Context, k: Kid): Int {
        val l = kids(c).filter { it.id != k.id } + k
        save(c, l)
        return l.size - 1
    }

    fun removeKid(c: Context, i: Int) = save(c, kids(c).filterIndexed { n, _ -> n != i })

    fun relay(c: Context): String = p(c).getString("relay", null)?.takeIf { Net.validBase(it) } ?: Net.DEFAULT_RELAY
    fun zoom(c: Context): Int = p(c).getInt("mz", 16).coerceIn(MapMath.ZMIN, MapMath.ZMAX)
    fun setZoom(c: Context, z: Int) = p(c).edit().putInt("mz", z).apply()
}
