package dev.kidguard

import android.app.Activity
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/** Android side of [OemSteps]: reads this phone, answers the checks, opens the Settings page of a step. */
object OemCheck {
    private fun prop(k: String): String = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, k) as String
    }.getOrDefault("")

    private val STORES = setOf("com.android.vending", "com.sec.android.app.samsungapps", "com.xiaomi.mipicks", "com.heytap.market",
        "com.oppo.market", "com.bbk.appstore", "com.vivo.appstore", "com.huawei.appmarket", "com.hihonor.appmarket")

    fun env(c: Context): OemSteps.Env {
        val src = runCatching {
            if (Build.VERSION.SDK_INT >= 30) c.packageManager.getInstallSourceInfo(c.packageName).installingPackageName
            else @Suppress("DEPRECATION") c.packageManager.getInstallerPackageName(c.packageName)
        }.getOrNull()
        return OemSteps.Env(Build.MANUFACTURER, Build.BRAND, Build.VERSION.SDK_INT,
            miui = prop("ro.miui.ui.version.name").isNotBlank(),
            oneUi = prop("ro.build.version.oneui").toIntOrNull() ?: 0,
            fromStore = src in STORES)
    }

    /** true = done, false = not done, null = this phone can't tell. */
    fun done(c: Context, s: OemSteps.Step): Boolean? = when (s.check) {
        OemSteps.Check.BATTERY_OPT -> runCatching { c.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(c.packageName) }.getOrNull()
        OemSteps.Check.AUTO_BLOCKER -> Settings.Secure.getInt(c.contentResolver, "auto_blocker_enabled", -1).let { if (it < 0) null else it == 0 }
        OemSteps.Check.RESTRICTED -> runCatching {
            val m = c.getSystemService(AppOpsManager::class.java).unsafeCheckOpNoThrow("android:access_restricted_settings", c.applicationInfo.uid, c.packageName)
            m != AppOpsManager.MODE_ERRORED
        }.getOrNull()
        OemSteps.Check.NONE -> null
    }

    private fun toIntent(a: Activity, g: OemSteps.Go): Intent = when {
        g.cls != null -> Intent().setComponent(ComponentName(g.pkg!!, g.cls))
        g.action == Settings.ACTION_APPLICATION_DETAILS_SETTINGS -> Intents.appDetails(a)
        else -> Intent(g.action)
    }

    /** Opens the first page of [s] this phone has; App info as the last resort. */
    fun open(a: Activity, s: OemSteps.Step): Boolean {
        if (s.id == "op_perm") { PermMon.open(a); return true }   // Developer options, scrolled to the row and outlined
        val list = mutableListOf<Intent>()
        if (s.dynamic) {
            if (s.id == "autostart") SetupSteps.autostartIntent(a)?.let { list += it }
            if (s.id == "battery") SetupSteps.miuiBg(a)?.let { list += it }
        }
        for (g in s.go) list += toIntent(a, g)
        list += Intents.appDetails(a)
        return Intents.startFirst(a, *list.toTypedArray())
    }

    /** Ids of checkable steps still not done (for state.oem.pending). */
    fun pending(c: Context): List<String> = OemSteps.pending(OemSteps.forEnv(env(c))) { done(c, it) }
}
