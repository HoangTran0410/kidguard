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

