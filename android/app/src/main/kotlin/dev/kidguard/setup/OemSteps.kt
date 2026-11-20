package dev.kidguard

/**
 * Per-brand checklist of what the phone's own skin needs before KidGuard keeps running (pure, unit-tested).
 * The caller ([OemSteps.env] in FeaturesSheet) reads Build + system props; [forEnv] only decides which steps and which
 * Settings intents. Sources: docs/decisions/platforms.md, research 2026-11-20.
 */
object OemSteps {
    enum class Check { NONE, BATTERY_OPT, RESTRICTED, AUTO_BLOCKER }

    /** A Settings screen to try: [action] alone, or [pkg]/[cls] component. */
    data class Go(val action: String? = null, val pkg: String? = null, val cls: String? = null)

    /** [id] doubles as the string key suffix (`oem_<id>`, `oem_<id>_d`). [dynamic] = "autostart" / "battery": the caller
     *  prepends the page it resolves itself (SetupSteps.autostartIntent / miuiBg). */
    data class Step(val id: String, val check: Check = Check.NONE, val go: List<Go> = emptyList(), val dynamic: Boolean = false)

    data class Env(
        val manufacturer: String, val brand: String, val sdk: Int,
        val miui: Boolean = false,          // ro.miui.ui.version.name present (MIUI and HyperOS)
        val oneUi: Int = 0,                 // ro.build.version.oneui, e.g. 60100 = One UI 6.1; 0 = none
        val fromStore: Boolean = true,      // installed by a store (restricted settings only hit sideloads)
    )

    private val APP_INFO = Go(action = "android.settings.APPLICATION_DETAILS_SETTINGS")
    private val BATTERY = Go(action = "android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS")
    private val DEV_OPTS = Go(action = "android.settings.APPLICATION_DEVELOPMENT_SETTINGS")

    /** Lower-cased "manufacturer brand" used to pick the family. */
    private fun b(e: Env) = (e.manufacturer + " " + e.brand).lowercase()
    private fun has(e: Env, vararg k: String) = k.any { it in b(e) }

    fun family(e: Env): String = when {
        has(e, "xiaomi", "redmi", "poco") || e.miui -> "xiaomi"
        has(e, "samsung") -> "samsung"
        has(e, "oppo", "realme", "oneplus") -> "oppo"
        has(e, "vivo", "iqoo") -> "vivo"
        has(e, "huawei", "honor") -> "huawei"
        has(e, "tecno", "infinix", "itel", "transsion") -> "transsion"
        else -> ""
    }

    fun forEnv(e: Env): List<Step> {
        val out = mutableListOf<Step>()
        // sideloaded builds on Android 13+ cannot get accessibility until "Allow restricted settings"
        if (e.sdk >= 33 && !e.fromStore) out += Step("restricted", Check.RESTRICTED, listOf(APP_INFO))
        when (family(e)) {
            "xiaomi" -> {
                out += Step("xi_usb", go = listOf(DEV_OPTS))
                out += Step("autostart", dynamic = true, go = listOf(APP_INFO))
                out += Step("battery", Check.BATTERY_OPT, listOf(BATTERY, APP_INFO), dynamic = true)
                out += Step("xi_space")
            }
            "samsung" -> {
                if (e.oneUi >= 60100) out += Step("sm_blocker", Check.AUTO_BLOCKER, listOf(
                    Go(pkg = "com.samsung.android.settings.autoblocker", cls = "com.samsung.android.settings.autoblocker.AutoBlockerActivity"),
                    Go(action = "android.settings.SECURITY_SETTINGS")))
                out += Step("battery", Check.BATTERY_OPT, listOf(BATTERY, APP_INFO))
                out += Step("sm_sleep", go = listOf(
                    Go(pkg = "com.samsung.android.lool", cls = "com.samsung.android.sm.battery.ui.BatteryActivity"), APP_INFO))
            }
            "oppo" -> {
                out += Step("op_perm", go = listOf(DEV_OPTS))
                out += Step("autostart", dynamic = true, go = listOf(APP_INFO))
                out += Step("battery", Check.BATTERY_OPT, listOf(BATTERY, APP_INFO))
            }
            "vivo" -> {
                out += Step("autostart", dynamic = true, go = listOf(
                    Go(pkg = "com.vivo.permissionmanager", cls = "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"), APP_INFO))
                out += Step("vi_power", go = listOf(
                    Go(pkg = "com.vivo.abe", cls = "com.vivo.applicationbehaviormanager.app.BackgroundPowerActivity"), BATTERY, APP_INFO))
                out += Step("vi_account")
            }
            "huawei" -> {
                out += Step("hw_launch", go = listOf(
                    Go(pkg = "com.huawei.systemmanager", cls = "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                    Go(pkg = "com.huawei.systemmanager", cls = "com.huawei.systemmanager.optimize.process.ProtectActivity"), APP_INFO))
            }
            "transsion" -> {
                out += Step("autostart", dynamic = true, go = listOf(APP_INFO))
                out += Step("ts_master", go = listOf(Go(pkg = "com.transsion.phonemaster", cls = "com.cyin.himgr.mainhome.MainHomeActivity"), BATTERY, APP_INFO))
            }
        }
        // Android 15 Private space: an app in it is invisible to the parent page and may be stopped
        if (e.sdk >= 35) out += Step("private_space")
        return out
    }

    /** Steps that can't be read back are never "pending": only checked ones that failed, in [done]'s terms. */
    fun pending(steps: List<Step>, done: (Step) -> Boolean?): List<String> =
        steps.filter { it.check != Check.NONE && done(it) == false }.map { it.id }
}
