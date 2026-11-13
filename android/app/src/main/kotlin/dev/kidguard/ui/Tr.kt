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
    reason_schedule, reason_bedtime, reason_daily, reason_settings,
    ev_zone_in, ev_zone_out, ev_clock_ahead, ev_clock_behind, ev_dur_m, ev_dur_hm, ev_dur_dh,
    ev_autotime_fixed, ev_autotime_left, ev_autotz_fixed, ev_autotz_left,
    answer_yes /* plurals */, answer_no, answer_note,
    diff_head, diff_limit, diff_none, diff_min, no_limit, diff_by_day, diff_by_day_off, diff_bed, diff_bed_off,
    diff_sched_n, diff_sched, diff_blocked, diff_allow, app_hidden, app_unhidden, diff_pause, diff_pause_off,
    diff_bonus, diff_bonus_off, diff_chores_n, diff_chores, diff_zones_n, diff_zones, diff_track, diff_track_off,
    diff_lockloc, diff_lockloc_off, diff_dns, diff_dns_off, diff_dns_pause, diff_novpn, diff_novpn_off, diff_tskeep, diff_tskeep_off, diff_autoupd, diff_autoupd_off, diff_watch, diff_watch_off, diff_pin, diff_other, diff_launcher, diff_launcher_off, diff_monitor, diff_monitor_off,
    diff_pass, diff_pass_off, diff_lost, diff_lost_off, diff_bed_nights,
    warn_d1, warn_d5, warn_bed, warn_bed10, warn_break, warn_break_named,
    tl_min, tl_h, tl_hm, tl_none,
}

/** Looks up user-visible text. [s]: a plain string with format args; [q]: a plural picked by [n] (args as given). */
interface Tr {
    fun s(k: T, vararg a: Any): String
    fun q(k: T, n: Int, vararg a: Any): String
}
