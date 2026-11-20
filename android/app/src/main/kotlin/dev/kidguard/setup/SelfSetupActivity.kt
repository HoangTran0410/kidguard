package dev.kidguard

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Sets KidGuard up without a computer (Android 11+): the phone runs adb against itself over Wireless debugging,
 * then does the same steps as setup.sh. The work lives in [SelfSetup]; this screen only shows it.
 */
class SelfSetupActivity : Activity() {
    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(Lang.wrap(base))   // in-app language (Android 8-12)
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var portInfo: TextView
    private lateinit var code: EditText
    private lateinit var pairPort: EditText
    private lateinit var connPort: EditText
    private lateinit var wifiOff: CheckBox
    private lateinit var start: android.widget.Button
    private lateinit var result: TextView
    private val rows = ArrayList<TextView>()

    private lateinit var u: Ui

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        u = Ui(this)
        // same look as the child screens (MainActivity): app background, cards, rounded buttons, dark mode
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(28), dp(18), dp(28)) }
        fun body(t: String, color: Int = u.sub) = u.label(t, 15f, color).apply { setLineSpacing(0f, 1.2f); setPadding(0, dp(6), 0, 0) }
        pairMode = intent.getBooleanExtra(EXTRA_PAIR, false)
        basicMode = !pairMode && intent.getBooleanExtra(EXTRA_BASIC, false)
        col.addView(u.label(getString(if (pairMode) R.string.ssa_pair_title else if (basicMode) R.string.ssa_basic_title else R.string.ssa_title), 26f, u.text, true))
        setContentView(ScrollView(this).apply { setBackgroundColor(u.bg); isFillViewport = true; addView(col) })
        window.statusBarColor = u.bg; window.navigationBarColor = u.bg
        if (!u.dark) window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or (if (Build.VERSION.SDK_INT >= 27) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0)

        if (Build.VERSION.SDK_INT < 30) {
            col.addView(u.cardBox().apply { addView(body(getString(R.string.ssa_old, Build.VERSION.RELEASE), u.text)) })
            return
        }
        col.addView(body(getString(if (pairMode) R.string.ssa_pair_intro else if (basicMode) R.string.ssa_basic_intro else R.string.ssa_intro)))

        /** One numbered step card: badge + title, explanation, its button. */
        fun step(kind: Int, n: Int, title: Int, desc: Int, btn: Int, icon: Int, f: () -> Unit) = u.cardBox().apply {
            val badge = TextView(context).apply {
                text = "$n"; gravity = Gravity.CENTER; textSize = 15f; setTypeface(typeface, Typeface.BOLD)
                setTextColor(u.accent); background = u.shape(u.tint, 999)
            }
            val note = u.label("", 14f, u.sub, true).apply { setPadding(0, dp(8), 0, 0); visibility = View.GONE }
            val button = u.button(getString(btn), icon, false, f)
            marks.add(StepMark(kind, n, badge, note, button))
            addView(LinearLayout(this@SelfSetupActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(badge, LinearLayout.LayoutParams(dp(30), dp(30)))
                addView(u.label(getString(title).replace(Regex("^[^.]*\\.\\s*"), ""), 17f, u.text, true),
                    LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
            })
            addView(body(getString(desc)))
            addView(note)
            addView(button)
        }
        col.addView(step(K_DEV, 1, R.string.ssa_s1, R.string.ssa_s1_d, R.string.ssa_s1_btn, R.drawable.li_smartphone) { open(Settings.ACTION_DEVICE_INFO_SETTINGS) })
        // ColorOS (Oppo / Realme / OnePlus): appops / pm grant fail until "Disable permission monitoring" is on (2026-11-20 owner,
        // new Oppo): its own step before pairing; the run also checks it over adb and waits here if it is still off
        colorOs = !pairMode && PermMon.colorOs()   // pair-only runs grant nothing
        if (colorOs) col.addView(step(K_PERM, 2, R.string.ssa_pm, R.string.ssa_pm_d, R.string.ssa_pm_btn, R.drawable.li_shield) { PermMon.open(this) })
        // owner 2026-11-18: a separate "turn on Wireless debugging" step was redundant, the pairing step's button opens the same page
        val s3 = step(K_PAIR, if (colorOs) 3 else 2, R.string.ssa_s3, R.string.ssa_s3_d, R.string.ssa_s3_btn, R.drawable.li_bell) { askNotification() }
        // Xiaomi / Redmi / POCO: adb may not grant anything until "USB debugging (Security settings)" is on (2026-11-19 owner:
        // dpm set-active-admin -> "Calling identity is not authorized"); say it before the run, not after it fails
        if (NavGestLogic.xiaomi(android.os.Build.MANUFACTURER)) s3.addView(body(getString(R.string.ssa_xiaomi), u.text))
        portInfo = body("").apply { textSize = 13f }
        s3.addView(portInfo)
        s3.addView(u.label(getString(R.string.ssa_or_here), 14f, u.text, true).apply { setPadding(0, dp(16), 0, 0) })
        fun field(hint: Int, len: Int) = EditText(this).apply {
            this.hint = getString(hint); inputType = InputType.TYPE_CLASS_NUMBER; filters = arrayOf(InputFilter.LengthFilter(len))
            textSize = 16f; setTextColor(u.text); setHintTextColor(u.sub); background = u.shape(u.bg, 14, u.stroke, 1)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
        }
        code = field(R.string.ssa_code_hint, 6).apply { textSize = 18f
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(e: android.text.Editable?) { startLook() }
                override fun beforeTextChanged(c: CharSequence?, a: Int, b: Int, d: Int) {}
                override fun onTextChanged(c: CharSequence?, a: Int, b: Int, d: Int) {}
            })
        }
        // the port hints are long ("... leave empty ..."): smaller text so they fit one line on a 360 dp screen
        pairPort = field(R.string.ssa_pair_port_hint, 5).apply { textSize = 13f }
        connPort = field(R.string.ssa_conn_port_hint, 5).apply { textSize = 13f }
        listOf(code, pairPort, connPort).forEach { s3.addView(it) }
        wifiOff = CheckBox(this).apply {
            text = getString(R.string.ssa_wifi_off); isChecked = true; setTextColor(u.text); textSize = 15f
            buttonTintList = ColorStateList.valueOf(u.primary)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
        }
        // the code is usually typed in the notification (PairCodeReceiver), never through go(): the box must count there too
        SelfSetup.turnOffWifi = !pairMode && wifiOff.isChecked
        wifiOff.setOnCheckedChangeListener { _, on -> SelfSetup.turnOffWifi = !pairMode && on }
        if (!pairMode) s3.addView(wifiOff)   // pair mode never reaches the step it is for
        start = u.button(getString(R.string.ssa_start), R.drawable.li_play, true) { go() }
        s3.addView(start)
        col.addView(s3)

        val prog = u.cardBox()
        prog.addView(u.label(getString(R.string.ssa_progress), 17f, u.text, true))
        for (s in if (pairMode) SelfSetup.STEPS.take(1).toIntArray() else SelfSetup.STEPS) {
            val r = u.label(getString(s), 15f, u.text).apply { compoundDrawablePadding = dp(12); gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, dp(8)) }
            rows.add(r); prog.addView(r)
        }
        result = u.label("", 15f, u.text, true).apply { setPadding(0, dp(8), 0, 0) }
        prog.addView(result)
        col.addView(prog)

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    override fun onResume() { super.onResume(); if (Build.VERSION.SDK_INT >= 30) { SelfSetup.startDiscovery(this); SelfSetup.recheck(); tick() } }
    override fun onPause() { super.onPause(); ui.removeCallbacksAndMessages(null) }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing && !SelfSetup.running) SelfSetup.stopDiscovery()
    }

    private fun open(action: String) {
        Intents.startFirst(this, Intent(action), Intent(Settings.ACTION_SETTINGS))
    }

    private fun askNotification() {
        SelfSetup.pairOnly = pairMode   // pair mode (Dọn máy without Device Owner): pair + connect only
        SelfSetup.wantBasic = basicMode   // read by start(), also when the code comes through the notification
        SelfSetup.startDiscovery(this)
        SelfSetup.askCodeNotification(this)
        openDev()
    }
    private fun openDev() {
        // step 1 skipped: without Developer options the action lands on the Settings home (owner 2026-11-20, new phone); send them to step 1's page
        if (runCatching { Settings.Global.getInt(contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) != 1 }.getOrDefault(false)) {
            Toasts.show(this, getString(R.string.ssa_dev_first)); open(Settings.ACTION_DEVICE_INFO_SETTINGS); return
        }
        Intents.startFirst(this, DevOpts.pageIntent(this), Intent(Settings.ACTION_SETTINGS))
    }

    private fun go() {
        val c = code.text.toString().filter { it.isDigit() }
        // the result line is at the bottom, off screen: say it where the finger is, and put the cursor in the box
        if (c.length != 6) { result.text = getString(R.string.ssa_code_6); Toasts.show(this, getString(R.string.ssa_code_6)); code.requestFocus(); return }
        SelfSetup.turnOffWifi = !pairMode && wifiOff.isChecked
        SelfSetup.wantBasic = basicMode
        SelfSetup.start(this, c, pairPort.text.toString().toIntOrNull() ?: 0, connPort.text.toString().toIntOrNull() ?: 0, pairOnly = pairMode)
        tick()
    }

    private var pairMode = false
    private var basicMode = false
    private var colorOs = false

    companion object {
        /** true: only pair KidGuard's own adb (FeaturesSheet "adb trên máy"), none of the Device Owner steps. */
        const val EXTRA_PAIR = "pair"
        /** Basic mode chosen on the first-run screen: keep every account, skip Device Owner. */
        const val EXTRA_BASIC = "basic"
        private const val K_DEV = 0; private const val K_PERM = 1; private const val K_PAIR = 2
    }

    private class StepMark(val kind: Int, val n: Int, val badge: TextView, val note: TextView, val button: View)
    private val marks = ArrayList<StepMark>()

    /**
     * Live status per step (owner 2026-11-11: "bật dev mode rồi sao nó vẫn hiện nút"): a done step gets a green tick,
     * a note and no button; step 2 (pairing) warns when the phone is not on Wi-Fi (Wireless debugging needs it).
     */
    private fun stepMarks() {
        val g = { k: String -> runCatching { Settings.Global.getInt(contentResolver, k, 0) == 1 }.getOrDefault(false) }
        val wifi = SelfAdb.onWifi(this)   // all networks: with a VPN (Tailscale) the active one is not Wi-Fi
        for (m in marks) {
            val (done, note) = when (m.kind) {
                K_DEV -> g(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED).let { it to if (it) getString(R.string.ssa_s1_ok) else null }
                // done only once the adb probe passed; the system prop is a hint (meaning unverified), the run waits here if it is off
                K_PERM -> when {
                    SelfSetup.permMonWait -> false to getString(R.string.ssa_pm_wait)
                    SelfSetup.permMonOk -> true to getString(R.string.ssa_pm_ok)
                    PermMon.hintOn() == false -> false to getString(R.string.ssa_pm_maybe)
                    else -> false to null
                }
                // not paired yet: say what blocks it (no Wi-Fi), or that Wireless debugging is on already
                else -> SelfAdb.hasKey(this).let { k -> k to when { k -> getString(R.string.ssa_s3_ok); !wifi -> getString(R.string.ssa_need_wifi)
                    g("adb_wifi_enabled") -> getString(R.string.ssa_s2_ok); else -> null } }
            }
            m.badge.text = if (done) "✓" else "${m.n}"
            m.badge.setTextColor(if (done) u.bg else u.accent)
            m.badge.background = u.shape(if (done) u.tone(Ic.OK) else u.tint, 999)
            m.note.visibility = if (note != null) View.VISIBLE else View.GONE
            m.note.text = note ?: ""; m.note.setTextColor(if (done) u.tone(Ic.OK) else if (note == getString(R.string.ssa_s2_ok) || note == getString(R.string.ssa_pm_maybe)) u.sub else u.danger)
            if (m.kind != K_PAIR) m.button.visibility = if (done) View.GONE else View.VISIBLE   // step 2 (pairing) button re-pairs: keep it
        }
    }

    private fun tick() {
        stepMarks()
        val pp = SelfSetup.pairingPort; val cp = SelfSetup.connectPort
        portInfo.text = (if (pp > 0) getString(R.string.ssa_pair_seen, pp) else getString(R.string.ssa_pair_none)) + " " +
            (if (cp > 0) getString(R.string.ssa_wd_on, cp) else getString(R.string.ssa_wd_off))
        if (!pairMode && rows.size > 2) rows[2].text = getString(if (SelfSetup.basic) R.string.step_admin else R.string.step_owner)
        for (i in rows.indices) {
            val (icon, tint) = when (SelfSetup.state[i]) {
                SelfSetup.STEP_OK -> R.drawable.ic_ok to u.tone(Ic.OK)
                SelfSetup.STEP_ERR -> R.drawable.ic_err to u.danger
                SelfSetup.STEP_RUN -> R.drawable.ic_run to u.accent
                else -> R.drawable.ic_todo to u.sub
            }
            rows[i].setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
            rows[i].compoundDrawableTintList = ColorStateList.valueOf(tint)
            rows[i].setTextColor(if (SelfSetup.state[i] == SelfSetup.STEP_ERR) u.danger else u.text)
        }
        startLook()
        result.text = SelfSetup.message
        result.setTextColor(if (SelfSetup.done) u.tone(Ic.OK) else u.danger)
        if (SelfSetup.done) {
            SelfSetup.done = false
            // the parent is holding the phone: show the pairing code + QR on the next screen for 10 minutes (A4.1)
            if (!pairMode && !Store.of(this).everPaired) Store.of(this).codeRevealUntil = System.currentTimeMillis() + 10 * 60_000L
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK))
            finish(); return
        }
        ui.postDelayed(::tick, 1000)
    }

    /**
     * Owner 2026-11-18: the button looked ready with no code typed and did nothing. Dimmed until the code has 6 digits;
     * still tappable then (toast + cursor in the box), off only while a run is going.
     */
    private fun startLook() {
        if (!::start.isInitialized) return
        start.isEnabled = !SelfSetup.running
        start.alpha = if (SelfSetup.running || code.text.count { it.isDigit() } != 6) 0.45f else 1f
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
