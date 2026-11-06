package dev.kidguard

import android.app.Activity
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.graphics.Bitmap
import android.widget.ImageView
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.text.SpannableStringBuilder
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
    private lateinit var ui2: Ui
    private lateinit var status: LinearLayout
    private lateinit var code: TextView
    private lateinit var codeLabel: TextView
    private lateinit var hint: TextView
    private lateinit var pairBtn: Button
    private lateinit var checklist: LinearLayout
    private lateinit var selfBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Guard.start(this)
        val u = Ui(this); ui2 = u
        val pad = dp(20)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, dp(36), pad, pad) }
        // header: shield badge + name + friendly line
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(android.widget.ImageView(this).apply {
            background = u.shape(u.tint, 18); scaleType = android.widget.ImageView.ScaleType.CENTER
            setImageDrawable(Ic.get(this@MainActivity, R.drawable.li_shield, u.accent, dp(30)))
        }, LinearLayout.LayoutParams(dp(56), dp(56)))
        val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        names.addView(u.label("KidGuard", 26f, u.text, true))
        names.addView(u.label("Giúp con dùng điện thoại an toàn", 14f))
        head.addView(names, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(14) })
        col.addView(head)
        // status card
        val statusCard = u.cardBox(12).apply { setPadding(dp(16), dp(8), dp(16), dp(8)) }
        status = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        statusCard.addView(status)
        col.addView(statusCard)
        // pairing card: label + code + hint
        val pairCard = u.cardBox(18)
        codeLabel = u.label("MÃ GHÉP ĐÔI", 12f, u.sub, true).apply { letterSpacing = 0.1f; gravity = Gravity.CENTER }
        pairCard.addView(codeLabel, LinearLayout.LayoutParams(-1, -2))
        code = TextView(this).apply { textSize = 46f; gravity = Gravity.CENTER; letterSpacing = 0.12f; setPadding(0, dp(6), 0, dp(10)); setTypeface(Typeface.MONOSPACE, Typeface.BOLD); setTextColor(u.accent) }
        pairCard.addView(code)
        qr = ImageView(this).apply { visibility = View.GONE; contentDescription = "Mã QR để bố mẹ quét" }
        pairCard.addView(qr, LinearLayout.LayoutParams(dp(208), dp(208)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(10) })
        hint = TextView(this).apply { textSize = 15f; setTextColor(u.sub); setLineSpacing(0f, 1.15f) }
        pairCard.addView(hint)
        col.addView(pairCard)
        // actions: one primary, rest secondary
        col.addView(u.button("Xin thêm giờ", R.drawable.li_timer, true) { askMore() }.apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(18) })
        pairBtn = u.button("Ghép thêm máy của bố mẹ", R.drawable.li_link, false) { askPin() }
        col.addView(pairBtn)
        col.addView(u.button("Cài đặt pin / tự khởi động", R.drawable.li_battery_charging, false) { openBatterySettings() })
        selfBtn = u.button("Tự cài không cần máy tính (Android 11+)", R.drawable.li_wifi, false) { startActivity(Intent(this@MainActivity, SelfSetupActivity::class.java)) }
        col.addView(selfBtn)
        checklist = Checklist.build(this, u)
        col.addView(checklist)
        setContentView(ScrollView(this).apply { setBackgroundColor(u.bg); isFillViewport = true; addView(col) })
        window.statusBarColor = u.bg; window.navigationBarColor = u.bg
        if (!u.dark) window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or (if (android.os.Build.VERSION.SDK_INT >= 27) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0)
    }

    private lateinit var qr: ImageView
    private var qrFor: String? = null

    /** Black-on-white QR (white even in dark mode, so phone cameras read it), with the standard quiet zone. */
    private fun qrBitmap(text: String, px: Int): Bitmap {
        val m = com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, px, px,
            mapOf(com.google.zxing.EncodeHintType.MARGIN to 2))
        val pixels = IntArray(m.width * m.height) { if (m.get(it % m.width, it / m.width)) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(pixels, m.width, m.height, Bitmap.Config.ARGB_8888)
    }

    override fun onResume() { super.onResume(); refresh() }
    override fun onPause() { super.onPause(); ui.removeCallbacksAndMessages(null) }

    private fun refresh() {
        val s = Store.of(this)
        val owner = Policy.isOwner(this)
        val usage = Usage.granted(this)
        selfBtn.visibility = if (owner) View.GONE else View.VISIBLE
        val lan = Guard.lanUrl()
        Checklist.update(this, checklist)
        val u = ui2
        status.removeAllViews()
        fun line(icon: Int, tone: String, t: String) = status.addView(u.row(icon, tone, t))
        val ok = R.drawable.li_circle_check; val warn = R.drawable.li_triangle_alert
        if (owner) line(ok, Ic.OK, "Đang được bố mẹ quản lý") else line(warn, Ic.WARN, "Chưa có quyền Device Owner: dùng nút \"Tự cài\" bên dưới hoặc setup.sh trên máy tính (xem README)")
        if (!usage) line(warn, Ic.WARN, "Chưa có quyền xem thời gian dùng app")
        if (Guard.running != null) line(ok, Ic.OK, "Đang chạy") else line(R.drawable.li_loader, Ic.INFO, "Đang khởi động…")
        if (Acc.isOn(this@MainActivity)) line(R.drawable.li_accessibility, Ic.OK, "Trợ năng: bật") else line(R.drawable.li_accessibility, Ic.WARN, "Trợ năng: tắt")
        val locOn = runCatching { getSystemService(LocationManager::class.java).isLocationEnabled }.getOrDefault(false)
        line(R.drawable.li_map_pin, if (locOn) Ic.OK else Ic.WARN, if (locOn) "Vị trí: bật" else "Vị trí: tắt")
        val pending = s.requests().length()
        if (pending > 0) line(R.drawable.li_message_circle, Ic.INFO, "Đang chờ bố mẹ trả lời: $pending yêu cầu")
        if (s.relayUrl.isNotBlank()) line(R.drawable.li_globe, Ic.INFO, "Điều khiển từ xa: bật")
        val c = s.pairCode()
        if (c != null) {
            code.visibility = View.VISIBLE; codeLabel.visibility = View.VISIBLE; code.text = c
            hint.gravity = Gravity.CENTER
            val link = lan?.let { "$it/#pair=$c" }
            if (link != qrFor) { qrFor = link; link?.let { qr.setImageBitmap(qrBitmap(it, dp(208))) } }
            qr.visibility = if (link != null) View.VISIBLE else View.GONE
            hint.text = if (link != null) "Trên máy bố mẹ (cùng Wi-Fi): quét mã QR bằng camera, hoặc mở trình duyệt:\n$lan\nrồi nhập mã ở trên."
                else "Trên máy bố mẹ (cùng Wi-Fi), mở trình duyệt:\n(chưa có Wi-Fi)\nrồi nhập mã ở trên."
            hint.setCompoundDrawablesRelative(null, null, null, null)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            pairBtn.visibility = View.GONE
        } else {
            code.visibility = View.GONE; codeLabel.visibility = View.GONE; qr.visibility = View.GONE; qrFor = null
            hint.gravity = Gravity.START
            hint.text = "Bố mẹ quản lý tại:\n${lan ?: "(chưa có Wi-Fi)"}"; Ic.lead(hint, R.drawable.li_users, ui2.sub)
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            pairBtn.visibility = View.VISIBLE
        }
        ui.postDelayed(::refresh, 3000)
    }

    /** Opening pairing on the child needs the parent's PIN, once one is set; else a child could pair their own browser. */
    private fun askPin() {
        val s = Store.of(this)
        val rules = s.rules
        if (rules.pinHash.isBlank()) {
            // already paired and no PIN: opening pairing would let the child pair their own browser
            if (s.everPaired) { Toast.makeText(this, "Bố mẹ hãy đặt mã PIN trong trang quản lý trước khi ghép thêm máy", Toast.LENGTH_LONG).show(); return }
            s.openPairing(); refresh(); return
        }
        s.pinLockLeft().takeIf { it > 0 }?.let { left ->
            AlertDialog.Builder(this).setMessage("Nhập sai PIN nhiều lần. Thử lại sau ${(left + 59_999) / 60_000} phút.").setPositiveButton("OK", null).show(); return
        }
        val input = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD; hint = "PIN của bố mẹ" }
        AlertDialog.Builder(this).setTitle("Nhập PIN của bố mẹ").setView(input)
            .setPositiveButton("OK") { _, _ ->
                if (s.checkParentPin(input.text.toString().filter { it.isDigit() })) { s.openPairing(); refresh() }
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
        col.addView(TextView(this).apply { text = "Bố mẹ nhắn"; textSize = 18f; setTextColor(Color.parseColor("#bfdbfe")); gravity = Gravity.CENTER; Ic.lead(this, R.drawable.li_mail) })
        col.addView(TextView(this).apply { text = intent.getStringExtra("text") ?: ""; textSize = 30f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setPadding(0, pad, 0, pad) })
        col.addView(Button(this).apply { text = "Con đã đọc"; Ic.lead(this, R.drawable.li_thumbs_up); setOnClickListener { finish() } })
        setContentView(col)
    }

    companion object {
        fun show(ctx: Context, text: String) {
            val i = Intent(ctx, MessageActivity::class.java).putExtra("text", text)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            runCatching { ctx.startActivity(i) }
            // background activity starts can be blocked: a heads-up notification with a full-screen intent shows it anyway
            runCatching {
                val nm = ctx.getSystemService(NotificationManager::class.java)
                nm.createNotificationChannel(NotificationChannel("msg", "Tin nhắn từ bố mẹ", NotificationManager.IMPORTANCE_HIGH))
                val pi = PendingIntent.getActivity(ctx, 3, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                nm.notify(3, Notification.Builder(ctx, "msg").setSmallIcon(android.R.drawable.ic_lock_lock).setContentTitle("Bố mẹ nhắn")
                    .setContentText(text).setStyle(Notification.BigTextStyle().bigText(text)).setCategory(Notification.CATEGORY_MESSAGE)
                    .setAutoCancel(true).setContentIntent(pi).setFullScreenIntent(pi, true).build())
            }
        }
    }
}
