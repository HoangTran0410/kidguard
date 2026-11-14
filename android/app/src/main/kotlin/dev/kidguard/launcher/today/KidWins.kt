package dev.kidguard

import android.content.Context
import java.util.Calendar

/**
 * "Tuần này của bạn": positive feedback for the kid, from data the phone already has (no new storage).
 *  - days within the daily limit (usage of the last 7 days from UsageStatsManager against that weekday's limit),
 *  - the current run of such days,
 *  - days the kid finished the "Chuỗi ngày" daily list ([StreakStore], only when that widget is used).
 * Not available on the phone, so not shown: chores approved (only the request is logged), bedtime kept.
 */
object KidWins {
    /** One day, oldest first in the list given to [wins]. [limitMin] 0 = no limit; [usedMin] 0 = no usage data (counted as unknown). */
    data class Day(val limitMin: Int, val usedMin: Int)
    data class Wins(val within: Int, val counted: Int, val streak: Int, val listDays: Int) {
        val any get() = within > 0 || listDays > 0
    }

    /** Days with a limit and some usage count; unknown days (no limit / no data) are skipped, they neither add nor break the run. */
    fun wins(days: List<Day>, listDays: Int): Wins {
        val known = days.filter { it.limitMin > 0 && it.usedMin > 0 }
        val within = known.count { it.usedMin <= it.limitMin }
        var streak = 0
        for (d in known.asReversed()) { if (d.usedMin <= d.limitMin) streak++ else break }
        return Wins(within, known.size, streak, listDays)
    }

    /** Days of the list inside the 7 days ending [today] (epoch days). */
    fun listInWeek(done: Set<Long>, today: Long): Int = done.count { it in (today - 6)..today }

    // ---- Android side: computed off the UI thread, cached 10 min ----
    @Volatile private var cache: Wins? = null
    @Volatile private var cacheAt = 0L
    @Volatile private var busy = false

    fun cached(): Wins? = cache

    /** Starts a background compute when the cache is older than 10 min; [done] runs on that thread only when the result changed. */
    fun refresh(ctx: Context, r: Rules, launchable: Set<String>, prot: Set<String>, done: () -> Unit) {
        val now = System.currentTimeMillis()
        if (busy || now - cacheAt < 600_000L) return
        busy = true
        val app = ctx.applicationContext
        Thread {
            try {
                val days = ArrayList<Day>()
                val free = prot + r.allow + app.packageName
                for (i in 6 downTo 0) {
                    val from = Usage.midnightAgo(i, now)
                    val to = if (i == 0) now else Usage.midnightAgo(i - 1, now)
                    val ymd = Calendar.getInstance().apply { timeInMillis = from }.let { "%04d-%02d-%02d".format(it.get(Calendar.YEAR), it.get(Calendar.MONTH) + 1, it.get(Calendar.DAY_OF_MONTH)) }
                    val used = Usage.range(app, from, to).ms.filterKeys { it in launchable && it !in free }.values.sum()
                    val limit = baseLimitMin(r, ymd).let { if (it > 0 && r.bonusDay == ymd) it + r.bonusMin else it }
                    days += Day(limit, (used / 60_000L).toInt())
                }
                val w = wins(days, listInWeek(StreakStore.days(app), StreakStore.today(now)))
                val changed = w != cache
                cache = w; cacheAt = System.currentTimeMillis()
                if (changed) done()
            } catch (_: Throwable) { cacheAt = now } finally { busy = false }
        }.start()
    }
}
