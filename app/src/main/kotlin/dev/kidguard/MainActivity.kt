package dev.kidguard

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import android.widget.ScrollView
import android.widget.TextView

/** The child's screen: is the guard on, and (only while pairing is open) the 6-digit code for the parent. */
class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var code: TextView
    private lateinit var hint: TextView
    private lateinit var pairBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Guard.start(this)
        val pad = dp(20)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad * 2, pad, pad) }
        col.addView(TextView(this).apply { text = "KidGuard"; textSize = 28f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.parseColor("#111827")) })
        status = TextView(this).apply { textSize = 16f; setPadding(0, dp(12), 0, dp(12)); setTextColor(Color.parseColor("#374151")) }
        col.addView(status)
        code = TextView(this).apply { textSize = 48f; gravity = Gravity.CENTER; letterSpacing = 0.15f; setTypeface(Typeface.MONOSPACE, Typeface.BOLD); setTextColor(Color.parseColor("#2563eb")) }
        col.addView(code)
        hint = TextView(this).apply { textSize = 15f; setPadding(0, dp(8), 0, dp(16)); setTextColor(Color.parseColor("#374151")) }
        col.addView(hint)
        pairBtn = Button(this).apply { text = "Ghép thêm máy của bố mẹ"; setOnClickListener { askPin() } }
        col.addView(Button(this).apply { text = "Xin thêm giờ"; setOnClickListener { askMore() } })
        col.addView(Button(this).apply { text = "Cài đặt pin / tự khởi động"; setOnClickListener { openBatterySettings() } })
        col.addView(pairBtn)
        setContentView(ScrollView(this).apply { setBackgroundColor(Color.WHITE); addView(col) })
    }

    override fun onResume() { super.onResume(); refresh() }
    override fun onPause() { super.onPause(); ui.removeCallbacksAndMessages(null) }

    private fun refresh() {
        val s = Store.of(this)
        val owner = Policy.isOwner(this)
        val usage = Usage.granted(this)
        val lan = Guard.lanUrl()
        status.text = buildString {
            append(if (owner) "✅ Đang được bố mẹ quản lý\n" else "⚠️ Chưa có quyền Device Owner: chạy setup.sh trên máy tính (xem README)\n")
            if (!usage) append("⚠️ Chưa có quyền xem thời gian dùng app (setup.sh cấp)\n")
            append(if (Guard.running != null) "✅ Đang chạy\n" else "⏳ Đang khởi động…\n")
            append(if (Acc.isOn(this@MainActivity)) "✅ Trợ năng: bật\n" else "⚠️ Trợ năng: tắt\n")
            append(if (runCatching { getSystemService(LocationManager::class.java).isLocationEnabled }.getOrDefault(false)) "✅ Vị trí: bật\n" else "⚠️ Vị trí: tắt\n")
            val pending = s.requests().length()
            if (pending > 0) append("⏳ Đang chờ bố mẹ trả lời: $pending yêu cầu\n")
            if (s.relayUrl.isNotBlank()) append("🌐 Điều khiển từ xa: bật\n")
        }
        val c = s.pairCode()
        if (c != null) {
            code.visibility = View.VISIBLE; code.text = c
            hint.text = "Trên máy bố mẹ (cùng Wi-Fi), mở trình duyệt:\n${lan ?: "(chưa có Wi-Fi)"}\nrồi nhập mã ở trên."
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            pairBtn.visibility = View.GONE
        } else {
            code.visibility = View.GONE
            hint.text = "Bố mẹ quản lý tại: ${lan ?: "(chưa có Wi-Fi)"}"
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            pairBtn.visibility = View.VISIBLE
        }
        ui.postDelayed(::refresh, 3000)
    }

    /** Opening pairing on the child needs the parent's PIN, once one is set; else a child could pair their own browser. */
    private fun askPin() {
        val s = Store.of(this)
        val hash = s.rules.pinHash
        if (hash.isBlank()) { s.openPairing(); refresh(); return }
        val input = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD; hint = "PIN của bố mẹ" }
        AlertDialog.Builder(this).setTitle("Nhập PIN của bố mẹ").setView(input)
            .setPositiveButton("OK") { _, _ ->
                if (Store.sha256("kg:" + input.text.toString()) == hash) { s.openPairing(); refresh() }
                else AlertDialog.Builder(this).setMessage("Sai PIN").setPositiveButton("OK", null).show()
            }.setNegativeButton("Huỷ", null).show()
    }

    /** The child asks for 15 / 30 / 60 more minutes, with an optional short note; the parent sees it in the state. */
    private fun askMore() {
        val s = Store.of(this)
        val mins = intArrayOf(15, 30, 60)
        val group = RadioGroup(this)
        mins.forEachIndexed { i, m -> group.addView(RadioButton(this).apply { text = "$m phút"; id = i + 1; isChecked = i == 0 }) }
        val note = EditText(this).apply { hint = "Lý do (không bắt buộc)"; filters = arrayOf(InputFilter.LengthFilter(100)) }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0); addView(group); addView(note) }
        AlertDialog.Builder(this).setTitle("Xin thêm giờ").setView(box)
            .setPositiveButton("Gửi") { _, _ ->
                val ok = s.addRequest(mins[(group.checkedRadioButtonId - 1).coerceIn(0, 2)], note.text.toString())
                Toast.makeText(this, if (ok) "Đã gửi cho bố mẹ" else "Đang có quá nhiều yêu cầu chờ", Toast.LENGTH_LONG).show()
                if (ok) { s.log("Con xin thêm giờ"); Guard.poke(this) }
                refresh()
            }.setNegativeButton("Huỷ", null).show()
    }

    /** Phone makers hide "autostart / no battery limits" in their own screens; try the known ones, else the app's page. */
    private fun openBatterySettings() {
        val known = listOf(
            "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
            "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
            "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.samsung.android.lool" to "com.samsung.android.sm.battery.ui.BatteryActivity",
        )
        for ((pkg, cls) in known) if (runCatching { startActivity(Intent().setComponent(ComponentName(pkg, cls))); true }.getOrDefault(false)) return
        runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}

/** A message from the parent, over everything. */
class MessageActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(pad, pad, pad, pad); setBackgroundColor(Color.parseColor("#1e3a8a")) }
        col.addView(TextView(this).apply { text = "Bố mẹ nhắn"; textSize = 18f; setTextColor(Color.parseColor("#bfdbfe")); gravity = Gravity.CENTER })
        col.addView(TextView(this).apply { text = intent.getStringExtra("text") ?: ""; textSize = 30f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setPadding(0, pad, 0, pad) })
        col.addView(Button(this).apply { text = "Con đã đọc"; setOnClickListener { finish() } })
        setContentView(col)
    }

    companion object {
        fun show(ctx: Context, text: String) {
            ctx.startActivity(Intent(ctx, MessageActivity::class.java).putExtra("text", text)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        }
    }
}
