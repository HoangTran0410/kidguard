package dev.kidguard

import android.Manifest
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import org.json.JSONObject

/** Basic mode (no Device Owner): mode detection and the child-screen checklist. Pure rules are in [BasicLogic]. docs/protocol.md "v0.7". */
object Basic {
    fun adminActive(ctx: Context): Boolean = runCatching { Policy.dpm(ctx).isAdminActive(Policy.admin(ctx)) }.getOrDefault(false)

    /** Blocking by bouncing is in force: not Device Owner, admin active, not released. (Accessibility is checked by the caller.) */
    fun enforcing(ctx: Context): Boolean = !Policy.isOwner(ctx) && adminActive(ctx) && !Store.of(ctx).released

    /** "owner" | "basic" | "setup" (docs/protocol.md). */
    fun mode(ctx: Context): String = when {
        Policy.isOwner(ctx) -> "owner"
        adminActive(ctx) && Acc.isOn(ctx) -> "basic"
        else -> "setup"
    }

    private fun granted(ctx: Context, p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    fun locationOk(ctx: Context) = granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION) &&
        (Build.VERSION.SDK_INT < 29 || granted(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION))
    /** Device location switch. LocationManager.isLocationEnabled is API 28+; older phones use the secure setting. */
    fun locationOn(ctx: Context): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= 28) ctx.getSystemService(android.location.LocationManager::class.java).isLocationEnabled
        else Settings.Secure.getInt(ctx.contentResolver, Settings.Secure.LOCATION_MODE, Settings.Secure.LOCATION_MODE_OFF) != Settings.Secure.LOCATION_MODE_OFF
    }.getOrDefault(false)
    fun notifyOk(ctx: Context) = Build.VERSION.SDK_INT < 33 || granted(ctx, Manifest.permission.POST_NOTIFICATIONS)
    fun batteryOk(ctx: Context) = runCatching { ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName) }.getOrDefault(false)

    /** What the checklist still misses: each key true = OK, as in state.basic (docs/protocol.md). */
    fun status(ctx: Context): JSONObject = JSONObject()
        .put("admin", adminActive(ctx)).put("acc", Acc.isOn(ctx)).put("usage", Usage.granted(ctx))
        .put("location", locationOk(ctx)).put("notify", notifyOk(ctx)).put("battery", batteryOk(ctx))
}

/** Child screen card "Cài nhanh không cần máy tính": one big button per missing item, rebuilt on every resume. */
class BasicChecklist(private val act: Activity, private val ui: Ui, private val autostart: () -> Unit) {
    val box: LinearLayout = ui.cardBox().apply {
        addView(ui.label(act.getString(R.string.basic_title), 17f, ui.text, true))
        addView(ui.label(act.getString(R.string.basic_intro), 14f))
    }
    private val items = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    init { box.addView(items) }

    private fun open(vararg i: Intent) { for (x in i) if (runCatching { act.startActivity(x.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)) return }

    /** True when something is still missing (the card stays visible). */
    private var lastKey = ""
    private var lastMissing = false

    fun update(): Boolean {
        val c = act
        // called every 3 s: rebuild only when a step changed, or a tap on a button being redrawn gets lost
        val key = listOf(Basic.adminActive(c), Acc.isOn(c), Usage.granted(c), Basic.locationOk(c),
            c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED,
            Basic.notifyOk(c), Basic.batteryOk(c)).joinToString()
        if (key == lastKey) return lastMissing
        lastKey = key
        items.removeAllViews()
        fun add(t: Int, why: Int, go: () -> Unit) {
            items.addView(ui.button(c.getString(t), null, true) { runCatching { go() } })
            items.addView(ui.label(c.getString(why), 13f).apply { setPadding(ui.dp(4), ui.dp(4), ui.dp(4), 0) })
        }
        var missing = false
        if (!Basic.adminActive(c)) { missing = true
            add(R.string.basic_admin, R.string.basic_admin_d) {
                c.startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, Policy.admin(c))
                    .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, c.getString(R.string.basic_admin_expl)))
            }
        }
        if (!Acc.isOn(c)) { missing = true
            add(R.string.basic_acc, R.string.basic_acc_d) {
                c.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        if (!Usage.granted(c)) { missing = true
            add(R.string.basic_usage, R.string.basic_usage_d) {
                open(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:${c.packageName}")), Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
        }
        if (!Basic.locationOk(c)) { missing = true
            val fine = c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (!fine) add(R.string.basic_loc, R.string.basic_loc_d) {
                c.requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 41)
            } else add(R.string.basic_loc_bg, R.string.basic_loc_bg_d) {
                if (Build.VERSION.SDK_INT >= 30) {
                    // Android 11+ sends the user to the app's location page itself
                    open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${c.packageName}")))
                    c.requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 42)
                } else c.requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 42)
            }
        }
        if (!Basic.notifyOk(c)) { missing = true
            add(R.string.basic_notif, R.string.basic_notif_d) {
                c.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 43)
            }
        }
        if (!Basic.batteryOk(c)) { missing = true
            add(R.string.basic_battery, R.string.basic_battery_d) {
                open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${c.packageName}")),
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
            items.addView(ui.button(c.getString(R.string.basic_autostart), null, false) { runCatching { autostart() } })
        }
        if (missing) items.addView(ui.label(c.getString(R.string.basic_play_protect), 13f).apply { setPadding(ui.dp(4), ui.dp(12), ui.dp(4), 0) })
        box.visibility = if (missing) View.VISIBLE else View.GONE
        lastMissing = missing
        return missing
    }
}
