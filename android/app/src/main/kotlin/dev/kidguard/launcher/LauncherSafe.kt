package dev.kidguard

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.util.Log

/**
 * Turns the kid launcher (HomeActivity) on and off, and keeps it safe: a launcher that crashes in a loop would leave
 * the child with no home screen, so 2 crashes in 5 min reset the layout and 3 turn it off for 24 h (LauncherLogic.crash).
 * Crash times go to their own prefs with commit(): the process dies right after the handler runs.
 */
object LauncherSafe {
    private const val TAG = "KidGuard"
    private const val PREFS = "kgcrash"

    fun comp(ctx: Context) = ComponentName(ctx, HomeActivity::class.java)

    private fun prefs(ctx: Context) = ctx.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(ctx: Context): Boolean =
        ctx.packageManager.getComponentEnabledSetting(comp(ctx)) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    fun safeUntil(ctx: Context): Long = prefs(ctx).getLong("safeUntil", 0)
    fun inSafeMode(ctx: Context): Boolean = System.currentTimeMillis() < safeUntil(ctx)

    /** The launcher should be the home screen now. */
    fun wanted(ctx: Context, r: Rules): Boolean {
        val s = Store.of(ctx)
        if (inSafeMode(ctx) || s.released) return false
        if (Flavor.HOME) return true
        if (Policy.isOwner(ctx)) return r.launcher.on
        // no Device Owner: nothing can be forced (the user picks HOME in Settings), so KidGuard is ALWAYS in the HOME list;
        // hiding it only locked people out (paired + rule off left no way to pick it). Safe mode / released still hide it.
        return true
    }

    private fun homeFilter() = IntentFilter(Intent.ACTION_MAIN).apply {
        addCategory(Intent.CATEGORY_HOME); addCategory(Intent.CATEGORY_DEFAULT)
    }

    /** Called from Policy.apply (Guard thread) and the minute check. Starts HOME only when the state really changed. */
    @Synchronized fun apply(ctx: Context, r: Rules) {
        if (wanted(ctx, r)) { on(ctx); shadeBlocked = r.launcher.shade } else off(ctx, goHome = true)
    }

    /**
     * Rule launcher.shade: the system notification shade / quick settings are closed as soon as they open (Acc, needs the
     * accessibility service); the kid's own control center and notification list replace them. Not
     * setStatusBarDisabled: that also mutes every notification's sound, vibration and heads-up (measured, API 34).
     * Cleared by off() = rule off, safe mode, release.
     */
    @Volatile var shadeBlocked = false; private set

    private fun on(ctx: Context) {
        val was = enabled(ctx)
        // first time on: the kid keeps the home screen they made in KidGuard Home (before that app is hidden below)
        if (!was) HomeExport.pull(ctx)
        val pm = ctx.packageManager
        if (!was) runCatching { pm.setComponentEnabledSetting(comp(ctx), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP) }
            .onFailure { Log.w(TAG, "launcher enable", it) }
        if (!Policy.isOwner(ctx)) return   // only the owner may force / lock HOME; the user picks it in Settings
        // re-added every time: cheap, and it fixes a HOME the user re-picked in Settings
        if (!homeIsOurs(ctx)) runCatching { Policy.dpm(ctx).addPersistentPreferredActivity(Policy.admin(ctx), homeFilter(), comp(ctx)) }
            .onFailure { Log.w(TAG, "launcher preferred", it) }
        if (!was) home(ctx)
        hideHomeApp(ctx, true)
    }

    /** The stand-alone "KidGuard Home" app (home flavor). While our launcher runs a managed phone it would be a second
     *  "KidGuard Home" icon and HOME choice without SOS / time requests, so the owner hides it; off() (rule off, safe
     *  mode, release) shows it again. Hidden, not removed: its layout stays. */
    private fun homePkg(ctx: Context) = ctx.packageName + ".home"
    fun homeAppShown(ctx: Context): Boolean = Policy.isOwner(ctx) && runCatching {
        ctx.packageManager.getApplicationInfo(homePkg(ctx), 0); true }.getOrDefault(false)   // hidden apps are not found
    private fun hideHomeApp(ctx: Context, hide: Boolean) {
        if (!Policy.isOwner(ctx)) return
        runCatching { Policy.dpm(ctx).setApplicationHidden(Policy.admin(ctx), homePkg(ctx), hide) }.onFailure { Log.w(TAG, "home app hidden=$hide", it) }
    }

    /** Undo: clear our preferred HOME and disable the component. Used by rule off, safe mode and release. */
    fun off(ctx: Context, goHome: Boolean) {
        shadeBlocked = false
        LauncherLock.off(ctx)
        val was = enabled(ctx)
        if (Policy.isOwner(ctx)) runCatching { Policy.dpm(ctx).clearPackagePersistentPreferredActivities(Policy.admin(ctx), ctx.packageName) }
            .onFailure { Log.w(TAG, "launcher clear preferred", it) }
        // manifest default = disabled: only a component we enabled needs it
        if (was) runCatching { ctx.packageManager.setComponentEnabledSetting(comp(ctx), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP) }
            .onFailure { Log.w(TAG, "launcher disable", it) }
        if (was && goHome) home(ctx)
        hideHomeApp(ctx, false)
    }

    /** Which activity HOME opens now. */
    fun homeIsOurs(ctx: Context): Boolean = runCatching {
        ctx.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.let { it.packageName == ctx.packageName && it.name == HomeActivity::class.java.name } == true
    }.getOrDefault(false)

    /**
     * After our own update (MY_PACKAGE_REPLACED). Android 8 kills the process and finishes HomeActivity, but its
     * starting window stays up: the old launcher underneath is resumed and stopped in a loop and the screen stays black
     * until someone presses Home. So if the kid was on the home screen (last foreground app = us or a launcher), start
     * HOME. If the kid was in another app, leave them there: their next Home press works.
     */
    fun afterUpdate(ctx: Context) = runCatching {
        freshStart(ctx)
        if (!enabled(ctx) || !homeIsOurs(ctx)) return@runCatching
        val top = lastForeground(ctx)
        val homes = ctx.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .map { it.activityInfo.packageName }.toSet()
        Log.i(TAG, "after update: top=$top")
        if (top == null || top == ctx.packageName || top in homes) home(ctx)
    }.onFailure { Log.w(TAG, "launcher after update", it) }

    /**
     * A new version may fix what crashed: forget the crash count and the 24 h safe mode, and if the 2nd crash reset the
     * layout within the last 24 h, give the kid their layout back (it crashes twice more if the layout was the cause).
     */
    private fun freshStart(ctx: Context) {
        val p = prefs(ctx)
        val now = System.currentTimeMillis()
        val resetAt = p.getLong("resetAt", 0)
        val safe = p.getLong("safeUntil", 0) > now
        if (resetAt == 0L && !safe && p.getString("times", "").isNullOrEmpty()) return
        val restored = resetAt > now - LauncherLogic.SAFE_MS && HomeFile.restoreBad(ctx)
        p.edit().remove("times").remove("safeUntil").remove("resetAt").putBoolean("reset", false).commit()
        Log.i(TAG, "after update: crash state cleared, safe=$safe layout restored=$restored")
        if (safe) apply(ctx, Store.of(ctx).rules)
    }

    private fun lastForeground(ctx: Context): String? {
        val um = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
        val now = System.currentTimeMillis()
        val ev = um.queryEvents(now - 30 * 60_000L, now)
        val e = android.app.usage.UsageEvents.Event()
        var top: String? = null
        while (ev.hasNextEvent()) { ev.getNextEvent(e); if (e.eventType == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND) top = e.packageName }
        return top
    }

    private fun home(ctx: Context) = runCatching {
        ctx.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun err(ctx: Context, r: Rules): String = LauncherLogic.err(r.launcher.on, Policy.isOwner(ctx), inSafeMode(ctx), homeIsOurs(ctx))

    /** The Features sheet's "launcher" row: turn the local opt-in on/off (enables / disables the HOME component). */
    fun setLocal(ctx: Context, on: Boolean) {
        Store.of(ctx).launcherLocal = on
        runCatching { apply(ctx, Store.of(ctx).rules) }.onFailure { Log.w(TAG, "launcher local", it) }
    }

    // ---- crash fallback ----

    /** Installed by HomeActivity.onCreate. Chains to the old handler (the process still dies). */
    fun watch(ctx: Context) {
        val app = ctx.applicationContext
        val old = Thread.getDefaultUncaughtExceptionHandler()
        if (old is Handler) return
        Thread.setDefaultUncaughtExceptionHandler(Handler(app, old))
    }

    private class Handler(val app: Context, val next: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            // only a crash with launcher frames counts: Guard / relay code on the main thread is not a launcher crash
            if (HomeActivity.alive > 0 && runCatching { LauncherLogic.isLauncherCrash(frames(e)) }.getOrDefault(true)) runCatching { crashed(app, e) }
            next?.uncaughtException(t, e)
        }
    }

    private fun frames(e: Throwable): List<String> {
        val out = ArrayList<String>(); var c: Throwable? = e; var n = 0
        while (c != null && n++ < 6) { c.stackTrace.mapTo(out) { it.className }; c = c.cause }
        return out
    }

    /** One launcher crash. Writes synchronously; the Guard reports the event later (pending). */
    fun crashed(ctx: Context, e: Throwable?) {
        val p = prefs(ctx)
        val now = System.currentTimeMillis()
        val times = (p.getString("times", "") ?: "").split(',').mapNotNull { it.toLongOrNull() }
        val (kept, act) = LauncherLogic.crash(times, now)
        val ed = p.edit().putString("times", kept.joinToString(","))
            .putString("last", (e?.javaClass?.simpleName ?: "") + ": " + (e?.message ?: "").take(120))
        when (act) {
            LauncherLogic.CrashAct.RESET -> ed.putBoolean("reset", true)
            LauncherLogic.CrashAct.DISABLE -> ed.putLong("safeUntil", now + LauncherLogic.SAFE_MS).putBoolean("pendingEv", true).putString("times", "")
            else -> {}
        }
        ed.commit()
        // 3rd crash: off now (the stock launcher takes HOME); the Guard re-checks it every minute too
        if (act == LauncherLogic.CrashAct.DISABLE) runCatching { off(ctx, goHome = false) }
    }

    /** HomeActivity asks on start: reset the layout once after a 2nd crash. */
    fun takeReset(ctx: Context): Boolean {
        val p = prefs(ctx)
        if (!p.getBoolean("reset", false)) return false
        p.edit().putBoolean("reset", false).putLong("resetAt", System.currentTimeMillis()).commit()
        return true
    }

    /** Guard tick: the `launcher` event after the safe mode started (once). */
    fun pendingEvent(ctx: Context): String? {
        val p = prefs(ctx)
        if (!p.getBoolean("pendingEv", false)) return null
        p.edit().putBoolean("pendingEv", false).commit()
        return p.getString("last", "") ?: ""
    }

    /** state.launcher (protocol "v0.9.9: kid launcher"). */
    fun state(ctx: Context, r: Rules, style: String, pages: Int): org.json.JSONObject = org.json.JSONObject()
        .put("on", r.launcher.on && enabled(ctx)).put("home", runCatching {
            ctx.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        }.getOrNull() ?: "")
        .put("err", err(ctx, r)).put("style", style).put("locked", r.launcher.lockCustom).put("pages", pages)
        .put("safeUntil", safeUntil(ctx).takeIf { it > System.currentTimeMillis() } ?: 0)
}
