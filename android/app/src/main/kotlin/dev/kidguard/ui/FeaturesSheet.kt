package dev.kidguard

import android.Manifest
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.LinearLayout
import org.json.JSONArray
import org.json.JSONObject

/** Fills [Caps] from the phone and builds the `caps` / `features` part of state (docs/protocol.md "Features and permissions"). */
object FeatureCaps {
    fun of(ctx: Context): Caps = Caps(
        owner = Policy.isOwner(ctx), admin = Basic.adminActive(ctx), acc = Acc.isOn(ctx), usage = Usage.granted(ctx),
        location = Basic.locationOk(ctx), notify = Basic.notifyOk(ctx), battery = Basic.batteryOk(ctx),
        home = LauncherSafe.homeIsOurs(ctx),
        adb = runCatching { SelfAdb.why(ctx).let { it == null || it == "wifi" || it == "wss" } }.getOrDefault(false),
        paired = Store.of(ctx).everPaired,
        wss = Sys.wss(ctx),
        adbNet = runCatching { DevOpts.tcpNow(ctx) }.getOrDefault(false),
        vpn = runCatching { VpnPause.vpnUp(ctx) }.getOrDefault(false),
    )

    fun caps(ctx: Context, c: Caps = of(ctx)): JSONObject = JSONObject().also { o -> c.toMap().forEach { (k, v) -> o.put(k, v) } }

    fun features(c: Caps): JSONArray = JSONArray().also { a ->
        Features.status(c).forEach { f -> a.put(JSONObject().put("id", f.id).put("ok", f.ok).put("missing", JSONArray(f.missing))) }
    }
}

/** Child-app screen "Tính năng và quyền": every feature, whether it works now, and a button per missing permission. */
object FeaturesSheet {
    private var live: (() -> Unit)? = null
    private var cur: Sheet? = null

    /** Re-reads the caps into the open sheet (no-op when closed). */
    fun refresh() { if (cur?.showing == true) live?.invoke() }
    /** Sheet gone: drop it (fill captures the Activity). */
    fun closed() { live = null; cur = null }

    const val GUIDE = "https://kid-guard.pages.dev/guide#install"

    private val ICON = mapOf(
        "launcher" to R.drawable.li_house, "launcherLock" to R.drawable.li_lock, "debloat" to R.drawable.li_eraser,
        "limits" to R.drawable.li_timer, "block" to R.drawable.li_octagon_x, "usage" to R.drawable.li_list_checks,
        "location" to R.drawable.li_map_pin, "screen" to R.drawable.li_eye, "remote" to R.drawable.li_link,
        "noUninstall" to R.drawable.li_shield, "dns" to R.drawable.li_globe, "lockLocation" to R.drawable.li_map_pin,
        "adbShell" to R.drawable.li_monitor, "autoGrant" to R.drawable.li_shield, "pcAdb" to R.drawable.li_laptop, "tailscale" to R.drawable.li_link,
    )

    private fun str(act: Activity, prefix: String, id: String): String {
        val r = act.resources.getIdentifier(prefix + id, "string", act.packageName)
        return if (r != 0) act.getString(r) else id
    }

    private fun open(act: Activity, vararg i: Intent) {
        for (x in i) if (runCatching { act.startActivity(x.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)) return
    }

    private fun guide(act: Activity, u: Ui, title: Int, body: Int) {
        Modal(act, u).show(R.drawable.li_shield, Modal.Tone.INFO, act.getString(title), act.getString(body),
            ok = act.getString(R.string.feat_guide), cancel = act.getString(R.string.feat_close)) {
            open(act, Intent(Intent.ACTION_VIEW, Uri.parse(GUIDE))); true
        }
    }

    /**
     * "Chống lách": what a child can still do against basic mode, what Device Owner closes, and what upgrading costs
     * (accounts) BEFORE the parent starts it. Upgrade = the on-phone setup (Android 11+), else the guide.
     */
    private val GATED = setOf("admin", "acc", "usage", "location", "battery", "home", "adb")

    fun tamper(act: Activity, u: Ui) {
        val onPhone = Build.VERSION.SDK_INT >= 30
        Modal(act, u).show(R.drawable.li_shield, Modal.Tone.WARN, act.getString(R.string.tamper_title), act.getString(R.string.tamper_body),
            ok = act.getString(if (onPhone) R.string.tamper_go else R.string.feat_guide), cancel = act.getString(R.string.feat_close)) {
            if (onPhone) SelfGate.run(act, u) { runCatching { act.startActivity(Intent(act, SelfSetupActivity::class.java)) } }
            else open(act, Intent(Intent.ACTION_VIEW, Uri.parse(GUIDE)))
            true
        }
    }

    /** The system screen that grants [cap]. [pair] starts the child app's pairing flow. */
    fun fix(act: Activity, u: Ui, cap: String, pair: () -> Unit) {
        // pages the anti-tamper closes on a paired basic phone: parent PIN first (SelfGate), then the page
        if (cap in GATED && !SelfGate.free(act)) { SelfGate.run(act, u) { fix(act, u, cap, pair) }; return }
        val pkg = Uri.parse("package:${act.packageName}")
        fun granted(p: String) = act.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        when (cap) {
            "admin" -> open(act, Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, Policy.admin(act))
                .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, act.getString(R.string.basic_admin_expl)))
            "acc" -> open(act, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            "usage" -> open(act, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, pkg), Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            "location" -> {
                if (!granted(Manifest.permission.ACCESS_FINE_LOCATION))
                    act.requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 41)
                else {
                    if (Build.VERSION.SDK_INT >= 30) open(act, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg))
                    act.requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 42)
                }
            }
            "notify" -> act.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 43)
            "battery" -> open(act, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg), Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            "home" -> {
                // no Device Owner: make sure the HOME component is enabled right now, so KidGuard is in the list that opens
                if (!Policy.isOwner(act)) LauncherSafe.setLocal(act, true)
                open(act, Intent(Settings.ACTION_HOME_SETTINGS), Intent(Settings.ACTION_SETTINGS))
            }
            "owner" -> tamper(act, u)
            // the guided 3-step screen (live status per step), pairing only: the bare Developer options page + a code
            // notification left people lost (owner 2026-11-11: "user không biết lấy mã ở đâu")
            "adb" -> if (Build.VERSION.SDK_INT >= 30) {
                runCatching { act.startActivity(Intent(act, SelfSetupActivity::class.java).putExtra(SelfSetupActivity.EXTRA_PAIR, true)) }
            } else guide(act, u, R.string.feat_cap_adb, R.string.feat_adb_body)
            "wss" -> guide(act, u, R.string.feat_cap_wss, R.string.feat_wss_body)
            "paired" -> pair()
            // the port opens through our own paired adb ([Shell.tcp] also sets the rule, so the Guard tick reopens it after a reboot)
            "adbNet" -> {
                val app = act.applicationContext
                Thread({
                    val r = runCatching { SelfAdb.invalidate(); Shell.tcp(app, true) }.getOrDefault("fail")
                    act.runOnUiThread {
                        when (r) {
                            "ok" -> say(act, R.string.feat_adbnet_ok)
                            "no adb" -> { say(act, R.string.feat_adbnet_pair); fix(act, u, "adb", pair) }
                            "blocked" -> say(act, R.string.feat_adbnet_blocked)
                            else -> say(act, R.string.feat_adbnet_fail)
                        }
                    }
                }, "fix-adbnet").start()
            }
            // Tailscale is its own app: open it to sign in / connect, or get it from the store
            "vpn" -> {
                val launch = if (Ts.installed(act)) act.packageManager.getLaunchIntentForPackage(TsLogic.PKG) else null
                if (launch != null) { say(act, R.string.feat_vpn_open); open(act, launch) }
                else {
                    say(act, R.string.feat_vpn_get)
                    open(act, Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${TsLogic.PKG}")),
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=${TsLogic.PKG}")))
                }
            }
            // never close the sheet silently: a cap added later without a fix says so
            else -> say(act, R.string.feat_nofix)
        }
    }

    private fun say(act: Activity, res: Int) { android.widget.Toast.makeText(act, res, android.widget.Toast.LENGTH_LONG).show() }

    /**
     * "Bảo vệ và quyền": [status] (MainActivity's live status rows, kept up to date by its refresh) on top, then every
     * feature with its missing permissions, then the battery / auto-start page. One screen instead of the old
     * "Trạng thái bảo vệ" modal + "Tính năng và quyền" sheet, which answered the same question twice.
     */
    fun show(act: Activity, u: Ui, pair: () -> Unit, status: android.view.View? = null, battery: (() -> Unit)? = null): Sheet {
        val sheet = Sheet(act, u, act.getString(if (status != null) R.string.prot_title else R.string.feat_title))
        val body = sheet.body
        val pad = u.dp(12)
        // rebuilt in place (the sheet never closes by itself: a tap opens a screen / dialog above it, and coming back
        // re-reads the caps). [refresh] is called from MainActivity.onResume.
        fun fill() {
        body.removeAllViews()
        if (status != null) {
            body.addView(u.section(act.getString(R.string.prot_status)))
            body.addView(u.cardBox(14).apply { (layoutParams as LinearLayout.LayoutParams).topMargin = u.dp(4); addView(status) })
            body.addView(u.section(act.getString(R.string.prot_features)))
        }
        body.addView(u.label(act.getString(R.string.feat_intro), 14f).apply { setPadding(pad, u.dp(6), pad, u.dp(6)) })
        // same card look as the features above (they were plain list rows at the bottom)
        fun actionCard(icon: Int, title: Int, desc: Int, need: String?, btn: String, run: () -> Unit) = body.addView(u.cardBox(14).apply {
            addView(u.label(act.getString(title), 17f, u.text, true).apply {
                setCompoundDrawablesRelative(u.badge(icon, u.accent, u.tint, 36, 20, 11), null, null, null)
                compoundDrawablePadding = u.dp(12); gravity = android.view.Gravity.CENTER_VERTICAL
            })
            addView(u.label(act.getString(desc), 14f).apply { setPadding(0, u.dp(6), 0, 0) })
            if (need != null) addView(u.label(act.getString(R.string.feat_need, need), 14f, u.tone(Ic.WARN), true).apply { setPadding(0, u.dp(8), 0, 0) })
            addView(u.button(btn, null, false) { run() })
        })
        val caps = FeatureCaps.of(act)
        val store = Store.of(act)
        // basic mode: say up front that it can be bypassed and what Device Owner adds (owner 2026-11-13)
        val feats = Features.byPriority(Features.status(caps))
        // the "can be bypassed" card after what is still missing (that is what to do first), before the working ones
        val tamperCard = { if (!caps.owner) actionCard(R.drawable.li_shield, R.string.tamper_card, R.string.tamper_card_d, null, act.getString(R.string.tamper_see)) { tamper(act, u) } }
        if (feats.none { !it.ok }) tamperCard()
        for ((i, f) in feats.withIndex()) {
            if (i > 0 && f.ok && !feats[i - 1].ok) tamperCard()
            val card = u.cardBox(14)
            val name = u.label(str(act, "ft_", f.id), 17f, u.text, true).apply {
                setCompoundDrawablesRelative(u.badge(ICON[f.id] ?: R.drawable.li_settings, u.accent, u.tint, 36, 20, 11), null, null, null)
                compoundDrawablePadding = u.dp(12); gravity = android.view.Gravity.CENTER_VERTICAL
            }
            card.addView(name)
            card.addView(u.label(str(act, "ft_", f.id + "_d"), 14f).apply { setPadding(0, u.dp(6), 0, 0) })
            // the one to do first in basic mode: it turns most of the other cards on by itself
            if (f.id == "autoGrant" && !f.ok) card.addView(u.label(act.getString(R.string.ft_autoGrant_first), 14f, u.accent, true).apply { setPadding(0, u.dp(8), 0, 0) })
            val chipColor = u.tone(if (f.ok) Ic.OK else Ic.WARN)
            val chip = if (f.ok) act.getString(R.string.feat_ok)
                else act.getString(R.string.feat_need, f.missing.joinToString(", ") { str(act, "feat_cap_", it) })
            card.addView(u.label(chip, 14f, chipColor, true).apply { setPadding(0, u.dp(8), 0, 0) })
            for (cap in f.missing) {
                val label = if (cap == "home") act.getString(R.string.feat_launcher_use)
                    else act.getString(R.string.feat_fix, str(act, "feat_cap_", cap))
                card.addView(u.button(label, null, false) { fix(act, u, cap, pair) })
            }
            body.addView(card)
        }
        if (feats.isNotEmpty() && feats.all { !it.ok }) tamperCard()
        // basic mode + bank apps: without "display over other apps" Android may block reopening the bank after the pause
        if (!Policy.isOwner(act) && store.rules.accPause.isNotEmpty() && !AccPause.canReopen(act)) {
            val cap = act.getString(R.string.feat_cap_overlay)
            actionCard(R.drawable.li_banknote, R.string.ft_bank, R.string.ft_bank_d, cap, act.getString(R.string.feat_fix, cap)) {
                open(act, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:" + act.packageName)), Intent(Settings.ACTION_SETTINGS))
            }
        }
        if (battery != null) actionCard(R.drawable.li_battery_charging, R.string.ft_battery, R.string.ft_battery_d, null, act.getString(R.string.ft_battery_open)) { battery() }
        }
        fill()
        live = ::fill; cur = sheet
        sheet.show()
        return sheet
    }
}
