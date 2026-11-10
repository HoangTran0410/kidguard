package dev.kidguard

import android.content.Context

/** Android side of [Tr]: string resources in the app's current locale (per-app language on Android 13+, else the phone's). */
class ResTr(private val ctx: Context) : Tr {
    override fun s(k: T, vararg a: Any): String = ctx.getString(id(k), *a)
    override fun q(k: T, n: Int, vararg a: Any): String = ctx.resources.getQuantityString(id(k), n, *a)

    private fun id(k: T): Int = when (k) {
        T.reason_blocked -> R.string.reason_blocked
        T.reason_app_limit -> R.string.reason_app_limit
        T.reason_group_hours -> R.string.reason_group_hours
        T.reason_group_limit -> R.string.reason_group_limit
        T.reason_new -> R.string.reason_new
        T.reason_paused -> R.string.reason_paused
        T.reason_schedule -> R.string.reason_schedule
        T.reason_bedtime -> R.string.reason_bedtime
        T.reason_settings -> R.string.reason_settings
        T.reason_daily -> R.string.reason_daily
        T.ev_zone_in -> R.string.ev_zone_in
        T.ev_zone_out -> R.string.ev_zone_out
        T.answer_yes -> R.plurals.answer_yes
        T.answer_no -> R.string.answer_no
        T.answer_note -> R.string.answer_note
        T.diff_head -> R.string.diff_head
        T.diff_limit -> R.string.diff_limit
        T.diff_none -> R.string.diff_none
        T.diff_min -> R.string.diff_min
        T.no_limit -> R.string.no_limit
        T.diff_by_day -> R.string.diff_by_day
        T.diff_by_day_off -> R.string.diff_by_day_off
        T.diff_bed -> R.string.diff_bed
        T.diff_bed_off -> R.string.diff_bed_off
        T.diff_sched_n -> R.string.diff_sched_n
        T.diff_sched -> R.string.diff_sched
        T.diff_blocked -> R.string.diff_blocked
        T.diff_allow -> R.string.diff_allow
        T.app_hidden -> R.string.app_hidden
        T.app_unhidden -> R.string.app_unhidden
        T.diff_pause -> R.string.diff_pause
        T.diff_pause_off -> R.string.diff_pause_off
        T.diff_bonus -> R.string.diff_bonus
        T.diff_bonus_off -> R.string.diff_bonus_off
        T.diff_chores_n -> R.string.diff_chores_n
        T.diff_chores -> R.string.diff_chores
        T.diff_zones_n -> R.string.diff_zones_n
        T.diff_zones -> R.string.diff_zones
        T.diff_track -> R.string.diff_track
        T.diff_track_off -> R.string.diff_track_off
        T.diff_lockloc -> R.string.diff_lockloc
        T.diff_lockloc_off -> R.string.diff_lockloc_off
        T.diff_dns -> R.string.diff_dns
        T.diff_dns_off -> R.string.diff_dns_off
        T.diff_dns_pause -> R.string.diff_dns_pause
        T.diff_novpn -> R.string.diff_novpn
        T.diff_novpn_off -> R.string.diff_novpn_off
        T.diff_autoupd -> R.string.diff_autoupd
        T.diff_autoupd_off -> R.string.diff_autoupd_off
        T.diff_watch -> R.string.diff_watch
        T.diff_watch_off -> R.string.diff_watch_off
        T.diff_pin -> R.string.diff_pin
        T.diff_other -> R.string.diff_other
        T.diff_launcher -> R.string.diff_launcher
        T.diff_launcher_off -> R.string.diff_launcher_off
        T.diff_monitor -> R.string.diff_monitor
        T.diff_monitor_off -> R.string.diff_monitor_off
        T.warn_d1 -> R.string.warn_d1
        T.warn_d5 -> R.string.warn_d5
        T.warn_bed -> R.string.warn_bed
        T.warn_bed10 -> R.string.warn_bed10
        T.warn_break -> R.string.warn_break
        T.warn_break_named -> R.string.warn_break_named
        T.tl_min -> R.string.tl_min
        T.tl_h -> R.string.tl_h
        T.tl_hm -> R.string.tl_hm
        T.tl_none -> R.string.tl_none
    }
}
