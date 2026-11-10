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
        watchStart(ctx)
        return had.isEmpty()
    }

    // ---- basic mode: the service IS the app blocker, so while it is off a blocked app could run freely (open the bank,
    // switch to a game). A light watch closes that gap: every [WATCH_MS], only while paused, only with the screen on, it
    // reads the last few seconds of usage events (one binder call, ~1-3 ms, vs the 10 s tick that reads since midnight)
    // and turns the service back on as soon as a blocked app is in front; Acc.onServiceConnected then bounces it.
    private const val WATCH_MS = 2_000L
    private val watchExec by lazy { java.util.concurrent.Executors.newSingleThreadScheduledExecutor() }
    private var watchFut: java.util.concurrent.ScheduledFuture<*>? = null
    private var watchFrom = 0L
    private var watchCur: String? = null

    @Synchronized private fun watchStart(ctx: Context) {
        if (watchFut != null || Policy.isOwner(ctx)) return
        val app = ctx.applicationContext
        watchFrom = System.currentTimeMillis() - 15_000; watchCur = null
        watchFut = watchExec.scheduleWithFixedDelay({ runCatching { watchStep(app) } }, WATCH_MS, WATCH_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    @Synchronized private fun watchStop() { watchFut?.cancel(false); watchFut = null }

    private fun watchStep(ctx: Context) {
        val s = Store.of(ctx)
        if (s.accPaused.isEmpty()) { watchStop(); return }
        if (ctx.getSystemService(android.os.PowerManager::class.java)?.isInteractive == false) return
        val now = System.currentTimeMillis()
        val usm = ctx.getSystemService(android.app.usage.UsageStatsManager::class.java) ?: return
        val ev = usm.queryEvents(watchFrom, now); val e = android.app.usage.UsageEvents.Event()
        while (ev.hasNextEvent()) { ev.getNextEvent(e); if (e.eventType == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED) watchCur = e.packageName }
        watchFrom = now - 1_000   // small overlap: events land a little late
        val pkg = watchCur ?: return
        if (pkg in s.rules.accPause || pkg in s.accPaused) return
        if (BasicLogic.bounceReason(pkg, ctx.packageName, Acc.blocked, Acc.prot) == null) return
        synchronized(this) { if (s.accPaused.isNotEmpty()) { resume(ctx, s, s.accPaused, kill = false); runCatching { s.log(R.string.log_acc_resumed_blocked, label(ctx, pkg)) } } }
        watchStop()
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

    /** Can reopen a bank from the background: Device Owner is exempt from the limit, basic mode needs "display over other apps". */
    fun canReopen(ctx: Context): Boolean = Policy.isOwner(ctx) || android.provider.Settings.canDrawOverlays(ctx)

    /** Basic mode set up before the grant was in setup: give ourselves "display over other apps" through the self-adb shell. */
    fun grantOverlay(ctx: Context, r: Rules) {
        if (Flavor.HOME || r.accPause.isEmpty() || canReopen(ctx) || !able(ctx) || !SelfAdb.hasKey(ctx)) return
        Thread({ runCatching { SelfAdb.run(ctx, "appops set ${ctx.packageName} SYSTEM_ALERT_WINDOW allow") } }, "overlay-grant").start()
    }

    private const val RELAUNCH_KILL_MS = 600L
    private const val RELAUNCH_OPEN_MS = 1000L

    /** Guard tick: count the time since the last paused app left the front; after [AccPauseLogic.RESUME_MS] turn back on. */
    @Synchronized fun tick(ctx: Context, current: String?, now: Long) {
        val s = Store.of(ctx)
        val paused = s.accPaused
        if (paused.isEmpty()) return
        if (!able(ctx)) { resume(ctx, s, paused, kill = false); return }   // lost owner / permission: never stay off
        watchStart(ctx)   // after a process restart while paused
        val wait = if (Policy.isOwner(ctx)) AccPauseLogic.RESUME_MS else AccPauseLogic.RESUME_BASIC_MS
        val (leftAt, back) = AccPauseLogic.step(current, s.rules.accPause + paused, s.accLeftAt, now, wait)
        if (back) resume(ctx, s, paused, kill = true) else if (leftAt != s.accLeftAt) s.accLeftAt = leftAt
    }

    private fun resume(ctx: Context, s: Store, paused: Set<String>, kill: Boolean) {
        if (kill) ctx.getSystemService(android.app.ActivityManager::class.java)?.let { am ->
            paused.forEach { p -> runCatching { am.killBackgroundProcesses(p) } }
        }
        s.accPaused = emptySet(); s.accLeftAt = 0L
        watchStop()
        runCatching { s.log(R.string.log_acc_resumed) }
        Acc.ensureEnabled(ctx, quiet = true)
    }

    /** Release: forget the pause (Acc's own release takes the service out anyway). */
    fun clear(ctx: Context) { val s = Store.of(ctx); s.accPaused = emptySet(); s.accLeftAt = 0L }

    private fun label(ctx: Context, pkg: String) = runCatching {
        val pm = ctx.packageManager; pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)
}
