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
        col.addView(u.label(getString(R.string.ssa_title), 26f, u.text, true))
        setContentView(ScrollView(this).apply { setBackgroundColor(u.bg); isFillViewport = true; addView(col) })
        window.statusBarColor = u.bg; window.navigationBarColor = u.bg
        if (!u.dark) window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or (if (Build.VERSION.SDK_INT >= 27) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0)

        if (Build.VERSION.SDK_INT < 30) {
            col.addView(u.cardBox().apply { addView(body(getString(R.string.ssa_old, Build.VERSION.RELEASE), u.text)) })
            return
        }
        col.addView(body(getString(R.string.ssa_intro)))

        /** One numbered step card: badge + title, explanation, its button. */
        fun step(n: Int, title: Int, desc: Int, btn: Int, icon: Int, f: () -> Unit) = u.cardBox().apply {
            val badge = TextView(context).apply {
                text = "$n"; gravity = Gravity.CENTER; textSize = 15f; setTypeface(typeface, Typeface.BOLD)
                setTextColor(u.accent); background = u.shape(u.tint, 999)
            }
            val note = u.label("", 14f, u.sub, true).apply { setPadding(0, dp(8), 0, 0); visibility = View.GONE }
            val button = u.button(getString(btn), icon, false, f)
            marks.add(StepMark(n, badge, note, button))
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
        col.addView(step(1, R.string.ssa_s1, R.string.ssa_s1_d, R.string.ssa_s1_btn, R.drawable.li_smartphone) { open(Settings.ACTION_DEVICE_INFO_SETTINGS) })
        col.addView(step(2, R.string.ssa_s2, R.string.ssa_s2_d, R.string.ssa_s2_btn, R.drawable.li_wifi) { open(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS) })
        val s3 = step(3, R.string.ssa_s3, R.string.ssa_s3_d, R.string.ssa_s3_btn, R.drawable.li_bell) { askNotification() }
        portInfo = body("").apply { textSize = 13f }
        s3.addView(portInfo)
        s3.addView(u.label(getString(R.string.ssa_or_here), 14f, u.text, true).apply { setPadding(0, dp(16), 0, 0) })
        fun field(hint: Int, len: Int) = EditText(this).apply {
            this.hint = getString(hint); inputType = InputType.TYPE_CLASS_NUMBER; filters = arrayOf(InputFilter.LengthFilter(len))
            textSize = 16f; setTextColor(u.text); setHintTextColor(u.sub); background = u.shape(u.bg, 14, u.stroke, 1)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
        }
        code = field(R.string.ssa_code_hint, 6).apply { textSize = 18f }
        // the port hints are long ("... leave empty ..."): smaller text so they fit one line on a 360 dp screen
        pairPort = field(R.string.ssa_pair_port_hint, 5).apply { textSize = 13f }
        connPort = field(R.string.ssa_conn_port_hint, 5).apply { textSize = 13f }
        listOf(code, pairPort, connPort).forEach { s3.addView(it) }
        wifiOff = CheckBox(this).apply {
            text = getString(R.string.ssa_wifi_off); isChecked = true; setTextColor(u.text); textSize = 15f
            buttonTintList = ColorStateList.valueOf(u.primary)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
        }
        s3.addView(wifiOff)
        start = u.button(getString(R.string.ssa_start), R.drawable.li_play, true) { go() }
        s3.addView(start)
        col.addView(s3)

        val prog = u.cardBox()
        prog.addView(u.label(getString(R.string.ssa_progress), 17f, u.text, true))
        for (s in SelfSetup.STEPS) {
            val r = u.label(getString(s), 15f, u.text).apply { compoundDrawablePadding = dp(12); gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, dp(8)) }
            rows.add(r); prog.addView(r)
        }
        result = u.label("", 15f, u.text, true).apply { setPadding(0, dp(8), 0, 0) }
        prog.addView(result)
        col.addView(prog)

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    override fun onResume() { super.onResume(); if (Build.VERSION.SDK_INT >= 30) { SelfSetup.startDiscovery(this); tick() } }
    override fun onPause() { super.onPause(); ui.removeCallbacksAndMessages(null) }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing && !SelfSetup.running) SelfSetup.stopDiscovery()
    }

    private fun open(action: String) {
        runCatching { startActivity(Intent(action)) }.onFailure { runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) } }
    }

    private fun askNotification() {
        SelfSetup.pairOnly = false   // the full setup, not SelfAdb.pair
        SelfSetup.startDiscovery(this)
        SelfSetup.askCodeNotification(this)
        open(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
    }

    private fun go() {
        val c = code.text.toString().filter { it.isDigit() }
        if (c.length != 6) { result.text = getString(R.string.ssa_code_6); return }
        SelfSetup.turnOffWifi = wifiOff.isChecked
        SelfSetup.start(this, c, pairPort.text.toString().toIntOrNull() ?: 0, connPort.text.toString().toIntOrNull() ?: 0)
        tick()
    }

    private class StepMark(val n: Int, val badge: TextView, val note: TextView, val button: View)
    private val marks = ArrayList<StepMark>()

    /**
     * Live status per step (owner 2026-11-11: "bật dev mode rồi sao nó vẫn hiện nút"): a done step gets a green tick,
     * a note and no button; step 2 warns when the phone is not on Wi-Fi (Wireless debugging needs it).
     */
    private fun stepMarks() {
        val g = { k: String -> runCatching { Settings.Global.getInt(contentResolver, k, 0) == 1 }.getOrDefault(false) }
        val wifi = runCatching { getSystemService(android.net.ConnectivityManager::class.java).let { cm ->
            cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true } }.getOrDefault(false)
        for (m in marks) {
            val (done, note) = when (m.n) {
                1 -> g(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED).let { it to if (it) getString(R.string.ssa_s1_ok) else null }
                2 -> g("adb_wifi_enabled").let { on -> (on && wifi) to when { on && wifi -> getString(R.string.ssa_s2_ok); !wifi -> getString(R.string.ssa_need_wifi); else -> null } }
                else -> SelfAdb.hasKey(this).let { it to if (it) getString(R.string.ssa_s3_ok) else null }
            }
            m.badge.text = if (done) "✓" else "${m.n}"
            m.badge.setTextColor(if (done) u.bg else u.accent)
            m.badge.background = u.shape(if (done) u.tone(Ic.OK) else u.tint, 999)
            m.note.visibility = if (note != null) View.VISIBLE else View.GONE
            m.note.text = note ?: ""; m.note.setTextColor(if (done) u.tone(Ic.OK) else u.danger)
            if (m.n != 3) m.button.visibility = if (done) View.GONE else View.VISIBLE   // step 3's button re-pairs: keep it
        }
    }

    private fun tick() {
        stepMarks()
        val pp = SelfSetup.pairingPort; val cp = SelfSetup.connectPort
        portInfo.text = (if (pp > 0) getString(R.string.ssa_pair_seen, pp) else getString(R.string.ssa_pair_none)) + " " +
            (if (cp > 0) getString(R.string.ssa_wd_on, cp) else getString(R.string.ssa_wd_off))
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
        start.isEnabled = !SelfSetup.running; start.alpha = if (SelfSetup.running) 0.5f else 1f
        result.text = SelfSetup.message
        result.setTextColor(if (SelfSetup.done) u.tone(Ic.OK) else u.danger)
        if (SelfSetup.done) {
            SelfSetup.done = false
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK))
            finish(); return
        }
        ui.postDelayed(::tick, 1000)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
