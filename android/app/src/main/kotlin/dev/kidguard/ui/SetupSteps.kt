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
    /** [confirm]: a step the phone can't read back; the "Đã bật" button calls it. */
    private class Step(val title: String, val why: String?, val done: Boolean, val confirm: (() -> Unit)? = null, val pair: Boolean = false, val go: () -> Unit)

    val box: LinearLayout = ui.cardBox(16)
    private var lastKey = ""

    companion object {
        /** OEM auto-start managers (the stock app list for "start in background"). Samsung: its battery page. */
        private val AUTOSTART = listOf(
            "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
            "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
            "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.view.StartupAppListActivity",
            "com.oplus.safecenter" to "com.oplus.safecenter.startupapp.view.StartupAppListActivity",
            "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
            "com.oneplus.security" to "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
            "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
            "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity",
            "com.asus.mobilemanager" to "com.asus.mobilemanager.autostart.AutoStartActivity",
            "com.samsung.android.lool" to "com.samsung.android.sm.battery.ui.BatteryActivity",
        )

        /** The auto-start page this phone has, or null (stock Android: nothing to do). */
        fun autostartIntent(a: Activity): Intent? = AUTOSTART.asSequence()
            .map { (p, c) -> Intent().setComponent(ComponentName(p, c)) }
            // ColorOS 11+ / realme UI keep some of these private (not exported or behind a signature permission): opening them
            // threw, so the step did nothing, or never showed (Nam's realme, owner 2026-11-17)
            .firstOrNull { i -> runCatching { a.packageManager.resolveActivity(i, 0)?.activityInfo?.let { it.exported && it.permission == null } == true }.getOrDefault(false) }

        private val BRAND get() = (Build.MANUFACTURER + " " + Build.BRAND).lowercase()
        /** OPPO / realme / OnePlus (ColorOS family): auto-start + "background activity" sit in the app's own page there. */
        val colorOs get() = listOf("oppo", "realme", "oneplus").any { it in BRAND }
        /** Phones whose own battery manager kills background apps even with Android's battery optimisation off. */
        val oemKiller get() = colorOs || listOf("xiaomi", "redmi", "poco", "vivo", "iqoo", "huawei", "honor").any { it in BRAND }

        /** MIUI / HyperOS "Battery saver" page of this app (No restrictions), or null where it isn't there. */
        fun miuiBg(a: Activity): Intent? = Intent().setComponent(ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"))
            .putExtra("package_name", a.packageName).putExtra("package_label", a.applicationInfo.loadLabel(a.packageManager).toString())
            .takeIf { i -> runCatching { a.packageManager.resolveActivity(i, 0)?.activityInfo?.exported == true }.getOrDefault(false) }

        /**
         * Buttons of the "Pin và tự khởi động" card (Bảo vệ và quyền): always there, so a step ticked "Đã bật" by mistake
         * can still be checked (owner 2026-11-20). Auto-start page, then the battery page (MIUI's own one when present).
         */
        fun bgActions(a: Activity): List<Pair<Int, () -> Unit>> {
            fun go(i: Intent) = runCatching { a.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)
            val out = mutableListOf<Pair<Int, () -> Unit>>()
            autostartIntent(a)?.let { i -> out += R.string.ft_bg_auto to { if (!go(i)) runCatching { a.startActivity(Intents.appDetails(a)) } } }
            val bat = miuiBg(a) ?: if (!Policy.isOwner(a)) Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) else null
            if (bat != null) out += R.string.ft_bg_battery to { if (!go(bat)) runCatching { a.startActivity(Intents.appDetails(a)) } }
            if (out.isEmpty()) out += R.string.ft_battery_open to { openBattery(a) }
            return out
        }

        /** Opens the battery / auto-start settings (the old "Cài đặt pin / tự khởi động" row). */
        fun openBattery(a: Activity) {
            autostartIntent(a)?.let { if (runCatching { a.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)) return }
            // App info has "Force stop": basic mode bounces it (self-protection), so it opens the battery list there
            if (!Policy.isOwner(a) && runCatching { a.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)) return
            runCatching { a.startActivity(Intents.appDetails(a)) }
        }
    }

    private fun usable(i: Intent) = runCatching { act.packageManager.resolveActivity(i, 0)?.activityInfo?.exported == true }.getOrDefault(false)
    /** KidGuard's own App info: basic mode's self-protection closes it (it has Force stop), so during first setup
     *  (never paired = the parent holds the phone) it opens the same 5-minute window the parent's selfOpen command does. */
    private fun openOwn(i: Intent) {
        val s = Store.of(act)
        if (!Policy.isOwner(act) && !s.everPaired) s.selfOpenUntil = System.currentTimeMillis() + CmdGate.SELF_OPEN_MS
        open(i)
    }
    private fun open(vararg i: Intent) = SelfGate.run(act, ui) { openNow(*i) }
    private fun openNow(vararg i: Intent) { Intents.startFirst(act, *i) }
    private fun cap(id: String) = open(*Intents.forCap(act, id).toTypedArray())
    /** Asks for [perms]; once Android stops showing its dialog (denied twice) the tap opens the app's settings page instead. */
    private fun request(code: Int, vararg perms: String) {
        val p = act.getSharedPreferences("setup_ask", android.content.Context.MODE_PRIVATE)
        val silent = p.getBoolean(perms[0], false) && perms.none { act.shouldShowRequestPermissionRationale(it) }
        if (silent) return open(Intents.appDetails(act))
        p.edit().putBoolean(perms[0], true).apply()
        act.requestPermissions(perms, code)
    }
    private fun s(id: Int) = act.getString(id).replace(Regex("^\\d+\\.\\s*"), "")   // old strings carry "3. ": the list numbers itself

    private fun steps(): List<Step> {
        val c = act
        val owner = Policy.isOwner(c)
        val out = ArrayList<Step>()
        // restricted settings FIRST: an APK installed from a file gets neither device admin nor accessibility until it is
        // allowed (2026-11-19, Xiaomi: the admin step was refused, "App was denied access", and this step came after it)
        if (Build.VERSION.SDK_INT >= 33) out += Step(s(R.string.acc_restricted), s(R.string.acc_restricted_d), !Acc.restricted(c)) { SelfGate.run(act, ui) { runCatching { c.startActivity(Acc.appInfo(c)) } } }
        if (!owner) out += Step(s(R.string.basic_admin), s(R.string.basic_admin_d), Basic.adminActive(c)) {
            Intents.startFirst(c, *Intents.forCap(c, "admin").toTypedArray())
        }
        out += Step(s(R.string.basic_acc), s(R.string.basic_acc_d), Acc.isOn(c) || AccPause.active(c)) { cap("acc") }
        out += Step(s(R.string.basic_usage), s(R.string.basic_usage_d), Usage.granted(c)) { cap("usage") }
        if (!owner) {
            val fine = c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val locOk = Basic.locationOk(c)
            if (!fine || locOk) out += Step(s(R.string.basic_loc), s(R.string.basic_loc_d), locOk) {
                request(41, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            } else out += Step(s(R.string.basic_loc_bg), s(R.string.basic_loc_bg_d), false) {
                if (Build.VERSION.SDK_INT >= 30) open(Intents.appDetails(c))
                c.requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 42)
            }
            if (Build.VERSION.SDK_INT >= 33) out += Step(s(R.string.basic_notif), s(R.string.basic_notif_d), Basic.notifyOk(c)) {
                request(43, Manifest.permission.POST_NOTIFICATIONS)
            }
            // mic ("listen around" after an SOS) + calls card: adb setups grant them, a manual / failed setup never asked
            // (owner 2026-11-19, Xiaomi: the page then said "missing: Micro, Calls" with no step on the phone)
            if (!Flavor.HOME) out += Step(s(R.string.basic_mic), s(R.string.basic_mic_d), c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                request(44, Manifest.permission.RECORD_AUDIO)
            }
            if (!Flavor.HOME) out += Step(s(R.string.basic_calls), s(R.string.basic_calls_d), Calls.canReadLog(c) && Calls.canReadContacts(c)) {
                request(45, Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_CONTACTS)
            }
        }
        out += Step(s(R.string.basic_battery), s(R.string.basic_battery_d), Basic.batteryOk(c)) {
            cap("battery")
        }
        // basic mode, Android 12+: updates install by themselves (Update.installQuiet) instead of asking the child later
        if (!owner && Build.VERSION.SDK_INT >= 31) out += Step(s(R.string.basic_update), s(R.string.basic_update_d), c.packageManager.canRequestPackageInstalls()) {
            cap("install")
        }
        // OEM battery managers kill KidGuard even with the steps above done (Nam's realme went silent for hours, owner
        // 2026-11-17): auto-start, the OEM's own "background activity" switch and the Recents lock. None can be read back.
        val st = Store.of(c)
        val info = Intents.appDetails(c)
        val auto = autostartIntent(c)
        if (auto != null) out += Step(c.getString(R.string.step_autostart), c.getString(R.string.step_autostart_d), st.autostartOk, { st.autostartOk = true }) { open(auto) }
        else if (colorOs) out += Step(c.getString(R.string.step_autostart), c.getString(R.string.step_autostart_info_d), st.autostartOk, { st.autostartOk = true }) { openOwn(info) }
        // Neither can be read back, so a phone already set up only gets them once it was really killed (owner 2026-11-17:
        // the Poco, never killed, showed both as to-dos)
        if (oemKiller && (!st.everPaired || st.killSeen > 0)) {
            val miui = miuiBg(act)
            out += Step(c.getString(R.string.step_bg), c.getString(R.string.step_bg_d), st.oemBgOk, { st.oemBgOk = true }) {
                if (miui == null || !runCatching { act.startActivity(miui.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)) openOwn(info)
            }
            out += Step(c.getString(R.string.step_recents), c.getString(R.string.step_recents_d), st.recentsOk, { st.recentsOk = true }) {
                Acc.running?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS)
            }
        }
        // last row: pair the parent's phone (A4.1). Done once paired; the code + QR open by themselves when the rest is done.
        if (!Flavor.HOME) out += Step(c.getString(R.string.step_pair_parent), c.getString(R.string.step_pair_parent_d), Store.of(c).everPaired, pair = true) { revealPairing() }
        return out
    }

    private fun prefs() = act.getSharedPreferences("setup_ask", android.content.Context.MODE_PRIVATE)
    private fun revealPairing() { Store.of(act).codeRevealUntil = System.currentTimeMillis() + 10 * 60_000L }

    /** First run on a phone that is not set up and not paired: "Full (recommended)" vs "Basic" (A4.2). Full needs Android 11+. */
    private fun chooserPending(): Boolean = !Flavor.HOME && Build.VERSION.SDK_INT >= 30 && !Policy.isOwner(act) &&
        !Store.of(act).everPaired && !prefs().getBoolean("firstChoice", false) && !Basic.adminActive(act)

    /**
     * First run (Android 11+): basic (keeps every account) or Device Owner, each with what it can and can't do (owner
     * 2026-11-18: "đa số sẽ chọn cơ bản để ko mất account"). Both run on the phone's own Wireless debugging, the fastest
     * path; basic can also be done by hand (no Wi-Fi) with the checklist.
     */
    private fun chooser() {
        box.removeAllViews(); box.visibility = View.VISIBLE
        box.addView(ui.label(act.getString(R.string.fr_title), 18f, ui.text, true))
        fun option(title: Int, pros: Int, cons: Int) {
            box.addView(ui.label(act.getString(title), 16f, ui.text, true).apply { setPadding(0, ui.dp(18), 0, ui.dp(2)) })
            box.addView(ui.label(act.getString(pros), 13f).apply { setPadding(0, ui.dp(4), 0, 0); setLineSpacing(0f, 1.1f) })
            box.addView(ui.label(act.getString(cons), 13f).apply { setPadding(0, ui.dp(4), 0, ui.dp(8)); setLineSpacing(0f, 1.1f) })
        }
        fun btn(t: Int, icon: Int, primary: Boolean, run: () -> Unit) =
            box.addView(ui.button(act.getString(t), icon, primary) { run() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(6) })
        fun self(basic: Boolean) = SelfGate.run(act, ui) { act.startActivity(Intent(act, SelfSetupActivity::class.java).putExtra(SelfSetupActivity.EXTRA_BASIC, basic)) }
        option(R.string.fr_basic, R.string.fr_basic_pro, R.string.fr_basic_con)
        btn(R.string.fr_basic_wifi, R.drawable.li_wifi, true) { self(true) }
        btn(R.string.fr_basic_hand, R.drawable.li_list_checks, false) { prefs().edit().putBoolean("firstChoice", true).apply(); lastKey = ""; update() }
        option(R.string.fr_full, R.string.fr_full_pro, R.string.fr_full_con)
        btn(R.string.fr_full_go, R.drawable.li_shield, false) { self(false) }
    }

    /** True while a step is left (the card shows). */
    fun update(): Boolean {
        if (chooserPending()) { if (lastKey != "chooser") { lastKey = "chooser"; chooser() }; return true }
        val l = steps()
        // everything but the pairing row is done: show the code + QR once, for 10 minutes (the parent is holding the phone)
        if (!Flavor.HOME && l.size > 1 && l.dropLast(1).all { it.done } && !Store.of(act).everPaired && !prefs().getBoolean("pairAuto", false)) {
            prefs().edit().putBoolean("pairAuto", true).apply(); revealPairing()
        }
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
        // Android 10 and older: no Wireless debugging pairing, so no self-setup; a computer does every step at once (owner 2026-11-19)
        if (!Flavor.HOME && Build.VERSION.SDK_INT < 30 && !Policy.isOwner(act) && !Basic.adminActive(act))
            box.addView(ui.label(act.getString(R.string.steps_pc), 13f, ui.text).apply { setPadding(0, ui.dp(8), 0, 0) })
        // Android 11+, basic: a step left that self setup does (all but pairing) -> the Wi-Fi run is one tap here too, not only in
        // the ⋯ menu (owner 2026-11-20: after a half-done Wi-Fi run the card showed only the manual steps)
        if (!Flavor.HOME && Build.VERSION.SDK_INT >= 30 && !Policy.isOwner(act) && l.any { !it.done && !it.pair })
            box.addView(ui.button(act.getString(R.string.fr_basic_wifi), R.drawable.li_wifi, true) {
                SelfGate.run(act, ui) { act.startActivity(Intent(act, SelfSetupActivity::class.java).putExtra(SelfSetupActivity.EXTRA_BASIC, true)) }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(10) })
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
        if (st.confirm != null && !st.done) {
            // can't be read back from the phone: the child says it's on (under the text, so it doesn't squeeze it)
            txt.addView(TextView(act).apply {
                text = act.getString(R.string.step_done_btn); textSize = 14f; setTypeface(typeface, Typeface.BOLD); setTextColor(ui.onPrimary); gravity = Gravity.CENTER
                setPadding(ui.dp(14), 0, ui.dp(14), 0); minHeight = ui.dp(40)
                background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(android.graphics.Color.argb(60, 255, 255, 255)), ui.shape(ui.primary, 99), null)
                isClickable = true; isFocusable = true
                setOnClickListener { st.confirm.invoke(); update() }
            }, LinearLayout.LayoutParams(-2, ui.dp(40)).apply { topMargin = ui.dp(8) })
        }
        if (!st.done) r.addView(ImageView(act).apply { setImageDrawable(Ic.get(act, R.drawable.li_chevron_right, ui.sub, ui.dp(18))) })
        return r.apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(4) } }
    }
}
