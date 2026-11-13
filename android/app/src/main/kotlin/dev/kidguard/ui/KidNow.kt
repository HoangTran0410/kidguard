package dev.kidguard

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.EditText
import android.widget.LinearLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Small helpers the kid screens share (child app, launcher, block card): the "right now" sentence from [NowWhy]
 * (contract C2), the number to call a parent, the online check for SOS, the "forgot PIN" help under a PIN field.
 * Android side only; the decision itself is [nowWhy] in Rules.kt.
 */
internal object KidNow {
    fun hhmm(ms: Long): String = SimpleDateFormat("HH:mm", Locale.ROOT).format(Date(ms))

    /** "Giờ ngủ, mở lại 06:30" / "Bố mẹ đang tạm khoá máy tới 18:00" ... ; null when the phone is free. */
    fun line(ctx: Context, nw: NowWhy): String? = when (nw.why) {
        "lost" -> ctx.getString(R.string.nw_lost)
        "paused" -> if (nw.until > 0) ctx.getString(R.string.nw_paused_until, hhmm(nw.until)) else ctx.getString(R.string.nw_paused)
        "schedule" -> when {
            nw.until <= 0 -> null
            nw.name.isNotBlank() -> ctx.getString(R.string.nw_schedule, nw.name, hhmm(nw.until))
            else -> ctx.getString(R.string.nw_quiet, hhmm(nw.until))
        }
        "bedtime" -> if (nw.until > 0) ctx.getString(R.string.nw_bed, hhmm(nw.until)) else null
        "daily" -> ctx.getString(R.string.nw_daily)
        "pass", "code" -> if (nw.until > 0) ctx.getString(R.string.nw_pass, hhmm(nw.until)) else null
        else -> null
    }

    /** Asking cannot change a lost or paused phone: no Ask button then. */
    fun canAsk(nw: NowWhy) = nw.why != "lost" && nw.why != "paused"

    /** The phone-wide reason now: the Guard's last tick when fresh, else computed here (same inputs as decide()). */
    fun now(ctx: Context, s: Store): NowWhy {
        val r = s.rules; val t = System.currentTimeMillis(); val today = Guard.day(t)
        val cal = java.util.Calendar.getInstance()
        val minute = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
        val sn = Guard.snap?.takeIf { t - it.now < 30_000 }
        if (sn != null) return nowWhy(r, minute, t, today, sn.usageMs, sn.apps, sn.prot, s.unlockUntil)
        val (all, prot) = Policy.cached(ctx)
        val use = runCatching { Usage.today(ctx, t).ms }.getOrDefault(emptyMap())
        return nowWhy(r, minute, t, today, use, all.keys, prot, s.unlockUntil)
    }

    /** The number to call a parent: the lost-mode phone, else the first number in the lock-screen contact; null = none. */
    fun parentNumber(r: Rules): String? =
        (r.lostPhone.filter { it.isDigit() || it == '+' }.takeIf { it.length >= 3 })
            ?: Regex("\\+?\\d[\\d .-]{6,}\\d").find(r.lockContact)?.value?.replace(Regex("[ .-]"), "")

    fun online(ctx: Context): Boolean = runCatching {
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java)
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }.getOrDefault(false)

    /** Opens the dialer with [num] filled in (nothing is called without a tap). */
    fun dial(ctx: Context, num: String) {
        runCatching { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$num")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** [input] with the "Forgot PIN?" help line under it (A6.3). */
    fun withForgot(act: Activity, u: Ui, input: EditText): LinearLayout = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        addView(input, LinearLayout.LayoutParams(-1, -2))
        addView(u.label(act.getString(R.string.pin_forgot), 13f, u.sub).apply { setLineSpacing(0f, 1.15f) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = u.dp(10) })
    }

    /** "+1 phút": the Guard gives a 1-minute pass once per lock period. True = granted. */
    fun takeOneMore(ctx: Context): Boolean = runCatching { Guard.oneMore() }.getOrDefault(false)
}
