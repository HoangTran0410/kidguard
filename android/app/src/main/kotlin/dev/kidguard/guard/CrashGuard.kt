package dev.kidguard

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import java.lang.ref.WeakReference

/**
 * Error boundary (owner 2026-11-18: "đừng làm crash app, launcher quan trọng"). The kid HomeActivity shares the process
 * with every other screen, the accessibility service and the Guard: one crash in a settings sheet killed the home too,
 * and two such crashes within a minute make Android clear a third-party default HOME (launcher.md INCIDENT 2026-11-18).
 *
 * Main thread: [Looper.loop] runs inside our own try/catch. A crash that is NOT the launcher's is reported (same files as
 * [Support.watchCrashes], sent by the next start / [Support.sendCrash]), every non-home screen is finished (its state is
 * unknown after a half-run lifecycle call) and the process lives on. A launcher crash is counted by [LauncherSafe]
 * (2nd = layout reset, 3rd = [SafeHome]) and the home restarts fresh. Only OutOfMemoryError and a crash loop still go to
 * the default handler and kill the process.
 * Other threads: a short-lived worker thread (no Looper) just ends; a Looper thread (Guard "guard", Relay "relay") still
 * kills the process, because the process restart is what brings that loop back.
 */
object CrashGuard {
    private const val TAG = "KidGuard"
    private val acts = ArrayList<WeakReference<Activity>>()
    private val recent = ArrayList<Long>()
    @Volatile private var installed = false

    fun install(app: Application) {
        if (installed) return
        installed = true
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: Activity, b: Bundle?) { synchronized(acts) { acts.removeAll { it.get() == null }; acts.add(WeakReference(a)) } }
            override fun onActivityDestroyed(a: Activity) { synchronized(acts) { acts.removeAll { it.get().let { x -> x == null || x === a } } } }
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
        })
        val next = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            // a worker thread without a Looper: report and let it end (it is not the main thread: that one is caught in loop())
            if (t !== Looper.getMainLooper().thread && Looper.myLooper() == null && e !is OutOfMemoryError) {
                Log.e(TAG, "crash on ${t.name}, process kept", e)
                runCatching { Support.recordCrash(app, t, e) }
            } else next?.uncaughtException(t, e)
        }
        Handler(Looper.getMainLooper()).post { loop(app) }
    }

    private fun loop(app: Application) {
        while (true) {
            try {
                Looper.loop()
                return   // the main looper quit: never happens in an app process
            } catch (e: Throwable) {
                val now = System.currentTimeMillis()
                val launcher = HomeActivity.alive > 0 && runCatching { LauncherLogic.isLauncherCrash(frames(e)) }.getOrDefault(true)
                val loopNow = synchronized(recent) { recent.removeAll { now - it !in 0 until CrashGuardLogic.WINDOW_MS }; recent.add(now); recent.size }
                if (!CrashGuardLogic.survive(e is OutOfMemoryError, loopNow) || CrashGuardLogic.componentCrash(methods(e))) throw e   // default handler: LauncherSafe + Support, process dies
                Log.e(TAG, "main-thread crash (launcher=$launcher), screens closed, process kept", e)
                runCatching { Support.recordCrash(app, Thread.currentThread(), e) }
                // a launcher crash still counts (2nd = layout reset, 3rd = SafeHome): the fresh HomeActivity below reads it
                if (launcher) runCatching { LauncherSafe.crashed(app, e) }
                closeScreens(app, launcher)
            }
        }
    }

    /**
     * Every screen but the kid home (its lifecycle may have stopped half way; home is the task below). A launcher crash
     * closes the home too and starts HOME again: a fresh HomeActivity (layout reset / SafeHome as [LauncherSafe] decided),
     * with the process alive, so Android never counts a crashed HOME process (2 = it forgets the default HOME).
     */
    private fun closeScreens(app: Application, home: Boolean) {
        val list = synchronized(acts) { acts.mapNotNull { it.get() } }
        var closed = 0
        for (a in list) if ((home || a !is HomeActivity) && !a.isFinishing) { runCatching { a.finish() }; closed++ }
        if (home) Handler(Looper.getMainLooper()).postDelayed({
            runCatching { app.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(app.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }, 400)
        else if (closed > 0) runCatching { Toast.makeText(app, R.string.crash_closed, Toast.LENGTH_LONG).show() }
    }

    private fun methods(e: Throwable): List<String> = runCatching { e.stackTrace.map { it.className + "." + it.methodName } }.getOrDefault(emptyList())

    private fun frames(e: Throwable): List<String> {
        val out = ArrayList<String>(); var c: Throwable? = e; var n = 0
        while (c != null && n++ < 6) { c.stackTrace.mapTo(out) { it.className }; c = c.cause }
        return out
    }
}

/** Pure: does the process survive a main-thread crash? */
object CrashGuardLogic {
    const val WINDOW_MS = 10_000L
    // more than this many caught crashes in 10 s = a loop the restarts don't end: die. 5 > 3, so a home crashing on every
    // start reaches SafeHome (3rd launcher crash) before this.
    const val MAX_IN_WINDOW = 5

    fun survive(outOfMemory: Boolean, crashesInWindow: Int): Boolean = !outOfMemory && crashesInWindow <= MAX_IN_WINDOW

    /**
     * A crash while Android creates / starts a service or runs a receiver ("Class.method" frames): the process must die
     * so START_STICKY brings a whole Guard back; kept alive it would be half built (http / relay never started). Review 2026-11-18.
     */
    private val COMPONENT = listOf("handleCreateService", "handleServiceArgs", "handleBindService", "handleReceiver")
    fun componentCrash(methods: List<String>): Boolean = methods.any { m -> m.startsWith("android.app.ActivityThread.") && COMPONENT.any { m.endsWith(".$it") } }
}
