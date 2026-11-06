package dev.kidguard

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

/**
 * Everything the child keeps, in device-protected storage. No component is directBootAware, so the guard starts on
 * BOOT_COMPLETED (after the first unlock), not before.
 */
class Store private constructor(ctx: Context) {
    private val p: SharedPreferences =
        ctx.createDeviceProtectedStorageContext().getSharedPreferences("kg", Context.MODE_PRIVATE)

    @get:Synchronized val id: String
        get() = p.getString("id", null) ?: randomHex(8).also { p.edit().putString("id", it).apply() }

    @get:Synchronized @set:Synchronized var rules: Rules
        get() = p.getString("rules", null)?.let { runCatching { Rules.fromJson(JSONObject(it)) }.getOrNull() } ?: Rules()
        set(v) { p.edit().putString("rules", v.toJson(persist = true).toString()).apply() }

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
    @Synchronized fun openPairing() { newPairCode(); p.edit().putLong("pairUntil", System.currentTimeMillis() + 10 * 60_000).putInt("wrong", 0).remove("pairBlock").apply() }
    /** When an opened pairing window (after the first pairing) closes; 0 = closed or never paired. */
    @Synchronized fun pairOpenUntil(): Long =
        if (!p.getBoolean("everPaired", false)) 0 else p.getLong("pairUntil", 0).takeIf { it > System.currentTimeMillis() } ?: 0
    @Synchronized fun closePairing() { p.edit().remove("pairUntil").remove("pairCode").putInt("wrong", 0).remove("pairBlock").apply() }
    @Synchronized fun newPairCode(): String {
        val c = (SecureRandom().nextInt(900_000) + 100_000).toString()
        p.edit().putString("pairCode", c).apply()
        return c
    }

    /** ms until the parent PIN may be tried again (0 = now). Shared by the child screen and `/api/pair`. */
    @Synchronized fun pinLockLeft(): Long = maxOf(0, p.getLong("pinLock", 0) - System.currentTimeMillis())

    /** Checks the parent PIN with the lockout: true = right; false = wrong or locked (see [pinLockLeft]). */
    @Synchronized fun checkParentPin(pin: String): Boolean {
        if (pinLockLeft() > 0) return false
        if (rules.checkPin(pin)) { p.edit().putInt("pinFails", 0).remove("pinLock").commit(); return true }
        val f = p.getInt("pinFails", 0) + 1
        val lock = Safety.pinLockMs(f)
        p.edit().putInt("pinFails", f).putLong("pinLock", if (lock > 0) System.currentTimeMillis() + lock else 0).commit()
        if (lock > 0) log("Nhập sai PIN $f lần, khoá ${lock / 60_000} phút")
        return false
    }

    /**
     * Pairing a parent browser. Needs the code shown on the child's screen, and the parent PIN once one is set
     * (the child can see the code, never the PIN). Every 3rd wrong guess makes a new code and blocks for a while.
     * Returns the token, or null with [lastPairError] = closed | wait | pin locked | wrong pin | wrong code.
     */
    @Synchronized fun tryPair(code: String, pin: String): String? {
        val now = System.currentTimeMillis()
        if (p.getLong("pairBlock", 0) > now) { lastPairError = "wait"; return null }
        val want = pairCode() ?: run { lastPairError = "closed"; return null }
        if (code.trim() != want) { pairFail(now); lastPairError = "wrong code"; return null }
        // after the code: anyone could otherwise send wrong PINs and keep the PIN (and new pairings) locked
        if (rules.pinHash.isNotBlank()) {
            if (pinLockLeft() > 0) { lastPairError = "pin locked"; return null }
            if (!checkParentPin(pin)) { pairFail(now); lastPairError = if (pinLockLeft() > 0) "pin locked" else "wrong pin"; return null }
        }
        val t = addToken()
        p.edit().putBoolean("everPaired", true).apply()
        closePairing()
        return t
    }
    @Volatile var lastPairError = ""; private set
    private fun pairFail(now: Long) {
        val f = p.getInt("wrong", 0) + 1
        val block = Safety.pairBlockMs(f)
        p.edit().putInt("wrong", f).apply()
        if (block > 0) { newPairCode(); p.edit().putLong("pairBlock", now + block).apply() }
    }

    @get:Synchronized val everPaired: Boolean get() = p.getBoolean("everPaired", false)

    /** Until this time the child screen shows the first-pairing code (revealed by a parent's 5 s long-press). Memory only. */
    @Volatile var codeRevealUntil = 0L
    /** "Lost the link" recovery from the child's screen (no PIN set): pairing opens only at this time, a day after the
     *  request, and the parents get an event at once; setting a PIN meanwhile cancels it. 0 = none. */
    @get:Synchronized @set:Synchronized var recoverAt: Long
        get() = p.getLong("recoverAt", 0)
        set(v) { p.edit().putLong("recoverAt", v).apply() }

    /** `pinAt` of the last PIN hash taken from the relay (see Relay.sync). */
    @get:Synchronized @set:Synchronized var relayPinAt: Long
        get() = p.getLong("relayPinAt", 0)
        set(v) { p.edit().putLong("relayPinAt", v).apply() }

    /** The code the child screen may show: before the first pairing only after the long-press reveal; later whenever pairing is open. */
    @Synchronized fun visiblePairCode(): String? {
        val c = pairCode() ?: return null
        return if (everPaired || codeRevealUntil > System.currentTimeMillis()) c else null
    }

    /** Last "listen around" session: [start, ms], or null. */
    @get:Synchronized @set:Synchronized var lastListen: LongArray?
        get() = p.getString("lastListen", null)?.split(',')?.mapNotNull { it.toLongOrNull() }?.takeIf { it.size == 2 }?.toLongArray()
        set(v) { p.edit().putString("lastListen", v?.joinToString(",")).commit() }

    /** Warning keys already shown today (see [Warn]); keys of other days are dropped. */
    @Synchronized fun warned(today: String): Set<String> = p.getStringSet("warned", emptySet())!!.filter { it.startsWith("$today:") }.toSet()
    @Synchronized fun markWarned(today: String, keys: Set<String>) { p.edit().putStringSet("warned", warned(today) + keys).apply() }

    // Remote access (Ts.kt). The auth key lives only here (private prefs); it is never sent back in state or log.
    @get:Synchronized @set:Synchronized var tsWanted: Boolean
        get() = p.getBoolean("tsWanted", false)
        set(v) { p.edit().putBoolean("tsWanted", v).commit() }
    @get:Synchronized @set:Synchronized var tsKey: String
        get() = p.getString("tsKey", "")!!
        set(v) { p.edit().putString("tsKey", v).commit() }
    @get:Synchronized @set:Synchronized var tsHost: String
        get() = p.getString("tsHost", "")!!
        set(v) { p.edit().putString("tsHost", v).apply() }
    /** Suspend the app once the tunnel is up (turned off for good if suspension breaks the tunnel). */
    @get:Synchronized @set:Synchronized var tsHide: Boolean
        get() = p.getBoolean("tsHide", true)
        set(v) { p.edit().putBoolean("tsHide", v).apply() }

    /** Set by release: the guard must not start again (Boot / watchdog / accessibility poke) until Device Owner is given again. */
    @get:Synchronized @set:Synchronized var released: Boolean
        get() = p.getBoolean("released", false)
        set(v) { p.edit().putBoolean("released", v).commit() }

    /** Remembers command [id] (last 100). False if it was already executed. Written with commit(): it must survive a reboot / release. */
    @Synchronized fun markCmd(rawId: String): Boolean {
        val id = rawId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.ifEmpty { return true }
        val l = (p.getString("cmds", "") ?: "").split(',').filter { it.isNotEmpty() }
        if (id in l) return false
        p.edit().putString("cmds", (l + id).takeLast(100).joinToString(",")).commit()
        return true
    }

    /** Location history, points [t, lat, lon, acc]. */
    @Synchronized private fun trackAll(): JSONArray = runCatching { JSONArray(p.getString("track", "[]")) }.getOrDefault(JSONArray())

    /** Adds a point if [Track.shouldStore]; keeps 7 days and at most 2000 points. Returns true if stored. */
    @Synchronized fun addTrack(t: Long, lat: Double, lon: Double, acc: Int): Boolean {
        val a = trackAll()
        val last = if (a.length() > 0) a.optJSONArray(a.length() - 1) else null
        if (!Track.shouldStore(last?.optLong(0), last?.optDouble(1) ?: 0.0, last?.optDouble(2) ?: 0.0, t, lat, lon)) return false
        val keep = JSONArray()
        val from = maxOf(0, a.length() - (Track.MAX_POINTS - 1))
        for (i in from until a.length()) { val q = a.optJSONArray(i) ?: continue; if (t - q.optLong(0) <= Track.KEEP_MS) keep.put(q) }
        keep.put(JSONArray().put(t).put(Math.round(lat * 1e5) / 1e5).put(Math.round(lon * 1e5) / 1e5).put(acc))
        p.edit().putString("track", keep.toString()).apply()
        return true
    }
    @get:Synchronized val lastTrackAt: Long get() = trackAll().let { if (it.length() > 0) it.optJSONArray(it.length() - 1)?.optLong(0) ?: 0 else 0 }

    /** Points since local midnight [days]-1 days ago: track(1) = today, track(2) = since yesterday's midnight, track(7) = a week. */
    @Synchronized fun track(days: Int): JSONArray {
        val from = Usage.midnightAgo((days.coerceIn(1, 7)) - 1)
        val out = JSONArray(); val a = trackAll()
        for (i in 0 until a.length()) { val q = a.optJSONArray(i) ?: continue; if (q.optLong(0) >= from) out.put(q) }
        return out
    }

    @get:Synchronized @set:Synchronized var suspendedByUs: Set<String>
        get() = p.getStringSet("susp", emptySet())!!.toSet()
        set(v) { p.edit().putStringSet("susp", v).commit() }   // must survive a crash: it is what release / the loop undo

    /** Apps we hid with setApplicationHidden (what release / the loop undo). */
    @get:Synchronized @set:Synchronized var hiddenByUs: Set<String>
        get() = p.getStringSet("hid", emptySet())!!.toSet()
        set(v) { p.edit().putStringSet("hid", v).commit() }

    @get:Synchronized @set:Synchronized var location: JSONObject?
        get() = p.getString("loc", null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        set(v) { p.edit().putString("loc", v?.toString()).apply() }

    /** Launchable apps seen on the first tick; anything not in here (and not approved) is a "new" app. Null until first tick. */
    @get:Synchronized @set:Synchronized var knownApps: Set<String>?
        get() = p.getStringSet("known", null)?.toSet()
        set(v) { p.edit().putStringSet("known", v).apply() }
    @get:Synchronized @set:Synchronized var loggedNew: Set<String>
        get() = p.getStringSet("loggedNew", emptySet())!!.toSet()
        set(v) { p.edit().putStringSet("loggedNew", v).apply() }

    /** Last 7 days of use, computed at most hourly (queryEvents over a week is not free). */
    @get:Synchronized val historyAt: Long get() = p.getLong("histAt", 0)
    @get:Synchronized val history: JSONArray get() = runCatching { JSONArray(p.getString("hist", "[]")) }.getOrDefault(JSONArray())
    @Synchronized fun saveHistory(a: JSONArray, at: Long) { p.edit().putString("hist", a.toString()).putLong("histAt", at).apply() }

    /** "More time, please" from the child, max 5. */
    @Synchronized fun requests(): JSONArray = runCatching { JSONArray(p.getString("reqs", "[]")) }.getOrDefault(JSONArray())
    /** [chore]: id of a parent's chore (then [min] is that chore's minutes); the parent still decides. */
    @Synchronized fun addRequest(min: Int, note: String, chore: String? = null): Boolean {
        val a = requests()
        if (a.length() >= 5) return false
        val o = JSONObject().put("id", randomHex(3)).put("t", System.currentTimeMillis()).put("min", min).put("note", note.trim().take(100))
        if (!chore.isNullOrBlank()) o.put("chore", chore)
        a.put(o)
        p.edit().putString("reqs", a.toString()).apply()
        return true
    }
    /** Removes request [id]; returns it (for the notification) or null. */
    @Synchronized fun removeRequest(id: String): JSONObject? {
        val a = requests(); val keep = JSONArray(); var hit: JSONObject? = null
        for (i in 0 until a.length()) { val o = a.getJSONObject(i); if (o.optString("id") == id) hit = o else keep.put(o) }
        if (hit != null) p.edit().putString("reqs", keep.toString()).apply()
        return hit
    }

    /** SOS presses from the child, newest last, max 10; the parent clears them with `sosAck`. */
    @Synchronized fun sos(): JSONArray = runCatching { JSONArray(p.getString("sos", "[]")) }.getOrDefault(JSONArray())
    @Synchronized fun addSos(): JSONObject? {
        val a = sos()
        val now = System.currentTimeMillis()
        // a double tap is one alarm
        if (a.length() > 0 && now - a.getJSONObject(a.length() - 1).optLong("t") < 30_000) return null
        val o = JSONObject().put("id", randomHex(3)).put("t", now)
        a.put(o)
        val keep = JSONArray(); for (i in maxOf(0, a.length() - 10) until a.length()) keep.put(a.get(i))
        p.edit().putString("sos", keep.toString()).commit()
        return o
    }
    /** Clears SOS [id], or all of them for "all". Returns how many were removed. */
    @Synchronized fun ackSos(id: String): Int {
        val a = sos(); val keep = JSONArray()
        for (i in 0 until a.length()) { val o = a.getJSONObject(i); if (id != "all" && o.optString("id") != id) keep.put(o) }
        p.edit().putString("sos", keep.toString()).apply()
        return a.length() - keep.length()
    }

    /** Recent events for the parent: commands done, apps paused. Last 50. */
    @Synchronized fun log(msg: String) {
        val a = runCatching { JSONArray(p.getString("log", "[]")) }.getOrDefault(JSONArray())
        a.put(JSONObject().put("t", System.currentTimeMillis()).put("m", msg))
        val keep = JSONArray(); for (i in maxOf(0, a.length() - 50) until a.length()) keep.put(a.get(i))
        p.edit().putString("log", keep.toString()).apply()
    }
    @Synchronized fun logs(): JSONArray = runCatching { JSONArray(p.getString("log", "[]")) }.getOrDefault(JSONArray())

    // Events for the parent (Events.kt): newest last, 50, 7 days.
    @Synchronized fun events(): JSONArray = Events.prune(runCatching { JSONArray(p.getString("events", "[]")) }.getOrDefault(JSONArray()), System.currentTimeMillis())
    @Synchronized fun addEvent(e: Ev) {
        val now = System.currentTimeMillis()
        val o = JSONObject().put("id", randomHex(3)).put("t", now).put("kind", e.kind).put("text", e.text)
        if (e.zone != null) o.put("zone", e.zone)
        p.edit().putString("events", Events.prune(events().put(o), now).toString()).apply()
    }

    /** Zones: the next location fix only records where the child is (set after the zone list changed). */
    @get:Synchronized @set:Synchronized var zoneBaseline: Boolean
        get() = p.getBoolean("zoneBase", false)
        set(v) { p.edit().putBoolean("zoneBase", v).apply() }

    /** One new location fix against the zones: stores the events, returns them. */
    @Synchronized fun zoneFix(zones: List<Zone>, lat: Double, lng: Double): List<Ev> {
        val inside = p.getStringSet("zin", emptySet())!!.toSet()
        val (now, evs) = Events.zoneStep(zones, inside, lat, lng, record = zoneBaseline || p.getStringSet("zin", null) == null)
        p.edit().putStringSet("zin", now).putBoolean("zoneBase", false).apply()
        evs.forEach { addEvent(it) }
        return evs
    }

    /** Battery event armed (false after it fired, until the level is back at >= 30 %). */
    @get:Synchronized @set:Synchronized var batteryArmed: Boolean
        get() = p.getBoolean("batArmed", true)
        set(v) { p.edit().putBoolean("batArmed", v).apply() }

    @Synchronized fun wipe() { p.edit().clear().apply() }

    companion object {
        @Volatile private var inst: Store? = null
        fun of(ctx: Context): Store = inst ?: synchronized(this) { inst ?: Store(ctx.applicationContext).also { inst = it } }

        fun randomHex(bytes: Int): String = ByteArray(bytes).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        fun sha256(s: String): String = Rules.sha256(s)
    }
}
