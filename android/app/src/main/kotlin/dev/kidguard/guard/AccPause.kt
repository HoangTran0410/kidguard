package dev.kidguard

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings

/**
 * Banking apps (Vietcombank & co.) refuse to run while ANY accessibility service is on, Play Store apps included
 * (Niagara Launcher too, seen by the owner), and check again every time they come to the front. Device Owner keeps
 * our service on ([Acc.ensureEnabled] every tick), so without this a child could never use a bank app.
 *
 * Rule `accPause` (package list, defaults to [AccPauseLogic.DEFAULT]): when one of them opens, our accessibility
 * service is really turned off (not hidden: the bank gets what it asks for), and turned back on once none of them has
 * been in front for [AccPauseLogic.RESUME_MS]; their background processes are then killed so the next open is a fresh
 * start (which the launcher / the window event pauses for again). Needs WRITE_SECURE_SETTINGS (to turn it back on);
 * basic mode resumes after [AccPauseLogic.RESUME_BASIC_MS] because it blocks apps through the service itself.
 */
object AccPauseLogic {
    const val RESUME_MS = 10 * 60_000L
    /** Basic mode blocks apps through the service itself: a paused service blocks nothing, so come back much sooner. */
    const val RESUME_BASIC_MS = 60_000L

    /** Vietnamese banks / wallets (package names from their Play Store pages; parents edit the list on the page). */
    val DEFAULT: Set<String> = setOf(
        "com.VCB",                          // Vietcombank
        "com.vnpay.bidv",                   // BIDV SmartBanking
        "com.vietinbank.ipay",              // VietinBank iPay
        "com.vnpay.Agribank3g",             // Agribank E-Mobile Banking
        "com.mbmobile",                     // MB Bank
        "vn.com.techcombank.bb.app",        // Techcombank
        "mobile.acb.com.vn",                // ACB ONE
        "com.vnpay.vpbankonline",           // VPBank NEO
        "com.tpb.mb.gprsandroid",           // TPBank Mobile
        "com.sacombank.ewallet",            // Sacombank Pay
        "com.vnpay.hdbank",                 // HDBank
        "vn.shb.mbanking",                  // SHB
        "com.vib.myvib2",                   // MyVIB
        "com.mservice.momotransfer",        // MoMo
        "vn.com.vng.zalopay",               // ZaloPay
    )

    /** Tick step while paused: new "left at" time (0 = a paused app is in front) and whether to turn the service back on. */
    fun step(current: String?, list: Set<String>, leftAt: Long, now: Long, resumeMs: Long = RESUME_MS): Pair<Long, Boolean> = when {
        current != null && current in list -> 0L to false
        leftAt <= 0L || leftAt > now -> now to false          // just left (or the clock went back): start counting
        now - leftAt >= resumeMs -> 0L to true
        else -> leftAt to false
    }
}

object AccPause {
    /**
     * Can we turn the service off AND back on by ourselves? WRITE_SECURE_SETTINGS is what matters: Device Owner setups
     * get it, and so do basic-mode phones set up over adb / Wireless debugging (they used to be excluded, so a bank app
     * never ran on a basic-mode phone).
     */
    fun able(ctx: Context): Boolean = !Flavor.HOME &&
        ctx.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED

    fun active(ctx: Context): Boolean = Store.of(ctx).accPaused.isNotEmpty()

    /**
     * [pkg] is opening: turn our accessibility service off (before the launcher starts it, or on its first window).
     * True when the service went off just now (nothing was paused before). [stopSelf] false = the caller still needs the
     * service for a moment (see [pauseLate]) and stops it itself.
     */
    @Synchronized fun pause(ctx: Context, pkg: String, stopSelf: Boolean = true): Boolean {
        val s = Store.of(ctx)
        if (pkg !in s.rules.accPause || !able(ctx)) return false
        val had = s.accPaused
        s.accPaused = had + pkg; s.accLeftAt = 0L
        if (had.isEmpty()) {
            runCatching { s.log(R.string.log_acc_paused, label(ctx, pkg)) }
            runCatching { android.widget.Toast.makeText(ctx.applicationContext, ctx.getString(R.string.acc_paused_toast), android.widget.Toast.LENGTH_LONG).show() }
        }
        val cr = ctx.contentResolver
        val me = "${ctx.packageName}/${Acc::class.java.name}"
        runCatching {
            val cur = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            val left = cur.split(':').filter { it.isNotEmpty() && !it.equals(me, true) && !it.equals("${ctx.packageName}/.Acc", true) }
            if (left.size != cur.split(':').count { it.isNotEmpty() }) {
                Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, left.joinToString(":"))
                if (left.isEmpty()) Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
            }
        }
        // the setting change unbinds us a moment later; the service can also stop itself right away
        if (stopSelf) Acc.running?.let { svc -> runCatching { svc.disableSelf() } }
        return had.isEmpty()
    }

    /**
     * The bank came up WITHOUT our launcher (Recents, a notification, a link from another app): it already saw the
     * service on and shows its "turn off accessibility" refusal. Pausing now is too late for this run, so restart it
     * once: Home (a global action of the still-bound service: no background-start limit), kill the bank's process, then
     * open it fresh with the service already off. The reopen comes from our app while our Home is in front; with another
     * launcher on a basic-mode phone Android may block it (the kid just taps the bank again). A deep link is lost.
     */
    fun pauseLate(svc: android.accessibilityservice.AccessibilityService, pkg: String) {
        if (Store.of(svc).rules.accPause.isEmpty()) return
        runCatching { svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME) }
        val fresh = pause(svc, pkg, stopSelf = false)
        val app = svc.applicationContext
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        h.postDelayed({ runCatching { svc.disableSelf() } }, 150)
        if (!fresh) return
        h.postDelayed({
            app.getSystemService(android.app.ActivityManager::class.java)?.let { am -> runCatching { am.killBackgroundProcesses(pkg) } }
        }, RELAUNCH_KILL_MS)
        h.postDelayed({
            runCatching {
                app.packageManager.getLaunchIntentForPackage(pkg)?.let { i ->
                    app.startActivity(i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK))
                }
            }
        }, RELAUNCH_OPEN_MS)
    }

    private const val RELAUNCH_KILL_MS = 600L
    private const val RELAUNCH_OPEN_MS = 1000L

    /** Guard tick: count the time since the last paused app left the front; after [AccPauseLogic.RESUME_MS] turn back on. */
    @Synchronized fun tick(ctx: Context, current: String?, now: Long) {
        val s = Store.of(ctx)
        val paused = s.accPaused
        if (paused.isEmpty()) return
        if (!able(ctx)) { resume(ctx, s, paused, kill = false); return }   // lost owner / permission: never stay off
        val wait = if (Policy.isOwner(ctx)) AccPauseLogic.RESUME_MS else AccPauseLogic.RESUME_BASIC_MS
        val (leftAt, back) = AccPauseLogic.step(current, s.rules.accPause + paused, s.accLeftAt, now, wait)
        if (back) resume(ctx, s, paused, kill = true) else if (leftAt != s.accLeftAt) s.accLeftAt = leftAt
    }

    private fun resume(ctx: Context, s: Store, paused: Set<String>, kill: Boolean) {
        if (kill) ctx.getSystemService(android.app.ActivityManager::class.java)?.let { am ->
            paused.forEach { p -> runCatching { am.killBackgroundProcesses(p) } }
        }
        s.accPaused = emptySet(); s.accLeftAt = 0L
        runCatching { s.log(R.string.log_acc_resumed) }
        Acc.ensureEnabled(ctx, quiet = true)
    }

    /** Release: forget the pause (Acc's own release takes the service out anyway). */
    fun clear(ctx: Context) { val s = Store.of(ctx); s.accPaused = emptySet(); s.accLeftAt = 0L }

    private fun label(ctx: Context, pkg: String) = runCatching {
        val pm = ctx.packageManager; pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)
}
