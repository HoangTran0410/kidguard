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

    /** A parent command touched adb (adbTcp / devOn / adbShell): try again on the next tick. */
    fun soon() { lastTry = 0L }

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
        val cmds = missing(ctx)
        // nothing missing / no adb link yet: look again in 2 min, not 30 (the slot is for real attempts; audit 2026-11-13)
        if (cmds.isEmpty() || !SelfAdb.canOpen(ctx) && !SelfAdb.ready(ctx)) { lastTry = now - EVERY_MS + 2 * 60_000L; return }
        lastTry = now
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
