package dev.kidguard

import org.json.JSONObject

/** Rule `launcher` (parent -> phone, protocol "v0.9.9: kid launcher"). Missing = off. */
data class LauncherRule(val on: Boolean = false, val settings: Boolean = false, val notes: Boolean = true, val lockCustom: Boolean = false, val style: String = "") {
    fun toJson(): JSONObject = JSONObject().put("on", on).put("settings", settings).put("notes", notes).put("lockCustom", lockCustom).put("style", style)

    companion object {
        val STYLES = setOf("", "be", "teen", "gon")
        fun fromJson(o: JSONObject?): LauncherRule = if (o == null) LauncherRule() else LauncherRule(
            o.optBoolean("on", false), o.optBoolean("settings", false), o.optBoolean("notes", true), o.optBoolean("lockCustom", false),
            o.optString("style", "").takeIf { it in STYLES } ?: "")
    }
}

/** Pure part of the kid launcher (HomeActivity): what shows, greyed or hidden, time left, search, crash counter. */
object LauncherLogic {
    enum class Show { OK, PAUSED, HIDE }

    /** Never listed: system plumbing that some phones give an icon, settings (unless the rule allows), remote access. */
    val PLUMBING = setOf("com.google.android.gms", "com.google.android.gsf", "com.android.stk", "com.android.cellbroadcastreceiver",
        "com.google.android.cellbroadcastreceiver", "com.google.android.apps.safetyhub", TsLogic.PKG)
    val SETTINGS = setOf("com.android.settings", "com.coloros.settings", "com.oppo.settings", "com.miui.securitycenter",
        "com.samsung.android.settings", "com.android.settings.intelligence", "com.google.android.apps.wellbeing")

    /** Reasons that mean "not for this child at all": the app is not shown anywhere (grid, folders, dock, drawer, search). */
    val HIDE_REASONS = setOf("blocked", "new")

    /**
     * [want]: Guard's decide() result (pkg -> reason). Blocked by the parent / waiting for approval / hidden ("Dọn máy") /
     * plumbing / settings => [Show.HIDE]; any other reason => greyed with a clock badge; else normal.
     */
    fun show(pkg: String, want: Map<String, String>, r: Rules, self: String): Show = when {
        pkg == self || pkg in PLUMBING -> Show.HIDE
        pkg in r.hidden || pkg in r.blocked || want[pkg] in HIDE_REASONS -> Show.HIDE
        pkg in SETTINGS && !r.launcher.settings -> Show.HIDE
        want[pkg] != null -> Show.PAUSED
        else -> Show.OK
    }

    /** ms left today for [pkg] under its own limit and its groups' limits (the smallest), -1 when none applies. */
    fun appLeftMs(r: Rules, usageMs: Map<String, Long>, pkg: String): Long {
        val left = mutableListOf<Long>()
        r.appMin[pkg]?.let { left += it * 60_000L - (usageMs[pkg] ?: 0L) }
        for (g in r.groups) if (pkg in g.pkgs && g.min > 0) left += g.min * 60_000L - g.pkgs.sumOf { usageMs[it] ?: 0L }
        return left.minOrNull()?.coerceAtLeast(0) ?: -1
    }

    /** When a greyed app opens again. */
    sealed class Reopen {
        object Tomorrow : Reopen()
        data class AtMinute(val minute: Int) : Reopen()
        data class AtMs(val ms: Long) : Reopen()
        object Unknown : Reopen()
    }

    fun reopen(r: Rules, reason: String, pkg: String, minute: Int, dow: Int, nowMs: Long): Reopen = when (reason) {
        "appLimit", "groupLimit", "daily" -> Reopen.Tomorrow
        "paused" -> if (r.pausedUntil - nowMs in 1 until 86_400_000L) Reopen.AtMs(r.pausedUntil) else Reopen.Unknown
        "bedtime", "schedule" -> HomeLogic.reopenMinute(r, reason, minute, dow)?.let { if (it == HomeLogic.TOMORROW) Reopen.Tomorrow else Reopen.AtMinute(it) } ?: Reopen.Unknown
        "groupHours" -> {
            // the next start of a window of a group holding this app, later today; else tomorrow
            val next = r.groups.filter { pkg in it.pkgs }.flatMap { it.hours }.filter { dow in it.days }
                .mapNotNull { Rules.parseHm(it.start) }.filter { it > minute }.minOrNull()
            if (next != null) Reopen.AtMinute(next) else Reopen.Tomorrow
        }
        else -> Reopen.Unknown
    }

    /** The next thing on today's timeline after [minute]: a schedule start or bedtime. (minute, schedule name, "" = bedtime). */
    fun nextEvent(r: Rules, minute: Int, dow: Int): Pair<Int, String>? {
        val c = mutableListOf<Pair<Int, String>>()
        for (s in r.schedules) if (s.on && dow in s.days) Rules.parseHm(s.start)?.let { if (it > minute) c += it to s.name }
        if (r.bedtimeOn) Rules.parseHm(r.bedStart)?.let { if (it > minute) c += it to "" }
        return c.minByOrNull { it.first }
    }

    /** Lower case, accents off, đ -> d: "Đồng hồ" matches "dong ho". */
    fun fold(s: String): String = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").replace('đ', 'd')

    /** Search: every typed word starts a word of the label, or the folded label contains the query. */
    fun matches(label: String, query: String): Boolean {
        val q = fold(query).trim()
        if (q.isEmpty()) return true
        val l = fold(label)
        return l.contains(q) || q.split(Regex("\\s+")).all { w -> l.split(Regex("[^\\p{L}\\p{N}]+")).any { it.startsWith(w) } }
    }

    /** Rail letter: the first letter without accents, except Đ (its own letter in Vietnamese); digits / symbols -> "#". */
    fun letter(label: String): String {
        val c = label.trim().firstOrNull() ?: return "#"
        if (c == 'Đ' || c == 'đ') return "Đ"
        val f = fold(c.toString()).uppercase().firstOrNull() ?: return "#"
        return if (f in 'A'..'Z') f.toString() else "#"
    }

    /** A–Z order: by letter (Đ after D), then folded label, then label. */
    fun sortKey(label: String): String {
        val l = letter(label)
        val rank = when (l) { "#" -> "~"; "Đ" -> "D~"; else -> l }
        return rank + "\u0000" + fold(label) + "\u0000" + label
    }

    /** Crash fallback: keep crash times of the last 5 min. 2 => reset layout + style, 3 => turn the launcher off for 24 h. */
    const val CRASH_WINDOW_MS = 5 * 60_000L
    const val SAFE_MS = 24 * 3_600_000L
    enum class CrashAct { NONE, RESET, DISABLE }

    fun crash(times: List<Long>, now: Long): Pair<List<Long>, CrashAct> {
        val kept = times.filter { now - it in 0 until CRASH_WINDOW_MS } + now
        return kept to when { kept.size >= 3 -> CrashAct.DISABLE; kept.size == 2 -> CrashAct.RESET; else -> CrashAct.NONE }
    }

    /** One status banner above the grid, or null when all is well. Order = what matters most. */
    fun banner(owner: Boolean, guardUp: Boolean, usage: Boolean, location: Boolean, acc: Boolean): String? = when {
        !owner -> "owner"
        !usage -> "usage"
        !location -> "location"
        !acc -> "acc"
        !guardUp -> "guard"
        else -> null
    }

    /** state.launcher.err: "" fine, "not owner", "crash" (24 h safe mode), "home" (HOME resolves elsewhere). */
    fun err(on: Boolean, owner: Boolean, safe: Boolean, homeIsOurs: Boolean): String = when {
        !on -> ""
        !owner -> "not owner"
        safe -> "crash"
        !homeIsOurs -> "home"
        else -> ""
    }
}
