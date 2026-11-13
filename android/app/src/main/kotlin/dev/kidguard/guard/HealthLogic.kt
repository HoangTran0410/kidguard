package dev.kidguard

/**
 * "Sức khoẻ máy" score (pure, unit-tested). Only things we can really measure; no fake "speed up %" and no virus scan.
 * A value of null = unknown on this phone: the check is left out (neither a point lost nor a tick shown).
 */
object HealthLogic {
    class In(
        val devOpts: Boolean? = null,        // Developer options switched on
        val installsBlocked: Boolean? = null, // Device Owner restriction: no installs from outside the store
        val patchDays: Int? = null,          // age of the security patch in days
        val freePct: Int? = null,            // free storage, percent
        val cacheMb: Int = 0,                // all app caches
        val sideloaded: Int? = null,         // user apps not installed by a store
        val battery: Int = -1, val charging: Boolean = false,
        val bgApps: Int? = null,             // user apps allowed to run in the background
        val otherAdmins: Int? = null,        // device admins other than KidGuard
        val ownAdb: Boolean = false,         // KidGuard's own adb is in use (it needs Developer options): on is not an issue, only info
        val canDevOff: Boolean = false, val canCache: Boolean = false, val canBg: Boolean = false,
    )

    /** [value]: devOpts 1 (2 = on but used by KidGuard's own adb: ok, 0 points, still switchable), patch days, free %, cache MB, sideloaded / bg / admin count, battery %. */
    class Check(val id: String, val ok: Boolean, val weight: Int, val fixable: Boolean, val value: Int)
    class Result(val score: Int, val checks: List<Check>) { val issues get() = checks.filter { !it.ok } }

    const val PATCH_OLD_DAYS = 180
    private val STORES = setOf("com.android.vending", "com.sec.android.app.samsungapps", "com.xiaomi.mipicks", "com.xiaomi.market",
        "com.heytap.market", "com.oppo.market", "com.bbk.appstore", "com.vivo.appstore", "com.huawei.appmarket", "com.amazon.venezia",
        "com.google.android.packageinstaller.store")
    /** Installed by an app store (null / the package installer / adb = not a store). */
    fun isStore(installer: String?): Boolean = installer != null && installer in STORES

    fun evaluate(i: In): Result {
        val c = ArrayList<Check>()
        fun add(id: String, bad: Boolean, w: Int, fix: Boolean, v: Int) { c += Check(id, !bad, if (bad) w else 0, bad && fix, v) }
        i.devOpts?.let {
            if (it && i.ownAdb) c += Check("devOpts", true, 0, i.canDevOff, 2) else add("devOpts", it, 15, i.canDevOff, if (it) 1 else 0)
        }
        i.installsBlocked?.let { add("installs", !it, 5, false, 0) }
        i.otherAdmins?.let { add("admins", it > 0, minOf(10, 5 * it), false, it) }
        i.sideloaded?.let { add("sideload", it > 0, minOf(10, 2 * it), false, it) }
        i.patchDays?.let { add("patch", it > PATCH_OLD_DAYS, 15, false, it) }
        i.freePct?.let { add("storage", it < 20, if (it < 10) 15 else 8, false, it) }
        add("cache", i.cacheMb >= 200, if (i.cacheMb >= 500) 10 else 5, i.canCache, i.cacheMb)
        i.bgApps?.let { add("bg", it >= 10, if (it >= 20) 15 else 8, i.canBg, it) }
        if (i.battery in 0..100) add("battery", i.battery < 15 && !i.charging, 3, false, i.battery)
        return Result((100 - c.sumOf { it.weight }).coerceIn(0, 100), c)
    }
}
