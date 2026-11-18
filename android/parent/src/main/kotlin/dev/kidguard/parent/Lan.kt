package dev.kidguard.parent

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import dev.kidguard.LanName
import org.json.JSONObject
import java.net.Inet4Address
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Finds a LAN kid whose IP changed (router DHCP) by the name it advertises on the Wi-Fi ([LanName], NSD = mDNS).
 * Owner 2026-11-18: "phụ huynh và trẻ luôn có đường kết nối nhau". Used by [CheckJob] (background alerts), the
 * notification / widget actions and the page (`KGApp.lanFind`, step 2 of core.js `relocate`).
 * An address is used only after `/api/hello?n=<nonce>` proves the kid knows our token ([LanName.proof]): the name and
 * the hello id are public, so a look-alike on the Wi-Fi would otherwise get the token (review 2026-11-18).
 * The found address is a hint per kid id (prefs), tried after the saved target; the saved kid list is not rewritten here
 * (the page moves `target` itself; notification state is keyed by target).
 */
object Lan {
    private const val TAG = "KGParent"
    private val seen = ConcurrentHashMap<String, String>()   // service name -> http://ip:port (this process)
    private val verified = ConcurrentHashMap<String, String>()   // kid id -> proven address, for the page's next lanFind
    private val looking = ConcurrentHashMap<String, Long>()   // kid id -> 0 while a page lookup runs, else when it ended
    @Volatile private var scanUntil = 0L

    private fun p(c: Context) = c.getSharedPreferences("kg_parent", Context.MODE_PRIVATE)
    fun hint(c: Context, id: String): String? = p(c).getString("lan:$id", null)
    private fun setHint(c: Context, id: String, url: String) { p(c).edit().putString("lan:$id", url).apply() }

    /** Does [url] answer `/api/hello` with this kid id AND a proof made from [token]? Only then may the token go there. */
    fun same(url: String, id: String, token: String, ms: Int = 4_000): Boolean {
        if (token.isEmpty()) return false
        val n = java.util.UUID.randomUUID().toString().replace("-", "")
        val h = Net.call("GET", "$url/api/hello?n=$n", emptyMap(), null, ms)
        if (h.status != 200) return false
        val o = runCatching { JSONObject(String(h.body, Charsets.UTF_8)) }.getOrNull() ?: return false
        val want = LanName.proof(LanName.tokenHash(token), n)
        val proofs = o.optJSONArray("proof") ?: return false
        return o.optString("id") == id && (0 until proofs.length()).any { proofs.optString(it) == want }
    }

    /** Any Wi-Fi network (a VPN may be the default one): no Wi-Fi = nothing to find, don't wait. */
    fun onWifi(c: Context): Boolean = runCatching {
        val cm = c.getSystemService(android.net.ConnectivityManager::class.java)
        @Suppress("DEPRECATION") cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true }
    }.getOrDefault(false)

    /**
     * Blocking (background threads only): the kid's current LAN address, or null. Order: the hint, then an NSD scan
     * of up to [ms]. [skip] = the address that just failed.
     */
    fun find(c: Context, id: String, token: String, skip: String, ms: Long = 6_000): String? {
        if (id.isEmpty() || token.isEmpty() || !onWifi(c)) return null
        hint(c, id)?.takeIf { it != skip && same(it, id, token) }?.let { return it }
        scan(c, ms, id)
        for ((name, url) in seen) if (LanName.matches(name, id) && url != skip && same(url, id, token)) { setHint(c, id, url); return url }
        return null
    }

    /**
     * For the page (never blocks the JS thread): a proven address, "" = still looking (ask again), "none" = looked, nothing
     * (or no Wi-Fi). The token comes from the saved kid list, not from the page.
     */
    fun peek(c: Context, id: String): String {
        if (!onWifi(c)) return "none"
        verified.remove(id)?.let { return it }
        val st = looking[id]
        if (st == 0L) return ""
        if (st != null && System.currentTimeMillis() - st < 15_000) return "none"
        val k = Logic.parseKids(Store.kids(c)).firstOrNull { it.id == id && it.via == "lan" } ?: return "none"
        looking[id] = 0L
        Thread({
            try { find(c, id, k.token, k.target)?.let { verified[id] = it } }
            catch (e: Exception) { Log.w(TAG, "lan find", e) }
            finally { looking[id] = System.currentTimeMillis() }
        }, "lan-find").start()
        return ""
    }

    /** Remember an address the page verified (it moved its own target there). */
    fun learned(c: Context, id: String, url: String) { if (id.isNotEmpty() && url.startsWith("http://")) setHint(c, id, url) }

    /** One discovery round: fills [seen] with resolved IPv4 addresses; ends early once [want]'s name resolved. */
    private fun scan(c: Context, ms: Long, want: String? = null) {
        val m = c.applicationContext.getSystemService(NsdManager::class.java) ?: return
        synchronized(this) {
            if (System.currentTimeMillis() < scanUntil) {   // another scan is running: wait for its results in [seen]
                while (System.currentTimeMillis() < scanUntil) runCatching { (this as Object).wait(200) }
                return
            }
            scanUntil = System.currentTimeMillis() + ms
        }
        seen.clear()   // a name that left the Wi-Fi must not win over a fresh one (onServiceLost stops with the listener)
        val found = java.util.concurrent.LinkedBlockingQueue<NsdServiceInfo>()
        val dl = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) {}
            override fun onServiceFound(s: NsdServiceInfo) { found.add(s) }
            override fun onServiceLost(s: NsdServiceInfo) { seen.remove(s.serviceName) }
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, code: Int) { Log.w(TAG, "nsd discovery $code") }
            override fun onStopDiscoveryFailed(t: String, code: Int) {}
        }
        try {
            m.discoverServices(LanName.TYPE, NsdManager.PROTOCOL_DNS_SD, dl)
            val end = System.currentTimeMillis() + ms
            while (true) {
                val left = end - System.currentTimeMillis(); if (left <= 0) break
                val s = found.poll(left, TimeUnit.MILLISECONDS) ?: break
                val u = resolve(m, s) ?: continue
                seen[s.serviceName] = u
                if (want != null && LanName.matches(s.serviceName, want)) break
            }
        } catch (e: Exception) { Log.w(TAG, "nsd", e) }
        finally { runCatching { m.stopServiceDiscovery(dl) }; scanUntil = 0 }
    }

    /** One resolve at a time (NsdManager refuses a second one), 3 s max. */
    @Suppress("DEPRECATION")
    private fun resolve(m: NsdManager, s: NsdServiceInfo): String? {
        val done = CountDownLatch(1)
        var out: String? = null
        m.resolveService(s, object : NsdManager.ResolveListener {
            override fun onResolveFailed(i: NsdServiceInfo, code: Int) { done.countDown() }
            override fun onServiceResolved(i: NsdServiceInfo) {
                val v4 = if (Build.VERSION.SDK_INT >= 34) i.hostAddresses.firstOrNull { it is Inet4Address } else i.host?.takeIf { it is Inet4Address }
                out = v4?.let { "http://${it.hostAddress}:${i.port}" }
                done.countDown()
            }
        })
        done.await(3, TimeUnit.SECONDS)
        return out
    }
}
