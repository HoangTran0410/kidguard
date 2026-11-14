package dev.kidguard

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * "N things are off" + one Fix (command `fixPerms`, docs/protocol.md). [missing] = permissions KidGuard needs that the child
 * phone lost (state `missing`); [fix] first gets them back itself (Device Owner grants, our accessibility via
 * WRITE_SECURE_SETTINGS, the setup's grants over self-adb = [AutoGrant]) and then, for what is left, opens the exact
 * Settings screen on the phone (background start: Device Owner / overlay) and always leaves a notification that opens it.
 * Sets nothing durable: [release] only removes the notification (the grants are the setup's own).
 */
object FixPerms {
    private const val NOTE = 8
    @Volatile private var busy = false
    @Volatile private var at = 0L
    @Volatile private var fixed: List<String> = emptyList()   // got back by KidGuard itself in the last Fix
    @Volatile private var tap: List<String> = emptyList()     // still needs a tap on the phone after the last Fix
    @Volatile private var noted = false

    /** Ids in display order: admin acc (basic mode only) usage listener location notify battery. Empty = all fine. */
    fun missing(ctx: Context): List<String> {
        if (Flavor.HOME) return emptyList()
        val owner = Policy.isOwner(ctx)
        return buildList {
            if (!owner && !Basic.adminActive(ctx)) add("admin")
            if (!owner && !Acc.isOn(ctx)) add("acc")
            if (!Usage.granted(ctx)) add("usage")
            if (!NotifDots.granted(ctx, fresh = true)) add("listener")
            if (!Basic.locationOk(ctx)) add("location")
            if (!Basic.notifyOk(ctx)) add("notify")
            if (!Basic.batteryOk(ctx)) add("battery")
        }
    }

    /** state `fixPerms`: the last Fix (docs/protocol.md). Also drops our notification once nothing is missing. */
    fun state(ctx: Context, miss: List<String>): JSONObject {
        if (miss.isEmpty() && noted) { noted = false; runCatching { ctx.getSystemService(NotificationManager::class.java).cancel(NOTE) } }
        return JSONObject().put("busy", busy).put("at", at).put("fixed", JSONArray(fixed)).put("tap", JSONArray(tap.filter { it in miss }))
    }

    /** Answer `ok` (nothing left), `working` (self-adb grants run in the background; see state `fixPerms`) or `none`. */
    fun fix(ctx: Context): String {
        if (Flavor.HOME) return "none"
        if (busy) return "working"
        val before = missing(ctx)
        if (before.isEmpty()) return "none"
        at = System.currentTimeMillis(); fixed = emptyList(); tap = before
        busy = true
        val app = ctx.applicationContext
        Thread({
            try {
                selfGrants(app)
                var rest = missing(app)
                if (rest.isNotEmpty() && (SelfAdb.canOpen(app) || SelfAdb.ready(app))) {
                    val cmds = AutoGrant.missing(app)
                    if (cmds.isNotEmpty()) runCatching { SelfAdb.run(app, cmds.joinToString("; ")) }.onFailure { Log.w("KidGuard", "fixPerms adb", it) }
                    Thread.sleep(600)
                    rest = missing(app)
                }
                fixed = before - rest.toSet(); tap = rest
                AutoGrant.soon()
                if (rest.isNotEmpty()) ask(app, rest)
                runCatching { Store.of(app).log(R.string.log_fixperms, fixed.size.toString(), rest.size.toString()) }
            } catch (e: Exception) { Log.w("KidGuard", "fixPerms", e) }
            finally { busy = false; Guard.running?.kick() }
        }, "fix-perms").start()
        return "working"
    }

    /** What the app can do alone: Device Owner runtime grants, our accessibility service back on (needs WRITE_SECURE_SETTINGS). */
    private fun selfGrants(ctx: Context) {
        if (Policy.isOwner(ctx)) {
            val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 29) perms += Manifest.permission.ACCESS_BACKGROUND_LOCATION
            if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
            for (p in perms) runCatching {
                Policy.dpm(ctx).setPermissionGrantState(Policy.admin(ctx), ctx.packageName, p, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
            }.onFailure { Log.w("KidGuard", "fixPerms grant $p", it) }
        }
        if (!Acc.isOn(ctx)) runCatching { Acc.ensureEnabled(ctx) }
    }

    /** The Settings page for [id]; the first one the phone can open. */
    private fun intent(ctx: Context, id: String): Intent {
        val pkg = Uri.parse("package:${ctx.packageName}")
        return when (id) {
            "admin" -> Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, Policy.admin(ctx))
                .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, ctx.getString(R.string.basic_admin_expl))
            "acc" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            "usage" -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, pkg)
            "listener" -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            "battery" -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg)
            else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)   // location, notify: Permissions
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun name(ctx: Context, id: String): String {
        val r = ctx.resources.getIdentifier("feat_cap_$id", "string", ctx.packageName)
        return if (r != 0) ctx.getString(r) else id
    }

    /** Opens the first missing screen now (when the phone lets us start it from the background) and leaves a tap-to-open notification. */
    private fun ask(ctx: Context, rest: List<String>) = runCatching {
        val first = intent(ctx, rest[0])
        if (AccPause.canReopen(ctx)) runCatching { ctx.startActivity(first) }
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("fix", ctx.getString(R.string.ch_fix), NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(ctx, NOTE, first, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = ctx.getString(R.string.fix_note, rest.joinToString(", ") { name(ctx, it) })
        nm.notify(NOTE, Notification(ctx, text, open))
        noted = true
    }

    @Suppress("FunctionName")
    private fun Notification(ctx: Context, text: String, open: PendingIntent) = android.app.Notification.Builder(ctx, "fix")
        .setSmallIcon(android.R.drawable.ic_dialog_alert).setContentTitle("KidGuard").setContentText(text)
        .setStyle(android.app.Notification.BigTextStyle().bigText(text)).setContentIntent(open).setAutoCancel(true).build()

    /** [Policy.release] / [Guard.release] hook: nothing durable is set here, only our notification goes. */
    fun release(ctx: Context) { noted = false; runCatching { ctx.getSystemService(NotificationManager::class.java).cancel(NOTE) } }
}
