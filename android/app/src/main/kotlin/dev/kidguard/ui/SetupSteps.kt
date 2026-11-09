package dev.kidguard

import android.Manifest
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Child screen card "Việc cần làm · 3/7": every setup step in one numbered list, for both modes (Device Owner and
 * basic), replacing the old owner checklist (status modal) and basic checklist. Battery and the OEM auto-start page are
 * steps too: auto-start can't be read back, so the child confirms it with "Đã bật" ([Store.autostartOk]).
 * Hidden once every step is done. Rebuilt only when a step changed (called every 3 s from MainActivity.refresh).
 */
class SetupSteps(private val act: Activity, private val ui: Ui) {
    private class Step(val title: String, val why: String?, val done: Boolean, val manual: Boolean = false, val go: () -> Unit)

    val box: LinearLayout = ui.cardBox(16)
    private var lastKey = ""

    companion object {
        /** OEM auto-start managers (the stock app list for "start in background"). Samsung: its battery page. */
        private val AUTOSTART = listOf(
            "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
            "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
            "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.samsung.android.lool" to "com.samsung.android.sm.battery.ui.BatteryActivity",
        )

        /** The auto-start page this phone has, or null (stock Android: nothing to do). */
        fun autostartIntent(a: Activity): Intent? = AUTOSTART.asSequence()
            .map { (p, c) -> Intent().setComponent(ComponentName(p, c)) }
            .firstOrNull { i -> runCatching { a.packageManager.resolveActivity(i, 0) != null }.getOrDefault(false) }

        /** Opens the battery / auto-start settings (the old "Cài đặt pin / tự khởi động" row). */
        fun openBattery(a: Activity) {
            autostartIntent(a)?.let { if (runCatching { a.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)) return }
            // App info has "Force stop": basic mode bounces it (self-protection), so it opens the battery list there
            if (!Policy.isOwner(a) && runCatching { a.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)) return
            runCatching { a.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    private fun open(vararg i: Intent) { for (x in i) if (runCatching { act.startActivity(x.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)) return }
    private fun s(id: Int) = act.getString(id).replace(Regex("^\\d+\\.\\s*"), "")   // old strings carry "3. ": the list numbers itself

    private fun steps(): List<Step> {
        val c = act
        val owner = Policy.isOwner(c)
        val pkg = Uri.parse("package:${c.packageName}")
        val out = ArrayList<Step>()
        if (!owner) out += Step(s(R.string.basic_admin), s(R.string.basic_admin_d), Basic.adminActive(c)) {
            c.startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, Policy.admin(c))
                .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, c.getString(R.string.basic_admin_expl)))
        }
        if (Build.VERSION.SDK_INT >= 33) out += Step(s(R.string.acc_restricted), s(R.string.acc_restricted_d), !Acc.restricted(c)) { c.startActivity(Acc.appInfo(c)) }
        out += Step(s(R.string.basic_acc), s(R.string.basic_acc_d), Acc.isOn(c)) { open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        out += Step(s(R.string.basic_usage), s(R.string.basic_usage_d), Usage.granted(c)) { open(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, pkg), Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
        if (!owner) {
            val fine = c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val locOk = Basic.locationOk(c)
            if (!fine || locOk) out += Step(s(R.string.basic_loc), s(R.string.basic_loc_d), locOk) {
                c.requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 41)
            } else out += Step(s(R.string.basic_loc_bg), s(R.string.basic_loc_bg_d), false) {
                if (Build.VERSION.SDK_INT >= 30) open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg))
                c.requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 42)
            }
            if (Build.VERSION.SDK_INT >= 33) out += Step(s(R.string.basic_notif), s(R.string.basic_notif_d), Basic.notifyOk(c)) {
                c.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 43)
            }
        }
        out += Step(s(R.string.basic_battery), s(R.string.basic_battery_d), Basic.batteryOk(c)) {
            open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg), Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
        autostartIntent(c)?.let { i ->
            out += Step(c.getString(R.string.step_autostart), c.getString(R.string.step_autostart_d), Store.of(c).autostartOk, manual = true) { open(i) }
        }
        return out
    }

    /** True while a step is left (the card shows). */
    fun update(): Boolean {
        val l = steps()
        val key = l.joinToString { "${it.title}:${it.done}" }
        val left = l.count { !it.done }
        if (key == lastKey) return left > 0
        lastKey = key
        box.removeAllViews()
        box.visibility = if (left > 0) View.VISIBLE else View.GONE
        if (left == 0) return false
        // header: title + "3/7", a thin progress bar
        val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(ui.label(act.getString(R.string.steps_title), 18f, ui.text, true), LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(ui.label(act.getString(R.string.steps_count, l.size - left, l.size), 14f, ui.accent, true))
        box.addView(head)
        box.addView(ui.label(act.getString(R.string.steps_intro), 14f).apply { setPadding(0, ui.dp(4), 0, 0) })
        val bar = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; weightSum = l.size.toFloat(); background = ui.shape(ui.stroke, 99) }
        bar.addView(View(act).apply { background = ui.shape(ui.primary, 99) }, LinearLayout.LayoutParams(0, -1, (l.size - left).toFloat()))
        box.addView(bar, LinearLayout.LayoutParams(-1, ui.dp(6)).apply { topMargin = ui.dp(12); bottomMargin = ui.dp(6) })
        val firstTodo = l.indexOfFirst { !it.done }
        // only what's left (the bar and "2/4" show the rest): at a big font scale done rows pushed "Hôm nay" off screen
        l.forEachIndexed { i, st -> if (!st.done) box.addView(row(i + 1, st, i == firstTodo)) }
        if (!Policy.isOwner(act)) box.addView(ui.label(act.getString(R.string.basic_play_protect), 12f).apply { setPadding(ui.dp(4), ui.dp(12), ui.dp(4), 0) })
        return true
    }

    /** One step: number (or a check once done), title, why; the next step to do is highlighted. */
    private fun row(n: Int, st: Step, next: Boolean): View {
        val ok = ui.tone(Ic.OK)
        val r = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10))
            minimumHeight = ui.dp(56)
            val base = if (next) ui.shape(ui.mix(ui.card, ui.primary, if (ui.dark) 0.16f else 0.08f), 16, ui.mix(ui.card, ui.primary, 0.45f), 1) else null
            background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(android.graphics.Color.argb(40, 128, 128, 128)), base, ui.shape(android.graphics.Color.WHITE, 16))
            isClickable = true; isFocusable = true
            setOnClickListener { runCatching { st.go() } }
            if (st.done) alpha = 0.6f
        }
        val num = if (st.done) ImageView(act).apply {
            setImageDrawable(Ic.get(act, R.drawable.li_check, ok, ui.dp(18))); scaleType = ImageView.ScaleType.CENTER
            background = ui.shape(ui.mix(ui.card, ok, if (ui.dark) 0.22f else 0.14f), 99)
        } else TextView(act).apply {
            text = n.toString(); textSize = 15f; gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (next) ui.onPrimary else ui.accent)
            background = ui.shape(if (next) ui.primary else ui.tint, 99)
        }
        r.addView(num, LinearLayout.LayoutParams(ui.dp(32), ui.dp(32)))
        val txt = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        txt.addView(ui.label(st.title, 16f, ui.text, true))
        if (!st.done && st.why != null) txt.addView(ui.label(st.why, 13f).apply { setPadding(0, ui.dp(2), 0, 0); setLineSpacing(0f, 1.1f) })
        r.addView(txt, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(12); marginEnd = ui.dp(6) })
        if (st.manual && !st.done) {
            // can't be read back from the phone: the child says it's on (under the text, so it doesn't squeeze it)
            txt.addView(TextView(act).apply {
                text = act.getString(R.string.step_done_btn); textSize = 14f; setTypeface(typeface, Typeface.BOLD); setTextColor(ui.onPrimary); gravity = Gravity.CENTER
                setPadding(ui.dp(14), 0, ui.dp(14), 0); minHeight = ui.dp(40)
                background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(android.graphics.Color.argb(60, 255, 255, 255)), ui.shape(ui.primary, 99), null)
                isClickable = true; isFocusable = true
                setOnClickListener { Store.of(act).autostartOk = true; update() }
            }, LinearLayout.LayoutParams(-2, ui.dp(40)).apply { topMargin = ui.dp(8) })
        }
        if (!st.done) r.addView(ImageView(act).apply { setImageDrawable(Ic.get(act, R.drawable.li_chevron_right, ui.sub, ui.dp(18))) })
        return r.apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(4) } }
    }
}
