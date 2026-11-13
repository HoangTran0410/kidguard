package dev.kidguard

import android.widget.CheckBox
import android.widget.LinearLayout

/**
 * Before a bank-type app opens from the kid home: what its pause will turn off right now (accessibility, debugging,
 * the adb port, the VPN), what stops working meanwhile and how each comes back. Only when something is actually on and
 * would go off; "don't ask again" is per app (Store.pauseNoAsk). Opened from elsewhere (notification, recents) there
 * is no popup: the pause then happens on the app's first window (Acc), as before.
 */
internal object PauseAsk {
    /** name.lowercase() = the row in [Features.PAUSES] (FeaturesTest checks it). */
    enum class Kind { ACC, DEV, TCP, VPN }

    /** Title + body per kind: what goes off, which features stop (from Features.TABLE / PAUSES), how it comes back. */
    fun lines(ctx: android.content.Context, kinds: List<Kind>): List<Pair<String, String>> {
        val caps = runCatching { FeatureCaps.of(ctx) }.getOrDefault(Caps())
        return kinds.map { k ->
            val (t, back) = when (k) {
                Kind.ACC -> R.string.pa_acc_t to R.string.pa_acc_b
                Kind.DEV -> R.string.pa_dev_t to R.string.pa_dev_b
                Kind.TCP -> R.string.pa_tcp_t to R.string.pa_tcp_b
                Kind.VPN -> R.string.pa_vpn_t to R.string.pa_vpn_b
            }
            val lost = Features.lost(caps, k.name.lowercase()).map { id ->
                ctx.resources.getIdentifier("ft_$id", "string", ctx.packageName).let { if (it != 0) ctx.getString(it) else id } }
            val stops = if (lost.isEmpty()) ctx.getString(R.string.pa_lost_none) else ctx.getString(R.string.pa_lost, lost.joinToString(", "))
            ctx.getString(t) to stops + "\n" + ctx.getString(back)
        }
    }

    /** What opening [pkg] would turn off now, in the order shown. */
    fun kinds(ctx: android.content.Context, pkg: String): List<Kind> {
        if (Flavor.HOME) return emptyList()
        val s = Store.of(ctx); val r = s.rules
        if (pkg !in r.accPause && pkg !in r.devPause && pkg !in r.tcpPause && pkg !in r.vpnPause) return emptyList()
        if (runCatching { BasicLogic.bounceReason(pkg, ctx.packageName, Acc.blocked, Acc.prot) }.getOrNull() != null) return emptyList()
        val out = ArrayList<Kind>()
        runCatching { if (pkg in r.accPause && AccPause.able(ctx) && s.accPaused.isEmpty() && Acc.isOn(ctx)) out += Kind.ACC }
        val canDev = runCatching { DevOpts.canOff(ctx) }.getOrDefault(false)
        runCatching { if (canDev && pkg in r.devPause && s.devPaused == 0 && DevOpts.debugNow(ctx)) out += Kind.DEV }
        runCatching { if (canDev && pkg in r.tcpPause && DevOpts.tcpNow(ctx)) out += Kind.TCP }
        runCatching { if (pkg in r.vpnPause && s.vpnPaused.isEmpty() && VpnPause.vpnUp(ctx)) out += Kind.VPN }
        return out
    }
}

/** Runs [go] now, or after the parent-of-the-phone said OK to what is about to go off. */
internal fun HomeActivity.pauseAsk(pkg: String, label: String, go: () -> Unit) {
    val ks = runCatching { PauseAsk.kinds(this, pkg) }.getOrDefault(emptyList())
    val st = Store.of(this)
    if (ks.isEmpty() || pkg in st.pauseNoAsk) { go(); return }
    val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    for ((t, b) in PauseAsk.lines(this, ks)) {
        box.addView(u.label("• " + t, 15f, u.text, true),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = u.dp(10) })
        box.addView(u.label(b, 13f, u.sub).apply { setLineSpacing(0f, 1.15f); setPadding(u.dp(12), u.dp(2), 0, 0) })
    }
    val cb = CheckBox(this).apply { text = getString(R.string.pa_noask); setTextColor(u.sub); textSize = 14f }
    box.addView(cb, LinearLayout.LayoutParams(-2, -2).apply { topMargin = u.dp(12) })
    Modal(this, u).show(R.drawable.li_info, if (PauseAsk.Kind.TCP in ks) Modal.Tone.WARN else Modal.Tone.INFO,
        getString(R.string.pa_title, label), getString(R.string.pa_body), content = box,
        ok = getString(R.string.pa_open), cancel = getString(R.string.cancel)) {
        if (cb.isChecked) st.pauseNoAsk = st.pauseNoAsk + pkg
        go(); true
    }
}
