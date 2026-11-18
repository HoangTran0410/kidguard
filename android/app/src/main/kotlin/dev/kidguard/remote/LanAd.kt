package dev.kidguard

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

/**
 * Advertises the LAN server on the Wi-Fi as [LanName.TYPE] / [LanName.of] (owner 2026-11-18: "phụ huynh và trẻ luôn có
 * đường kết nối nhau"): when the router gives this phone a new IP, the parent app finds it again without a new QR.
 * Registered while the Guard runs (Android re-announces it after a Wi-Fi change); no permission needed.
 */
object LanAd {
    private var nsd: NsdManager? = null
    private var listener: NsdManager.RegistrationListener? = null

    @Synchronized fun start(ctx: Context, id: String, port: Int) {
        if (listener != null || id.isEmpty()) return
        val m = ctx.applicationContext.getSystemService(NsdManager::class.java) ?: return
        val info = NsdServiceInfo().apply { serviceName = LanName.of(id); serviceType = LanName.TYPE; this.port = port }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(s: NsdServiceInfo) { Log.i("KidGuard", "lan ad: ${s.serviceName}") }
            override fun onRegistrationFailed(s: NsdServiceInfo, code: Int) { Log.w("KidGuard", "lan ad failed $code"); synchronized(this@LanAd) { if (listener === this) listener = null } }
            override fun onServiceUnregistered(s: NsdServiceInfo) {}
            override fun onUnregistrationFailed(s: NsdServiceInfo, code: Int) {}
        }
        runCatching { m.registerService(info, NsdManager.PROTOCOL_DNS_SD, l); nsd = m; listener = l }
            .onFailure { Log.w("KidGuard", "lan ad", it) }
    }

    @Synchronized fun stop() {
        val l = listener ?: return
        listener = null
        runCatching { nsd?.unregisterService(l) }
    }
}
