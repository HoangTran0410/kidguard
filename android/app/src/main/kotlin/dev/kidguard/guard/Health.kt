package dev.kidguard

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import android.provider.Settings
import android.provider.Telephony
import android.os.UserManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * "Sức khoẻ máy": state.health (score + checks, see [HealthLogic]) and the background limit ("bgLimit" / "bgRestore").
 * The slow parts (installer of every app, appops of every app through the self-adb shell) are measured on a background
 * thread at most every [TTL] ms; state() just reads the cache. docs/protocol.md "Phone health".
 */
object Health {
    private const val TAG = "KidGuard"
    private const val TTL = 600_000L
    private const val OP = "RUN_ANY_IN_BACKGROUND"
    private const val BATCH = 25
    private data class Slow(val sideloaded: Int, val bg: Int?, val at: Long, val modes: Map<String, Boolean>, val user: Set<String>, val cand: Set<String>, val idle: Set<String>)
    @Volatile private var slow: Slow? = null
    @Volatile private var running = false

    /** Measure again at the next state (after a fix). */
    fun invalidate() { slow = slow?.copy(at = 0) }   // keeps the last numbers on screen until the new measure lands

    /** After a successful appops change: show the new mode at once (the re-measure takes a while). */
    private fun patch(pkgs: Collection<String>, allowed: Boolean) {
        slow = slow?.let { o -> val m = o.modes + pkgs.associateWith { allowed }; o.copy(modes = m, bg = if (o.bg != null) m.count { it.value } else null) }
    }

    private fun userApps(ctx: Context): List<String> = runCatching {
        ctx.packageManager.getInstalledApplications(0)
            .filter { it.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0 }
            .map { it.packageName }.filter { it != ctx.packageName && !it.startsWith("dev.kidguard.") }
    }.getOrDefault(emptyList())

    private fun installer(ctx: Context, pkg: String): String? = runCatching {
        if (Build.VERSION.SDK_INT >= 30) ctx.packageManager.getInstallSourceInfo(pkg).installingPackageName
        else @Suppress("DEPRECATION") ctx.packageManager.getInstallerPackageName(pkg)
    }.getOrNull()

    /** User apps we may restrict in the background: never KidGuard, the parent app, home / keyboard / SMS / dialer, rules' allow + bank lists. */
    fun bgCandidates(ctx: Context, s: Store): Set<String> {
        val r = s.rules
        val keep = Policy.neverHide(ctx) + r.allow + r.accPause + r.vpnPause + r.devPause + r.tcpPause + "dev.kidguard.parent" +
            listOfNotNull(runCatching { Telephony.Sms.getDefaultSmsPackage(ctx) }.getOrNull())
        return userApps(ctx).toSet() - keep
    }

    /** package -> true when RUN_ANY_IN_BACKGROUND is allowed (the default); null = no shell. In batches: one shell call each. */
    private fun bgModes(ctx: Context, pkgs: Collection<String>): Map<String, Boolean>? {
        val out = HashMap<String, Boolean>()
        for (chunk in pkgs.filter { Debloat.validPkg(it) }.chunked(BATCH)) {
            val txt = SelfAdb.run(ctx, "for p in ${chunk.joinToString(" ")}; do echo \"\$p \$(cmd appops get \$p $OP)\"; done") ?: return null
            for (l in txt.lineSequence()) {
                val p = l.substringBefore(' ').trim(); if (p.isEmpty() || p !in chunk) continue
                val m = l.substringAfter(' ', "").lowercase()
                if ("ignore" in m || "deny" in m || "errors" in m) out[p] = false else if ("allow" in m || "no operations" in m) out[p] = true
            }
        }
        return out
    }

    /** Candidates with no use and no notification in 7 days ("Gợi ý"). Empty when either is unknown (no usage access / notification access / short history): never a guess. */
    private fun idleApps(ctx: Context, cand: Set<String>): Set<String> = runCatching {
        if (!Usage.granted(ctx) || !NotifDots.granted(ctx)) return emptySet()
        val used = Usage.range(ctx, Usage.midnightAgo(7), System.currentTimeMillis()).ms.filterValues { it > 0 }.keys
        val posted = NotifLog.postersSince(ctx, 7) ?: return emptySet()
        cand - used - posted
    }.getOrDefault(emptySet())

    /** Per-app fields for state.apps[]: bg (true allowed / false restricted, omitted when unknown), bgMine, bgKeep (never restrictable, user apps only), bgIdle. */
    fun appFields(pkg: String, s: Store, o: JSONObject) {
        val sl = slow
        val mine = pkg in s.bgLimited
        if (mine) o.put("bgMine", true)
        if (sl == null) { if (mine) o.put("bg", false); return }
        if (mine) o.put("bg", false) else sl.modes[pkg]?.let { o.put("bg", it) }
        if (pkg in sl.user && pkg !in sl.cand) o.put("bgKeep", true)
        if (pkg in sl.idle && sl.modes[pkg] == true) o.put("bgIdle", true)
    }

    private fun measure(ctx: Context, s: Store) {
        if (running) return
        running = true
        Thread {
            try {
                val side = userApps(ctx).count { !HealthLogic.isStore(installer(ctx, it)) }
                val cand = bgCandidates(ctx, s)
                val modes = if (SelfAdb.ready(ctx)) bgModes(ctx, cand) else null
                val bg = modes?.count { it.value }
                slow = Slow(side, bg, System.currentTimeMillis(), modes ?: emptyMap(), userApps(ctx).toSet(), cand, idleApps(ctx, cand))
            } catch (e: Exception) { Log.w(TAG, "health measure", e) } finally { running = false }
        }.apply { isDaemon = true }.start()
    }

    private fun patchDays(): Int? = runCatching {
        val d = java.time.LocalDate.parse(Build.VERSION.SECURITY_PATCH)
        java.time.temporal.ChronoUnit.DAYS.between(d, java.time.LocalDate.now()).toInt()
    }.getOrNull()

    fun state(ctx: Context, s: Store, cacheMb: Int, clearCacheOk: Boolean): JSONObject {
        val now = System.currentTimeMillis()
        val sl = slow
        if (sl == null || now - sl.at > TTL) measure(ctx, s)
        val owner = Policy.isOwner(ctx)
        val dev = runCatching { Settings.Global.getInt(ctx.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1 }.getOrNull()
        val installs = if (!owner) null else runCatching {
            val um = ctx.getSystemService(Context.USER_SERVICE) as UserManager
            um.hasUserRestriction(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES) || um.hasUserRestriction(UserManager.DISALLOW_INSTALL_APPS)
        }.getOrNull()
        val admins = runCatching {
            val d = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            (d.activeAdmins ?: emptyList()).count { it.packageName != ctx.packageName }
        }.getOrNull()
        val free = runCatching { android.os.StatFs(android.os.Environment.getDataDirectory().path).let { (it.availableBytes * 100 / it.totalBytes.coerceAtLeast(1)).toInt() } }.getOrNull()
        val bm = ctx.getSystemService(BatteryManager::class.java)
        val bat = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val r = HealthLogic.evaluate(HealthLogic.In(dev, installs, patchDays(), free, cacheMb, sl?.sideloaded, bat, bm?.isCharging == true, sl?.bg, admins,
            ownAdb = clearCacheOk, canDevOff = DevOpts.canOff(ctx), canCache = clearCacheOk, canBg = clearCacheOk))
        val limited = s.bgLimited.size
        return JSONObject().put("score", r.score).put("limited", limited).put("at", sl?.at ?: 0L)
            .put("checks", JSONArray().apply { r.checks.forEach { put(JSONObject().put("id", it.id).put("ok", it.ok).put("w", it.weight).put("fix", it.fixable).put("v", it.value)) } })
    }

    /** "bgLimit": RUN_ANY_IN_BACKGROUND -> ignore for the candidates that still allow it. [only] = the parent's list (still filtered). "ok" | "no adb" | "failed". */
    fun bgLimit(ctx: Context, s: Store, only: Collection<String>?): String {
        if (Build.VERSION.SDK_INT < 28) return "unsupported"   // RUN_ANY_IN_BACKGROUND is API 28+ (Oppo 8.1: "Unknown operation string")
        val cand = bgCandidates(ctx, s).let { c -> if (only.isNullOrEmpty()) c else c.intersect(only.toSet()) }
        val modes = bgModes(ctx, cand) ?: return "no adb"
        val todo = cand.filter { modes[it] == true }
        var done = s.bgLimited; var bad = 0
        for (chunk in todo.chunked(BATCH)) {
            val out = SelfAdb.run(ctx, chunk.joinToString("; ") { "cmd appops set $it $OP ignore" }) ?: return if (done.size > s.bgLimited.size) { s.bgLimited = done; "failed" } else "no adb"
            if (out.isNotBlank()) { bad += chunk.size; Log.w(TAG, "bgLimit: $out") } else { done = done + chunk; patch(chunk, false) }
            s.bgLimited = done
        }
        invalidate()
        if (todo.isNotEmpty() && bad == todo.size) return "failed"
        s.log(R.string.log_bg_limit, todo.size - bad)
        return "ok"
    }

    /** Puts back what [bgLimit] changed (command "bgRestore", release). Only packages we changed; [only] narrows it to those (per-app undo). "ok" | "no adb". Nothing to do = "ok". */
    fun bgRestore(ctx: Context, s: Store, only: Collection<String>? = null): String {
        val ours = if (only.isNullOrEmpty()) s.bgLimited else s.bgLimited.intersect(only.toSet())
        if (ours.isEmpty()) return "ok"
        var left = s.bgLimited
        for (chunk in ours.filter { Debloat.validPkg(it) }.chunked(BATCH)) {
            val out = SelfAdb.run(ctx, chunk.joinToString("; ") { "cmd appops set $it $OP allow" }) ?: break
            if (out.isBlank()) { left = left - chunk.toSet(); patch(chunk, true) }
        }
        val undone = s.bgLimited.size - left.size
        s.bgLimited = left
        invalidate()
        if (undone > 0) s.log(R.string.log_bg_restore, undone)
        return if (left.intersect(ours).isEmpty()) "ok" else "no adb"
    }
}
