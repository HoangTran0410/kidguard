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
class Store private constructor(private val app: Context) {
    private val p: SharedPreferences =
        app.createDeviceProtectedStorageContext().getSharedPreferences("kg", Context.MODE_PRIVATE)

    @get:Synchronized val id: String
        get() = p.getString("id", null) ?: randomHex(8).also { p.edit().putString("id", it).apply() }

    private var rulesRaw: String? = null; private var rulesObj: Rules? = null
    /** A stored JSON string as is: a cheap "did it change" key (no parse). */
    @Synchronized fun raw(k: String): String = p.getString(k, "") ?: ""

    @get:Synchronized @set:Synchronized var rules: Rules
        get() {
            // parsed once per stored string: Rules is immutable, and the launcher + Guard read it many times a minute
            // (1-5 ms of JSON per read on an Oppo A5s)
            val raw = p.getString("rules", null)
            if (raw == rulesRaw) rulesObj?.let { return it }
            return (raw?.let { runCatching { Rules.fromJson(JSONObject(it)) }.getOrNull() } ?: Rules()).also { rulesRaw = raw; rulesObj = it }
        }
        set(v) { p.edit().putString("rules", v.toJson(persist = true).toString()).apply() }

    /** Tokens of paired parent browsers. Kept as sha256, the token itself only lives in the parent's browser. */
    @Synchronized fun tokenOk(t: String?): Boolean = !t.isNullOrBlank() && sha256(t) in tokenHashes()
    @Synchronized fun tokenHashes(): Set<String> = p.getStringSet("tokens", emptySet())!!.toSet()
    @Synchronized fun addToken(): String {
        val t = randomHex(24)
        p.edit().putStringSet("tokens", tokenHashes() + sha256(t)).apply()
        return t
    }
    @Synchronized fun clearTokens() { pending.clear(); p.edit().remove("tokens").remove("relayToken").remove("relayId").remove("devs").apply() }

    /** Name / first seen / last seen of each paired token (keyed by its sha256), see [Paired]. */
    @Synchronized private fun devMeta(): Map<String, Paired.Dev> {
        val o = runCatching { JSONObject(p.getString("devs", "{}")!!) }.getOrDefault(JSONObject())
        return o.keys().asSequence().associateWith { k -> o.getJSONObject(k).let { Paired.Dev(it.optString("n"), it.optLong("a"), it.optLong("l")) } }
    }
    @Synchronized private fun putDevMeta(m: Map<String, Paired.Dev>) {
        val o = JSONObject(); m.forEach { (k, d) -> o.put(k, JSONObject().put("n", d.name).put("a", d.at).put("l", d.last)) }
        p.edit().putString("devs", o.toString()).apply()
    }
    /** A request with a valid paired token: remember the device (name from the page's X-KG-Name header, first time only) and when it was last seen. */
    @Synchronized fun touchDevice(t: String?, name: String?) {
        if (t.isNullOrBlank()) return
        val h = sha256(t); if (h !in tokenHashes()) return
        val m = devMeta(); val n = Paired.touch(m, h, System.currentTimeMillis(), name)
        if (n !== m) putDevMeta(n)
    }
    /** state.devices; [selfToken] marks the caller's own row. */
    @Synchronized fun devices(selfToken: String? = null): JSONArray {
        val a = JSONArray()
        Paired.rows(tokenHashes(), devMeta(), selfToken?.takeIf { it.isNotBlank() }?.let { sha256(it) }).forEach {
            a.put(JSONObject().put("id", it.id).put("name", it.name).put("at", it.at).put("last", it.last).also { o -> if (it.self) o.put("self", true) })
        }
        return a
    }
    /** Revokes one paired device by [id]. If it held the relay link, the relay link dies with it. False = no such device. */
    @Synchronized fun unpair(id: String): Boolean {
        val hs = tokenHashes(); val h = Paired.find(hs, id) ?: return false
        val e = p.edit().putStringSet("tokens", hs - h)
        if (relayToken.isNotBlank() && sha256(relayToken) == h) e.remove("relayToken").remove("relayId")
        e.commit()
        putDevMeta(Paired.prune(devMeta(), hs - h))
        return true
    }

    /** The token the child uses with the relay: one of the paired ones, shown to the parent as a link. */
    @get:Synchronized @set:Synchronized var relayToken: String
        get() = p.getString("relayToken", "")!!
        set(v) { p.edit().putString("relayToken", v).apply() }
    /** The relay's name for this phone; new with each relay token, so a revoked link can't come back. */
    @get:Synchronized @set:Synchronized var relayId: String
        get() = p.getString("relayId", "")!!
        set(v) { p.edit().putString("relayId", v).apply() }
    /**
     * Relay on by default: once a parent has paired, a phone that never had a relay setting gets the shared relay
     * ([Relay.DEFAULT_URL]) with its own id/token. A parent turning the relay off stores "" (key present) = never again.
     * True = just turned on.
     */
    @Synchronized fun defaultRelay(): Boolean {
        if (Flavor.HOME || !p.getBoolean("everPaired", false) || p.contains("relayUrl") || relayToken.isNotBlank()) return false
        val t = addToken()
        p.edit().putString("relayUrl", Relay.DEFAULT_URL).putString("relayToken", t).putString("relayId", randomHex(8)).apply()
        touchDevice(t, "relay")
        return true
    }
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
        if (rules.checkPin(pin)) {
            p.edit().putInt("pinFails", 0).remove("pinLock").commit()
            if (pinResetAt > 0) { clearPinReset(); log(R.string.log_pin_reset_cancel) }   // whoever knows the PIN is still here: no reset
            return true
        }
        val f = p.getInt("pinFails", 0) + 1
        val lock = Safety.pinLockMs(f)
        p.edit().putInt("pinFails", f).putLong("pinLock", if (lock > 0) System.currentTimeMillis() + lock else 0).commit()
        if (lock > 0) log(R.string.log_pin_wrong, f, (lock / 60_000).toInt())
        return false
    }

    /**
     * Pairing a parent browser. Needs the code shown on the child's screen, and the parent PIN once one is set
     * (the child can see the code, never the PIN). Every 3rd wrong guess makes a new code and blocks for a while.
     * Returns the token, or null with [lastPairError] = closed | wait | pin locked | wrong pin | wrong code.
     */
    @Synchronized fun tryPair(code: String, pin: String, newPin: String = ""): String? {
        val now = System.currentTimeMillis()
        if (p.getLong("pairBlock", 0) > now) { lastPairError = "wait"; return null }
        val want = pairCode() ?: run { lastPairError = "closed"; return null }
        if (code.trim() != want) { pairFail(now); lastPairError = "wrong code"; return null }
        // after the code: anyone could otherwise send wrong PINs and keep the PIN (and new pairings) locked
        var set = PendingPin("", "")
        when (val c = PairPin.decide(rules.pinHash.isNotBlank(), newPin)) {
            is PairPin.Check.Bad -> { lastPairError = c.error; return null }   // no PIN yet: this pairing must set one
            is PairPin.Check.SetPin -> { val salt = Rules.salt8(); set = PendingPin(Rules.hashPin(c.pin, salt), salt) }
            PairPin.Check.UsePin -> {
                if (pinLockLeft() > 0) { lastPairError = "pin locked"; return null }
                if (!checkParentPin(pin)) { pairFail(now); lastPairError = if (pinLockLeft() > 0) "pin locked" else "wrong pin"; return null }
            }
        }
        // nothing is stored yet: the token (and the new PIN) only count once the page uses it (commitPair)
        val t = randomHex(24)
        pending.add(sha256(t), set, now)
        return t
    }
    class PendingPin(val hash: String, val salt: String)
    private val pending = PendingPairs<PendingPin>()

    /**
     * The first authenticated request with a pending pairing token: store the token, the new PIN (if this pairing set
     * one), "paired" and close pairing, all in one commit. False = not a pending token (unknown or expired).
     */
    @Synchronized fun commitPair(t: String?): Boolean {
        if (t.isNullOrBlank()) return false
        val now = System.currentTimeMillis()
        val pp = pending.take(sha256(t), now) ?: return false
        val e = p.edit().putStringSet("tokens", tokenHashes() + sha256(t)).putBoolean("everPaired", true)
            .remove("pairUntil").remove("pairCode").putInt("wrong", 0).remove("pairBlock").remove("recoverAt")
        val r = rules
        if (pp.hash.isNotEmpty() && r.pinHash.isBlank())
            e.putString("rules", r.copy(pinHash = pp.hash, pinSalt = pp.salt, rev = maxOf(r.rev + 1, now)).toJson(persist = true).toString())
        e.commit()
        pending.clear()
        return true
    }

    /**
     * "Bố mẹ quản lý trên máy này": the parent PIN (shared lockout with pairing) or a current Authenticator code.
     * Returns 1 = right, 0 = wrong (a `tamper` event for the parents), -1 = locked (see [adminLockLeft]).
     */
    @Synchronized fun checkAdmin(code: String, now: Long = System.currentTimeMillis()): Int {
        val c = code.filter { it.isDigit() }
        val secret = totpSecret
        val pinOk = rules.pinHash.isNotBlank() && pinLockLeft() == 0L
        val totpOk = secret.isNotBlank() && unlockLockLeft() == 0L
        if (!pinOk && !totpOk) return -1
        if (totpOk && c.length == 6) {
            val step = Totp.check(secret, c, now, p.getLong("totpLast", 0))
            if (step >= 0) { p.edit().putLong("totpLast", step).putInt("unlockFails", 0).putInt("adminRun", 0).remove("unlockLock").commit(); log(R.string.log_admin_open); return 1 }
        }
        if (pinOk) {
            if (checkParentPin(c)) { p.edit().putInt("adminRun", 0).apply(); log(R.string.log_admin_open); return 1 }
        } else {
            // no PIN (or it is locked): the wrong code counts against the Authenticator's own limit
            val f = p.getInt("unlockFails", 0) + 1
            val lock = Safety.pinLockMs(f)
            p.edit().putInt("unlockFails", f).putLong("unlockLock", if (lock > 0) now + lock else 0).commit()
        }
        event("tamper", R.string.ev_admin_wrong)
        val run = p.getInt("adminRun", 0) + 1
        val due = ShotLogic.due(rules.pinShot, run, p.getLong("shotLast", 0), now)
        p.edit().putInt("adminRun", run).apply { if (due) putLong("shotLast", now) }.commit()
        shotDue = due
        return if (adminLockLeft() > 0) -1 else 0
    }
    /** Set by a wrong [checkAdmin] when a photo is due (ShotLogic.due); the PIN dialog takes it once ([takeShotDue]). */
    @Volatile private var shotDue = false
    fun takeShotDue(): Boolean = shotDue.also { shotDue = false }
    /** Links photo [id] to the newest wrong-PIN event (last 2 min). */
    @Synchronized fun attachShot(id: String, now: Long = System.currentTimeMillis()) {
        val a = events()
        for (i in a.length() - 1 downTo 0) {
            val o = a.optJSONObject(i) ?: continue
            if (o.optString("kind") == "tamper" && now - o.optLong("t") < 120_000 && !o.has("shot")) { o.put("shot", id); break }
        }
        p.edit().putString("events", a.toString()).apply()
    }
    /** ms until the on-device admin can be tried again: both ways in must be locked. */
    @Synchronized fun adminLockLeft(): Long {
        val a = if (rules.pinHash.isNotBlank()) pinLockLeft() else Long.MAX_VALUE
        val b = if (totpSecret.isNotBlank()) unlockLockLeft() else Long.MAX_VALUE
        return minOf(a, b).takeIf { it != Long.MAX_VALUE } ?: 0
    }
    // ---- Telegram bot on this phone (Tg.kt). The token never leaves the phone (not in state, not in rules). ----
    var tgToken: String
        get() = p.getString("tgToken", "") ?: ""
        set(v) { p.edit().putString("tgToken", v).commit() }
    var tgBot: String
        get() = p.getString("tgBot", "") ?: ""
        set(v) { p.edit().putString("tgBot", v).apply() }
    var tgName: String   // the child's name as the parent page calls it (texts only)
        get() = p.getString("tgName", "") ?: ""
        set(v) { p.edit().putString("tgName", v).apply() }
    var tgLang: String
        get() = p.getString("tgLang", "vi") ?: "vi"
        set(v) { p.edit().putString("tgLang", v).apply() }
    var tgErr: String
        get() = p.getString("tgErr", "") ?: ""
        set(v) { p.edit().putString("tgErr", v).apply() }
    var tgOffset: Long
        get() = p.getLong("tgOffset", 0)
        set(v) { p.edit().putLong("tgOffset", v).apply() }
    /** Linked chats [{id, name, at}], max TgLogic.MAX_CHATS: only these get alerts and may send commands. */
    @Synchronized fun tgChats(): JSONArray = runCatching { JSONArray(p.getString("tgChats", "[]")) }.getOrDefault(JSONArray())
    @Synchronized fun tgChatOk(id: Long): Boolean = tgChats().let { a -> (0 until a.length()).any { a.optJSONObject(it)?.optLong("id") == id } }
    @Synchronized fun tgAddChat(id: Long, name: String, lang: String = tgLang): Boolean {
        val a = tgChats(); if (tgChatOk(id)) return true
        if (a.length() >= TgLogic.MAX_CHATS) return false
        a.put(JSONObject().put("id", id).put("name", name.take(40)).put("at", System.currentTimeMillis()).put("lang", lang))
        p.edit().putString("tgChats", a.toString()).commit(); return true
    }
    /** The chat's own language ("vi" / "en"); the bot default (page language at tgSet) for an unknown chat. */
    @Synchronized fun tgChatLang(id: Long): String = tgChats().let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }
        .firstOrNull { it.optLong("id") == id }?.optString("lang")?.takeIf { it == "vi" || it == "en" } } ?: tgLang
    @Synchronized fun tgSetChatLang(id: Long, lang: String) {
        val a = tgChats(); for (i in 0 until a.length()) a.optJSONObject(i)?.takeIf { it.optLong("id") == id }?.put("lang", lang)
        p.edit().putString("tgChats", a.toString()).commit()
    }
    @Synchronized fun tgUnlinkAll() { p.edit().remove("tgChats").commit() }
    @Synchronized fun tgRemoveChat(id: Long): Boolean {
        val a = tgChats(); val keep = JSONArray(); var hit = false
        for (i in 0 until a.length()) { val o = a.optJSONObject(i) ?: continue; if (o.optLong("id") == id) hit = true else keep.put(o) }
        p.edit().putString("tgChats", keep.toString()).commit(); return hit
    }
    /** A fresh 6-digit link code, valid TgLogic.CODE_MS, TgLogic.CODE_TRIES wrong tries. */
    @Synchronized fun tgNewCode(now: Long = System.currentTimeMillis()): String {
        val c = (SecureRandom().nextInt(900_000) + 100_000).toString()
        p.edit().putString("tgCode", c).putLong("tgCodeUntil", now + TgLogic.CODE_MS).putInt("tgCodeTries", 0).commit(); return c
    }
    @Synchronized fun tgCode(now: Long = System.currentTimeMillis()): String? =
        p.getString("tgCode", null)?.takeIf { now < p.getLong("tgCodeUntil", 0) && p.getInt("tgCodeTries", 0) < TgLogic.CODE_TRIES }
    @Synchronized fun tgCodeUntil(): Long = p.getLong("tgCodeUntil", 0)
    /** True = right code (then used up); a wrong one counts a try. */
    @Synchronized fun tgTryCode(given: String, now: Long = System.currentTimeMillis()): Boolean {
        val c = tgCode(now) ?: return false
        if (TgLogic.codeOk(given, c, p.getLong("tgCodeUntil", 0), now)) { p.edit().remove("tgCode").commit(); return true }
        p.edit().putInt("tgCodeTries", p.getInt("tgCodeTries", 0) + 1).commit(); return false
    }
    @Synchronized fun tgSeen(): List<String> = (p.getString("tgSeen", "") ?: "").split(',').filter { it.isNotEmpty() }
    @Synchronized fun tgSetSeen(l: List<String>) { p.edit().putString("tgSeen", l.joinToString(",")).apply() }
    /** Bot off: token, chats, code, offset, seen all gone. */
    @Synchronized fun tgClear() {
        p.edit().remove("tgToken").remove("tgBot").remove("tgChats").remove("tgCode").remove("tgCodeUntil").remove("tgCodeTries")
            .remove("tgOffset").remove("tgSeen").remove("tgErr").remove("tgName").commit()
    }

    /** Basic mode asked the parent once for the camera (wrong-PIN photo); a "deny" is not asked again. */
    var camAsked: Boolean
        get() = p.getBoolean("camAsked", false)
        set(v) { p.edit().putBoolean("camAsked", v).apply() }
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

    /**
     * The PIN (or a current Authenticator code) carried by a gated command ([CmdGate]). Shares the lockouts of the
     * on-device admin. 1 = right, 2 = nothing to check (no PIN and no Authenticator yet), 3 = none given (not counted),
     * 0 = wrong (a `tamper` event naming the command), -1 = locked.
     */
    @Synchronized fun checkCmdPin(code: String, what: String, now: Long = System.currentTimeMillis()): Int {
        val secret = totpSecret
        if (rules.pinHash.isBlank() && secret.isBlank()) return 2
        val c = code.filter { it.isDigit() }
        if (c.isEmpty()) return 3
        val pinOk = rules.pinHash.isNotBlank() && pinLockLeft() == 0L
        val totpOk = secret.isNotBlank() && unlockLockLeft() == 0L
        if (!pinOk && !totpOk) return -1
        if (totpOk && c.length == 6) {
            val step = Totp.check(secret, c, now, p.getLong("totpLast", 0))
            if (step >= 0) { p.edit().putLong("totpLast", step).putInt("unlockFails", 0).remove("unlockLock").commit(); return 1 }
        }
        if (pinOk) { if (checkParentPin(c)) return 1 }
        else {
            val f = p.getInt("unlockFails", 0) + 1
            val lock = Safety.pinLockMs(f)
            p.edit().putInt("unlockFails", f).putLong("unlockLock", if (lock > 0) now + lock else 0).commit()
        }
        event("tamper", R.string.ev_cmd_pin, what)
        return if (adminLockLeft() > 0) -1 else 0
    }

    /** "Forgot the PIN" (`pinReset`): the new PIN's salted hash waits here until [pinResetAt]; 0 = none pending. */
    @get:Synchronized val pinResetAt: Long get() = p.getLong("pinResetAt", 0)
    @Synchronized fun pinResetPending(): Pair<String, String> = (p.getString("pinResetHash", "") ?: "") to (p.getString("pinResetSalt", "") ?: "")
    @Synchronized fun setPinReset(hash: String, salt: String, at: Long) { p.edit().putString("pinResetHash", hash).putString("pinResetSalt", salt).putLong("pinResetAt", at).commit() }
    @Synchronized fun clearPinReset() { p.edit().remove("pinResetHash").remove("pinResetSalt").remove("pinResetAt").commit() }

    /** Base32 Authenticator secret set by a parent (`totpSet` command), "" = off. Never sent back to any parent. */
    @get:Synchronized @set:Synchronized var totpSecret: String
        get() = p.getString("totpSecret", "") ?: ""
        set(v) { p.edit().putString("totpSecret", v).putLong("totpLast", 0).apply() }

    /** Everything but "blocked" / "new" is lifted until this time (an Authenticator code was entered). */
    @get:Synchronized @set:Synchronized var unlockUntil: Long
        get() = p.getLong("unlockUntil", 0)
        set(v) { p.edit().putLong("unlockUntil", v).apply() }

    @Synchronized fun unlockLockLeft(): Long = maxOf(0, p.getLong("unlockLock", 0) - System.currentTimeMillis())

    /** Child typed a parent's Authenticator code: minutes unlocked, or 0 (wrong / reused / locked, see [unlockLockLeft]). */
    @Synchronized fun tryUnlock(code: String, now: Long = System.currentTimeMillis()): Int {
        val secret = totpSecret
        if (secret.isBlank() || unlockLockLeft() > 0) return 0
        val step = Totp.check(secret, code.filter { it.isDigit() }, now, p.getLong("totpLast", 0))
        if (step < 0) {
            val f = p.getInt("unlockFails", 0) + 1
            val lock = Safety.pinLockMs(f)
            p.edit().putInt("unlockFails", f).putLong("unlockLock", if (lock > 0) now + lock else 0).commit()
            if (f == 5) event("tamper", R.string.ev_unlock_wrong)
            return 0
        }
        val min = rules.unlockMin
        p.edit().putLong("totpLast", step).putInt("unlockFails", 0).remove("unlockLock")
            .putLong("unlockUntil", maxOf(now, p.getLong("unlockUntil", 0)) + min * 60_000L).commit()
        log(R.string.log_unlock, min)
        event("unlock", R.string.ev_unlock, min)
        return min
    }

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
    /** The phone's own Tailscale node key never expires (we turned that off through the API with the parent's token). */
    @get:Synchronized @set:Synchronized var tsNoExpiry: Boolean
        get() = p.getBoolean("tsNoExpiry", false)
        set(v) { p.edit().putBoolean("tsNoExpiry", v).apply() }
    @get:Synchronized @set:Synchronized var tsHide: Boolean
        get() = p.getBoolean("tsHide", true)
        set(v) { p.edit().putBoolean("tsHide", v).apply() }

    /** Alarm volume before a parent "ring" turned it up (-1 = none). Kept on disk so a process death mid-ring can still restore it (Ring.restoreAfterDeath). */
    @get:Synchronized @set:Synchronized var ringVol: Int
        get() = p.getInt("ringVol", -1)
        set(v) { p.edit().putInt("ringVol", v).commit() }
    /** Set by release: the guard must not start again (Boot / watchdog / accessibility poke) until Device Owner is given again. */
    @get:Synchronized @set:Synchronized var released: Boolean
        get() = p.getBoolean("released", false)
        set(v) { p.edit().putBoolean("released", v).commit() }

    /** Local opt-in for the kid launcher before any parent rule exists (Features sheet). Once paired, the parent's `launcher` rule wins. */
    /** The child confirmed the phone's auto-start page (can't be read back): the "Việc cần làm" step is done. */
    @get:Synchronized @set:Synchronized var autostartOk: Boolean
        get() = p.getBoolean("autostartOk", false)
        set(v) { p.edit().putBoolean("autostartOk", v).apply() }
    /** The on-device parent page offered the starting levels once (its WebView storage is wiped each session). */
    var lvOffered: Boolean
        get() = p.getBoolean("lvOffered", false)
        set(v) { p.edit().putBoolean("lvOffered", v).apply() }
    @get:Synchronized @set:Synchronized var launcherLocal: Boolean
        get() = p.getBoolean("launcherLocal", false)
        set(v) { p.edit().putBoolean("launcherLocal", v).commit() }

    /** Remembers command [id] (last 100). False if it was already executed. Written with commit(): it must survive a reboot / release. */
    @Synchronized fun markCmd(rawId: String): Boolean {
        val id = rawId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.ifEmpty { return true }
        val l = (p.getString("cmds", "") ?: "").split(',').filter { it.isNotEmpty() }
        if (id in l) return false
        p.edit().putString("cmds", (l + id).takeLast(100).joinToString(",")).commit()
        return true
    }

    /** Forgets command [rawId] again (its run failed, so a re-send with the same id must run). */
    @Synchronized fun unmarkCmd(rawId: String) {
        val id = rawId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.ifEmpty { return }
        val l = (p.getString("cmds", "") ?: "").split(',').filter { it.isNotEmpty() && it != id }
        p.edit().putString("cmds", l.joinToString(",")).commit()
    }

    /** Location history, points [t, lat, lon, acc]. */
    @Synchronized private fun trackAll(): JSONArray = runCatching { JSONArray(p.getString("track", "[]")) }.getOrDefault(JSONArray())

    /** Adds a point if [Track.shouldStore]; keeps 7 days and at most 2000 points. Returns true if stored. */
    @Synchronized fun addTrack(t: Long, lat: Double, lon: Double, acc: Int, gapMs: Long = Track.MAX_GAP_MS): Boolean {
        val a = trackAll()
        val last = if (a.length() > 0) a.optJSONArray(a.length() - 1) else null
        if (!Track.shouldStore(last?.optLong(0), last?.optDouble(1) ?: 0.0, last?.optDouble(2) ?: 0.0, t, lat, lon, gapMs)) return false
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

    /** Preinstalled apps the parent wants hidden but the OS refused (state.preinstalled[].refused). */
    @get:Synchronized @set:Synchronized var refusedHide: Set<String>
        get() = p.getStringSet("hideRefused", emptySet())!!.toSet()
        set(v) { p.edit().putStringSet("hideRefused", v).apply() }

    /** Apps we hid with setApplicationHidden (what release / the loop undo). */
    @get:Synchronized @set:Synchronized var hiddenByUs: Set<String>
        get() = p.getStringSet("hid", emptySet())!!.toSet()
        set(v) { p.edit().putStringSet("hid", v).commit() }

    /** Apps we disabled through the self-adb shell (`pm disable-user`) without Device Owner: the only ones we ever re-enable. */
    @get:Synchronized @set:Synchronized var shellDisabled: Set<String>
        get() = p.getStringSet("shellOff", emptySet())!!.toSet()
        set(v) { p.edit().putStringSet("shellOff", v).commit() }

    @get:Synchronized @set:Synchronized var location: JSONObject?
        get() = p.getString("loc", null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        set(v) { p.edit().putString("loc", v?.toString()).apply() }

    /** Launchable apps seen on the first tick; anything not in here (and not approved) is a "new" app. Null until first tick. */
    /** Settings we found before our first apply, put back by Policy.release (see Policy.savePrior). */
    @get:Synchronized @set:Synchronized var priorSaved: Boolean
        get() = p.getBoolean("priorSaved", false)
        set(v) { p.edit().putBoolean("priorSaved", v).commit() }
    @get:Synchronized @set:Synchronized var priorAutoTime: Int
        get() = p.getInt("priorAutoTime", -1)
        set(v) { p.edit().putInt("priorAutoTime", v).apply() }
    @get:Synchronized @set:Synchronized var priorDnsMode: String
        get() = p.getString("priorDnsMode", "") ?: ""
        set(v) { p.edit().putString("priorDnsMode", v).apply() }
    /** Private DNS was written by us without Device Owner (WRITE_SECURE_SETTINGS), so release / pause must put the prior value back. */
    @get:Synchronized @set:Synchronized var dnsByWss: Boolean
        get() = p.getBoolean("dnsByWss", false)
        set(v) { p.edit().putBoolean("dnsByWss", v).apply() }
    @get:Synchronized @set:Synchronized var priorDnsHost: String
        get() = p.getString("priorDnsHost", "") ?: ""
        set(v) { p.edit().putString("priorDnsHost", v).apply() }
    /** True once the "usage access off" tamper event was raised for the current revoke (cleared when the access is back). */
    @get:Synchronized @set:Synchronized var usageOffRaised: Boolean
        get() = p.getBoolean("usageOffRaised", false)
        set(v) { p.edit().putBoolean("usageOffRaised", v).commit() }
    /** Non-system default HOME package we already reported ("" = none). */
    @get:Synchronized @set:Synchronized var foreignHomeSeen: String
        get() = p.getString("foreignHomeSeen", "") ?: ""
        set(v) { p.edit().putString("foreignHomeSeen", v).apply() }

    @get:Synchronized @set:Synchronized var knownApps: Set<String>?
        get() = p.getStringSet("known", null)?.toSet()
        set(v) { p.edit().putStringSet("known", v).apply() }
    /** Bank apps our accessibility service is turned off for right now (see AccPause); empty = not paused. */
    @get:Synchronized @set:Synchronized var accPaused: Set<String>
        get() = p.getStringSet("accPaused", emptySet())!!.toSet()
        set(v) { p.edit().putStringSet("accPaused", v).commit() }
    /** When the last paused app left the front (0 = one is in front). */
    @get:Synchronized @set:Synchronized var accLeftAt: Long
        get() = p.getLong("accLeftAt", 0L)
        set(v) { p.edit().putLong("accLeftAt", v).apply() }
    /** Debugging switched off for a bank app ([DevOpts.bankPause], DevLogic.mask), 0 = nothing to put back. */
    @get:Synchronized @set:Synchronized var devPaused: Int
        get() = p.getInt("devPaused", 0)
        set(v) { p.edit().putInt("devPaused", v).commit() }
    /** Bank-app packages any website/bundled list ever offered ([BankList]), per pause kind; null = never set. */
    @get:Synchronized @set:Synchronized var bankSeen: Set<String>?
        get() = if (p.contains("bankSeen")) p.getStringSet("bankSeen", emptySet())!!.toSet() else null
        set(v) { p.edit().putStringSet("bankSeen", v ?: emptySet()).apply() }
    @get:Synchronized @set:Synchronized var vpnSeen: Set<String>?
        get() = if (p.contains("vpnSeen")) p.getStringSet("vpnSeen", emptySet())!!.toSet() else null
        set(v) { p.edit().putStringSet("vpnSeen", v ?: emptySet()).apply() }
    @get:Synchronized @set:Synchronized var bankListAt: Long
        get() = p.getLong("bankListAt", 0)
        set(v) { p.edit().putLong("bankListAt", v).apply() }
    /** Apps the VPN is paused for right now ([VpnPause]); empty = not paused. */
    @get:Synchronized @set:Synchronized var vpnPaused: Set<String>
        get() = p.getStringSet("vpnPaused", emptySet())!!.toSet()
        set(v) { p.edit().putStringSet("vpnPaused", v).commit() }
    @get:Synchronized @set:Synchronized var vpnLeftAt: Long
        get() = p.getLong("vpnLeftAt", 0L)
        set(v) { p.edit().putLong("vpnLeftAt", v).apply() }
    /** Owner mode: the always-on VPN we took off ("1|pkg" = with lockdown), "" = none. */
    @get:Synchronized @set:Synchronized var vpnSnap: String
        get() = p.getString("vpnSnap", "") ?: ""
        set(v) { p.edit().putString("vpnSnap", v).commit() }
    /** Tailscale was up and we sent it DISCONNECT_VPN: send CONNECT_VPN on resume. */
    @get:Synchronized @set:Synchronized var vpnTs: Boolean
        get() = p.getBoolean("vpnTs", false)
        set(v) { p.edit().putBoolean("vpnTs", v).commit() }
    /** A VPN stayed up after the pause (another VPN app, or always-on set in Settings on a basic-mode phone). */
    @get:Synchronized @set:Synchronized var vpnStuck: Boolean
        get() = p.getBoolean("vpnStuck", false)
        set(v) { p.edit().putBoolean("vpnStuck", v).apply() }
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
    @Synchronized fun addRequest(min: Int, note: String, chore: String? = null, tpl: String? = null): Boolean {
        val a = requests()
        if (a.length() >= 5) return false
        val o = JSONObject().put("id", randomHex(3)).put("t", System.currentTimeMillis()).put("min", min).put("note", note.trim().take(100))
        if (!chore.isNullOrBlank()) o.put("chore", chore)
        if (!tpl.isNullOrBlank()) o.put("tpl", tpl)   // v0.9.7 ReqTpl id
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

    /** Text in the app's language. Log lines and events go to the parent as they are (protocol: free text). */
    val tr: Tr = ResTr(app)
    fun str(id: Int, vararg a: Any): String = app.getString(id, *a)
    /** Kind = the string's resource name without "log_" (log_pin_wrong -> "pin_wrong"), so the page can pick an icon. */
    fun log(id: Int, vararg a: Any) = log(app.getString(id, *a), runCatching { app.resources.getResourceEntryName(id).removePrefix("log_") }.getOrDefault(""))
    fun event(kind: String, id: Int, vararg a: Any) = addEvent(Ev(kind, app.getString(id, *a)))

    /** Recent events for the parent: commands done, apps paused. Last 50. */
    @Synchronized fun log(msg: String, kind: String = "") {
        val a = runCatching { JSONArray(p.getString("log", "[]")) }.getOrDefault(JSONArray())
        a.put(JSONObject().put("t", System.currentTimeMillis()).put("m", msg).apply { if (kind.isNotEmpty()) put("k", kind) })
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
        val (now, evs) = Events.zoneStep(zones, inside, lat, lng, record = zoneBaseline || p.getStringSet("zin", null) == null, tr = tr)
        p.edit().putStringSet("zin", now).putBoolean("zoneBase", false).apply()
        evs.forEach { addEvent(it) }
        return evs
    }

    /** Battery event armed (false after it fired, until the level is back at >= 30 %). */
    @get:Synchronized @set:Synchronized var batteryArmed: Boolean
        get() = p.getBoolean("batArmed", true)
        set(v) { p.edit().putBoolean("batArmed", v).apply() }

    // v0.9.7 wind-down grayscale: we switched it on (owned) and what the phone had before (Gray.Saved)
    @get:Synchronized val grayOwned: Boolean get() = p.getBoolean("grayOwned", false)
    @get:Synchronized val graySaved: Gray.Saved? get() =
        if (!p.contains("grayOn")) null else Gray.Saved(Gray.dec(p.getString("grayOn", null)), Gray.dec(p.getString("grayMode", null)))
    @Synchronized fun setGray(owned: Boolean, saved: Gray.Saved?) {
        val e = p.edit().putBoolean("grayOwned", owned)
        if (saved == null) e.remove("grayOn").remove("grayMode") else e.putString("grayOn", Gray.enc(saved.on)).putString("grayMode", Gray.enc(saved.mode))
        e.commit()
    }

    /** v0.9.7 check-in ("arrived" / "pickup"): an event with the last fix if it is fresh. Null = same button < 1 min ago. */
    @Synchronized fun addCheckin(ck: String, text: String, loc: JSONObject?): JSONObject? {
        val now = System.currentTimeMillis()
        val a = events()
        if (Checkin.tooSoon(a, ck, now)) return null
        val o = Checkin.event(randomHex(3), now, ck, text, Checkin.fresh(loc, now))
        p.edit().putString("events", Events.prune(a.put(o), now).toString()).commit()
        return o
    }
    /** A new location fix: attached to check-ins of the last 2 minutes. */
    @Synchronized fun fillCheckins(loc: JSONObject): Boolean {
        val now = System.currentTimeMillis(); val a = events()
        if (!Checkin.fill(a, loc, now)) return false
        p.edit().putString("events", a.toString()).apply()
        return true
    }
    /** Time of the last check-in of [ck] (button label), 0 = none in the event list. */
    @Synchronized fun lastCheckin(ck: String): Long {
        val a = events(); var t = 0L
        for (i in 0 until a.length()) a.optJSONObject(i)?.takeIf { it.optString("kind") == Checkin.KIND && it.optString("ck") == ck }?.let { t = maxOf(t, it.optLong("t")) }
        return t
    }

    @Synchronized fun wipe() { p.edit().clear().apply() }

    companion object {
        @Volatile private var inst: Store? = null
        fun of(ctx: Context): Store = inst ?: synchronized(this) { inst ?: Store(ctx.applicationContext).also { inst = it } }

        fun randomHex(bytes: Int): String = ByteArray(bytes).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        fun sha256(s: String): String = Rules.sha256(s)
    }
}
