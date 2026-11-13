package dev.kidguard

import org.json.JSONArray
import org.json.JSONObject

/** One thing worth telling the parent (protocol v0.6 "events"). [zone] is set for zoneIn / zoneOut. */
data class Ev(val kind: String, val text: String, val zone: String? = null)

/** Pure event logic: zones, battery, clock, the rule-change summary, the answer text. Android-free, unit-tested. */
object Events {
    const val MAX = 50
    const val KEEP_MS = 7 * 24 * 3_600_000L
    const val LEAVE_EXTRA_M = 50.0
    const val BATTERY_LOW = 15
    const val BATTERY_REARM = 30
    const val CLOCK_JUMP_MS = 2 * 60_000L

    /** Alert level (C7): these kinds are "important" (Telegram /quiet, parent app channels); every other kind is info. */
    val IMPORTANT = setOf("sos", "tamper", "lost", "request")
    fun important(kind: String): Boolean = kind in IMPORTANT

    /**
     * Zones the fix is in, and the events it causes. Inside = distance <= r; a zone already inside is left only
     * beyond r + 50 m (no flapping at the edge). [record] = true (first fix after a rules change) only updates the set.
     */
    fun zoneStep(zones: List<Zone>, inside: Set<String>, lat: Double, lng: Double, record: Boolean, tr: Tr): Pair<Set<String>, List<Ev>> {
        val now = linkedSetOf<String>(); val out = mutableListOf<Ev>()
        for (z in zones) {
            val d = Track.meters(z.lat, z.lng, lat, lng)
            val was = z.id in inside
            val isIn = if (was) d <= z.r + LEAVE_EXTRA_M else d <= z.r
            if (isIn) now += z.id
            if (!record && isIn != was) out += if (isIn) Ev("zoneIn", tr.s(T.ev_zone_in, z.name), z.id) else Ev("zoneOut", tr.s(T.ev_zone_out, z.name), z.id)
        }
        return now to out
    }

    /** Battery: returns (armed, fire). Fires once at <= 15 % unplugged, re-arms at >= 30 %. [pct] < 0 = unknown. */
    fun batteryStep(armed: Boolean, pct: Int, charging: Boolean): Pair<Boolean, Boolean> = when {
        pct < 0 -> armed to false
        pct >= BATTERY_REARM -> true to false
        armed && pct <= BATTERY_LOW && !charging -> false to true
        else -> armed to false
    }

    /** The wall clock moved more than 2 min compared with elapsed (boot-based) time since the reference point. */
    fun clockJumped(refWall: Long, refElapsed: Long, nowWall: Long, nowElapsed: Long): Boolean =
        Math.abs((nowWall - refWall) - (nowElapsed - refElapsed)) > CLOCK_JUMP_MS

    /** The clock-jump event text: what the phone shows now, what the time really is (reference + elapsed), how far off. */
    fun clockText(refWall: Long, refElapsed: Long, nowWall: Long, nowElapsed: Long, tr: Tr, tz: java.util.TimeZone = java.util.TimeZone.getDefault()): String {
        val real = refWall + (nowElapsed - refElapsed); val off = nowWall - real
        val day = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.ROOT).apply { timeZone = tz }
        val sameDay = day.format(java.util.Date(nowWall)) == day.format(java.util.Date(real))
        val f = java.text.SimpleDateFormat(if (sameDay) "HH:mm" else "HH:mm dd/MM", java.util.Locale.ROOT).apply { timeZone = tz }
        val m = (Math.abs(off) + 30_000) / 60_000
        val d = when { m < 60 -> tr.s(T.ev_dur_m, m.toInt()); m < 24 * 60 -> tr.s(T.ev_dur_hm, (m / 60).toInt(), (m % 60).toInt()); else -> tr.s(T.ev_dur_dh, (m / 1440).toInt(), (m % 1440 / 60).toInt()) }
        return tr.s(if (off > 0) T.ev_clock_ahead else T.ev_clock_behind, f.format(java.util.Date(nowWall)), f.format(java.util.Date(real)), d)
    }

    /** Newest last, drops entries older than 7 days (and times in the future of [now] are kept), keeps the last 50. */
    fun prune(a: JSONArray, now: Long): JSONArray {
        val keep = ArrayList<JSONObject>()
        for (i in 0 until a.length()) { val o = a.optJSONObject(i) ?: continue; if (now - o.optLong("t") <= KEEP_MS) keep += o }
        return JSONArray().also { j -> keep.takeLast(MAX).forEach { j.put(it) } }
    }

    /** Child notification for an `answer` command; [note] (<= 200 chars) is the parent's message. */
    fun answerText(ok: Boolean, min: Int, note: String, tr: Tr): String {
        val m = min.coerceIn(0, 24 * 60)
        val base = if (ok) tr.q(T.answer_yes, m, m) else tr.s(T.answer_no)
        val n = note.trim().take(200)
        return if (n.isEmpty()) base else tr.s(T.answer_note, base, n)
    }

    /** "Bố mẹ đổi luật: giới hạn 120 → 90 phút; giờ ngủ 21:30–06:30" (in the phone's language), at most 160 chars. */
    fun ruleDiff(o: Rules, n: Rules, tr: Tr): String {
        val p = mutableListOf<String>()
        fun lim(v: Int, unit: Boolean) = if (v > 0) (if (unit) tr.s(T.diff_min, v) else "$v") else tr.s(if (unit) T.no_limit else T.diff_none)
        if (o.dailyMin != n.dailyMin) p += tr.s(T.diff_limit, lim(o.dailyMin, false), lim(n.dailyMin, true))
        if (o.dailyByDay != n.dailyByDay) p += if (n.dailyByDay.size == 7) tr.s(T.diff_by_day, n.dailyByDay.joinToString("/")) else tr.s(T.diff_by_day_off)
        if (o.bedtimeOn != n.bedtimeOn || (n.bedtimeOn && (o.bedStart != n.bedStart || o.bedEnd != n.bedEnd)))
            p += if (n.bedtimeOn) tr.s(T.diff_bed, n.bedStart, n.bedEnd) else tr.s(T.diff_bed_off)
        if (o.schedules != n.schedules) p += if (o.schedules.size != n.schedules.size) tr.s(T.diff_sched_n, o.schedules.size, n.schedules.size) else tr.s(T.diff_sched)
        if (o.blocked != n.blocked) p += tr.s(T.diff_blocked, o.blocked.size, n.blocked.size)
        if (o.allow != n.allow) p += tr.s(T.diff_allow, o.allow.size, n.allow.size)
        if (o.hidden != n.hidden) {
            (n.hidden - o.hidden).sorted().take(3).forEach { p += tr.s(T.app_hidden, it) }
            (o.hidden - n.hidden).sorted().take(3).forEach { p += tr.s(T.app_unhidden, it) }
        }
        if (o.pausedUntil != n.pausedUntil) p += tr.s(if (n.pausedUntil > 0) T.diff_pause else T.diff_pause_off)
        if (o.passUntil != n.passUntil) p += if (n.passUntil > 0) tr.s(T.diff_pass, java.text.SimpleDateFormat("HH:mm", java.util.Locale.ROOT).format(java.util.Date(n.passUntil))) else tr.s(T.diff_pass_off)
        if (o.lostOn != n.lostOn) p += tr.s(if (n.lostOn) T.diff_lost else T.diff_lost_off)
        if (o.bedByDay != n.bedByDay) p += tr.s(T.diff_bed_nights)
        if (o.bonusDay != n.bonusDay || o.bonusMin != n.bonusMin) p += if (n.bonusMin > 0) tr.s(T.diff_bonus, n.bonusMin) else tr.s(T.diff_bonus_off)
        if (o.chores != n.chores) p += if (o.chores.size != n.chores.size) tr.s(T.diff_chores_n, o.chores.size, n.chores.size) else tr.s(T.diff_chores)
        if (o.zones != n.zones) p += if (o.zones.size != n.zones.size) tr.s(T.diff_zones_n, o.zones.size, n.zones.size) else tr.s(T.diff_zones)
        if (o.trackMin != n.trackMin) p += if (n.trackMin > 0) tr.s(T.diff_track, n.trackMin) else tr.s(T.diff_track_off)
        if (o.lockLocation != n.lockLocation) p += tr.s(if (n.lockLocation) T.diff_lockloc else T.diff_lockloc_off)
        if (o.safeDnsPauseUntil != n.safeDnsPauseUntil && n.safeDns.isNotEmpty() && o.safeDns == n.safeDns)
            p += tr.s(if (n.safeDnsPauseUntil > 0) T.diff_dns_pause else T.diff_dns, n.safeDns)
        if (o.safeDns != n.safeDns) p += tr.s(if (n.safeDns.isNotEmpty()) T.diff_dns else T.diff_dns_off, n.safeDns)
        if (o.noVpn != n.noVpn) p += tr.s(if (n.noVpn) T.diff_novpn else T.diff_novpn_off)
        if (o.tsKeep != n.tsKeep) p += tr.s(if (n.tsKeep) T.diff_tskeep else T.diff_tskeep_off)
        if (o.autoUpdate != n.autoUpdate) p += tr.s(if (n.autoUpdate) T.diff_autoupd else T.diff_autoupd_off)
        if (o.showWatching != n.showWatching) p += tr.s(if (n.showWatching) T.diff_watch else T.diff_watch_off)
        if (o.launcher.on != n.launcher.on) p += tr.s(if (n.launcher.on) T.diff_launcher else T.diff_launcher_off)
        if (o.monitor != n.monitor) p += tr.s(if (n.monitor) T.diff_monitor else T.diff_monitor_off)
        if (o.pinHash != n.pinHash) p += tr.s(T.diff_pin)
        if (p.isEmpty()) p += tr.s(T.diff_other)
        val s = tr.s(T.diff_head, p.joinToString("; "))
        return if (s.length <= 160) s else s.take(159) + "…"
    }
}
