package dev.kidguard

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Resources
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject

/**
 * A few phone settings the parent changes remotely (command `setting`, state `sys`): volumes, brightness, screen
 * timeout, Do Not Disturb. Everything else goes through the Screen tab's remote control.
 *  - volume: AudioManager, no permission (ring may be held by DND).
 *  - brightness / auto / timeout: DevicePolicyManager.setSystemSetting (Android 9+, Device Owner), else Settings.System
 *    with WRITE_SECURE_SETTINGS (from setup; SettingsProvider lets it write System too) or the WRITE_SETTINGS app-op.
 *  - DND: NotificationManager.setInterruptionFilter, needs policy access (setup: `cmd notification allow_dnd`).
 * State is read at most once a minute (the guard loop builds state every 10 s) and right after a change.
 */
object Sys {
    private const val TTL = 60_000L
    @Volatile private var cache: JSONObject? = null
    @Volatile private var at = 0L
    /** The last `setting` command per key: {key: {id, res, t}}, so a relay parent learns the result of a queued one. */
    private val last = JSONObject()

    private val STREAMS = mapOf(SysLogic.VOL_RING to AudioManager.STREAM_RING, SysLogic.VOL_MEDIA to AudioManager.STREAM_MUSIC,
        SysLogic.VOL_ALARM to AudioManager.STREAM_ALARM)

    fun invalidate() { at = 0 }

    fun state(ctx: Context): JSONObject {
        val now = System.currentTimeMillis()
        cache?.let { if (now - at < TTL) return it }
        val s = runCatching { read(ctx) }.getOrElse { JSONObject().put("error", it.javaClass.simpleName) }
        synchronized(last) { s.put("last", JSONObject(last.toString())) }
        cache = s; at = now
        return s
    }

    private fun read(ctx: Context): JSONObject {
        val am = ctx.getSystemService(AudioManager::class.java)
        val cr = ctx.contentResolver
        val vol = JSONObject()
        if (am != null) for ((k, st) in STREAMS) vol.put(k.removePrefix("vol."), JSONArray().put(am.getStreamVolume(st)).put(am.getStreamMaxVolume(st)))
        val b = runCatching { Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS) }.getOrNull()
        val mode = runCatching { Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE) }.getOrNull()
        val to = runCatching { Settings.System.getInt(cr, Settings.System.SCREEN_OFF_TIMEOUT) }.getOrNull()
        val filter = ctx.getSystemService(NotificationManager::class.java)?.currentInterruptionFilter
        val dnd = filter?.takeIf { it != NotificationManager.INTERRUPTION_FILTER_UNKNOWN }?.let { it != NotificationManager.INTERRUPTION_FILTER_ALL }
        val canSys = canWriteSystem(ctx)
        return JSONObject()
            .put("vol", vol)
            .put("bright", if (b == null) JSONObject.NULL else JSONArray().put(b).put(maxOf(brightMax(), b)))
            .put("autoBright", mode?.let { it == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC } ?: JSONObject.NULL)
            .put("timeoutMs", to ?: JSONObject.NULL)
            .put("dnd", dnd ?: JSONObject.NULL)
            .put("can", JSONObject().put("vol", am != null && !am.isVolumeFixed).put("bright", canSys && b != null)
                .put("timeout", canSys && to != null).put("dnd", dndAccess(ctx)))
    }

    /** OEMs (Xiaomi, some Oppo) use a bigger brightness range than 0..255; the framework config says which. */
    internal fun brightMax(): Int = runCatching {
        val r = Resources.getSystem(); val id = r.getIdentifier("config_screenBrightnessSettingMaximum", "integer", "android")
        if (id != 0) r.getInteger(id) else 255
    }.getOrDefault(255).let { if (it in 1..65535) it else 255 }

    private fun ownerP(ctx: Context) = Build.VERSION.SDK_INT >= 28 && Policy.isOwner(ctx)
    internal fun wss(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
    internal fun canWriteSystem(ctx: Context) = ownerP(ctx) || wss(ctx) || Settings.System.canWrite(ctx)
    internal fun dndAccess(ctx: Context) = ctx.getSystemService(NotificationManager::class.java)?.isNotificationPolicyAccessGranted == true

    /** Device Owner API first (9+), then a plain write; true when the value reads back. */
    private fun writeSystem(ctx: Context, key: String, v: Int): Boolean {
        val cr = ctx.contentResolver
        if (ownerP(ctx)) runCatching { Policy.dpm(ctx).setSystemSetting(Policy.admin(ctx), key, v.toString()) }
        if (runCatching { Settings.System.getInt(cr, key) }.getOrNull() == v) return true
        if (wss(ctx) || Settings.System.canWrite(ctx)) runCatching { Settings.System.putInt(cr, key, v) }
        return runCatching { Settings.System.getInt(cr, key) }.getOrNull() == v
    }

    /** The `setting` command. Result "ok" or a short reason; logs one line per change. */
    fun set(ctx: Context, store: Store, c: JSONObject): String {
        val key = c.optString("key")
        val s = SysLogic.parse(key, c.opt("value"))
        val res = if (s == null) SysLogic.error(key) else runCatching { apply(ctx, store, s) }.getOrElse { if (it is SecurityException) "no permission" else "failed" }
        if (key in SysLogic.KEYS) synchronized(last) { last.put(key, JSONObject().put("id", c.optString("id")).put("res", res).put("t", System.currentTimeMillis())) }
        invalidate()
        return res
    }

    private fun apply(ctx: Context, store: Store, s: SysLogic.Setting): String {
        when (s.key) {
            in SysLogic.VOLS -> {
                val am = ctx.getSystemService(AudioManager::class.java) ?: return "failed"
                if (am.isVolumeFixed) return "volume fixed"
                val st = STREAMS.getValue(s.key)
                val max = am.getStreamMaxVolume(st)
                val v = SysLogic.clamp(s, max)
                val dndOn = ctx.getSystemService(NotificationManager::class.java)?.currentInterruptionFilter.let { it != null && it > NotificationManager.INTERRUPTION_FILTER_ALL }
                try { am.setStreamVolume(st, v, 0) } catch (e: SecurityException) { return if (dndOn) "blocked by dnd" else "no permission" }
                if (am.getStreamVolume(st) != v) return if (dndOn && st == AudioManager.STREAM_RING) "blocked by dnd" else "not applied"
                val name = store.str(when (s.key) { SysLogic.VOL_RING -> R.string.sys_ring; SysLogic.VOL_MEDIA -> R.string.sys_media; else -> R.string.sys_alarm })
                store.log(R.string.log_setting_vol, name, v, max)
            }
            SysLogic.BRIGHT -> {
                if (!canWriteSystem(ctx)) return "no permission"
                val max = maxOf(brightMax(), runCatching { Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS) }.getOrDefault(0))
                val v = SysLogic.clamp(s, max)
                if (!writeSystem(ctx, Settings.System.SCREEN_BRIGHTNESS, v)) return "not applied"
                store.log(R.string.log_setting_bright, (v * 100 + max / 2) / max)
            }
            SysLogic.AUTO -> {
                if (!canWriteSystem(ctx)) return "no permission"
                val v = if (s.on) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                if (!writeSystem(ctx, Settings.System.SCREEN_BRIGHTNESS_MODE, v)) return "not applied"
                store.log(if (s.on) R.string.log_setting_auto_on else R.string.log_setting_auto_off)
            }
            SysLogic.TIMEOUT -> {
                if (!canWriteSystem(ctx)) return "no permission"
                val v = SysLogic.clamp(s, 0)
                if (!writeSystem(ctx, Settings.System.SCREEN_OFF_TIMEOUT, v)) return "not applied"
                store.log(R.string.log_setting_timeout, v / 1000)
            }
            SysLogic.DND -> {
                val nm = ctx.getSystemService(NotificationManager::class.java) ?: return "failed"
                if (!nm.isNotificationPolicyAccessGranted) return "no dnd access"
                nm.setInterruptionFilter(if (s.on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
                store.log(if (s.on) R.string.log_setting_dnd_on else R.string.log_setting_dnd_off)
            }
            else -> return "bad key"
        }
        return "ok"
    }
}
