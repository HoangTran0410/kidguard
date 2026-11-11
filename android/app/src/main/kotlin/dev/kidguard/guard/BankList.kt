package dev.kidguard

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * The bank-app list ([AccPauseLogic.DEFAULT]) kept up to date without a release (owner 2026-11-11: "list để trên
 * Cloudflare Pages, app sync xuống"). Once a day the phone reads [URL] (the website, `website/data/bank-apps.json`) and
 * adds to `rules.accPause` only packages it has never been offered before ([Store.bankSeen]); a package the parent
 * took off stays off. `drop` takes a package back out, but only one an earlier list offered (a mistake in the list).
 * Offline / site down: the bundled list still counts. A listed app that is blocked never pauses (AccPause.pause).
 */
object BankList {
    const val URL = "https://kid-guard.pages.dev/data/bank-apps.json"
    private const val RETRY_MS = 3_600_000L
    @Volatile private var triedAt = 0L
    @Volatile private var bundledDone = false

    /** Guard tick: the bundled list once per process, the website list once a day (retry in an hour when it fails). */
    fun maybe(g: Guard, now: Long) {
        if (Flavor.HOME) return
        val s = Store.of(g)
        if (!bundledDone) { bundledDone = true; apply(g, s, AccPauseLogic.DEFAULT, emptySet()) }
        if (now - s.bankListAt < BankListLogic.DAY_MS || now - triedAt < RETRY_MS) return
        triedAt = now
        Thread({
            runCatching {
                val c = URL(URL).openConnection() as HttpURLConnection
                c.connectTimeout = 15_000; c.readTimeout = 15_000
                val body = try { if (c.responseCode == 200) c.inputStream.use { it.readBytes().take(64_000).toByteArray().toString(Charsets.UTF_8) } else null } finally { c.disconnect() }
                val l = body?.let { BankListLogic.parse(it) } ?: return@runCatching
                apply(g, s, AccPauseLogic.DEFAULT + l.first, l.second)
                s.bankListAt = System.currentTimeMillis()
            }.onFailure { Log.w("KidGuard", "bank list", it) }
        }, "bankList").start()
    }

    @Synchronized private fun apply(g: Guard, s: Store, offered: Set<String>, drop: Set<String>) {
        val r = s.rules
        val (list, seen) = BankListLogic.merge(r.accPause, s.bankSeen ?: BankListLogic.LEGACY, offered, drop)
        s.bankSeen = seen
        if (list == r.accPause) return
        g.setRules(r.copy(accPause = list, rev = maxOf(r.rev + 1, System.currentTimeMillis())))
        runCatching { s.log(R.string.log_bank_list, (list - r.accPause).size, (r.accPause - list).size) }
    }
}

/** Pure part of [BankList] (unit-tested). */
object BankListLogic {
    const val DAY_MS = 24 * 3_600_000L
    const val MAX = 200
    private val PKG = Regex("^[A-Za-z][\\w]*(\\.[A-Za-z_][\\w]*)+$")

    /** What phones before the website list were offered (the bundled list up to 0.9.12): the rest counts as new. */
    val LEGACY: Set<String> = AccPauseLogic.DEFAULT - setOf("xyz.be.cake", "vms.com.vn.mymobifone")

    /** {"v":1,"apps":[{"pkg":..,"name":..}],"drop":[..]} -> (apps, drop); null = not a valid list. Bad entries are skipped. */
    fun parse(json: String): Pair<Set<String>, Set<String>>? = runCatching {
        val o = JSONObject(json)
        val a = o.getJSONArray("apps")
        val apps = (0 until minOf(a.length(), MAX)).mapNotNull { a.optJSONObject(it)?.optString("pkg")?.takeIf(::ok) }.toSet()
        val d = o.optJSONArray("drop")
        val drop = if (d == null) emptySet() else (0 until minOf(d.length(), MAX)).mapNotNull { d.optString(it).takeIf(::ok) }.toSet()
        if (apps.isEmpty()) null else apps to drop
    }.getOrNull()

    fun ok(p: String) = p.length <= 200 && PKG.matches(p)

    /**
     * New rules list and new "seen" set: packages never offered before are added (up to the rules' 100 cap); `drop`
     * removes only packages a list offered earlier (never one the parent typed in themselves).
     */
    fun merge(list: Set<String>, seen: Set<String>, offered: Set<String>, drop: Set<String>): Pair<Set<String>, Set<String>> {
        val add = (offered - seen - drop).filter(::ok)
        val out = LinkedHashSet(list.filterNot { it in drop && it in seen })
        for (p in add) if (out.size < 100) out += p
        return out to (seen + offered)
    }
}
