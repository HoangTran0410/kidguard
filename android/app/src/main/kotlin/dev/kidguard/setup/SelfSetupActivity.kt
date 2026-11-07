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
import android.view.View
import android.widget.Button
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
    private lateinit var start: Button
    private lateinit var result: TextView
    private val rows = ArrayList<TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(40), dp(20), dp(24)) }
        fun text(t: String, size: Float = 15f, bold: Boolean = false, color: String = "#374151") = TextView(this).apply {
            this.text = t; textSize = size; setTextColor(Color.parseColor(color)); if (bold) setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(6), 0, dp(6))
        }
        fun button(t: String, f: () -> Unit) = Button(this).apply { text = t; isAllCaps = false; setOnClickListener { f() } }
        col.addView(text(getString(R.string.ssa_title), 24f, true, "#111827"))

        if (Build.VERSION.SDK_INT < 30) {
            col.addView(text(getString(R.string.ssa_old, Build.VERSION.RELEASE)))
            setContentView(ScrollView(this).apply { setBackgroundColor(Color.WHITE); addView(col) })
            return
        }
        col.addView(text(getString(R.string.ssa_intro)))

        col.addView(text(getString(R.string.ssa_s1), 17f, true, "#111827"))
        col.addView(text(getString(R.string.ssa_s1_d)))
        col.addView(button(getString(R.string.ssa_s1_btn)) { open(Settings.ACTION_DEVICE_INFO_SETTINGS) })

        col.addView(text(getString(R.string.ssa_s2), 17f, true, "#111827"))
        col.addView(text(getString(R.string.ssa_s2_d)))
        col.addView(button(getString(R.string.ssa_s2_btn)) { open(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS) })

        col.addView(text(getString(R.string.ssa_s3), 17f, true, "#111827"))
        col.addView(text(getString(R.string.ssa_s3_d)))
        col.addView(button(getString(R.string.ssa_s3_btn)) { askNotification() })
        portInfo = text("", 14f)
        col.addView(portInfo)

        col.addView(text(getString(R.string.ssa_or_here), 14f))
        code = EditText(this).apply { hint = getString(R.string.ssa_code_hint); inputType = InputType.TYPE_CLASS_NUMBER; filters = arrayOf(InputFilter.LengthFilter(6)) }
        col.addView(code)
        pairPort = EditText(this).apply { hint = getString(R.string.ssa_pair_port_hint); inputType = InputType.TYPE_CLASS_NUMBER; filters = arrayOf(InputFilter.LengthFilter(5)) }
        col.addView(pairPort)
        connPort = EditText(this).apply { hint = getString(R.string.ssa_conn_port_hint); inputType = InputType.TYPE_CLASS_NUMBER; filters = arrayOf(InputFilter.LengthFilter(5)) }
        col.addView(connPort)
        wifiOff = CheckBox(this).apply { text = getString(R.string.ssa_wifi_off); isChecked = true }
        col.addView(wifiOff)
        start = button(getString(R.string.ssa_start)) { go() }
        col.addView(start)

        col.addView(text(getString(R.string.ssa_progress), 17f, true, "#111827"))
        for (s in SelfSetup.STEPS) { val r = text(getString(s), 15f); r.compoundDrawablePadding = dp(10); rows.add(r); col.addView(r) }
        result = text("", 15f, true)
        col.addView(result)
        setContentView(ScrollView(this).apply { setBackgroundColor(Color.WHITE); addView(col) })

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

    private fun tick() {
        val pp = SelfSetup.pairingPort; val cp = SelfSetup.connectPort
        portInfo.text = (if (pp > 0) getString(R.string.ssa_pair_seen, pp) else getString(R.string.ssa_pair_none)) + " " +
            (if (cp > 0) getString(R.string.ssa_wd_on, cp) else getString(R.string.ssa_wd_off))
        for (i in rows.indices) {
            val (icon, tint) = when (SelfSetup.state[i]) {
                SelfSetup.STEP_OK -> R.drawable.ic_ok to "#16a34a"
                SelfSetup.STEP_ERR -> R.drawable.ic_err to "#dc2626"
                SelfSetup.STEP_RUN -> R.drawable.ic_run to "#2563eb"
                else -> R.drawable.ic_todo to "#9ca3af"
            }
            rows[i].setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
            rows[i].compoundDrawableTintList = ColorStateList.valueOf(Color.parseColor(tint))
        }
        start.isEnabled = !SelfSetup.running
        result.text = SelfSetup.message
        result.setTextColor(Color.parseColor(if (SelfSetup.done) "#16a34a" else "#dc2626"))
        if (SelfSetup.done) {
            SelfSetup.done = false
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK))
            finish(); return
        }
        ui.postDelayed(::tick, 1000)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
