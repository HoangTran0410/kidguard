package dev.kidguard

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
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
