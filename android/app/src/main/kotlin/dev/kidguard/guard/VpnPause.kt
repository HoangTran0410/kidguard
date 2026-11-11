package dev.kidguard

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Apps that refuse to run while a VPN is up (Cake by VPBank: "thiết bị có dấu hiệu đang bật VPN"; owner 2026-11-11).
 * Independent of [AccPause] (owner: an app may check one and not the other): rule `vpnPause` (package list, defaults
 * to [VpnPauseLogic.DEFAULT], also fed by the website list [BankList]).
 *
 * When one opens: Device Owner takes the always-on VPN off (snapshot, put back exactly), and Tailscale gets its own
 * DISCONNECT_VPN broadcast; Tailscale keep-alive ([Ts.keep]) and the policy re-assert wait while paused. Once none of
 * them has been in front for the same time as the accessibility pause, CONNECT_VPN + always-on back. Only Tailscale
 * can be turned off (no other VPN app takes orders), and an always-on VPN set in Settings on a basic-mode phone comes
 * straight back (only a Device Owner can change it): both show as `vpnStuck` in state, the page says so.
 */
object VpnPauseLogic {
    val DEFAULT: Set<String> = setOf(
        "xyz.be.cake",                      // Cake by VPBank (refuses a VPN; owner 2026-11-11)
    )

    fun snap(pkg: String?, lockdown: Boolean): String = if (pkg.isNullOrBlank()) "" else (if (lockdown) "1|" else "0|") + pkg

    /** (package, lockdown) or null. */
    fun unsnap(s: String): Pair<String, Boolean>? {
        val i = s.indexOf('|'); if (i != 1) return null
        val p = s.substring(2); if (p.isBlank()) return null
        return p to (s[0] == '1')
    }
}

object VpnPause {
    const val DISCONNECT = "com.tailscale.ipn.DISCONNECT_VPN"
    /** Launcher: start the app this much later after a fresh pause, so it does not see the tunnel still closing. */
    const val LAUNCH_DELAY_MS = 900L
    private const val STUCK_CHECK_MS = 5_000L

    fun active(ctx: Context): Boolean = Store.of(ctx).vpnPaused.isNotEmpty()

    fun vpnUp(ctx: Context): Boolean = runCatching {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION") cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
    }.getOrDefault(false)

    /**
     * [pkg] is opening. True when a VPN was really turned off just now (the caller may wait / restart the app); a
     * session starts anyway for a listed app so keep-alive does not bring Tailscale up under it.
     */
    @Synchronized fun pause(ctx: Context, pkg: String): Boolean {
        if (Flavor.HOME) return false
        val s = Store.of(ctx)
        if (pkg !in s.rules.vpnPause) return false
        if (BasicLogic.bounceReason(pkg, ctx.packageName, Acc.blocked, Acc.prot) != null) return false   // never for a blocked app
        val had = s.vpnPaused
        s.vpnPaused = had + pkg; s.vpnLeftAt = 0L
        if (had.isNotEmpty() || !vpnUp(ctx)) return false
        if (Policy.isOwner(ctx)) runCatching {
            val d = Policy.dpm(ctx); val a = Policy.admin(ctx)
            val cur = d.getAlwaysOnVpnPackage(a)
            if (cur != null) {
                val lock = android.os.Build.VERSION.SDK_INT >= 29 && d.isAlwaysOnVpnLockdownEnabled(a)
                s.vpnSnap = VpnPauseLogic.snap(cur, lock)
                d.setAlwaysOnVpnPackage(a, null, false)
            }
        }.onFailure { Log.w("KidGuard", "vpn pause always-on", it) }
        if (Ts.findIp().isNotEmpty()) {
            s.vpnTs = true
            runCatching { ctx.sendBroadcast(Intent(DISCONNECT).setClassName(TsLogic.PKG, TsLogic.RECEIVER)) }
        }
        runCatching { s.log(R.string.log_vpn_paused, label(ctx, pkg)) }
        runCatching { Toasts.show(ctx.applicationContext, ctx.getString(R.string.vpn_paused_toast), true) }
        val app = ctx.applicationContext
        Handler(Looper.getMainLooper()).postDelayed({ runCatching { val st = Store.of(app); st.vpnStuck = st.vpnPaused.isNotEmpty() && vpnUp(app) } }, STUCK_CHECK_MS)
        return true
    }

    /** Guard tick: same "left for a while" timing as the accessibility pause, then the VPN goes back. */
    @Synchronized fun tick(ctx: Context, current: String?, now: Long) {
        val s = Store.of(ctx)
        val paused = s.vpnPaused
        if (paused.isEmpty()) return
        val wait = if (Policy.isOwner(ctx)) AccPauseLogic.RESUME_MS else AccPauseLogic.RESUME_BASIC_MS
        val (leftAt, back) = AccPauseLogic.step(current, s.rules.vpnPause + paused, s.vpnLeftAt, now, wait)
        if (back) resume(ctx, s) else if (leftAt != s.vpnLeftAt) s.vpnLeftAt = leftAt
    }

    private fun resume(ctx: Context, s: Store) {
        val snap = VpnPauseLogic.unsnap(s.vpnSnap)
        if (snap != null && Policy.isOwner(ctx)) runCatching { Policy.dpm(ctx).setAlwaysOnVpnPackage(Policy.admin(ctx), snap.first, snap.second) }
            .onFailure { Log.w("KidGuard", "vpn resume always-on", it) }
        val ts = s.vpnTs
        s.vpnPaused = emptySet(); s.vpnLeftAt = 0L; s.vpnSnap = ""; s.vpnTs = false; s.vpnStuck = false
        if (ts) runCatching { ctx.sendBroadcast(Intent(TsLogic.CONNECT).setClassName(TsLogic.PKG, TsLogic.RECEIVER)) }
        if (ts || snap != null) runCatching { s.log(R.string.log_vpn_resumed) }
    }

    /** Release: Tailscale back on; the always-on VPN is not put back (it was ours, and release takes it off: Ts.undo). */
    fun clear(ctx: Context) {
        val s = Store.of(ctx); val ts = s.vpnTs
        s.vpnPaused = emptySet(); s.vpnLeftAt = 0L; s.vpnSnap = ""; s.vpnTs = false; s.vpnStuck = false
        if (ts) runCatching { ctx.sendBroadcast(Intent(TsLogic.CONNECT).setClassName(TsLogic.PKG, TsLogic.RECEIVER)) }
    }

    private fun label(ctx: Context, pkg: String) = runCatching {
        val pm = ctx.packageManager; pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)
}
