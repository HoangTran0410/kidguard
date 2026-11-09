package dev.kidguard

import org.json.JSONArray
import org.json.JSONObject

/** A bedtime-like window on chosen days (ISO 1=Mon..7=Sun). It may cross midnight and then belongs to the day it starts. */
data class Schedule(val name: String, val on: Boolean, val days: Set<Int>, val start: String, val end: String) {
    fun toJson(): JSONObject = JSONObject().put("name", name).put("on", on).put("days", JSONArray(days.sorted()))
        .put("start", start).put("end", end)

    /** [dow]: ISO weekday of today. */
    fun active(minute: Int, dow: Int): Boolean {
        if (!on) return false
        val s = Rules.parseHm(start) ?: return false; val e = Rules.parseHm(end) ?: return false
        return when {
            s == e -> false
            s < e -> dow in days && minute in s until e
            else -> (dow in days && minute >= s) || ((if (dow == 1) 7 else dow - 1) in days && minute < e)
        }
    }

    companion object {
        fun fromJson(o: JSONObject): Schedule? {
            val st = o.optString("start", ""); val en = o.optString("end", "")
            if (Rules.parseHm(st) == null || Rules.parseHm(en) == null) return null
            val a = o.optJSONArray("days") ?: return null
            val days = (0 until a.length()).map { a.optInt(it, 0) }.filter { it in 1..7 }.toSet()
            if (days.isEmpty()) return null
            return Schedule(o.optString("name", "").take(40), o.optBoolean("on", true), days, st, en)
        }
    }
}

/** A chore the child can offer in "Xin thêm giờ": +[min] minutes if the parent agrees. */
data class Chore(val id: String, val name: String, val min: Int) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("min", min)

    companion object {
        const val MAX = 20
        private val ID = Regex("[\\w-]{1,16}")
        fun fromJson(o: JSONObject): Chore? {
            val id = o.optString("id", ""); val name = o.optString("name", "").trim()
            val min = o.optInt("min", 0)
            if (!id.matches(ID) || name.isEmpty() || name.length > 40 || min !in 5..120) return null
            return Chore(id, name, min)
        }
    }
}

/** A place the parent is told about when the child arrives / leaves (circle of [r] metres). */
data class Zone(val id: String, val name: String, val lat: Double, val lng: Double, val r: Int) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("lat", lat).put("lng", lng).put("r", r)

    companion object {
        const val MAX = 10
        private val ID = Regex("[\\w-]{1,16}")
        fun fromJson(o: JSONObject): Zone? {
            val id = o.optString("id", ""); val name = o.optString("name", "").trim().take(40)
            val lat = o.optDouble("lat", Double.NaN); val lng = o.optDouble("lng", Double.NaN)
            val r = o.optInt("r", 0)
            if (!id.matches(ID) || name.isEmpty() || lat.isNaN() || lng.isNaN() || lat !in -90.0..90.0 || lng !in -180.0..180.0 || r !in 50..2000) return null
            return Zone(id, name, lat, lng, r)
        }
    }
}

/**
 * Apps that share one daily limit ([min], 0 = none) and/or may only be used inside [hours] (empty = any time).
 * A group of one app is how a single app gets "only 19:00-20:00".
 */
data class AppGroup(val id: String, val name: String, val pkgs: Set<String>, val min: Int, val hours: List<Schedule>) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("pkgs", JSONArray(pkgs.sorted())).put("min", min)
        .put("hours", JSONArray().apply { hours.forEach { put(it.toJson()) } })

    /** Inside an allowed window now (or no windows set). */
    fun open(minute: Int, dow: Int): Boolean = hours.isEmpty() || hours.any { it.active(minute, dow) }

    companion object {
        const val MAX = 10
        const val MAX_PKGS = 100
        private val ID = Regex("[\\w-]{1,16}")
        fun fromJson(o: JSONObject): AppGroup? {
            val id = o.optString("id", ""); val name = o.optString("name", "").trim().take(40)
            if (!id.matches(ID) || name.isEmpty()) return null
            val pkgs = o.optJSONArray("pkgs")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { Debloat.validPkg(it) } }
                ?.take(MAX_PKGS)?.toSet() ?: emptySet()
            val hours = o.optJSONArray("hours")?.let { a ->
                (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(Schedule::fromJson)?.copy(name = "", on = true) }.take(5)
            } ?: emptyList()
            return AppGroup(id, name, pkgs, o.optInt("min", 0).coerceIn(0, 24 * 60), hours)
        }
    }
}

/** Google Authenticator style codes (RFC 6238: HMAC-SHA1, 30 s, 6 digits). Pure, unit tested. */
object Totp {
    private const val B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    /** Base32 (no padding, spaces / case ignored) -> bytes, or null when invalid or shorter than 80 bits. */
    fun decode(s: String): ByteArray? {
        val c = s.uppercase().filter { it != ' ' && it != '=' && it != '-' }
        if (c.isEmpty() || c.any { it !in B32 }) return null
        var buf = 0; var bits = 0
        val out = java.io.ByteArrayOutputStream()
        for (ch in c) {
            buf = (buf shl 5) or B32.indexOf(ch); bits += 5
            if (bits >= 8) { out.write((buf shr (bits - 8)) and 0xff); bits -= 8 }
        }
        return out.toByteArray().takeIf { it.size >= 10 }
    }

    fun code(key: ByteArray, counter: Long): String {
        val mac = javax.crypto.Mac.getInstance("HmacSHA1").apply { init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA1")) }
        val h = mac.doFinal(java.nio.ByteBuffer.allocate(8).putLong(counter).array())
        val o = h[h.size - 1].toInt() and 0xf
        val bin = ((h[o].toInt() and 0x7f) shl 24) or ((h[o + 1].toInt() and 0xff) shl 16) or ((h[o + 2].toInt() and 0xff) shl 8) or (h[o + 3].toInt() and 0xff)
        return "%06d".format(bin % 1_000_000)
    }

    /**
     * The time step [code] matches within +-1 step of [nowMs] and after [lastUsed] (a code works once), else -1.
     */
    fun check(secret: String, code: String, nowMs: Long, lastUsed: Long): Long {
        val key = decode(secret) ?: return -1
        if (code.length != 6 || !code.all { it.isDigit() }) return -1
        val t = nowMs / 30_000
        for (step in longArrayOf(t, t - 1, t + 1)) if (step > lastUsed && code(key, step) == code) return step
        return -1
    }
}

/** What the parent set. Plain data + JSON, so the same shape travels over LAN and the relay. */
data class Rules(
    val rev: Long = 0,                       // ms of the last edit; the newer one wins
    val dailyMin: Int = 0,                   // 0 = no daily limit
    val appMin: Map<String, Int> = emptyMap(),
    val blocked: Set<String> = emptySet(),
    val bedtimeOn: Boolean = false,
    val bedStart: String = "21:30",
    val bedEnd: String = "06:30",
    val allow: Set<String> = emptySet(),     // never blocked by daily / bedtime / pause
    val pausedUntil: Long = 0,
    val bonusDay: String = "",
    val bonusMin: Int = 0,
    val noInstall: Boolean = false,
    val noDebug: Boolean = false,
    val pinHash: String = "",                // hash of the parent PIN, guards "pair another device" on the child (never sent to the parent)
    val pinSalt: String = "",                // "" = legacy sha256("kg:"+pin); else iterated sha256 with this salt
    val dailyByDay: List<Int> = emptyList(), // Monday..Sunday minutes, or empty; replaces dailyMin when present
    val schedules: List<Schedule> = emptyList(),
    val blockNew: Boolean = false,           // apps installed after setup stay blocked until approved
    val approved: Set<String> = emptySet(),
    val lockLocation: Boolean = true,
    val noAirplane: Boolean = true,
    val safeDns: String = "",                // Private DNS host, "" = off
    val noVpn: Boolean = false,              // Device Owner: DISALLOW_CONFIG_VPN (no VPN apps / settings for the child)
    val trackMin: Int = 10,                  // location history every N minutes: 0 (off), 5, 10, 15, 30, 60
    val showWatching: Boolean = true,        // child sees "parent is watching / controlling" while the screen is viewed
    val autoUpdate: Boolean = true,          // daily check of GitHub releases; owner installs silently, basic asks the child
    val chores: List<Chore> = emptyList(),   // max 20
    val zones: List<Zone> = emptyList(),     // max 10
    val hidden: Set<String> = emptySet(),    // preinstalled apps the parent hid (owner only), max Debloat.MAX_HIDDEN
    val groups: List<AppGroup> = emptyList(), // max AppGroup.MAX
    val unlockMin: Int = 30,                 // minutes an Authenticator code unlocks on the child phone: 15, 30, 60, 120
    val windGray: Boolean = false,           // v0.9.7 wind-down: grayscale from windMin before bedtime until it ends
    val windMin: Int = 30,                   // WindDown.STEPS
    val windNudge: Boolean = false,          // "10 minutes to bedtime" notification
    val lockMsg: String = "",                // v0.9.7 lock-screen owner info (owner only), Notes.MSG_MAX
    val lockContact: String = "",
    val blockNote: String = "",              // v0.9.7 parent note on the block message, Notes.NOTE_MAX
    val launcher: LauncherRule = LauncherRule(), // v0.9.9 kid launcher (HomeActivity), owner only
) {
    /** [persist] = true also writes pinHash/pinSalt (only for this phone's own storage). The default is safe to send to a parent: it has `pinSet` only. */
    fun toJson(persist: Boolean = false): JSONObject = JSONObject()
        .put("rev", rev).put("dailyMin", dailyMin)
        .put("appMin", JSONObject().apply { appMin.forEach { (k, v) -> put(k, v) } })
        .put("blocked", JSONArray(blocked.sorted()))
        .put("bedtime", JSONObject().put("on", bedtimeOn).put("start", bedStart).put("end", bedEnd))
        .put("allow", JSONArray(allow.sorted()))
        .put("pausedUntil", pausedUntil)
        .put("bonus", JSONObject().put("day", bonusDay).put("min", bonusMin))
        .put("noInstall", noInstall).put("noDebug", noDebug)
        .put("pinSet", pinHash.isNotBlank())
        .put("dailyByDay", JSONArray(dailyByDay))
        .put("schedules", JSONArray().apply { schedules.forEach { put(it.toJson()) } })
        .put("blockNew", blockNew).put("approved", JSONArray(approved.sorted()))
        .put("lockLocation", lockLocation).put("noAirplane", noAirplane).put("safeDns", safeDns).put("noVpn", noVpn).put("trackMin", trackMin)
        .put("showWatching", showWatching).put("autoUpdate", autoUpdate)
        .put("chores", JSONArray().apply { chores.forEach { put(it.toJson()) } })
        .put("zones", JSONArray().apply { zones.forEach { put(it.toJson()) } })
        .put("hidden", JSONArray(hidden.sorted()))
        .put("groups", JSONArray().apply { groups.forEach { put(it.toJson()) } }).put("unlockMin", unlockMin)
        .put("windDown", JSONObject().put("gray", windGray).put("min", windMin).put("nudge", windNudge))
        .put("lockInfo", JSONObject().put("msg", lockMsg).put("contact", lockContact)).put("blockNote", blockNote)
        .put("launcher", launcher.toJson())
        .also { if (persist) it.put("pinHash", pinHash).put("pinSalt", pinSalt) }

    /** True when [pin] matches the stored hash (legacy or salted). False when no PIN is set. */
    fun checkPin(pin: String): Boolean =
        pinHash.isNotBlank() && java.security.MessageDigest.isEqual(hashPin(pin, pinSalt).toByteArray(), pinHash.toByteArray())

    companion object {
        fun salt8() = ByteArray(8).also { java.security.SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        private val HEX = "0123456789abcdef".toCharArray()
        /** Lower-case hex. A table, not "%02x".format: 10 000 PIN rounds x 32 formats took ~2 s on an old phone. */
        fun hex(b: ByteArray): String {
            val c = CharArray(b.size * 2)
            for (i in b.indices) { val v = b[i].toInt() and 0xff; c[2 * i] = HEX[v ushr 4]; c[2 * i + 1] = HEX[v and 15] }
            return String(c)
        }
        fun sha256(s: String): String = hex(java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray()))

        /** Salt "" = the old unsalted form; else sha256 iterated 10 000 times (same output as before, one digest reused). */
        fun hashPin(pin: String, salt: String): String {
            if (salt.isEmpty()) return sha256("kg:$pin")
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val sb = salt.toByteArray()
            var h = md.digest("$salt:$pin".toByteArray())
            val buf = ByteArray(64 + sb.size)
            System.arraycopy(sb, 0, buf, 64, sb.size)
            repeat(9_999) {
                // h + salt, where h is the 64-char hex of the previous round
                for (i in h.indices) { val v = h[i].toInt() and 0xff; buf[2 * i] = HEX[v ushr 4].code.toByte(); buf[2 * i + 1] = HEX[v and 15].code.toByte() }
                h = md.digest(buf)
            }
            return hex(h)
        }

        fun fromJson(o: JSONObject): Rules {
            val bed = o.optJSONObject("bedtime") ?: JSONObject()
            val bonus = o.optJSONObject("bonus") ?: JSONObject()
            val appMin = mutableMapOf<String, Int>()
            o.optJSONObject("appMin")?.let { m -> m.keys().forEach { k -> m.optInt(k, 0).takeIf { it > 0 }?.let { appMin[k] = it } } }
            // the page sends a new PIN in plain ("pin"); only a salted hash is kept
            val newPin = o.optString("pin", "").filter { it.isDigit() }.takeIf { it.length >= 4 }
            val salt = if (newPin != null) salt8() else o.optString("pinSalt", "").filter { it.isLetterOrDigit() }
            return Rules(
                rev = o.optLong("rev", 0),
                dailyMin = o.optInt("dailyMin", 0).coerceIn(0, 24 * 60),
                appMin = appMin,
                blocked = o.optJSONArray("blocked").strings(),
                bedtimeOn = bed.optBoolean("on", false),
                bedStart = bed.optString("start", "21:30").takeIf { parseHm(it) != null } ?: "21:30",
                bedEnd = bed.optString("end", "06:30").takeIf { parseHm(it) != null } ?: "06:30",
                allow = o.optJSONArray("allow").strings(),
                pausedUntil = o.optLong("pausedUntil", 0),
                bonusDay = bonus.optString("day", ""),
                bonusMin = bonus.optInt("min", 0).coerceIn(0, 24 * 60),
                noInstall = o.optBoolean("noInstall", false),
                noDebug = o.optBoolean("noDebug", false),
                pinHash = if (newPin != null) hashPin(newPin, salt) else o.optString("pinHash", ""),
                pinSalt = if (newPin != null || o.optString("pinHash", "").isNotEmpty()) salt else "",
                dailyByDay = o.optJSONArray("dailyByDay")?.let { a ->
                    (0 until a.length()).map { a.optInt(it, 0).coerceIn(0, 24 * 60) }.takeIf { l -> l.size == 7 }
                } ?: emptyList(),
                schedules = o.optJSONArray("schedules")?.let { a ->
                    (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(Schedule::fromJson) }.take(20)
                } ?: emptyList(),
                chores = o.optJSONArray("chores")?.let { a ->
                    (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(Chore::fromJson) }.distinctBy { it.id }.take(Chore.MAX)
                } ?: emptyList(),
                zones = o.optJSONArray("zones")?.let { a ->
                    (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(Zone::fromJson) }.distinctBy { it.id }.take(Zone.MAX)
                } ?: emptyList(),
                hidden = o.optJSONArray("hidden").strings().filter { Debloat.validPkg(it) }.sorted().take(Debloat.MAX_HIDDEN).toSet(),
                groups = o.optJSONArray("groups")?.let { a ->
                    (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(AppGroup::fromJson) }.distinctBy { it.id }.take(AppGroup.MAX)
                } ?: emptyList(),
                unlockMin = o.optInt("unlockMin", 30).takeIf { it in UNLOCK_STEPS } ?: 30,
                windGray = o.optJSONObject("windDown")?.optBoolean("gray", false) ?: false,
                windMin = o.optJSONObject("windDown")?.optInt("min", 30)?.takeIf { it in WindDown.STEPS } ?: 30,
                windNudge = o.optJSONObject("windDown")?.optBoolean("nudge", false) ?: false,
                lockMsg = Notes.clean(o.optJSONObject("lockInfo")?.optString("msg", "") ?: "", Notes.MSG_MAX),
                lockContact = Notes.clean(o.optJSONObject("lockInfo")?.optString("contact", "") ?: "", Notes.MSG_MAX),
                blockNote = Notes.clean(o.optString("blockNote", ""), Notes.NOTE_MAX),
                launcher = LauncherRule.fromJson(o.optJSONObject("launcher")),
                blockNew = o.optBoolean("blockNew", false),
                approved = o.optJSONArray("approved").strings(),
                lockLocation = o.optBoolean("lockLocation", true),
                noAirplane = o.optBoolean("noAirplane", true),
                showWatching = o.optBoolean("showWatching", true),
                autoUpdate = o.optBoolean("autoUpdate", true),
                noVpn = o.optBoolean("noVpn", false),
                trackMin = o.optInt("trackMin", 10).takeIf { it in TRACK_STEPS } ?: 10,
                safeDns = o.optString("safeDns", "").trim().lowercase().takeIf { it.matches(DNS_HOST) } ?: "",
            )
        }

        val TRACK_STEPS = setOf(0, 5, 10, 15, 30, 60)
        val UNLOCK_STEPS = setOf(15, 30, 60, 120)
        private val DNS_HOST = Regex("[a-z0-9]([a-z0-9.-]{0,251}[a-z0-9])?")

        /** ISO weekday (1=Mon..7=Sun) of "yyyy-MM-dd", or 1 if unparsable. Pure, no java.time (old API levels). */
        fun isoDow(day: String): Int {
            val p = day.split("-").mapNotNull { it.toIntOrNull() }
            if (p.size != 3) return 1
            val y = if (p[1] <= 2) p[0] - 1 else p[0]
            val t = intArrayOf(0, 3, 2, 5, 0, 3, 5, 1, 4, 6, 2, 4)
            val sun0 = (y + y / 4 - y / 100 + y / 400 + t[(p[1] - 1).coerceIn(0, 11)] + p[2]) % 7   // 0=Sunday
            return if (sun0 == 0) 7 else sun0
        }

        private fun JSONArray?.strings(): Set<String> =
            if (this == null) emptySet() else (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotBlank() } }.toSet()

        /** "21:30" -> 1290 minutes, or null. */
        fun parseHm(s: String): Int? {
            val p = s.split(":")
            if (p.size != 2) return null
            val h = p[0].toIntOrNull() ?: return null
            val m = p[1].toIntOrNull() ?: return null
            return if (h in 0..23 && m in 0..59) h * 60 + m else null
        }

        /** Is minute-of-day [now] inside [start, end), the window may cross midnight. */
        fun inWindow(now: Int, start: Int, end: Int): Boolean = when {
            start == end -> false
            start < end -> now in start until end
            else -> now >= start || now < end
        }
    }
}

/** Today's daily limit in minutes without bonus (0 = none): the weekday entry of dailyByDay when present. */
fun baseLimitMin(r: Rules, today: String): Int = if (r.dailyByDay.size == 7) r.dailyByDay[Rules.isoDow(today) - 1] else r.dailyMin

/**
 * Which launcher apps to suspend right now, and why. Pure, so it is unit-tested.
 * [usageMs]: foreground ms today per package. [launchable]: apps with a launcher icon. [protected]: never touched.
 * [newApps]: installed after setup (only looked at when blockNew). Priority: blocked > appLimit > groupHours > groupLimit > new > paused > schedule > bedtime > daily.
 * [unlockUntil]: an Authenticator code was entered on the child phone; until then only "blocked" and "new" stay.
 */
fun decide(
    r: Rules, usageMs: Map<String, Long>, minuteOfDay: Int, nowMs: Long, today: String,
    launchable: Set<String>, protected: Set<String>, newApps: Set<String> = emptySet(), unlockUntil: Long = 0,
): Map<String, String> {
    val out = linkedMapOf<String, String>()
    val free = protected + r.allow
    for (p in r.blocked) if (p !in protected) out[p] = "blocked"
    for ((p, min) in r.appMin) if (p !in protected && p !in out && (usageMs[p] ?: 0) >= min * 60_000L) out[p] = "appLimit"
    val dow = Rules.isoDow(today)
    for (g in r.groups) {
        val why = when {
            !g.open(minuteOfDay, dow) -> "groupHours"
            g.min > 0 && g.pkgs.sumOf { usageMs[it] ?: 0L } >= g.min * 60_000L -> "groupLimit"
            else -> null
        } ?: continue
        for (p in g.pkgs) if (p !in protected && p !in out) out[p] = why
    }
    if (r.blockNew) for (p in newApps) if (p !in free && p !in r.approved && p !in out) out[p] = "new"

    val bed = r.bedtimeOn && run {
        val s = Rules.parseHm(r.bedStart); val e = Rules.parseHm(r.bedEnd)
        s != null && e != null && Rules.inWindow(minuteOfDay, s, e)
    }
    val sched = r.schedules.any { it.active(minuteOfDay, dow) }
    val paused = r.pausedUntil > nowMs
    val base = baseLimitMin(r, today)
    val limit = base + if (r.bonusDay == today) r.bonusMin else 0
    val used = usageMs.filterKeys { it in launchable && it !in free }.values.sum()
    val overDaily = base > 0 && used >= limit * 60_000L
    val all = when { paused -> "paused"; sched -> "schedule"; bed -> "bedtime"; overDaily -> "daily"; else -> null }
    if (all != null) for (p in launchable) if (p !in free && p !in out) out[p] = all
    if (unlockUntil > nowMs) out.entries.removeAll { it.value != "blocked" && it.value != "new" }
    return out
}

/** ms of use left today against the daily limit, or -1 when there is none. */
fun dailyLeftMs(r: Rules, usageMs: Map<String, Long>, today: String, launchable: Set<String>, protected: Set<String>): Long {
    val base = baseLimitMin(r, today)
    if (base <= 0) return -1
    val free = protected + r.allow
    val used = usageMs.filterKeys { it in launchable && it !in free }.values.sum()
    val limit = (base + if (r.bonusDay == today) r.bonusMin else 0) * 60_000L
    return (limit - used).coerceAtLeast(0)
}

/** Location history filter, pure so it is unit-tested. */
object Track {
    const val MIN_MOVE_M = 30.0
    const val MAX_GAP_MS = 60 * 60_000L
    const val KEEP_MS = 7 * 24 * 3_600_000L
    const val MAX_POINTS = 2100              // 7 days at the 5-min step = 2016

    /** Metres between two points (haversine). */
    fun meters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val a = Math.sin((p2 - p1) / 2).let { it * it } + Math.cos(p1) * Math.cos(p2) * Math.sin(Math.toRadians(lon2 - lon1) / 2).let { it * it }
        return 2 * r * Math.asin(Math.sqrt(a))
    }

    /** Same-spot points are kept once per interval (minus slack for a late tick), so the parent's timeline has no hour-long holes. */
    fun gapFor(everyMin: Int): Long = (everyMin * 60_000L - 90_000L).coerceIn(60_000L, MAX_GAP_MS)

    /** Store a new point? Yes when there is none yet, it moved >= 30 m from the last stored one, or >= [gapMs] passed. */
    fun shouldStore(lastT: Long?, lastLat: Double, lastLon: Double, t: Long, lat: Double, lon: Double, gapMs: Long = MAX_GAP_MS): Boolean =
        lastT == null || t - lastT >= gapMs || meters(lastLat, lastLon, lat, lon) >= MIN_MOVE_M
}

/** Pure guards for the LAN server and the PIN / pairing brute-force limits (unit tested). */
object Safety {
    /** 0 while [fails] < [free]; then [baseMs], doubling per extra failure, capped at 64x. */
    fun backoffMs(fails: Int, free: Int, baseMs: Long): Long =
        if (fails < free) 0 else baseMs shl minOf(fails - free, 6)

    /** PIN: 5 free tries, then 5 min, 10 min, … */
    fun pinLockMs(fails: Int): Long = backoffMs(fails, 5, 5 * 60_000L)

    /** Pairing: every 3rd wrong guess (code or PIN) makes a new code and blocks for 30 s, 60 s, … */
    fun pairBlockMs(fails: Int): Long = if (fails > 0 && fails % 3 == 0) backoffMs(fails / 3, 1, 30_000L) else 0

    private val UI = Regex("""/ui/([a-z0-9][a-z0-9-]{0,40})\.(js|css)""")

    /** `/ui/<name>.js|css` (the page's code, ui/assets/) -> (asset path, Content-Type); anything else (traversal, other dirs) -> null. */
    fun uiAsset(path: String): Pair<String, String>? = UI.matchEntire(path)?.let { m ->
        "assets/${m.groupValues[1]}.${m.groupValues[2]}" to (if (m.groupValues[2] == "js") "text/javascript; charset=utf-8" else "text/css; charset=utf-8")
    }

    private fun portOk(p: String) = p.isEmpty() || (p.startsWith(":") && p.length in 2..6 && p.drop(1).all { it.isDigit() })
    private val V4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")
    private val LABEL = Regex("[a-z0-9-]{1,63}")

    /**
     * Host header check against DNS rebinding: a web page can only reach us under a name its attacker controls,
     * i.e. a public dotted domain. Allowed: IP literals, localhost, single-label LAN / MagicDNS names, *.ts.net.
     */
    fun hostOk(raw: String?): Boolean {
        val h0 = raw?.trim()?.lowercase() ?: return true    // HTTP/1.0 clients send none
        if (h0.isEmpty()) return true
        if (h0.startsWith("[")) return h0.substringBefore("]").drop(1).matches(Regex("[0-9a-z:.%]+")) && portOk(h0.substringAfter("]", ""))
        if (!portOk(h0.substring(h0.indexOf(':').let { if (it < 0) h0.length else it }))) return false
        val h = h0.substringBefore(":").trimEnd('.')
        return h == "localhost" || h.matches(V4) || h.matches(LABEL) || (h.endsWith(".ts.net") && h.split('.').all { it.matches(LABEL) })
    }

    /**
     * CORS for /api: only a page served by another KidGuard phone on the LAN / tailnet may read our answers, so a
     * parent's browser can manage several phones from one page. Origin must be plain http with a host that [hostOk]
     * takes and, for IP literals, a private / CGNAT / loopback / link-local address: a public web site never qualifies.
     */
    fun corsOriginOk(raw: String?): Boolean {
        val o = raw?.trim()?.lowercase() ?: return false
        if (!o.startsWith("http://")) return false
        val hp = o.removePrefix("http://")
        if (hp.isEmpty() || hp.contains('/') || !hostOk(hp)) return false
        if (hp.startsWith("[")) {
            val a = hp.substringBefore("]").drop(1)
            return a == "::1" || a.startsWith("fe80:") || a.startsWith("fc") || a.startsWith("fd")
        }
        val h = hp.substringBefore(":").trimEnd('.')
        if (!h.matches(V4)) return true   // localhost, single-label LAN names, *.ts.net (hostOk already refused public names)
        val b = h.split('.').map { it.toInt() }
        if (b.any { it > 255 }) return false
        return b[0] == 10 || b[0] == 127 || (b[0] == 172 && b[1] in 16..31) || (b[0] == 192 && b[1] == 168) ||
            (b[0] == 169 && b[1] == 254) || (b[0] == 100 && b[1] in 64..127)
    }
}
