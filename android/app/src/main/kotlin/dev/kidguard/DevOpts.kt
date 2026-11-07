package dev.kidguard

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import org.json.JSONObject

/**
 * Developer options / USB + wireless debugging. Banking apps refuse to open while these are on,
 * so the parent can turn them off ("devOff"). Needs WRITE_SECURE_SETTINGS (granted at setup) or Device Owner.
 */
object DevOpts {
    private const val ADB_WIFI = "adb_wifi_enabled"   // Settings.Global.ADB_WIFI_ENABLED is @hide (API 30+)

    private fun global(ctx: Context, key: String) = runCatching { Settings.Global.getInt(ctx.contentResolver, key, 0) == 1 }.getOrDefault(false)
    private fun canWrite(ctx: Context) = ctx.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED

    fun canOff(ctx: Context) = canWrite(ctx) || Policy.isOwner(ctx)

    /** {"on", "adb", "canOff"}: adb is USB or (API 30+) wireless debugging. */
    fun state(ctx: Context): JSONObject = JSONObject()
        .put("on", global(ctx, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED))
        .put("adb", global(ctx, Settings.Global.ADB_ENABLED) || (Build.VERSION.SDK_INT >= 30 && global(ctx, ADB_WIFI)))
        .put("canOff", canOff(ctx))

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
    fun verdict(canOff: Boolean, stillOn: Boolean, err: String?): String = when {
        !canOff -> "not allowed"
        !stillOn -> "ok"
        else -> "failed: " + (err ?: "still on")
    }
}
