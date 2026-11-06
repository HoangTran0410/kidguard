package dev.kidguard

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

/** Everything the child keeps. Device-protected storage, so the guard runs right after a reboot, before the first unlock. */
class Store private constructor(ctx: Context) {
    private val p: SharedPreferences =
        ctx.createDeviceProtectedStorageContext().getSharedPreferences("kg", Context.MODE_PRIVATE)

    @get:Synchronized val id: String
        get() = p.getString("id", null) ?: randomHex(8).also { p.edit().putString("id", it).apply() }

    @get:Synchronized @set:Synchronized var rules: Rules
        get() = p.getString("rules", null)?.let { runCatching { Rules.fromJson(JSONObject(it)) }.getOrNull() } ?: Rules()
        set(v) { p.edit().putString("rules", v.toJson().toString()).apply() }

    /** Tokens of paired parent browsers. Kept as sha256, the token itself only lives in the parent's browser. */
    @Synchronized fun tokenOk(t: String?): Boolean = !t.isNullOrBlank() && sha256(t) in tokenHashes()
    @Synchronized fun tokenHashes(): Set<String> = p.getStringSet("tokens", emptySet())!!.toSet()
    @Synchronized fun addToken(): String {
        val t = randomHex(24)
        p.edit().putStringSet("tokens", tokenHashes() + sha256(t)).apply()
        return t
    }
    @Synchronized fun clearTokens() { p.edit().remove("tokens").remove("relayToken").remove("relayId").apply() }

    /** The token the child uses with the relay: one of the paired ones, shown to the parent as a link. */
    @get:Synchronized @set:Synchronized var relayToken: String
        get() = p.getString("relayToken", "")!!
        set(v) { p.edit().putString("relayToken", v).apply() }
    /** The relay's name for this phone; new with each relay token, so a revoked link can't come back. */
    @get:Synchronized @set:Synchronized var relayId: String
        get() = p.getString("relayId", "")!!
        set(v) { p.edit().putString("relayId", v).apply() }
    @get:Synchronized @set:Synchronized var relayUrl: String
        get() = p.getString("relayUrl", "")!!
        set(v) { p.edit().putString("relayUrl", v.trim().trimEnd('/')).apply() }

    // Pairing: a 6-digit code, open for 10 minutes; open by itself while nobody is paired.
    @Synchronized fun pairCode(): String? {
        val open = !p.getBoolean("everPaired", false) || p.getLong("pairUntil", 0) > System.currentTimeMillis()
        if (!open) return null
        return p.getString("pairCode", null) ?: newPairCode()
    }
    @Synchronized fun openPairing() { newPairCode(); p.edit().putLong("pairUntil", System.currentTimeMillis() + 10 * 60_000).apply() }
    @Synchronized fun closePairing() { p.edit().remove("pairUntil").remove("pairCode").putInt("wrong", 0).apply() }
    @Synchronized fun newPairCode(): String {
        val c = (SecureRandom().nextInt(900_000) + 100_000).toString()
        p.edit().putString("pairCode", c).putInt("wrong", 0).apply()
        return c
    }
    /** Returns a token, or null. 5 wrong codes make a new code. */
    @Synchronized fun tryPair(code: String): String? {
        val want = pairCode() ?: return null
        if (code.trim() != want) {
            val w = p.getInt("wrong", 0) + 1
            if (w >= 5) newPairCode() else p.edit().putInt("wrong", w).apply()
            return null
        }
        val t = addToken()
        p.edit().putBoolean("everPaired", true).apply()
        closePairing()
        return t
    }

    @get:Synchronized @set:Synchronized var suspendedByUs: Set<String>
        get() = p.getStringSet("susp", emptySet())!!.toSet()
        set(v) { p.edit().putStringSet("susp", v).apply() }

    @get:Synchronized @set:Synchronized var location: JSONObject?
        get() = p.getString("loc", null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        set(v) { p.edit().putString("loc", v?.toString()).apply() }

    /** Recent events for the parent: commands done, apps paused. Last 50. */
    @Synchronized fun log(msg: String) {
        val a = runCatching { JSONArray(p.getString("log", "[]")) }.getOrDefault(JSONArray())
        a.put(JSONObject().put("t", System.currentTimeMillis()).put("m", msg))
        val keep = JSONArray(); for (i in maxOf(0, a.length() - 50) until a.length()) keep.put(a.get(i))
        p.edit().putString("log", keep.toString()).apply()
    }
    @Synchronized fun logs(): JSONArray = runCatching { JSONArray(p.getString("log", "[]")) }.getOrDefault(JSONArray())

    @Synchronized fun wipe() { p.edit().clear().apply() }

    companion object {
        @Volatile private var inst: Store? = null
        fun of(ctx: Context): Store = inst ?: synchronized(this) { inst ?: Store(ctx.applicationContext).also { inst = it } }

        fun randomHex(bytes: Int): String = ByteArray(bytes).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        fun sha256(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
