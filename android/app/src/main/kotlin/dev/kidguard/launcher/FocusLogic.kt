package dev.kidguard

/**
 * Pure logic of "Chế độ Học bài" (study / focus mode); Android-free, unit-tested.
 * Two ways in: a timed [Session] the kid starts (25/45/60 min), and a weekly [Slot] schedule ("giờ học").
 * Either one makes the home show only the study apps ([visible]). That is a launcher view only: the parent's block
 * rules (LauncherLogic.Show) are applied first and this can only hide more, never un-hide.
 */
object FocusLogic {
    val DURATIONS = listOf(25, 45, 60)
    const val MAX_MIN = 180                 // a longer "end" than this is a clock jump: the session is over
    const val MAX_SLOTS = 14
    const val ALL_DAYS = 0b1111111          // bit 0 = Monday ... bit 6 = Sunday (ISO weekday - 1)

    /** DND bookkeeping: [prev] = the interruption filter before we changed it, [dndSet] = we changed it (so we restore it). */
    data class Session(val start: Long, val end: Long, val prev: Int = 0, val dndSet: Boolean = false) {
        fun toPref(): String = "$start|$end|$prev|${if (dndSet) 1 else 0}"
        fun withDnd(prev: Int, set: Boolean) = copy(prev = prev, dndSet = set)
    }

    fun parse(s: String?): Session? {
        val p = s?.split('|') ?: return null
        if (p.size != 4) return null
        val a = p[0].toLongOrNull() ?: return null; val b = p[1].toLongOrNull() ?: return null
        if (b <= a) return null
        return Session(a, b, p[2].toIntOrNull() ?: 0, p[3] == "1")
    }

    fun begin(now: Long, minutes: Int): Session = Session(now, now + minutes.coerceIn(1, MAX_MIN) * 60_000L)

    /** Running now. An end more than [MAX_MIN] ahead of the wall clock (the clock was set back) counts as over: never stuck. */
    fun active(s: Session?, now: Long): Boolean = s != null && now < s.end && s.end - now <= MAX_MIN * 60_000L

    /** Whole minutes left, rounded up (the countdown shows "1" until the last second). */
    fun leftMin(s: Session, now: Long): Int = (((s.end - now).coerceAtLeast(0) + 59_999) / 60_000).toInt()

    fun clock(min: Int) = "%d:%02d".format(min / 60, min % 60)

    /** The DND filter to put back when a session ends: [current] still the one we set (PRIORITY = 2) -> [prev]; else null (the kid / parent changed it: leave it). */
    fun restore(s: Session, current: Int?): Int? =
        if (s.dndSet && (current == null || current == PRIORITY)) s.prev else null
    const val ALL = 1; const val PRIORITY = 2   // NotificationManager.INTERRUPTION_FILTER_*

    // ---- schedule ----

    /** [days] bit mask (bit 0 = Monday), minutes since midnight, start < end (no wrap over midnight: split it in two slots). */
    data class Slot(val days: Int, val from: Int, val to: Int) {
        fun toPref() = "$days:$from:$to"
        fun has(dow: Int) = dow in 1..7 && days and (1 shl (dow - 1)) != 0
    }

    fun slotsParse(s: String?): List<Slot> = (s ?: "").split(',').mapNotNull { t ->
        val p = t.split(':'); if (p.size != 3) return@mapNotNull null
        val d = p[0].toIntOrNull() ?: return@mapNotNull null; val a = p[1].toIntOrNull() ?: return@mapNotNull null; val b = p[2].toIntOrNull() ?: return@mapNotNull null
        Slot(d and ALL_DAYS, a, b).takeIf { it.days != 0 && a in 0 until 1440 && b in 1..1440 && a < b }
    }.take(MAX_SLOTS)

    fun slotsPref(l: List<Slot>) = l.joinToString(",") { it.toPref() }

    /** [dow] = ISO weekday (Mon = 1). */
    fun scheduled(slots: List<Slot>, dow: Int, minute: Int): Boolean = slots.any { it.has(dow) && minute >= it.from && minute < it.to }

    /** The slot running now, or the next one today ([minute] before it starts): for the card's "next study time" text. */
    fun next(slots: List<Slot>, dow: Int, minute: Int): Slot? = slots.filter { it.has(dow) && it.from > minute }.minByOrNull { it.from }

    enum class Mode { OFF, SESSION, SCHEDULE }

    fun mode(s: Session?, slots: List<Slot>, now: Long, dow: Int, minute: Int): Mode =
        if (active(s, now)) Mode.SESSION else if (scheduled(slots, dow, minute)) Mode.SCHEDULE else Mode.OFF

    // ---- which apps show ----

    /** Apps always kept in a focus home: the phone (dialer) and the launcher itself, so a kid can always call. */
    fun allowed(study: Set<String>, keep: Collection<String?>): Set<String> = study + keep.filterNotNull()

    /** Home view: outside focus every app stays as [shown] says; in focus only [allowed] ones, and never more than [shown]. */
    fun visible(mode: Mode, pkg: String, shown: Boolean, allowed: Set<String>): Boolean = shown && (mode == Mode.OFF || pkg in allowed)

    /** Ending early needs the parent PIN only in the kid flavor and only when a PIN exists. */
    fun needsPin(homeFlavor: Boolean, pinSet: Boolean) = !homeFlavor && pinSet
}
