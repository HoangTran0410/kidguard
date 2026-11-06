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

    /**
     * Zones the fix is in, and the events it causes. Inside = distance <= r; a zone already inside is left only
     * beyond r + 50 m (no flapping at the edge). [record] = true (first fix after a rules change) only updates the set.
     */
    fun zoneStep(zones: List<Zone>, inside: Set<String>, lat: Double, lng: Double, record: Boolean): Pair<Set<String>, List<Ev>> {
        val now = linkedSetOf<String>(); val out = mutableListOf<Ev>()
        for (z in zones) {
            val d = Track.meters(z.lat, z.lng, lat, lng)
            val was = z.id in inside
            val isIn = if (was) d <= z.r + LEAVE_EXTRA_M else d <= z.r
            if (isIn) now += z.id
            if (!record && isIn != was) out += if (isIn) Ev("zoneIn", "Đã đến ${z.name}", z.id) else Ev("zoneOut", "Đã rời ${z.name}", z.id)
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

    /** Newest last, drops entries older than 7 days (and times in the future of [now] are kept), keeps the last 50. */
    fun prune(a: JSONArray, now: Long): JSONArray {
        val keep = ArrayList<JSONObject>()
        for (i in 0 until a.length()) { val o = a.optJSONObject(i) ?: continue; if (now - o.optLong("t") <= KEEP_MS) keep += o }
        return JSONArray().also { j -> keep.takeLast(MAX).forEach { j.put(it) } }
    }

    /** Child notification for an `answer` command; [note] (<= 200 chars) is the parent's message. */
    fun answerText(ok: Boolean, min: Int, note: String): String {
        val base = if (ok) "Bố mẹ đã đồng ý thêm ${min.coerceIn(0, 24 * 60)} phút" else "Bố mẹ chưa đồng ý"
        val n = note.trim().take(200)
        return if (n.isEmpty()) base else "$base. Bố mẹ nhắn: $n"
    }

    /** "Bố mẹ đổi luật: giới hạn 120 → 90 phút; giờ ngủ 21:30–06:30", at most 160 chars. */
    fun ruleDiff(o: Rules, n: Rules): String {
        val p = mutableListOf<String>()
        if (o.dailyMin != n.dailyMin) p += "giới hạn ${if (o.dailyMin > 0) "${o.dailyMin}" else "không"} → ${if (n.dailyMin > 0) "${n.dailyMin} phút" else "không giới hạn"}"
        if (o.dailyByDay != n.dailyByDay) p += if (n.dailyByDay.size == 7) "giới hạn từng ngày: " + n.dailyByDay.joinToString("/") else "bỏ giới hạn từng ngày"
        if (o.bedtimeOn != n.bedtimeOn || (n.bedtimeOn && (o.bedStart != n.bedStart || o.bedEnd != n.bedEnd)))
            p += if (n.bedtimeOn) "giờ ngủ ${n.bedStart}–${n.bedEnd}" else "tắt giờ ngủ"
        if (o.schedules != n.schedules) p += if (o.schedules.size != n.schedules.size) "lịch nghỉ ${o.schedules.size} → ${n.schedules.size}" else "sửa lịch nghỉ"
        if (o.blocked != n.blocked) p += "chặn ${o.blocked.size} → ${n.blocked.size} app"
        if (o.allow != n.allow) p += "luôn cho dùng ${o.allow.size} → ${n.allow.size} app"
        if (o.hidden != n.hidden) {
            (n.hidden - o.hidden).sorted().take(3).forEach { p += "Ẩn app: $it" }
            (o.hidden - n.hidden).sorted().take(3).forEach { p += "Hiện lại app: $it" }
        }
        if (o.pausedUntil != n.pausedUntil) p += if (n.pausedUntil > 0) "tạm dừng máy" else "bỏ tạm dừng"
        if (o.bonusDay != n.bonusDay || o.bonusMin != n.bonusMin) p += if (n.bonusMin > 0) "thưởng +${n.bonusMin} phút" else "bỏ thưởng giờ"
        if (o.chores != n.chores) p += if (o.chores.size != n.chores.size) "việc nhà ${o.chores.size} → ${n.chores.size}" else "sửa việc nhà"
        if (o.zones != n.zones) p += if (o.zones.size != n.zones.size) "vùng ${o.zones.size} → ${n.zones.size}" else "sửa vùng"
        if (o.trackMin != n.trackMin) p += if (n.trackMin > 0) "lưu vị trí mỗi ${n.trackMin} phút" else "tắt lưu vị trí"
        if (o.lockLocation != n.lockLocation) p += if (n.lockLocation) "khoá bật vị trí" else "bỏ khoá vị trí"
        if (o.showWatching != n.showWatching) p += if (n.showWatching) "báo con khi xem màn hình" else "không báo con khi xem màn hình"
        if (o.pinHash != n.pinHash) p += "đổi PIN"
        if (p.isEmpty()) p += "cài đặt khác"
        val s = "Bố mẹ đổi luật: " + p.joinToString("; ")
        return if (s.length <= 160) s else s.take(159) + "…"
    }
}
