package dev.kidguard

import android.app.Activity
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
import android.text.SpannableStringBuilder
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** The child's screen: is the guard on, and (only while pairing is open) the 6-digit code for the parent. */
/** "Mở ghép lại" from the child's screen waits this long (see Store.recoverAt). */
private const val RECOVER_MS = 24 * 3_600_000L

class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var ui2: Ui
    private lateinit var status: LinearLayout
    private lateinit var code: TextView
    private lateinit var codeLabel: TextView
    private lateinit var hint: TextView
    private lateinit var pairBtn: Button
    private lateinit var checklist: LinearLayout
    private lateinit var basicList: BasicChecklist
    private lateinit var selfBtn: Button
    private lateinit var revealBtn: Button
    private lateinit var recoverBtn: Button

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
        // basic mode (no Device Owner): the quick setup checklist, shown until every item is on
        basicList = BasicChecklist(this, u) { openBatterySettings() }
        col.addView(basicList.box)
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
        sosBtn = u.button("SOS: báo bố mẹ ngay", R.drawable.li_siren, true) { askSos() }.apply {
            background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(Color.argb(60, 255, 255, 255)), u.shape(Color.parseColor("#dc2626"), 16), null)
        }
        col.addView(sosBtn)
        (sosBtn.layoutParams as LinearLayout.LayoutParams).topMargin = dp(18)
        // basic mode can't update silently: a downloaded, verified update waits for this tap (owner installs by itself)
        updBtn = u.button("Cập nhật KidGuard (bản mới)", R.drawable.li_shield, true) {
            if (!Update.installInteractive(this)) android.widget.Toast.makeText(this, "Bật \"Cho phép từ nguồn này\" cho KidGuard rồi bấm lại", android.widget.Toast.LENGTH_LONG).show()
        }
        col.addView(updBtn)
        moreBtn = u.button("Xin thêm giờ", R.drawable.li_timer, true) { askMore() }
        col.addView(moreBtn)
        pairBtn = u.button("Ghép thêm máy của bố mẹ", R.drawable.li_link, false) { askPin() }
        col.addView(pairBtn)
        col.addView(u.button("Luật của con", R.drawable.li_shield, false) { showMyRules() })
        col.addView(u.button("Nhật ký: bố mẹ đã đổi gì", R.drawable.li_shield, false) { showLog() })
        revealBtn = u.button("Bố mẹ: hiện mã ghép (giữ 5 giây)", R.drawable.li_link, false) {}
        holdToRun(revealBtn, 5_000) { Store.of(this).codeRevealUntil = System.currentTimeMillis() + 10 * 60_000; refresh() }
        col.addView(revealBtn)
        recoverBtn = u.button("Bố mẹ mất liên kết? Mở ghép lại (giữ 10 giây)", R.drawable.li_link, false) {}
        holdToRun(recoverBtn, 10_000) {
            // only reachable while no PIN is set (see refresh). Not at once: a child could pair their own browser and
            // release. Pairing opens in 24 h, the parents are told now and can cancel by setting a PIN.
            val s = Store.of(this)
            if (s.rules.pinHash.isBlank() && s.recoverAt == 0L) {
                s.recoverAt = System.currentTimeMillis() + RECOVER_MS
                s.log("Máy con yêu cầu mở ghép lại (mở sau 24 giờ)")
                s.addEvent(Ev("pairing", "Máy con yêu cầu mở ghép lại: sẽ mở sau 24 giờ. Đặt PIN để huỷ."))
                Guard.poke(this)
                Modal(this, ui2).show(R.drawable.li_link, Modal.Tone.INFO, "Sẽ mở ghép sau 24 giờ",
                    "Để an toàn, mã ghép hiện sau 24 giờ và bố mẹ được báo ngay. Nếu bố mẹ vẫn còn quản lý được, hãy đặt mã PIN để huỷ.")
            }
            refresh()
        }
        col.addView(recoverBtn)
        col.addView(u.button("Cài đặt pin / tự khởi động", R.drawable.li_battery_charging, false) { openBatterySettings() })
        selfBtn = u.button("Cài đầy đủ không cần máy tính (Android 11+)", R.drawable.li_wifi, false) { startActivity(Intent(this@MainActivity, SelfSetupActivity::class.java)) }
        col.addView(selfBtn)
        checklist = Checklist.build(this, u)
        col.addView(checklist)
        setContentView(ScrollView(this).apply { setBackgroundColor(u.bg); isFillViewport = true; addView(col) })
        window.statusBarColor = u.bg; window.navigationBarColor = u.bg
        if (!u.dark) window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or (if (android.os.Build.VERSION.SDK_INT >= 27) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0)
    }

    /** Fires [action] after the button is held for [ms]; releasing early cancels. A tap does nothing (no pocket / child accidents). */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun holdToRun(b: Button, ms: Long, action: () -> Unit) {
        var fired = false; var downAt = 0L
        val run = Runnable { fired = true; action() }
        b.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { fired = false; downAt = e.eventTime; ui.postDelayed(run, ms) }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    ui.removeCallbacks(run)
                    // a short tap did nothing: say why, or the parent thinks the button is broken
                    if (!fired && e.actionMasked == android.view.MotionEvent.ACTION_UP && e.eventTime - downAt < ms)
                        android.widget.Toast.makeText(this, "Giữ nút ${ms / 1000} giây", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            false
        }
    }

    private lateinit var qr: ImageView
    private lateinit var sosBtn: View
    private lateinit var updBtn: View
    private lateinit var moreBtn: View
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
        // SOS / more time only reach someone once a parent has paired
        (if (s.everPaired) View.VISIBLE else View.GONE).let { sosBtn.visibility = it; moreBtn.visibility = it }
        updBtn.visibility = if (!owner && Update.apk(this).exists() && UpdateLogic.newer(Update.stateJson(this).optString("latest"), Update.current())) View.VISIBLE else View.GONE
        selfBtn.visibility = if (owner || android.os.Build.VERSION.SDK_INT < 30) View.GONE else View.VISIBLE
        if (owner) basicList.box.visibility = View.GONE else basicList.update()
        val lan = Guard.lanUrl()
        Checklist.update(this, checklist)
        if (!owner) checklist.visibility = View.GONE   // basic mode has its own, longer checklist
        val u = ui2
        status.removeAllViews()
        fun line(icon: Int, tone: String, t: String) = status.addView(u.row(icon, tone, t))
        val ok = R.drawable.li_circle_check; val warn = R.drawable.li_triangle_alert
        if (owner) line(ok, Ic.OK, "Đang được bố mẹ quản lý") else if (Basic.mode(this) == "basic") line(ok, Ic.OK, "Chế độ cơ bản: KidGuard đang bảo vệ")
            else line(warn, Ic.WARN, "Chưa cài xong: làm lần lượt các bước trong khung \"Cài nhanh\" bên dưới")
        if (!usage) line(warn, Ic.WARN, "Chưa có quyền xem thời gian dùng app")
        if (Guard.running != null) line(ok, Ic.OK, "Đang chạy") else line(R.drawable.li_loader, Ic.INFO, "Đang khởi động…")
        if (Acc.isOn(this@MainActivity)) line(R.drawable.li_accessibility, Ic.OK, "Trợ năng: bật") else line(R.drawable.li_accessibility, Ic.WARN, "Trợ năng: tắt")
        val locOn = Basic.locationOn(this)
        line(R.drawable.li_map_pin, if (locOn) Ic.OK else Ic.WARN, if (locOn) "Vị trí: bật" else "Vị trí: tắt")
        val pending = s.requests().length()
        if (pending > 0) line(R.drawable.li_message_circle, Ic.INFO, "Đang chờ bố mẹ trả lời: $pending yêu cầu")
        if (Listen.active) line(R.drawable.li_mic, Ic.WARN, "Bố mẹ đang nghe âm thanh xung quanh máy")
        if (s.sos().length() > 0) line(R.drawable.li_siren, Ic.WARN, "Đã gửi SOS, đang chờ bố mẹ xem")
        if (s.relayUrl.isNotBlank()) line(R.drawable.li_globe, Ic.INFO, "Điều khiển từ xa: bật")
        s.lastListen?.let { (t, ms) ->
            val fmt = if (Guard.day(t) == Guard.day(System.currentTimeMillis())) "HH:mm" else "dd/MM HH:mm"
            val at = java.text.SimpleDateFormat(fmt, java.util.Locale.US).format(java.util.Date(t))
            val min = (ms + 30_000) / 60_000
            line(R.drawable.li_mic, Ic.INFO, "Lần nghe gần nhất: $at (${if (min < 1) "dưới 1 phút" else "$min phút"})")
        }
        val c = s.visiblePairCode()
        val hidden = c == null && s.pairCode() != null     // first pairing, code not revealed yet
        revealBtn.visibility = if (hidden) View.VISIBLE else View.GONE
        recoverBtn.visibility = if (s.everPaired && c == null && s.rules.pinHash.isBlank()) View.VISIBLE else View.GONE
        recoverBtn.text = s.recoverAt.takeIf { it > 0 }?.let { "Mã ghép sẽ hiện lúc " + java.text.SimpleDateFormat("HH:mm dd/MM", java.util.Locale.ROOT).format(java.util.Date(it)) }
            ?: "Bố mẹ mất liên kết? Mở ghép lại (giữ 10 giây)"
        if (c != null) {
            code.visibility = View.VISIBLE; codeLabel.visibility = View.VISIBLE; code.text = c
            val left = s.pairOpenUntil() - System.currentTimeMillis()
            codeLabel.text = if (left > 0) "MÃ GHÉP ĐÔI · CÒN " + (if (left < 60_000) "DƯỚI 1 PHÚT" else "${(left + 59_999) / 60_000} PHÚT") else "MÃ GHÉP ĐÔI"
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
            hint.text = if (hidden) "Chưa ghép với máy bố mẹ. Bố mẹ giữ nút \"hiện mã ghép\" bên dưới 5 giây để bắt đầu ghép."
                else "Bố mẹ quản lý tại:\n${lan ?: "(chưa có Wi-Fi)"}"; Ic.lead(hint, R.drawable.li_users, ui2.sub)
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            pairBtn.visibility = if (hidden) View.GONE else View.VISIBLE
        }
        ui.removeCallbacks(refreshLoop); ui.postDelayed(refreshLoop, 3000)   // one loop, however often refresh() is called
    }
    private val refreshLoop = Runnable { refresh() }

    /** Opening pairing on the child needs the parent's PIN, once one is set; else a child could pair their own browser. */
    private fun askPin() {
        val s = Store.of(this)
        val rules = s.rules
        if (rules.pinHash.isBlank()) {
            // already paired and no PIN: opening pairing would let the child pair their own browser
            if (s.everPaired) { Modal(this, ui2).show(R.drawable.li_shield, Modal.Tone.WARN, "Cần mã PIN", "Bố mẹ hãy đặt mã PIN trong trang quản lý trước khi ghép thêm máy."); return }
            s.openPairing(); refresh(); return
        }
        fun lockText(left: Long) = "Nhập sai PIN nhiều lần. Thử lại sau ${(left + 59_999) / 60_000} phút."
        s.pinLockLeft().takeIf { it > 0 }?.let { left ->
            Modal(this, ui2).show(R.drawable.li_triangle_alert, Modal.Tone.WARN, "Tạm khoá", lockText(left)); return
        }
        val m = Modal(this, ui2)
        val input = m.field("PIN của bố mẹ", 12, pin = true)
        m.show(R.drawable.li_shield, Modal.Tone.INFO, "Nhập PIN của bố mẹ", "Chỉ bố mẹ mới được ghép thêm máy.", input, ok = "Mở ghép", cancel = "Huỷ") {
            if (s.checkParentPin(input.text.toString().filter { it.isDigit() })) { s.openPairing(); refresh(); true }
            else {
                input.setText("")
                m.setError(s.pinLockLeft().takeIf { it > 0 }?.let { lockText(it) } ?: "Sai PIN, thử lại")
                false
            }
        }
        input.requestFocus()
    }

    /** Read-only view of what the parent set, so the child knows the rules (and what is watched). */
    private fun showMyRules() {
        val r = Store.of(this).rules
        val now = System.currentTimeMillis()
        val today = Guard.day(now)
        val (apps, prot) = Policy.cached(this)
        fun name(p: String) = apps[p] ?: p
        val days = listOf("T2", "T3", "T4", "T5", "T6", "T7", "CN")
        val L = mutableListOf<String>()
        val base = baseLimitMin(r, today)
        if (base <= 0) L += "Hôm nay con không bị giới hạn giờ"
        else {
            val left = dailyLeftMs(r, Usage.today(this, now).ms, today, apps.keys, prot)
            val limit = base + if (r.bonusDay == today) r.bonusMin else 0
            L += "Hôm nay: còn ${(left + 59_999) / 60_000} / $limit phút"
        }
        L += if (r.bedtimeOn) "Giờ ngủ: ${r.bedStart} - ${r.bedEnd}" else "Giờ ngủ: không đặt"
        val sch = r.schedules.filter { it.on }
        if (sch.isEmpty()) L += "Giờ nghỉ theo lịch: không có"
        else sch.forEach { L += "Giờ nghỉ${if (it.name.isBlank()) "" else " \"${it.name}\""}: ${it.start} - ${it.end} (${it.days.sorted().joinToString(", ") { d -> days[d - 1] }})" }
        L += if (r.allow.isEmpty()) "App luôn dùng được: chỉ các app hệ thống (điện thoại, đồng hồ…)"
            else "App luôn dùng được: " + r.allow.joinToString { name(it) }
        L += "App đang bị chặn: ${r.blocked.size}"
        L += if (r.trackMin > 0) "Vị trí: bố mẹ xem được, lưu lịch sử mỗi ${r.trackMin} phút" else "Vị trí: bố mẹ chỉ xem khi bấm tìm máy"
        L += if (Acc.isOn(this)) "Xem màn hình: bố mẹ có thể xem khi bật" + (if (r.showWatching) " (con sẽ thấy thông báo)" else "")
            else "Xem màn hình: đang tắt"
        L += "Nghe âm thanh xung quanh: chỉ khi có tiếng bíp và thông báo cho con"
        if (r.zones.isNotEmpty()) L += "Bố mẹ được báo khi con đến / rời: " + r.zones.joinToString { it.name }
        if (r.chores.isNotEmpty()) L += "Việc nhà đổi giờ: " + r.chores.joinToString { "${it.name} +${it.min} phút" }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        L.forEach { box.addView(ui2.label("• $it", 15f, ui2.text).apply { setPadding(0, dp(4), 0, dp(4)) }) }
        Modal(this, ui2).show(R.drawable.li_shield, Modal.Tone.INFO, "Luật của con", "Chỉ để xem, bố mẹ là người thay đổi.", ScrollView(this).apply { addView(box) }, ok = "Đóng")
    }

    /** The last 20 log lines: what the parent changed and what the guard did. */
    private fun showLog() {
        val a = Store.of(this).logs()
        val fmt = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.US)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (a.length() == 0) box.addView(ui2.label("Chưa có gì.", 15f, ui2.sub))
        for (i in a.length() - 1 downTo maxOf(0, a.length() - 20)) {
            val o = a.optJSONObject(i) ?: continue
            box.addView(ui2.label("${fmt.format(java.util.Date(o.optLong("t")))}  ${o.optString("m")}", 14f, ui2.text).apply { setPadding(0, dp(4), 0, dp(4)) })
        }
        Modal(this, ui2).show(R.drawable.li_shield, Modal.Tone.INFO, "Nhật ký", "20 dòng gần nhất, mới nhất ở trên.", ScrollView(this).apply { addView(box) }, ok = "Đóng")
    }

    /** SOS: one confirm (no pocket presses), then the state carries it to the parent with a fresh location. */
    private fun askSos() {
        Modal(this, ui2).show(R.drawable.li_siren, Modal.Tone.DANGER, "Gửi SOS cho bố mẹ?", "Bố mẹ sẽ nhận báo khẩn cấp kèm vị trí của con.",
            ok = "Gửi SOS", cancel = "Huỷ") {
            val g = Guard.running
            val ok = if (g != null) g.sos() else { val o = Store.of(this).addSos(); if (o != null) Guard.start(this); o != null }
            refresh()
            ui.post {
                if (ok) Modal(this, ui2).show(R.drawable.li_circle_check, Modal.Tone.OK, "Đã báo cho bố mẹ", "Bố mẹ sẽ thấy báo SOS và vị trí của con.")
                else Modal(this, ui2).show(R.drawable.li_siren, Modal.Tone.WARN, "Vừa gửi rồi", "Bố mẹ sẽ thấy ngay khi máy có mạng.")
            }
            true
        }
    }

    /** The child asks for 15 / 30 / 60 more minutes, with an optional short note; the parent sees it in the state. */
    private fun askMore() {
        val s = Store.of(this)
        val mins = intArrayOf(15, 30, 60)
        val chores = s.rules.chores
        val m = Modal(this, ui2)
        val pick = m.choice(mins.map { "$it phút" })
        val note = m.field("Lý do (không bắt buộc)", 100)
        // chores first, the last entry is the free form (minutes row below)
        val chorePick = if (chores.isEmpty()) null else m.choice(chores.map { "${it.name} +${it.min} phút" } + "Khác: tự chọn số phút", chores.size, vertical = true)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            if (chorePick != null) addView(chorePick.view, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
            addView(pick.view, LinearLayout.LayoutParams(-1, -2))
            addView(note, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        }
        m.show(R.drawable.li_timer, Modal.Tone.INFO, "Xin thêm giờ", if (chores.isEmpty()) "Chọn số phút con muốn xin bố mẹ." else "Làm việc nhà để xin thêm giờ, hoặc tự chọn số phút. Bố mẹ sẽ quyết định.", if (chores.isEmpty()) box else ScrollView(this).apply { addView(box) }, ok = "Gửi", cancel = "Huỷ") {
            val chore = chorePick?.selected?.let { chores.getOrNull(it) }
            val ok = if (chore != null) s.addRequest(chore.min, note.text.toString(), chore.id) else s.addRequest(mins[pick.selected.coerceIn(0, 2)], note.text.toString())
            if (ok) { s.log("Con xin thêm giờ"); Guard.poke(this) }
            refresh()
            ui.post {
                if (ok) Modal(this, ui2).show(R.drawable.li_send, Modal.Tone.OK, "Đã gửi cho bố mẹ", "Con sẽ nhận thông báo khi bố mẹ trả lời.")
                else Modal(this, ui2).show(R.drawable.li_triangle_alert, Modal.Tone.WARN, "Chưa gửi được", "Đang có quá nhiều yêu cầu chờ bố mẹ trả lời.")
            }
            true
        }
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
        for ((pkg, cls) in known) if (runCatching { startActivity(Intent().setComponent(ComponentName(pkg, cls)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)) return
        // App info has "Force stop": basic mode bounces it (self-protection), so it opens the battery list there
        if (!Policy.isOwner(this) && runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)) return
        runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
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
