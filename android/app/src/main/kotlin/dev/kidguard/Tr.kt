@file:Suppress("EnumEntryName")

package dev.kidguard

/**
 * Text that pure logic (Rules / Events / Warn / BasicLogic) builds for people. Keeps those files Android-free:
 * they ask a [Tr] for a key, the Android side ([ResTr]) answers from string resources in the phone's locale,
 * unit tests answer from the same XML files (see src/test TestTr).
 * Each entry's name IS the resource name in res/values(-xx)/strings.xml (a `<plurals>` when used through [Tr.q]).
 */
enum class T {
    reason_blocked, reason_app_limit, reason_group_hours, reason_group_limit, reason_new, reason_paused,
    reason_schedule, reason_bedtime, reason_daily,
    ev_zone_in, ev_zone_out,
    answer_yes /* plurals */, answer_no, answer_note,
    diff_head, diff_limit, diff_none, diff_min, no_limit, diff_by_day, diff_by_day_off, diff_bed, diff_bed_off,
    diff_sched_n, diff_sched, diff_blocked, diff_allow, app_hidden, app_unhidden, diff_pause, diff_pause_off,
    diff_bonus, diff_bonus_off, diff_chores_n, diff_chores, diff_zones_n, diff_zones, diff_track, diff_track_off,
    diff_lockloc, diff_lockloc_off, diff_autoupd, diff_autoupd_off, diff_watch, diff_watch_off, diff_pin, diff_other,
    warn_d1, warn_d5, warn_bed, warn_break, warn_break_named,
}

/** Looks up user-visible text. [s]: a plain string with format args; [q]: a plural picked by [n] (args as given). */
interface Tr {
    fun s(k: T, vararg a: Any): String
    fun q(k: T, n: Int, vararg a: Any): String
}
