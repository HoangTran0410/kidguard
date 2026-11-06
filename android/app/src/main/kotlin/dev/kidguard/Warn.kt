package dev.kidguard

/** "Heads-up before the lock": which warning (if any) is due right now. Pure, unit-tested. */
object Warn {
    /** [keys] are marked as sent once [text] is shown (a 1-minute warning also retires the 5-minute one). */
    data class Due(val keys: Set<String>, val text: String)

    const val LEAD_MIN = 5

    /**
     * [leftMs]: use left today (-1 = no daily limit). [sent]: keys already shown today (they carry the day, "yyyy-MM-dd:name").
     * Returns the most urgent warning not shown yet, or null.
     */
    fun due(r: Rules, leftMs: Long, minuteOfDay: Int, today: String, sent: Set<String>): Due? {
        val out = mutableListOf<Due>()
        if (leftMs > 0) {
            if (leftMs <= 60_000L) {
                if ("$today:d1" !in sent) out += Due(setOf("$today:d1", "$today:d5"), "Còn 1 phút: lưu lại việc đang làm nhé")
            } else if (leftMs <= LEAD_MIN * 60_000L) {
                if ("$today:d5" !in sent) out += Due(setOf("$today:d5"), "Còn 5 phút nữa là hết giờ hôm nay")
            }
        }
        val dow = Rules.isoDow(today)
        fun minutesTo(start: Int) = (start - minuteOfDay + 1440) % 1440
        fun startDow(delta: Int) = if (minuteOfDay + delta >= 1440) (if (dow == 7) 1 else dow + 1) else dow
        if (r.bedtimeOn) {
            val s = Rules.parseHm(r.bedStart); val e = Rules.parseHm(r.bedEnd)
            if (s != null && e != null && !Rules.inWindow(minuteOfDay, s, e)) {
                val d = minutesTo(s)
                if (d in 1..LEAD_MIN && "$today:bed" !in sent) out += Due(setOf("$today:bed"), "$LEAD_MIN phút nữa đến giờ ngủ")
            }
        }
        r.schedules.forEachIndexed { i, sc ->
            val s = Rules.parseHm(sc.start)
            if (sc.on && s != null && !sc.active(minuteOfDay, dow)) {
                val d = minutesTo(s)
                if (d in 1..LEAD_MIN && startDow(d) in sc.days && "$today:s$i" !in sent)
                    out += Due(setOf("$today:s$i"), if (sc.name.isBlank()) "$LEAD_MIN phút nữa đến giờ nghỉ" else "$LEAD_MIN phút nữa đến giờ nghỉ: ${sc.name}")
            }
        }
        // the daily 1-minute warning is the most urgent; otherwise the first one found
        return out.firstOrNull { "d1" in it.keys.first() } ?: out.firstOrNull()
    }
}
