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
        adb = runCatching { SelfAdb.ready(ctx) }.getOrDefault(false),
        paired = Store.of(ctx).everPaired,
    )

    fun caps(ctx: Context, c: Caps = of(ctx)): JSONObject = JSONObject().also { o -> c.toMap().forEach { (k, v) -> o.put(k, v) } }

    fun features(c: Caps): JSONArray = JSONArray().also { a ->
        Features.status(c).forEach { f -> a.put(JSONObject().put("id", f.id).put("ok", f.ok).put("missing", JSONArray(f.missing))) }
    }
}

/** Child-app screen "Tính năng và quyền": every feature, whether it works now, and a button per missing permission. */
object FeaturesSheet {
    const val GUIDE = "https://kid-guard.pages.dev/guide#install"

    private val ICON = mapOf(
        "launcher" to R.drawable.li_house, "launcherLock" to R.drawable.li_lock, "debloat" to R.drawable.li_eraser,
        "limits" to R.drawable.li_timer, "block" to R.drawable.li_octagon_x, "usage" to R.drawable.li_list_checks,
        "location" to R.drawable.li_map_pin, "screen" to R.drawable.li_eye, "remote" to R.drawable.li_link,
        "noUninstall" to R.drawable.li_shield, "dns" to R.drawable.li_globe,
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

    /** The system screen that grants [cap]. [pair] starts the child app's pairing flow. */
    private fun fix(act: Activity, u: Ui, cap: String, pair: () -> Unit) {
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
                // no Device Owner and no parent yet: this is the user's own choice (local opt-in); the owner path forces HOME itself
                if (!Policy.isOwner(act) && !Store.of(act).everPaired) LauncherSafe.setLocal(act, true)
                open(act, Intent(Settings.ACTION_HOME_SETTINGS), Intent(Settings.ACTION_SETTINGS))
            }
            "owner" -> guide(act, u, R.string.feat_owner_title, R.string.feat_owner_body)
            "adb" -> if (Build.VERSION.SDK_INT >= 30) {
                open(act, Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS), Intent(Settings.ACTION_SETTINGS))
                runCatching { SelfAdb.pair(act) }
            } else guide(act, u, R.string.feat_cap_adb, R.string.feat_adb_body)
            "paired" -> pair()
        }
    }

    fun show(act: Activity, u: Ui, pair: () -> Unit) {
        val sheet = Sheet(act, u, act.getString(R.string.feat_title))
        val body = sheet.body
        val pad = u.dp(12)
        body.addView(u.label(act.getString(R.string.feat_intro), 14f).apply { setPadding(pad, u.dp(6), pad, u.dp(6)) })
        val caps = FeatureCaps.of(act)
        val store = Store.of(act)
        for (f in Features.status(caps)) {
            val card = u.cardBox(14)
            val name = u.label(str(act, "ft_", f.id), 17f, u.text, true).apply {
                setCompoundDrawablesRelative(u.badge(ICON[f.id] ?: R.drawable.li_settings, u.accent, u.tint, 36, 20, 11), null, null, null)
                compoundDrawablePadding = u.dp(12); gravity = android.view.Gravity.CENTER_VERTICAL
            }
            card.addView(name)
            card.addView(u.label(str(act, "ft_", f.id + "_d"), 14f).apply { setPadding(0, u.dp(6), 0, 0) })
            val chipColor = u.tone(if (f.ok) Ic.OK else Ic.WARN)
            val chip = if (f.ok) act.getString(R.string.feat_ok)
                else act.getString(R.string.feat_need, f.missing.joinToString(", ") { str(act, "feat_cap_", it) })
            card.addView(u.label(chip, 14f, chipColor, true).apply { setPadding(0, u.dp(8), 0, 0) })
            for (cap in f.missing) {
                val label = if (cap == "home") act.getString(R.string.feat_launcher_use)
                    else act.getString(R.string.feat_fix, str(act, "feat_cap_", cap))
                card.addView(u.button(label, null, false) { sheet.dismiss(); fix(act, u, cap, pair) })
            }
            // launcher with the local opt-in on: a way back (once paired, the parent's rule decides)
            if (f.id == "launcher" && store.launcherLocal && !caps.owner && !caps.paired)
                card.addView(u.button(act.getString(R.string.feat_launcher_off), null, false) { sheet.dismiss(); LauncherSafe.setLocal(act, false) })
            body.addView(card)
        }
        sheet.show()
    }
}
