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
        col.addView(text("Tự cài KidGuard, không cần máy tính", 24f, true, "#111827"))

        if (Build.VERSION.SDK_INT < 30) {
            col.addView(text("Máy này dùng Android ${Build.VERSION.RELEASE} (dưới 11) nên không tự cài được. Cần cắm máy tính chạy setup.sh (xem README), hoặc cài lại máy rồi quét mã QR."))
            setContentView(ScrollView(this).apply { setBackgroundColor(Color.WHITE); addView(col) })
            return
        }
        col.addView(text("Máy sẽ tự \"nói chuyện\" với chính nó qua Gỡ lỗi không dây. Cần Wi-Fi, và máy chưa đăng nhập tài khoản nào (Google, Samsung, Xiaomi...). Làm lần lượt 3 bước:"))

        col.addView(text("Bước 1. Bật Tuỳ chọn nhà phát triển", 17f, true, "#111827"))
        col.addView(text("Vào Giới thiệu về điện thoại, bấm 7 lần vào \"Số hiệu bản tạo\" (Build number)."))
        col.addView(button("Mở Giới thiệu về điện thoại") { open(Settings.ACTION_DEVICE_INFO_SETTINGS) })

        col.addView(text("Bước 2. Bật Gỡ lỗi không dây", 17f, true, "#111827"))
        col.addView(text("Trong Tuỳ chọn nhà phát triển, bật \"Gỡ lỗi không dây\" (Wireless debugging) và cho phép Wi-Fi này."))
        col.addView(button("Mở Tuỳ chọn nhà phát triển") { open(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS) })

        col.addView(text("Bước 3. Nhập mã ghép nối", 17f, true, "#111827"))
        col.addView(text("Bấm nút dưới đây. Rồi trong Gỡ lỗi không dây, bấm \"Ghép thiết bị bằng mã ghép nối\". Hộp thoại sẽ tắt nếu con rời màn hình Cài đặt, nên mã được nhập ở thông báo: kéo thanh thông báo xuống, bấm \"Nhập mã ghép\" và gõ mã 6 số."))
        col.addView(button("Hiện ô nhập mã ở thông báo") { askNotification() })
        portInfo = text("", 14f)
        col.addView(portInfo)

        col.addView(text("Hoặc nhập ngay tại đây (nếu thông báo không hiện):", 14f))
        code = EditText(this).apply { hint = "Mã ghép nối 6 số"; inputType = InputType.TYPE_CLASS_NUMBER; filters = arrayOf(InputFilter.LengthFilter(6)) }
        col.addView(code)
        pairPort = EditText(this).apply { hint = "Cổng ghép nối (để trống nếu máy tự tìm thấy)"; inputType = InputType.TYPE_CLASS_NUMBER; filters = arrayOf(InputFilter.LengthFilter(5)) }
        col.addView(pairPort)
        connPort = EditText(this).apply { hint = "Cổng kết nối (để trống nếu máy tự tìm thấy)"; inputType = InputType.TYPE_CLASS_NUMBER; filters = arrayOf(InputFilter.LengthFilter(5)) }
        col.addView(connPort)
        wifiOff = CheckBox(this).apply { text = "Tắt Gỡ lỗi không dây sau khi cài xong"; isChecked = true }
        col.addView(wifiOff)
        start = button("Bắt đầu cài") { go() }
        col.addView(start)

        col.addView(text("Tiến trình", 17f, true, "#111827"))
        for (s in SelfSetup.STEPS) { val r = text(s, 15f); r.compoundDrawablePadding = dp(10); rows.add(r); col.addView(r) }
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
        if (c.length != 6) { result.text = "Mã ghép nối phải đủ 6 số."; return }
        SelfSetup.turnOffWifi = wifiOff.isChecked
        SelfSetup.start(this, c, pairPort.text.toString().toIntOrNull() ?: 0, connPort.text.toString().toIntOrNull() ?: 0)
        tick()
    }

    private fun tick() {
        val pp = SelfSetup.pairingPort; val cp = SelfSetup.connectPort
        portInfo.text = (if (pp > 0) "Đã thấy hộp thoại ghép nối (cổng $pp). " else "Chưa thấy hộp thoại ghép nối. ") +
            (if (cp > 0) "Gỡ lỗi không dây đang bật (cổng $cp)." else "Gỡ lỗi không dây chưa bật.")
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
