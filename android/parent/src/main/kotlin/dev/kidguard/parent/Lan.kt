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
 * An address is only kept after `/api/hello` names the same kid id (the token never goes to a stranger).
 * The found address is a hint per kid id (prefs), tried after the saved target; the saved kid list is not rewritten here
 * (the page moves `target` itself; notification state is keyed by target).
 */
object Lan {
    private const val TAG = "KGParent"
    private val seen = ConcurrentHashMap<String, String>()   // service name -> http://ip:port (this process)
    @Volatile private var scanUntil = 0L

    private fun p(c: Context) = c.getSharedPreferences("kg_parent", Context.MODE_PRIVATE)
    fun hint(c: Context, id: String): String? = p(c).getString("lan:$id", null)
    private fun setHint(c: Context, id: String, url: String) { p(c).edit().putString("lan:$id", url).apply() }

    /** Does [url] answer `/api/hello` with this kid id? */
    fun same(url: String, id: String, ms: Int = 4_000): Boolean {
        val h = Net.call("GET", "$url/api/hello", emptyMap(), null, ms)
        return h.status == 200 && runCatching { JSONObject(String(h.body, Charsets.UTF_8)).optString("id") == id }.getOrDefault(false)
    }

    /**
     * Blocking (background threads only): the kid's current LAN address, or null. Order: the hint, then an NSD scan
     * of up to [ms]. [skip] = the address that just failed.
     */
    fun find(c: Context, id: String, skip: String, ms: Long = 6_000): String? {
        if (id.isEmpty()) return null
        hint(c, id)?.takeIf { it != skip && same(it, id) }?.let { return it }
        scan(c, ms)
        for ((name, url) in seen) if (LanName.matches(name, id) && url != skip && same(url, id)) { setHint(c, id, url); return url }
        return null
    }

    /** For the page (no blocking on the JS thread): what is known now; also starts a scan, so a later call may know more. */
    fun peek(c: Context, id: String): String {
        if (System.currentTimeMillis() > scanUntil) Thread({ runCatching { scan(c, 6_000) } }, "lan-scan").start()
        val fromScan = seen.entries.firstOrNull { LanName.matches(it.key, id) }?.value
        return fromScan ?: hint(c, id) ?: ""
    }

    /** Remember an address the page verified (it moved its own target there). */
    fun learned(c: Context, id: String, url: String) { if (id.isNotEmpty() && url.startsWith("http://")) setHint(c, id, url) }

    /** One discovery round: fills [seen] with resolved IPv4 addresses. */
    private fun scan(c: Context, ms: Long) {
        val m = c.applicationContext.getSystemService(NsdManager::class.java) ?: return
        synchronized(this) {
            if (System.currentTimeMillis() < scanUntil) {   // another scan is running: wait for its results in [seen]
                while (System.currentTimeMillis() < scanUntil) runCatching { (this as Object).wait(200) }
                return
            }
            scanUntil = System.currentTimeMillis() + ms
        }
        val found = java.util.concurrent.LinkedBlockingQueue<NsdServiceInfo>()
        val started = CountDownLatch(1)
        val dl = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) { started.countDown() }
            override fun onServiceFound(s: NsdServiceInfo) { found.add(s) }
            override fun onServiceLost(s: NsdServiceInfo) { seen.remove(s.serviceName) }
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, code: Int) { Log.w(TAG, "nsd discovery $code"); started.countDown() }
            override fun onStopDiscoveryFailed(t: String, code: Int) {}
        }
        try {
            m.discoverServices(LanName.TYPE, NsdManager.PROTOCOL_DNS_SD, dl)
            val end = System.currentTimeMillis() + ms
            while (true) {
                val left = end - System.currentTimeMillis(); if (left <= 0) break
                val s = found.poll(left, TimeUnit.MILLISECONDS) ?: break
                resolve(m, s)?.let { seen[s.serviceName] = it }
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
