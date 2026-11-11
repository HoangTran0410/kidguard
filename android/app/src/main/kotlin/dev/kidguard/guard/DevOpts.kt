package dev.kidguard

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import org.json.JSONObject

/**
 * Developer options / USB + wireless debugging. Banking apps refuse to open while these are on,
 * so the parent can turn them off ("devOff"). Needs WRITE_SECURE_SETTINGS (granted at setup) or Device Owner.
 */
/** Bundled list of apps that refuse debugging; more come from the website list (BankList, `dev`). */
object DevPauseLogic {
    val DEFAULT: Set<String> = setOf(
        "vms.com.vn.mymobifone",            // My MobiFone (INKA AppSealing, error 50052; owner 2026-11-11)
    )
}

object DevOpts {
    private const val ADB_WIFI = "adb_wifi_enabled"   // Settings.Global.ADB_WIFI_ENABLED is @hide (API 30+)

    /** When [pause] last turned debugging off (elapsedRealtime), 0 = not in this process. */
    @Volatile var offAt = 0L; private set

    /**
     * adbd keeps running ~1 s after adb_enabled goes to 0 (Poco, HyperOS: setting 12.128, AppSealing kill 50052 at 12.790,
     * adbd gone 13.228). A bank opened in that gap still sees debugging, so the launcher holds the start until this says
     * adbd is down. Unknown (property not readable by an app on some builds): a fixed [ADBD_UNKNOWN_MS].
     */
    fun adbdUp(): Boolean? = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java, String::class.java)
            .invoke(null, "init.svc.adbd", "") as String
    }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { it == "running" || it == "restarting" }

    const val ADBD_POLL_MS = 100L
    const val ADBD_WAIT_MAX_MS = 3000L
    const val ADBD_UNKNOWN_MS = 1600L  // measured gap 1.1 s (Poco) + margin
    const val ADBD_MARGIN_MS = 250L   // after adbd exits, the USB function switch still settles

    /**
     * Run [go] on [h] once debugging that [pause] just turned off is really off: at once when nothing was turned off in
     * the last few seconds, else when adbd is down (+ a margin), at most [ADBD_WAIT_MAX_MS]; never sooner than [minMs].
     */
    fun afterOff(h: android.os.Handler, minMs: Long = 0L, go: () -> Unit) {
        val t0 = android.os.SystemClock.elapsedRealtime()
        val fresh = offAt != 0L && t0 - offAt < ADBD_WAIT_MAX_MS
        if (!fresh) { if (minMs > 0) h.postDelayed(go, minMs) else go(); return }
        val tick = object : Runnable { override fun run() {
            val el = android.os.SystemClock.elapsedRealtime() - t0
            val up = adbdUp()
            val cap = if (up == null) ADBD_UNKNOWN_MS else ADBD_WAIT_MAX_MS   // can't read the property: a fixed wait
            if (el < cap && up != false) { h.postDelayed(this, ADBD_POLL_MS); return }
            val rest = maxOf(minMs - el, if (up == false) ADBD_MARGIN_MS else 0L)
            if (rest > 0) h.postDelayed(go, rest) else go()
        } }
        tick.run()
    }

    private fun global(ctx: Context, key: String) = runCatching { Settings.Global.getInt(ctx.contentResolver, key, 0) == 1 }.getOrDefault(false)
    private fun canWrite(ctx: Context) = ctx.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED

    fun canOff(ctx: Context) = canWrite(ctx) || Policy.isOwner(ctx)

    /** {"on", "adb", "canOff"}: adb is USB or (API 30+) wireless debugging. */
    fun state(ctx: Context): JSONObject = JSONObject()
        .put("on", global(ctx, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED))
        .put("adb", global(ctx, Settings.Global.ADB_ENABLED) || (Build.VERSION.SDK_INT >= 30 && global(ctx, ADB_WIFI)))
        .put("canOff", canOff(ctx))
        .put("canOn", canOn(ctx))

    /** "devOn" works only with WRITE_SECURE_SETTINGS and while no debugging restriction (rule noDebug) is set. */
    fun canOn(ctx: Context) = canWrite(ctx) && !blocked(ctx)
    private fun blocked(ctx: Context) = runCatching {
        (ctx.getSystemService(Context.USER_SERVICE) as UserManager).hasUserRestriction(UserManager.DISALLOW_DEBUGGING_FEATURES) }.getOrDefault(false)

    /** Developer options, then USB debugging (the parent's way back after "devOff"). "ok", "not allowed", "blocked" or "failed: ...". */
    fun on(ctx: Context): String {
        if (!canWrite(ctx)) return "not allowed"
        if (blocked(ctx)) return "blocked"
        var err: String? = null
        for (k in listOf(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, Settings.Global.ADB_ENABLED))
            runCatching { Settings.Global.putInt(ctx.contentResolver, k, 1) }.onFailure { err = it.javaClass.simpleName }
        val ok = global(ctx, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED) && global(ctx, Settings.Global.ADB_ENABLED)
        return (if (ok) "ok" else "failed: " + (err ?: "still off")).also { if (it != "ok") Log.w("KidGuard", "devOn: $it") }
    }

    // ---- bank apps (owner 2026-11-11, My MobiFone error 50052 "USB debugging on"): with the accessibility pause
    // (AccPause) debugging goes off too, and exactly what was on comes back when the service resumes.

    /**
     * An app on `rules.devPause` is opening (owner 2026-11-11: split from the accessibility pause, most banks only check
     * accessibility, My MobiFone checks debugging): switch debugging off, remember what was on (Store.devPaused).
     * True = turned off just now (the caller holds the start: [afterOff]); a toast says the app opens in a moment.
     */
    @Synchronized fun pause(ctx: Context, pkg: String): Boolean {
        if (Flavor.HOME) return false
        val s = Store.of(ctx)
        if (pkg !in s.rules.devPause) return false
        if (BasicLogic.bounceReason(pkg, ctx.packageName, Acc.blocked, Acc.prot) != null) return false   // never for a blocked app
        s.devApps = s.devApps + pkg; s.devLeftAt = 0L
        if (s.devPaused != 0 || !canOff(ctx)) return false
        val mask = DevLogic.mask(global(ctx, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED), global(ctx, Settings.Global.ADB_ENABLED),
            Build.VERSION.SDK_INT >= 30 && global(ctx, ADB_WIFI))
        if (mask == 0) return false
        s.devPaused = mask
        val r = off(ctx)
        if (r != "ok") { Log.w("KidGuard", "bank devOff: $r"); return false }
        offAt = android.os.SystemClock.elapsedRealtime()
        val label = runCatching { val pm = ctx.packageManager; pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
        runCatching { s.log(R.string.log_dev_paused) }
        runCatching { Toasts.show(ctx.applicationContext, ctx.getString(R.string.dev_paused_toast, label), true) }
        return true
    }

    /** Guard tick: the same "left for a while" timing as the other pauses, then debugging comes back. */
    @Synchronized fun tick(ctx: Context, current: String?, now: Long) {
        val s = Store.of(ctx)
        val apps = s.devApps
        if (apps.isEmpty()) { if (s.devPaused != 0) bankResume(ctx); return }   // from a version where accessibility drove it
        val wait = if (Policy.isOwner(ctx)) AccPauseLogic.RESUME_MS else AccPauseLogic.RESUME_BASIC_MS
        val (leftAt, back) = AccPauseLogic.step(current, s.rules.devPause + apps, s.devLeftAt, now, wait)
        if (back) { s.devApps = emptySet(); s.devLeftAt = 0L; bankResume(ctx) } else if (leftAt != s.devLeftAt) s.devLeftAt = leftAt
    }

    /** Release: put debugging back as it was and forget the pause. */
    fun clearPause(ctx: Context) { val s = Store.of(ctx); s.devApps = emptySet(); s.devLeftAt = 0L; runCatching { bankResume(ctx) } }

    /** The pause ended (or release): put back what [pause] turned off, unless debugging got forbidden meanwhile. */
    fun bankResume(ctx: Context) {
        val s = Store.of(ctx)
        val mask = s.devPaused
        if (mask == 0) return
        s.devPaused = 0
        if (blocked(ctx)) return   // debugging got forbidden meanwhile (rule noDebug = DISALLOW_DEBUGGING_FEATURES, owner mode only): stay off
        val can = canWrite(ctx); val owner = Policy.isOwner(ctx)
        for (k in DevLogic.restoreKeys(mask, Build.VERSION.SDK_INT >= 30)) {
            val key = if (k == "adb") Settings.Global.ADB_ENABLED else if (k == "dev") Settings.Global.DEVELOPMENT_SETTINGS_ENABLED else ADB_WIFI
            val ok = can && runCatching { Settings.Global.putInt(ctx.contentResolver, key, 1) }.getOrDefault(false)
            if (!ok && owner && k == "adb") runCatching { Policy.dpm(ctx).setGlobalSetting(Policy.admin(ctx), key, "1") }
        }
        runCatching { s.log(R.string.log_dev_restored) }
    }

    /** Wireless debugging, USB debugging, then Developer options itself. Returns "ok", "not allowed" or "failed: ...". */
    fun off(ctx: Context): String {
        val can = canWrite(ctx); val owner = Policy.isOwner(ctx)
        if (!can && !owner) return DevLogic.verdict(false, false, null)
        val keys = buildList { if (Build.VERSION.SDK_INT >= 30) add(ADB_WIFI); add(Settings.Global.ADB_ENABLED); add(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED) }
        var err: String? = null
        for (k in keys) {
            val okPut = can && runCatching { Settings.Global.putInt(ctx.contentResolver, k, 0) }.onFailure { err = it.javaClass.simpleName }.getOrDefault(false)
            // fallback: Device Owner may write ADB_ENABLED (the others throw SecurityException; caught)
            if (!okPut && owner) runCatching { Policy.dpm(ctx).setGlobalSetting(Policy.admin(ctx), k, "0") }.onFailure { err = it.javaClass.simpleName }
        }
        val stillOn = global(ctx, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED) || global(ctx, Settings.Global.ADB_ENABLED) ||
            (Build.VERSION.SDK_INT >= 30 && global(ctx, ADB_WIFI))
        return DevLogic.verdict(true, stillOn, err).also { if (it != "ok") Log.w("KidGuard", "devOff: $it") }
    }
}

/** Pure part of [DevOpts] (unit-tested). */
object DevLogic {
    /** What was on before a bank pause: bit 1 Developer options, 2 USB debugging, 4 wireless debugging. */
    fun mask(dev: Boolean, adb: Boolean, wifi: Boolean): Int = (if (dev) 1 else 0) or (if (adb) 2 else 0) or (if (wifi) 4 else 0)

    /** Settings to switch back on, in order (Developer options first: debugging needs it). */
    fun restoreKeys(mask: Int, api30: Boolean): List<String> = buildList {
        if (mask and 1 != 0) add("dev"); if (mask and 2 != 0) add("adb"); if (api30 && mask and 4 != 0) add("wifi")
    }

    fun verdict(canOff: Boolean, stillOn: Boolean, err: String?): String = when {
        !canOff -> "not allowed"
        !stillOn -> "ok"
        else -> "failed: " + (err ?: "still on")
    }
}
