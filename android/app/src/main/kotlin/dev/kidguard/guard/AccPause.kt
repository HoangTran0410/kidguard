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
    /**
     * When the status notification's countdown ends, over every pause running (accessibility, debugging + port 5555):
     * each `leftAt` (0 = its app is still in front, nothing counts yet). 0 = some app is in front: no countdown.
     */
    fun noticeAt(leftAts: List<Long>, wait: Long): Long = if (leftAts.isEmpty() || leftAts.any { it <= 0L }) 0L else leftAts.max() + wait
    /** Basic mode blocks apps through the service itself: a paused service blocks nothing, so come back much sooner. */
    const val RESUME_BASIC_MS = 60_000L
    /** After a pause ends the service binds a few seconds later: no "accessibility off" alarm meanwhile (owner 2026-11-16). */
    const val SETTLE_MS = 30_000L
    /** Raise the "child turned accessibility off" alarm now? Not during a bank pause, nor while it is coming back. */
    fun accOffAlarm(enforcing: Boolean, everPaired: Boolean, accOn: Boolean, paused: Boolean, resumedAt: Long, now: Long): Boolean =
        // -SETTLE_MS: the Guard tick takes `now` at its start and the resume inside that same tick sets resumedAt a few ms
        // later (emulator 2026-11-17: false "accoff" the second the pause ended); a clock jump still can't hide it for long
        enforcing && everPaired && !accOn && !paused && !(resumedAt > 0L && now - resumedAt in -SETTLE_MS until SETTLE_MS)

    /** No WRITE_SECURE_SETTINGS: the service can only turn itself off ([AccPause.selfOff]); the kid is asked this long after leaving the bank... */
    const val SELF_ASK_MS = 15_000L
    /** ...and when it is still off this long after the bank left, the normal "accessibility off" alarm goes to the parent. */
    const val SELF_DEADLINE_MS = 5 * 60_000L
    /** [leftAt] new "left at" (0 = a listed app is in front), [ask] show "turn protection back on", [expired] stop excusing it. */
    data class SelfStep(val leftAt: Long, val ask: Boolean, val expired: Boolean)
    fun selfStep(current: String?, list: Set<String>, leftAt: Long, now: Long): SelfStep = when {
        current != null && current in list -> SelfStep(0L, false, false)
        leftAt <= 0L || leftAt > now -> SelfStep(now, false, false)
        else -> SelfStep(leftAt, now - leftAt >= SELF_ASK_MS, now - leftAt >= SELF_DEADLINE_MS)
    }

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
        "xyz.be.cake",                      // Cake by VPBank (refuses any accessibility service: "read what you type"; owner 2026-11-11)
        "vms.com.vn.mymobifone",            // My MobiFone (refuses USB debugging, error 50052; owner 2026-11-11)
    )

    /** Basic mode, a bank app paused the service: resume at once when a blocked app comes up, or a Settings / installer page
     *  (the service also guards those; audit L1: they were unguarded for up to 60 s). */
    fun resumeFor(bounce: String?, pkg: String, everPaired: Boolean): Boolean = bounce != null || (everPaired && BasicLogic.isSettingsPkg(pkg))

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
    /** When the last pause ended (memory only): [AccPauseLogic.accOffAlarm] waits [AccPauseLogic.SETTLE_MS] for the bind.
     *  Starts at process start: after an app update / crash the service also binds seconds later (INCIDENT 2026-11-17). */
    @Volatile var resumedAt = System.currentTimeMillis()

    /**
     * [pkg] is opening: turn our accessibility service off (before the launcher starts it, or on its first window).
     * True when the service went off just now (nothing was paused before). [stopSelf] false = the caller still needs the
     * service for a moment (see [pauseLate]) and stops it itself.
     */
    @Synchronized fun pause(ctx: Context, pkg: String, stopSelf: Boolean = true): Boolean {
        val s = Store.of(ctx)
        if (pkg !in s.rules.accPause) return false
        // a blocked app never pauses the blocker, whatever list it is on (the list also comes from the website: BankList)
        if (BasicLogic.bounceReason(pkg, ctx.packageName, Acc.blocked, Acc.prot) != null) return false
        if (!able(ctx)) return selfOff(ctx, pkg, stopSelf)
        val had = s.accPaused
        s.accPaused = had + pkg; s.accLeftAt = 0L
        runCatching { NavGest.sync(ctx, pkg) }   // the 3-button bar comes back before the bank opens
        runCatching { VpnPause.pause(ctx, pkg) }   // on that list too: the tunnel goes in the same step
        if (had.isEmpty()) {
            runCatching { s.log(R.string.log_acc_paused, label(ctx, pkg)) }
            runCatching { Toasts.show(ctx.applicationContext, ctx.getString(R.string.acc_paused_toast), true) }
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
        notice(ctx)
        return had.isEmpty()
    }

    // ---- no WRITE_SECURE_SETTINGS (basic mode, e.g. Xiaomi: the adb grant needs SIM + Mi account): the service can still
    // turn ITSELF off (disableSelf needs no permission) but only the kid can turn it back on (Settings). So: off for the
    // bank, [Store.accSelf] marks it as expected (accPaused is non-empty: state, status line and the tamper alarm all
    // treat it as "paused for <app>"), once the bank has been left [SELF_ASK_MS] a notification offers "turn protection
    // back on" (button = accessibility settings), and [SELF_DEADLINE_MS] after leaving, still off = the normal alarm.
    private const val NOTIF_SELF = 7351
    private var selfShown = ""

    /** [pkg] is opening and we cannot turn back on by ourselves: turn the service off for it. True = went off just now. */
    @Synchronized fun selfOff(ctx: Context, pkg: String, stopSelf: Boolean = true): Boolean {
        val svc = Acc.running ?: return false   // already off: nothing to do, the bank just runs
        val s = Store.of(ctx)
        val had = s.accPaused
        s.accSelf = true; s.accPaused = had + pkg; s.accLeftAt = 0L
        runCatching { NavGest.sync(ctx, pkg) }
        runCatching { VpnPause.pause(ctx, pkg) }
        if (had.isEmpty()) {
            runCatching { s.log(R.string.log_acc_self_off, label(ctx, pkg)) }
            runCatching { Toasts.show(ctx.applicationContext, ctx.getString(R.string.acc_paused_toast), true) }
        }
        if (stopSelf) runCatching { svc.disableSelf() }
        notice(ctx)
        return had.isEmpty()
    }

    /** Guard tick while [Store.accSelf]: back on (kid turned it on) / ask / give up excusing. */
    private fun selfTick(ctx: Context, s: Store, current: String?, now: Long) {
        val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
        if (Acc.isOn(ctx)) { selfClear(ctx, s, nm, resumed = true); return }
        val st = AccPauseLogic.selfStep(current, s.rules.accPause + s.accPaused, s.accLeftAt, now)
        if (st.leftAt != s.accLeftAt) s.accLeftAt = st.leftAt
        if (st.expired) { selfClear(ctx, s, nm, resumed = false); return }   // the Guard alarm sees "accessibility off" right after
        if (!st.ask) return
        val key = s.accPaused.sorted().joinToString() + "|" + (now - st.leftAt) / 120_000L   // re-ask every 2 min
        if (key == selfShown) return
        selfShown = key
        runCatching {
            val ch = Chan.open(nm, "bankwarn", ctx.getString(R.string.ch_bankwarn), android.app.NotificationManager.IMPORTANCE_HIGH)
            val i = android.content.Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            // some Settings apps scroll to / highlight this service
            val me = "${ctx.packageName}/${Acc::class.java.name}"
            i.putExtra(":settings:fragment_args_key", me).putExtra(":settings:show_fragment_args", android.os.Bundle().apply { putString(":settings:fragment_args_key", me) })
            val pi = android.app.PendingIntent.getActivity(ctx, 8, i, android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
            nm.notify(NOTIF_SELF, android.app.Notification.Builder(ctx, ch).setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setContentTitle(ctx.getString(R.string.self_on_t)).setContentText(ctx.getString(R.string.self_on_d))
                .setStyle(android.app.Notification.BigTextStyle().bigText(ctx.getString(R.string.self_on_d))).setAutoCancel(true)
                .addAction(android.app.Notification.Action.Builder(null, ctx.getString(R.string.self_on_btn), pi).build()).build())
        }
    }

    private fun selfClear(ctx: Context, s: Store, nm: android.app.NotificationManager?, resumed: Boolean) {
        s.accPaused = emptySet(); s.accLeftAt = 0L; s.accSelf = false; selfShown = ""
        if (resumed) { resumedAt = System.currentTimeMillis(); runCatching { s.log(R.string.log_acc_resumed) } }
        runCatching { nm?.cancel(NOTIF_SELF) }
        notice(ctx)
    }

    /** No permission, service bound: bank apps opened without our launcher (see [pauseLate]): Home, off, reopen fresh. */
    private fun selfOffLate(svc: android.accessibilityservice.AccessibilityService, pkg: String): Boolean {
        if (BasicLogic.bounceReason(pkg, svc.packageName, Acc.blocked, Acc.prot) != null) return false
        val ks = runCatching { PauseAsk.kinds(svc, pkg) }.getOrDefault(emptyList())
        runCatching { svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME) }
        val fresh = selfOff(svc, pkg, stopSelf = false)
        val dev = runCatching { DevOpts.pause(svc, pkg) }.getOrDefault(false)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ runCatching { svc.disableSelf() } }, 150)
        if (fresh || dev) {
            runCatching { PauseNotice.show(svc, pkg, label(svc, pkg), ks) }
            relaunch(svc, pkg)
        }
        return true
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
        if (!AccPauseLogic.resumeFor(BasicLogic.bounceReason(pkg, ctx.packageName, Acc.blocked, Acc.prot), pkg, s.everPaired)) return
        synchronized(this) { if (s.accPaused.isNotEmpty()) { resume(ctx, s, s.accPaused, kill = false); runCatching { s.log(R.string.log_acc_resumed_blocked, label(ctx, pkg)) } } }
        watchStop()
    }

    /**
     * The bank came up WITHOUT our launcher (Recents, a notification, a link from another app): it already saw the
     * service on and shows its "turn off accessibility" refusal. Pausing now is too late for this run, so restart it
     * once: Home (a global action of the still-bound service: no background-start limit), kill the bank's process, then
     * open it fresh with the service already off. Came from a notification tap: that notification's intent is sent again
     * (deep link kept, NotifLog.recentClick); else the launch intent (deep link lost). Basic mode needs SYSTEM_ALERT_WINDOW
     * for a background start ([canReopen]); without it Android may block it (the kid taps the bank again).
     * False = nothing done, the caller treats [pkg] like any other app: without WRITE_SECURE_SETTINGS the service could
     * never turn itself back on (basic mode would stop blocking for good), and a blocked app is bounced, not restarted.
     */
    fun pauseLate(svc: android.accessibilityservice.AccessibilityService, pkg: String): Boolean {
        if (!able(svc) && !Flavor.HOME && pkg in Store.of(svc).rules.accPause) return selfOffLate(svc, pkg)   // no permission: off by ourselves
        if (!able(svc) || pkg !in Store.of(svc).rules.accPause) return false
        if (BasicLogic.bounceReason(pkg, svc.packageName, Acc.blocked, Acc.prot) != null) return false
        val ks = runCatching { PauseAsk.kinds(svc, pkg) }.getOrDefault(emptyList())   // what is on NOW (before it goes off)
        runCatching { svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME) }   // before the service goes
        val fresh = pause(svc, pkg, stopSelf = false)
        val dev = runCatching { DevOpts.pause(svc, pkg) }.getOrDefault(false)   // same app may also refuse debugging
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ runCatching { svc.disableSelf() } }, 150)
        if (fresh || dev) {
            runCatching { PauseNotice.show(svc, pkg, label(svc, pkg), ks) }   // tells afterwards what went off (no time to ask)
            relaunch(svc, pkg)
        }
        return true
    }

    /** Kill [pkg]'s process and open it again fresh (its notification intent if it was opened from one). */
    fun relaunch(svc: android.accessibilityservice.AccessibilityService, pkg: String) {
        runCatching { svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME) }
        val app = svc.applicationContext
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        h.postDelayed({
            app.getSystemService(android.app.ActivityManager::class.java)?.let { am -> runCatching { am.killBackgroundProcesses(pkg) } }
        }, RELAUNCH_KILL_MS)
        h.postDelayed({ DevOpts.afterOff(h) {
            // opened by tapping its notification: send that notification's own intent again, so the deep link
            // (the payment / OTP screen) survives; it is held in memory by the notification history (NotifLog)
            val pi = runCatching { NotifLog.recentClick(pkg, 8_000) }.getOrNull()
            if (pi != null && NotifLog.send(app, pi)) return@afterOff
            runCatching {
                app.packageManager.getLaunchIntentForPackage(pkg)?.let { i ->
                    app.startActivity(i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK))
                }
            }
        } }, RELAUNCH_OPEN_MS)
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
        if (s.accSelf) {
            if (!able(ctx)) { selfTick(ctx, s, current, now); return }
            if (Acc.isOn(ctx)) { selfClear(ctx, s, null, resumed = true); return }   // got the permission meanwhile: the normal path below
            s.accSelf = false
        }
        if (!able(ctx)) { resume(ctx, s, paused, kill = false); return }   // lost owner / permission: never stay off
        watchStart(ctx)   // after a process restart while paused
        val wait = if (Policy.isOwner(ctx)) AccPauseLogic.RESUME_MS else AccPauseLogic.RESUME_BASIC_MS
        val (leftAt, back) = AccPauseLogic.step(current, s.rules.accPause + paused, s.accLeftAt, now, wait)
        if (back) resume(ctx, s, paused, kill = true) else { if (leftAt != s.accLeftAt) s.accLeftAt = leftAt; notice(ctx) }
    }

    private fun resume(ctx: Context, s: Store, paused: Set<String>, kill: Boolean) {
        if (kill) ctx.getSystemService(android.app.ActivityManager::class.java)?.let { am ->
            paused.forEach { p -> runCatching { am.killBackgroundProcesses(p) } }
        }
        s.accPaused = emptySet(); s.accLeftAt = 0L; s.accSelf = false; resumedAt = System.currentTimeMillis()
        watchStop()
        runCatching { s.log(R.string.log_acc_resumed) }
        notice(ctx)
        Acc.ensureEnabled(ctx, quiet = true)
    }

    /** Parent's "end now" (command bankEnd): the service comes back now instead of after the 10-minute wait. */
    @Synchronized fun endNow(ctx: Context) { val s = Store.of(ctx); val p = s.accPaused; if (p.isNotEmpty()) resume(ctx, s, p, kill = true) }

    /** Release: forget the pause (Acc's own release takes the service out anyway). */
    fun clear(ctx: Context) { val s = Store.of(ctx); s.accPaused = emptySet(); s.accLeftAt = 0L; s.accSelf = false; runCatching { ctx.getSystemService(android.app.NotificationManager::class.java)?.cancel(NOTIF_SELF) } }

    // ---- status notification while paused: says WHEN it comes back (a countdown once the app is left) and offers
    // "turn on now" then. Not while the paused app is in front: turning back on closes it (lost transaction).
    private const val CH_STATE = "accstate"
    private const val NOTIF_STATE = 7350
    private var shown = ""

    // Covers the debugging pause too (DevOpts.pause: USB debugging + adb port 5555): owner 2026-11-18, VNeID turned 3 things
    // off and the notification named only accessibility; "turn on now" brings all of them back (same as the parent's bankEnd).
    @Synchronized fun notice(ctx: Context) = runCatching {
        val s = Store.of(ctx)
        val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
        val acc = if (s.accSelf) emptySet() else s.accPaused; val dev = s.devApps
        val what = buildList {
            if (acc.isNotEmpty()) add(ctx.getString(R.string.pause_what_acc))
            if (dev.isNotEmpty() && s.devPaused != 0) add(ctx.getString(R.string.pause_what_dev))
            if (dev.any { it in s.rules.tcpPause } && s.adbTcp) add(ctx.getString(R.string.pause_what_tcp))
        }
        val apps = (acc + dev).sorted()
        val wait = if (Policy.isOwner(ctx)) AccPauseLogic.RESUME_MS else AccPauseLogic.RESUME_BASIC_MS
        val at = AccPauseLogic.noticeAt(buildList { if (acc.isNotEmpty()) add(s.accLeftAt); if (dev.isNotEmpty()) add(s.devLeftAt) }, wait)
        val key = if (what.isEmpty()) "" else apps.joinToString() + "|" + what.joinToString() + "|" + at
        if (key == shown) return@runCatching
        shown = key
        if (what.isEmpty()) { nm.cancel(NOTIF_STATE); return@runCatching }
        val ch = Chan.open(nm, CH_STATE, ctx.getString(R.string.ch_accstate), android.app.NotificationManager.IMPORTANCE_LOW)
        val min = (wait / 60_000L).toInt()
        val b = android.app.Notification.Builder(ctx, ch).setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle(ctx.getString(R.string.acc_state_t, apps.joinToString(", ") { label(ctx, it) }))
            .setStyle(android.app.Notification.BigTextStyle())
            .setOngoing(true).setOnlyAlertOnce(true).setShowWhen(at > 0L)
        if (at > 0L) {
            b.setContentText(ctx.getString(R.string.pause_off, what.joinToString(", ")) + " " + ctx.getString(R.string.acc_state_left)).setWhen(at).setUsesChronometer(true).setChronometerCountDown(true)
            val pi = android.app.PendingIntent.getBroadcast(ctx, 1, android.content.Intent(ctx, AccResumeReceiver::class.java),
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
            b.addAction(android.app.Notification.Action.Builder(null, ctx.getString(R.string.acc_state_now), pi).build())
        } else b.setContentText(ctx.getString(R.string.pause_off, what.joinToString(", ")) + " " + ctx.resources.getQuantityString(R.plurals.acc_state_front, min, min))
        nm.notify(NOTIF_STATE, b.build())
    }

    private fun label(ctx: Context, pkg: String) = runCatching {
        val pm = ctx.packageManager; pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)
}

/** "Turn on now" on the pause status notification: same as the parent's end-now (closes the paused app), all pauses. */
class AccResumeReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(c: Context, i: android.content.Intent?) {
        val r = goAsync()
        Thread({ try {
            val app = c.applicationContext
            runCatching { AccPause.endNow(app) }
            runCatching { DevOpts.clearPause(app) }   // debugging back now; Shell.tick reopens port 5555 once devApps is empty
            runCatching { SelfAdb.invalidate(); AutoGrant.soon() }
            AccPause.notice(app)
        } finally { r.finish() } }, "acc-resume").start()
    }
}
