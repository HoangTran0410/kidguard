package dev.kidguard

import android.content.Context
import android.os.Build
import android.util.Log

/**
 * Self-heal grants (owner 2026-11-11: "what else can KidGuard do itself once it has the rights, to cut the user's steps").
 * Once KidGuard's own adb is paired ([SelfAdb]), a Guard tick re-grants what the checklist would otherwise send the child
 * to Settings for: usage access, notification listener, battery whitelist, location (fine + background), notifications.
 * Same commands as the setup ([SelfSetup], scripts/setup.sh). Only the missing ones, at most every [EVERY_MS]; nothing
 * when all are there (no adb window). Not on ColorOS 5 without "Disable permission monitoring" (fails, retried later).
 */
object AutoGrant {
    private const val EVERY_MS = 30 * 60_000L
    @Volatile private var lastTry = 0L
    @Volatile private var running = false

    /** The shell commands for what is missing now (empty = all granted). */
    fun missing(ctx: Context): List<String> {
        val p = ctx.packageName
        return buildList {
            if (!Usage.granted(ctx)) add("appops set $p GET_USAGE_STATS allow")
            if (!NotifDots.granted(ctx, fresh = true)) add("cmd notification allow_listener $p/$p.NotifDots")
            if (!Basic.batteryOk(ctx)) add("dumpsys deviceidle whitelist +$p")
            if (!Basic.locationOk(ctx)) {
                add("pm grant $p android.permission.ACCESS_FINE_LOCATION"); add("pm grant $p android.permission.ACCESS_COARSE_LOCATION")
                if (Build.VERSION.SDK_INT >= 29) add("pm grant $p android.permission.ACCESS_BACKGROUND_LOCATION")
            }
            if (!Basic.notifyOk(ctx)) add("pm grant $p android.permission.POST_NOTIFICATIONS")
        }
    }

    fun tick(ctx: Context, now: Long) {
        if (Flavor.HOME || running || now - lastTry < EVERY_MS || SelfSetup.running || !SelfAdb.hasKey(ctx)) return
        lastTry = now
        val cmds = missing(ctx)
        if (cmds.isEmpty() || !SelfAdb.canOpen(ctx) && !SelfAdb.ready(ctx)) return
        running = true
        val app = ctx.applicationContext
        Thread({
            try {
                val out = SelfAdb.run(app, cmds.joinToString("; "))
                Log.i("KidGuard", "auto-grant ${cmds.size}: ${out?.take(200)}")
                val left = missing(app).size
                if (left < cmds.size) runCatching { Store.of(app).log(R.string.log_autogrant, (cmds.size - left).toString()) }
                if (left > 0) lastTry = now + 6 * 3_600_000L - EVERY_MS   // some won't take (OEM, ColorOS monitoring): back off to 6 h
                Guard.running?.kick()
            } finally { running = false }
        }, "auto-grant").start()
    }
}
