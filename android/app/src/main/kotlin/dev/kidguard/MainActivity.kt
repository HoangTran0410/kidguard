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
    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(Lang.wrap(base))   // in-app language (Android 8-12)
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
        val tc = android.os.SystemClock.elapsedRealtime()
        window.decorView.post { android.util.Log.i("KG.perf", "main create ${android.os.SystemClock.elapsedRealtime() - tc}ms") }
        Guard.start(this)
        val u = Ui(this); ui2 = u
        val pad = dp(20)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, dp(36), pad, pad) }
        // header: shield badge + name + health line (opens the status details), "Thêm" sheet button on the right
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(android.widget.ImageView(this).apply {
            background = u.shape(u.tint, 14); scaleType = android.widget.ImageView.ScaleType.CENTER; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setImageDrawable(Ic.get(this@MainActivity, R.drawable.li_shield, u.accent, dp(24)))
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        val names = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(48); isClickable = true; isFocusable = true
            setPadding(dp(4), 0, dp(4), 0)
            background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(Color.argb(40, 128, 128, 128)), null, u.shape(Color.WHITE, 12))
            setOnClickListener { showStatus() }
        }
        names.addView(u.label("KidGuard", 20f, u.text, true).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO })
        health = u.label("", 13f, u.sub).apply { compoundDrawablePadding = dp(6); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        names.addView(health)
        healthBox = names
        head.addView(names, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
        head.addView(android.widget.ImageView(this).apply {
            background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(Color.argb(40, 128, 128, 128)), u.shape(u.card, 24, u.stroke, 1), null)
            scaleType = android.widget.ImageView.ScaleType.CENTER; isClickable = true; isFocusable = true
            setImageDrawable(Ic.get(this@MainActivity, R.drawable.li_ellipsis, u.text, dp(22)))
            contentDescription = getString(R.string.home_more_desc)
            setOnClickListener { sheet.show() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        col.addView(head)
        // status details (tech rows + Device Owner checklist): a modal from the health line / the sheet, not the home screen
        statusBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        status = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        statusBox.addView(status)
        // status card: time left today, or why the phone is locked and until when, with the one primary button
        buildHero(u)
        col.addView(hero)
        // "Thêm" sheet: everything secondary, in three groups (rows are added below where each one is built)
        buildSheet(u)
        // pairing card: label + code + hint
        pairCard = u.cardBox(18)
        codeLabel = u.label(getString(R.string.main_code_label), 12f, u.sub, true).apply { letterSpacing = 0.1f; gravity = Gravity.CENTER }
        pairCard.addView(codeLabel, LinearLayout.LayoutParams(-1, -2))
        code = TextView(this).apply { textSize = 46f; gravity = Gravity.CENTER; letterSpacing = 0.12f; setPadding(0, dp(6), 0, dp(10)); setTypeface(Typeface.MONOSPACE, Typeface.BOLD); setTextColor(u.accent) }
        pairCard.addView(code)
        qr = ImageView(this).apply { visibility = View.GONE; contentDescription = getString(R.string.main_qr_desc) }
        pairCard.addView(qr, LinearLayout.LayoutParams(dp(208), dp(208)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(10) })
        hint = TextView(this).apply { textSize = 15f; setTextColor(u.sub); setLineSpacing(0f, 1.15f) }
        pairCard.addView(hint)
        col.addView(pairCard)
        // basic mode (no Device Owner): the quick setup checklist, shown until every item is on
        basicList = BasicChecklist(this, u) { openBatterySettings() }
        col.addView(basicList.box)
        // quick row: frequent kid actions as round tiles (SOS keeps its confirm modal, see askSos)
        val quick = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; weightSum = 4f }
        fun tileLp() = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(2); marginEnd = dp(2) }
        sosBtn = u.tile(getString(R.string.home_sos), R.drawable.li_siren, u.danger) { askSos() }.apply { contentDescription = getString(R.string.main_sos) }
        quick.addView(sosBtn, tileLp())
        // v0.9.7 check-ins: secondary, no alarm (see askCheckin)
        ciArrived = u.tile(getString(R.string.main_ci_arrived), R.drawable.li_map_pin, u.accent) { askCheckin("arrived") }
        quick.addView(ciArrived, tileLp())
        ciPickup = u.tile(getString(R.string.main_ci_pickup), R.drawable.li_car, u.accent) { askCheckin("pickup") }
        quick.addView(ciPickup, tileLp())
        quick.addView(u.tile(getString(R.string.q_lock_short), R.drawable.li_lock, u.accent) { Quick.lockOrSay(this) }.apply { contentDescription = getString(R.string.q_lock) }, tileLp())
        col.addView(quick, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(22) })
        // basic mode can't update silently: a downloaded, verified update waits for this tap (owner installs by itself)
        updBtn = u.button(getString(R.string.main_update), R.drawable.li_shield, false) {
            if (!Update.installInteractive(this)) android.widget.Toast.makeText(this, getString(R.string.main_update_allow), android.widget.Toast.LENGTH_LONG).show()
        }
        col.addView(updBtn, 1)   // right under the header
        moreBtn = u.button(getString(R.string.main_more), R.drawable.li_timer, true) { askMore() }
        hero.addView(moreBtn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        hero.addView(heroUnlock); hero.addView(kidNotes)
        moreRow = u.listRow(getString(R.string.main_more), R.drawable.li_timer) { sheet.dismiss(); askMore() }
        kidG.addView(moreRow)
        unlockBtn = u.listRow(getString(R.string.main_unlock), R.drawable.li_key) { sheet.dismiss(); askUnlock() }
        parentG.addView(unlockBtn)
        pairBtn = u.listRow(getString(R.string.main_pair_more), R.drawable.li_link) { sheet.dismiss(); askPin() }
        parentG.addView(pairBtn)
        // "Bố mẹ quản lý trên máy này": first in the sheet's parent group (self-contained: askAdmin + refresh visibility)
        adminBtn = u.listRow(getString(R.string.main_admin), R.drawable.li_users) { sheet.dismiss(); askAdmin() }
        parentG.addView(adminBtn, 1)
        col.addView(u.cardBox(4).apply { addView(u.listRow(getString(R.string.main_my_rules), R.drawable.li_shield) { showMyRules() }) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(22) })
        kidG.addView(u.listRow(getString(R.string.main_log), R.drawable.li_scroll_text) { sheet.dismiss(); showLog() })
        // shared folder (Files.kt): system picker -> outbox, the parent page lists it under "File con gửi"
        kidG.addView(u.listRow(getString(R.string.files_menu), R.drawable.li_send) { sheet.dismiss(); ShareInActivity.pick(this@MainActivity) })
        revealBtn = u.button(getString(R.string.main_reveal), R.drawable.li_link, true) {}
        holdToRun(revealBtn, 5_000) { Store.of(this).codeRevealUntil = System.currentTimeMillis() + 10 * 60_000; refresh() }
        pairCard.addView(revealBtn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        recoverBtn = u.listRow(getString(R.string.main_recover), R.drawable.li_link, chevron = false) {}
        holdToRun(recoverBtn, 10_000) {
            // only reachable while no PIN is set (see refresh). Not at once: a child could pair their own browser and
            // release. Pairing opens in 24 h, the parents are told now and can cancel by setting a PIN.
            val s = Store.of(this)
            if (s.rules.pinHash.isBlank() && s.recoverAt == 0L) {
                s.recoverAt = System.currentTimeMillis() + RECOVER_MS
                s.log(R.string.log_recover_req)
                s.event("pairing", R.string.ev_recover_req)
                Guard.poke(this)
                Modal(this, ui2).show(R.drawable.li_link, Modal.Tone.INFO, getString(R.string.main_recover_title),
                    getString(R.string.main_recover_body))
            }
            refresh()
        }
        parentG.addView(recoverBtn)
        devG.addView(u.listRow(getString(R.string.home_status), R.drawable.li_circle_check) { sheet.dismiss(); showStatus() })
        devG.addView(u.listRow(getString(R.string.main_battery), R.drawable.li_battery_charging) { sheet.dismiss(); openBatterySettings() })
        // kid conveniences: a lock icon on the home screen, and where the phone keeps "double tap to wake"
        kidG.addView(u.listRow(getString(R.string.main_pin_lock), R.drawable.li_lock) {
            sheet.dismiss()
            if (!Quick.requestPin(this)) Modal(this, ui2).show(R.drawable.li_lock, Modal.Tone.INFO, getString(R.string.q_lock), getString(R.string.main_pin_lock_no))
        })
        kidG.addView(u.listRow(getString(R.string.main_gestures), R.drawable.li_smartphone) { sheet.dismiss(); askGestures() })
        // language: harmless, so no PIN (the parent's rules don't depend on it)
        val langName = Langs.ALL.firstOrNull { it.first == Lang.get(this) }?.second ?: getString(R.string.lang_system)
        kidG.addView(u.listRow(getString(R.string.main_lang, langName), R.drawable.li_globe) { sheet.dismiss(); askLang() })
        selfBtn = u.listRow(getString(R.string.main_self), R.drawable.li_wifi) { sheet.dismiss(); startActivity(Intent(this@MainActivity, SelfSetupActivity::class.java)) }
        devG.addView(selfBtn)
        checklist = Checklist.build(this, u)
        statusBox.addView(checklist)
        setContentView(ScrollView(this).apply { setBackgroundColor(u.bg); isFillViewport = true; addView(col) })
        window.statusBarColor = u.bg; window.navigationBarColor = u.bg
        if (!u.dark) window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or (if (android.os.Build.VERSION.SDK_INT >= 27) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0)
    }

    /** Language picker: phone setting + every [Langs.ALL] entry in its own language. */
    private fun askLang() {
        val codes = listOf("") + Langs.ALL.map { it.first }
        val m = Modal(this, ui2)
        val pick = m.choice(listOf(getString(R.string.lang_system)) + Langs.ALL.map { it.second }, codes.indexOf(Lang.get(this)).coerceAtLeast(0), vertical = true)
        m.show(R.drawable.li_globe, Modal.Tone.INFO, getString(R.string.lang_title), null, pick.view, cancel = getString(R.string.cancel)) {
            // Android 13+ recreates the activity by itself when the language really changes
            if (Lang.set(this, codes[pick.selected]) && android.os.Build.VERSION.SDK_INT < 33) recreate()
            Guard.poke(this)
            true
        }
    }

    /** "Bố mẹ quản lý trên máy này": the parent PIN or a current Authenticator code opens the full page here (AdminActivity). */
    private fun askAdmin() {
        val s = Store.of(this)
        val pinSet = s.rules.pinHash.isNotBlank(); val totpOn = s.totpSecret.isNotBlank()
        if (!PairPin.adminAvailable(pinSet, totpOn)) return
        fun lockText(left: Long) = ((left + 59_999) / 60_000).toInt().let { resources.getQuantityString(R.plurals.pin_locked, it, it) }
        s.adminLockLeft().takeIf { it > 0 }?.let { Modal(this, ui2).show(R.drawable.li_triangle_alert, Modal.Tone.WARN, getString(R.string.locked_title), lockText(it)); return }
        val m = Modal(this, ui2)
        val input = m.field(getString(if (pinSet) R.string.admin_field else R.string.unl_hint), 8, pin = true)
        val body = getString(if (pinSet && totpOn) R.string.admin_body else if (pinSet) R.string.admin_body_pin else R.string.admin_body_totp)
        m.show(R.drawable.li_users, Modal.Tone.INFO, getString(R.string.admin_title), body, input, ok = getString(R.string.admin_ok), cancel = getString(R.string.cancel)) {
            if (Guard.running == null) { m.setError(getString(R.string.admin_not_ready)); Guard.start(this); return@show false }
            when (s.checkAdmin(input.text.toString())) {
                1 -> { AdminActivity.start(this); true }
                -1 -> { input.setText(""); m.setError(lockText(s.adminLockLeft())); false }
                else -> { input.setText(""); m.setError(getString(R.string.admin_wrong)); false }
            }
        }
        input.requestFocus()
    }

    /** Hold-to-confirm state: the label each button shows when idle (refresh() may change it while a hold runs). */
    private val holdBase = HashMap<Button, CharSequence>()
    private var holding: Button? = null
    private fun holdLabel(b: Button, t: CharSequence) { holdBase[b] = t; if (holding != b) b.text = t }

    /** The progress fill drawn over a hold button (its foreground), left to right. */
    private class HoldFill(color: Int, private val r: Float) : android.graphics.drawable.Drawable() {
        var f = 0f; set(v) { field = v; invalidateSelf() }
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        override fun draw(c: android.graphics.Canvas) {
            if (f <= 0f) return
            val b = bounds
            c.save(); c.clipRect(b.left.toFloat(), b.top.toFloat(), b.left + b.width() * f, b.bottom.toFloat())
            c.drawRoundRect(android.graphics.RectF(b), r, r, paint); c.restore()
        }
        override fun setAlpha(a: Int) {}
        override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }

    /**
     * Fires [action] after the button is held for [ms]; releasing early cancels (no pocket / child accidents). While held:
     * a fill grows over the button and the label counts down ("Giữ tiếp… 3 giây"); an early release animates back and
     * says "Giữ đủ 5 giây"; a haptic tick at the end. TalkBack: activating the button (or its labelled action) runs the
     * same countdown without a hold, activating again cancels.
     */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun holdToRun(b: Button, ms: Long, action: () -> Unit) {
        val secs = (ms / 1000).toInt()
        val fill = HoldFill(android.graphics.Color.argb(if (ui2.dark) 90 else 60, android.graphics.Color.red(ui2.primary), android.graphics.Color.green(ui2.primary), android.graphics.Color.blue(ui2.primary)), ui2.dpf(16))
        b.foreground = fill
        holdBase[b] = b.text
        var anim: android.animation.ValueAnimator? = null
        var fired = false
        var lastSec = -1
        val restore = Runnable { if (holding != b) b.text = holdBase[b] }
        fun holdBegin() {
            fired = false; holding = b; lastSec = -1; anim?.cancel(); ui.removeCallbacks(restore)
            val from = fill.f
            anim = android.animation.ValueAnimator.ofFloat(from, 1f).apply {
                duration = ((1f - from) * ms).toLong().coerceAtLeast(1); interpolator = android.view.animation.LinearInterpolator()
                addUpdateListener {
                    val v = it.animatedValue as Float; fill.f = v
                    val left = Hold.secondsLeft((v * ms).toLong(), ms).coerceAtLeast(1)
                    if (left != lastSec) { lastSec = left; b.text = resources.getQuantityString(R.plurals.hold_left, left, left); if (left == secs || left <= 3) b.announceForAccessibility(b.text) }
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    private var cancelled = false
                    override fun onAnimationCancel(a: android.animation.Animator) { cancelled = true }
                    override fun onAnimationEnd(a: android.animation.Animator) {
                        if (cancelled || holding != b) return
                        fired = true; holding = null; fill.f = 0f; b.text = holdBase[b]
                        b.performHapticFeedback(if (android.os.Build.VERSION.SDK_INT >= 30) android.view.HapticFeedbackConstants.CONFIRM else android.view.HapticFeedbackConstants.LONG_PRESS)
                        action()
                    }
                })
                start()
            }
        }
        fun holdRelease() {
            if (fired || holding != b) return
            holding = null; anim?.cancel()
            // back to empty, and say why nothing happened (else the parent thinks the button is broken)
            anim = android.animation.ValueAnimator.ofFloat(fill.f, 0f).apply {
                duration = 250; addUpdateListener { fill.f = it.animatedValue as Float }; start()
            }
            b.text = resources.getQuantityString(R.plurals.hold_need, secs, secs)
            b.announceForAccessibility(b.text)
            ui.postDelayed(restore, 1800)
        }
        b.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> holdBegin()
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> holdRelease()
            }
            false
        }
        // TalkBack (no hold possible with a double tap): a click starts / cancels the countdown
        val am = getSystemService(android.view.accessibility.AccessibilityManager::class.java)
        b.setOnClickListener { if (am?.isTouchExplorationEnabled == true) { if (holding == b) holdRelease() else holdBegin() } }
        b.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK, resources.getQuantityString(R.plurals.hold_a11y, secs, secs)))
            }
            override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
                if (action == android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) { if (holding == b) holdRelease() else holdBegin(); return true }
                return super.performAccessibilityAction(host, action, args)
            }
        }
    }

    private lateinit var qr: ImageView
    private lateinit var sosBtn: View
    private lateinit var ciArrived: Button
    private lateinit var ciPickup: Button
    private lateinit var updBtn: View
    private lateinit var moreBtn: View
    private lateinit var unlockBtn: Button
    private lateinit var adminBtn: Button
    private var qrFor: String? = null

    // ---- home layout: status card ("hero"), the "Thêm" sheet, the status details modal ----
    private lateinit var pairCard: LinearLayout
    private lateinit var health: TextView
    private lateinit var healthBox: View
    private lateinit var statusBox: LinearLayout
    private lateinit var hero: LinearLayout
    private lateinit var heroInfo: LinearLayout
    private lateinit var heroIcon: ImageView
    private lateinit var heroLabel: TextView
    private lateinit var heroBig: TextView
    private lateinit var heroSub: TextView
    private lateinit var heroBar: LinearLayout
    private lateinit var heroFill: View
    private lateinit var heroMeta: LinearLayout
    private lateinit var heroUsed: TextView
    private lateinit var heroLimit: TextView
    private lateinit var heroUnlock: Button
    private lateinit var kidNotes: LinearLayout
    private lateinit var sheet: Sheet
    private lateinit var kidG: LinearLayout
    private lateinit var parentG: LinearLayout
    private lateinit var devG: LinearLayout
    private lateinit var moreRow: Button
    private var warnN = 0     // WARN rows in the status details (refresh), shown as "Cần kiểm tra N mục"

    private fun buildHero(u: Ui) {
        hero = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(20), dp(20), dp(20)) }
        hero.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) }
        // the text part reads as one TalkBack item (contentDescription set in heroRefresh)
        heroInfo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; isFocusable = true }
        heroIcon = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        heroInfo.addView(heroIcon, LinearLayout.LayoutParams(dp(48), dp(48)).apply { bottomMargin = dp(12) })
        heroLabel = u.label("", 12f, u.sub, true).apply { letterSpacing = 0.1f }
        heroInfo.addView(heroLabel)
        heroBig = u.label("", 36f, u.accent).apply {
            typeface = if (android.os.Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.DEFAULT, 800, false) else Typeface.DEFAULT_BOLD
            letterSpacing = -0.01f
        }
        heroInfo.addView(heroBig, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
        heroSub = u.label("", 15f, u.sub).apply { setLineSpacing(0f, 1.1f) }
        heroInfo.addView(heroSub, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        heroBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; weightSum = 1f }
        heroFill = View(this)
        heroBar.addView(heroFill, LinearLayout.LayoutParams(0, -1, 0f))
        heroInfo.addView(heroBar, LinearLayout.LayoutParams(-1, dp(6)).apply { topMargin = dp(14) })
        heroMeta = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        heroUsed = u.label("", 13f, u.sub); heroLimit = u.label("", 13f, u.sub)
        heroMeta.addView(heroUsed, LinearLayout.LayoutParams(0, -2, 1f)); heroMeta.addView(heroLimit, LinearLayout.LayoutParams(-2, -2))
        heroInfo.addView(heroMeta, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        hero.addView(heroInfo)
        heroUnlock = u.button(getString(R.string.main_unlock), R.drawable.li_key, false) { askUnlock() }
        kidNotes = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, 0) }
    }

    private fun buildSheet(u: Ui) {
        sheet = Sheet(this, u, getString(R.string.home_more))
        fun group(t: Int) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(u.section(getString(t)).apply { if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true })
        }.also { sheet.body.addView(it) }
        kidG = group(R.string.home_sec_kid); parentG = group(R.string.home_sec_parent); devG = group(R.string.home_sec_device)
        sheet.body.addView(u.label(getString(R.string.home_version, BuildConfig.VERSION_NAME), 12f, u.sub).apply { gravity = Gravity.CENTER; setPadding(0, dp(16), 0, dp(4)) })
    }

    /** The tech status rows + Device Owner checklist (kept up to date by refresh) in a modal. */
    private fun showStatus() {
        (statusBox.parent as? android.view.ViewGroup)?.removeView(statusBox)
        val maxH = (resources.displayMetrics.heightPixels * 0.55f).toInt()
        val scroll = object : ScrollView(this) {
            override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST))
        }.apply { addView(statusBox) }
        Modal(this, ui2).show(R.drawable.li_shield, Modal.Tone.INFO, getString(R.string.home_status), getString(R.string.home_status_sub), scroll, ok = getString(R.string.close))
            .setOnDismissListener { (statusBox.parent as? android.view.ViewGroup)?.removeView(statusBox) }
    }

    private fun dot(color: Int) = android.graphics.drawable.GradientDrawable().apply {
        shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(color); setBounds(0, 0, dp(8), dp(8))
    }

    /** Status card, health line, pairing card swap and sheet group headers; end of refresh(). */
    private fun heroRefresh(s: Store) {
        val u = ui2
        val pairing = s.visiblePairCode() != null || s.pairCode() != null   // code shown, or first pairing waiting for "hiện mã"
        pairCard.visibility = if (pairing) View.VISIBLE else View.GONE
        hero.visibility = if (pairing) View.GONE else View.VISIBLE
        val ht = if (warnN == 0) getString(R.string.home_ok) else resources.getQuantityString(R.plurals.home_warn, warnN, warnN)
        health.text = ht
        health.setCompoundDrawablesRelative(dot(u.tone(if (warnN == 0) Ic.OK else Ic.WARN)), null, null, null)
        healthBox.contentDescription = getString(R.string.home_status_desc, ht)
        kidNotes.visibility = if (kidNotes.childCount > 0) View.VISIBLE else View.GONE
        if (pairing) moreRow.visibility = if (s.everPaired) View.VISIBLE else View.GONE
        else {
            val r = s.rules; val now = System.currentTimeMillis(); val today = Guard.day(now)
            val cal = java.util.Calendar.getInstance()
            val minute = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
            val (apps, prot) = Policy.cached(this)
            val use = runCatching { Usage.today(this, now).ms }.getOrDefault(emptyMap())
            val left = dailyLeftMs(r, use, today, apps.keys, prot)
            val block = QuickLogic.phoneBlock(decide(r, use, minute, now, today, apps.keys, prot, unlockUntil = s.unlockUntil))
            val warnC = u.tone(Ic.WARN)
            fun dur(ms: Long) = HomeLogic.hm(ms).let { (h, m) -> if (h == 0) getString(R.string.home_m, m) else if (m == 0) getString(R.string.home_h, h) else getString(R.string.home_hm, h, m) }
            fun hhmm(t: Long) = java.text.SimpleDateFormat("HH:mm", java.util.Locale.ROOT).format(java.util.Date(t))
            heroBar.visibility = View.GONE; heroMeta.visibility = View.GONE
            heroIcon.visibility = if (block != null) View.VISIBLE else View.GONE
            heroLabel.visibility = if (block != null) View.GONE else View.VISIBLE
            var sub: String?
            if (block != null) {
                // locked: warm amber card (16% on dark + a stroke, else it looks muddy), reason + when it opens again
                hero.background = u.shape(u.mix(u.card, warnC, if (u.dark) 0.16f else 0.10f), 24, u.mix(u.card, warnC, 0.35f), 1)
                val icon = when (block) { "bedtime" -> R.drawable.li_moon; "schedule" -> R.drawable.li_calendar; "paused" -> R.drawable.li_circle_pause; else -> R.drawable.li_timer }
                heroIcon.background = u.shape(u.mix(u.card, warnC, if (u.dark) 0.28f else 0.20f), 16)
                heroIcon.setImageDrawable(Ic.get(this, icon, warnC, dp(26)))
                heroBig.text = BasicLogic.reasonText(block, s.tr).replaceFirstChar { it.titlecase() }
                heroBig.textSize = 26f; heroBig.setTextColor(u.text)
                val at = HomeLogic.reopenMinute(r, block, minute, Rules.isoDow(today))
                sub = when {
                    block == "paused" -> r.pausedUntil.takeIf { it - now in 1 until 86_400_000L }?.let { getString(R.string.home_reopen_at, hhmm(it)) }
                    at == HomeLogic.TOMORROW -> getString(R.string.home_reopen_tomorrow)
                    at != null -> getString(R.string.home_reopen_at, HomeLogic.clock(at))
                    else -> null
                }
                heroSub.textSize = 17f; heroSub.setTextColor(u.text)
            } else {
                hero.background = u.shape(if (u.dark) u.mix(u.bg, Color.parseColor("#1e3a8a"), 0.55f) else u.tint, 24)
                heroSub.textSize = 15f; heroSub.setTextColor(u.sub)
                if (left < 0) {
                    heroLabel.text = getString(R.string.home_today).uppercase()
                    heroBig.text = getString(R.string.tl_none); heroBig.textSize = 30f; heroBig.setTextColor(u.text)
                    sub = if (r.bedtimeOn) getString(R.string.home_bed, r.bedStart, r.bedEnd) else null
                } else {
                    heroLabel.text = getString(R.string.home_left).uppercase()
                    val c = if (left < 10 * 60_000L) warnC else u.accent
                    heroBig.text = dur(left); heroBig.textSize = 36f; heroBig.setTextColor(c)
                    val limitMs = (baseLimitMin(r, today) + if (r.bonusDay == today) r.bonusMin else 0) * 60_000L
                    val used = use.filterKeys { it in apps.keys && it !in prot && it !in r.allow }.values.sum()
                    heroBar.background = u.shape(if (u.dark) u.mix(u.bg, u.stroke, 0.9f) else Color.WHITE, 3)
                    heroFill.background = u.shape(c, 3)
                    (heroFill.layoutParams as LinearLayout.LayoutParams).weight = if (limitMs > 0) (used.toFloat() / limitMs).coerceIn(0.02f, 1f) else 0f
                    heroFill.requestLayout()
                    heroUsed.text = getString(R.string.home_used, dur(used)); heroLimit.text = getString(R.string.home_limit, dur(limitMs))
                    heroBar.visibility = View.VISIBLE; heroMeta.visibility = View.VISIBLE
                    sub = null
                }
            }
            s.unlockUntil.takeIf { it > now }?.let { sub = getString(R.string.home_unlocked, hhmm(it)) }
            heroSub.text = sub ?: ""; heroSub.visibility = if (sub == null) View.GONE else View.VISIBLE
            moreBtn.visibility = if (HomeLogic.canAsk(s.everPaired, left, block)) View.VISIBLE else View.GONE
            heroUnlock.visibility = if (block != null && s.totpSecret.isNotBlank()) View.VISIBLE else View.GONE
            moreRow.visibility = if (s.everPaired && moreBtn.visibility != View.VISIBLE) View.VISIBLE else View.GONE
            heroInfo.contentDescription = listOfNotNull(heroLabel.text.takeIf { block == null }, heroBig.text, sub,
                heroUsed.text.takeIf { heroMeta.visibility == View.VISIBLE }, heroLimit.text.takeIf { heroMeta.visibility == View.VISIBLE }).joinToString(". ")
        }
        // a sheet group title only while one of its rows is shown
        for (g in listOf(kidG, parentG, devG)) g.getChildAt(0).visibility =
            if ((1 until g.childCount).any { g.getChildAt(it).visibility == View.VISIBLE }) View.VISIBLE else View.GONE
    }

    /** Black-on-white QR (white even in dark mode, so phone cameras read it), with the standard quiet zone. */
    private fun qrBitmap(text: String, px: Int): Bitmap {
        val m = com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, px, px,
            mapOf(com.google.zxing.EncodeHintType.MARGIN to 2))
        val pixels = IntArray(m.width * m.height) { if (m.get(it % m.width, it / m.width)) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(pixels, m.width, m.height, Bitmap.Config.ARGB_8888)
    }

    override fun onResume() {
        super.onResume()
        if (Ui.isDark(this) != ui2.dark) { recreate(); return }   // light / dark changed in the launcher's Tuỳ chỉnh
        launched = false   // back from a screen we opened (see onStop)
        holding = null; holdBase.forEach { (b, t) -> b.text = t }   // a hint left over from before the pause
        val t0 = android.os.SystemClock.elapsedRealtime()
        refresh()
        (android.os.SystemClock.elapsedRealtime() - t0).let { if (it > 16) android.util.Log.i("KG.perf", "main refresh ${it}ms") }
        // the time-left tile / widget: "ask for more time" (only once a parent is paired, like the button)
        if (intent?.getBooleanExtra(Quick.EXTRA_MORE, false) == true) {
            intent.removeExtra(Quick.EXTRA_MORE)
            if (Store.of(this).everPaired) ui.post { askMore() }
        }
    }

    override fun onNewIntent(intent: Intent?) { super.onNewIntent(intent); setIntent(intent) }

    /** Double tap to wake & co. are phone settings no app can switch on: say so, then open the closest page we find. */
    private fun askGestures() {
        Modal(this, ui2).show(R.drawable.li_smartphone, Modal.Tone.INFO, getString(R.string.main_gestures), getString(R.string.main_gestures_body),
            ok = getString(R.string.main_gestures_open), cancel = getString(R.string.cancel)) { openGestures(); true }
    }

    private fun openGestures() {
        val pm = packageManager
        val acts = runCatching {
            @Suppress("DEPRECATION") pm.getPackageInfo(QuickLogic.SETTINGS_PKG, android.content.pm.PackageManager.GET_ACTIVITIES).activities
                ?.filter { it.exported }?.map { it.name }
        }.getOrNull().orEmpty()
        for (t in QuickLogic.gestureTargets(android.os.Build.MANUFACTURER, acts)) {
            val i = (if (t.action != null) Intent(t.action) else Intent().setClassName(t.pkg ?: continue, t.cls ?: continue)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val ai = pm.resolveActivity(i, 0)?.activityInfo ?: continue
            if (!ai.exported && ai.packageName != packageName) continue
            if (runCatching { startActivity(i); true }.getOrDefault(false)) return
        }
    }
    override fun onPause() { super.onPause(); ui.removeCallbacksAndMessages(null) }
    override fun onDestroy() { sheet.dismiss(); super.onDestroy() }

    /**
     * ColorOS force-stops an app whose Recents card is swiped away, so leave no card: on leaving (home, Recents, screen
     * off) remove the task. Not while a screen we opened is on top (settings pages, self-setup, the parent's admin page:
     * the child comes back here with Back), and not for a config change / language recreate.
     */
    private var launched = false
    override fun startActivityForResult(intent: Intent, requestCode: Int, options: Bundle?) { launched = true; super.startActivityForResult(intent, requestCode, options) }
    override fun onStop() { super.onStop(); if (!isChangingConfigurations && !launched && !isFinishing) finishAndRemoveTask() }

    private fun refresh() {
        val s = Store.of(this)
        val owner = Policy.isOwner(this)
        val usage = Usage.granted(this)
        // SOS / more time only reach someone once a parent has paired
        (if (s.everPaired) View.VISIBLE else View.GONE).let { sosBtn.visibility = it; moreBtn.visibility = it }
        unlockBtn.visibility = if (s.totpSecret.isNotBlank()) View.VISIBLE else View.GONE
        adminBtn.visibility = if (PairPin.adminAvailable(s.rules.pinHash.isNotBlank(), s.totpSecret.isNotBlank())) View.VISIBLE else View.GONE
        unlockBtn.text = s.unlockUntil.takeIf { it > System.currentTimeMillis() }?.let { getString(R.string.main_unlocked_until, java.text.SimpleDateFormat("HH:mm", java.util.Locale.ROOT).format(java.util.Date(it))) }
            ?: getString(R.string.main_unlock)
        updBtn.visibility = if (!owner && Update.apk(this).exists() && UpdateLogic.newer(Update.stateJson(this).optString("latest"), Update.current())) View.VISIBLE else View.GONE
        selfBtn.visibility = if (owner || android.os.Build.VERSION.SDK_INT < 30) View.GONE else View.VISIBLE
        if (owner) basicList.box.visibility = View.GONE else basicList.update()
        val lan = Guard.lanUrl()
        Checklist.update(this, checklist)
        if (!owner) checklist.visibility = View.GONE   // basic mode has its own, longer checklist
        val u = ui2
        status.removeAllViews(); kidNotes.removeAllViews(); warnN = 0
        // line: tech status (the details modal, WARNs counted for the health line); kidLine: what the child must see, in the status card
        fun line(icon: Int, tone: String, t: String) { if (tone == Ic.WARN) warnN++; status.addView(u.row(icon, tone, t)) }
        fun kidLine(icon: Int, tone: String, t: String) = kidNotes.addView((u.row(icon, tone, t) as LinearLayout).apply {
            setPadding(0, dp(5), 0, dp(5))   // compact inside the status card
            getChildAt(0).layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)); (getChildAt(1) as TextView).textSize = 14f
        })
        val ok = R.drawable.li_circle_check; val warn = R.drawable.li_triangle_alert
        if (owner) line(ok, Ic.OK, getString(R.string.st_owner)) else if (Basic.mode(this) == "basic") line(ok, Ic.OK, getString(R.string.st_basic))
            else line(warn, Ic.WARN, getString(R.string.st_unfinished))
        if (!usage) line(warn, Ic.WARN, getString(R.string.st_no_usage))
        if (Guard.running != null) line(ok, Ic.OK, getString(R.string.st_running)) else line(R.drawable.li_loader, Ic.INFO, getString(R.string.st_starting))
        if (Acc.isOn(this@MainActivity)) line(R.drawable.li_accessibility, Ic.OK, getString(R.string.st_acc_on)) else line(R.drawable.li_accessibility, Ic.WARN, getString(R.string.st_acc_off))
        val locOn = Basic.locationOn(this)
        line(R.drawable.li_map_pin, if (locOn) Ic.OK else Ic.WARN, getString(if (locOn) R.string.st_loc_on else R.string.st_loc_off))
        val pending = s.requests().length()
        if (pending > 0) kidLine(R.drawable.li_message_circle, Ic.INFO, resources.getQuantityString(R.plurals.st_pending, pending, pending))
        if (Listen.active) kidLine(R.drawable.li_mic, Ic.WARN, getString(R.string.notif_listen_title))
        if (s.sos().length() > 0) kidLine(R.drawable.li_siren, Ic.WARN, getString(R.string.st_sos))
        comfortRefresh(s) { icon, tone, text -> kidLine(icon, tone, text) }
        if (s.relayUrl.isNotBlank()) line(R.drawable.li_globe, Ic.INFO, getString(R.string.st_remote))
        // paired before PINs were mandatory: only a parent's page can set it (never this phone: the child holds it)
        if (PairPin.legacyNoPin(s.everPaired, s.rules.pinHash.isNotBlank())) line(warn, Ic.WARN, getString(R.string.st_no_pin))
        s.lastListen?.let { (t, ms) ->
            val fmt = if (Guard.day(t) == Guard.day(System.currentTimeMillis())) "HH:mm" else "dd/MM HH:mm"
            val at = java.text.SimpleDateFormat(fmt, java.util.Locale.US).format(java.util.Date(t))
            val min = (ms + 30_000) / 60_000
            line(R.drawable.li_mic, Ic.INFO, getString(R.string.st_last_listen, at, if (min < 1) getString(R.string.under_1_min) else resources.getQuantityString(R.plurals.min_n, min.toInt(), min.toInt())))
        }
        val c = s.visiblePairCode()
        val hidden = c == null && s.pairCode() != null     // first pairing, code not revealed yet
        // the pairing card is hidden once paired: its "managed at <url>" line lives in the status details
        if (c == null && !hidden) line(R.drawable.li_users, Ic.INFO, getString(R.string.main_hint_managed, lan ?: getString(R.string.main_no_wifi)))
        revealBtn.visibility = if (hidden) View.VISIBLE else View.GONE
        recoverBtn.visibility = if (s.everPaired && c == null && s.rules.pinHash.isBlank()) View.VISIBLE else View.GONE
        holdLabel(recoverBtn, s.recoverAt.takeIf { it > 0 }?.let { getString(R.string.main_recover_at, java.text.SimpleDateFormat("HH:mm dd/MM", java.util.Locale.ROOT).format(java.util.Date(it))) }
            ?: getString(R.string.main_recover))
        if (c != null) {
            code.visibility = View.VISIBLE; codeLabel.visibility = View.VISIBLE; code.text = c
            val left = s.pairOpenUntil() - System.currentTimeMillis()
            codeLabel.text = if (left > 0) getString(R.string.main_code_left, if (left < 60_000) getString(R.string.main_code_under1) else getString(R.string.main_code_min, ((left + 59_999) / 60_000).toInt())) else getString(R.string.main_code_label)
            hint.gravity = Gravity.CENTER
            val link = lan?.let { "$it/#pair=$c" }
            if (link != qrFor) { qrFor = link; link?.let { qr.setImageBitmap(qrBitmap(it, dp(208))) } }
            qr.visibility = if (link != null) View.VISIBLE else View.GONE
            hint.text = if (link != null) getString(R.string.main_hint_qr, lan)
                else getString(R.string.main_hint_nowifi)
            hint.setCompoundDrawablesRelative(null, null, null, null)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            pairBtn.visibility = View.GONE
        } else {
            code.visibility = View.GONE; codeLabel.visibility = View.GONE; qr.visibility = View.GONE; qrFor = null
            hint.gravity = Gravity.START
            hint.text = if (hidden) getString(R.string.main_hint_hidden)
                else getString(R.string.main_hint_managed, lan ?: getString(R.string.main_no_wifi)); Ic.lead(hint, R.drawable.li_users, ui2.sub)
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            pairBtn.visibility = if (hidden) View.GONE else View.VISIBLE
        }
        heroRefresh(s)
        ui.removeCallbacks(refreshLoop); ui.postDelayed(refreshLoop, 3000)   // one loop, however often refresh() is called
    }
    private val refreshLoop = Runnable { refresh() }

    /** Opening pairing on the child needs the parent's PIN, once one is set; else a child could pair their own browser. */
    private fun askPin() {
        val s = Store.of(this)
        val rules = s.rules
        if (rules.pinHash.isBlank()) {
            // already paired and no PIN: opening pairing would let the child pair their own browser
            if (s.everPaired) { Modal(this, ui2).show(R.drawable.li_shield, Modal.Tone.WARN, getString(R.string.pin_need_title), getString(R.string.pin_need_body)); return }
            s.openPairing(); refresh(); return
        }
        fun lockText(left: Long) = ((left + 59_999) / 60_000).toInt().let { resources.getQuantityString(R.plurals.pin_locked, it, it) }
        s.pinLockLeft().takeIf { it > 0 }?.let { left ->
            Modal(this, ui2).show(R.drawable.li_triangle_alert, Modal.Tone.WARN, getString(R.string.locked_title), lockText(left)); return
        }
        val m = Modal(this, ui2)
        val input = m.field(getString(R.string.pin_field), 12, pin = true)
        m.show(R.drawable.li_shield, Modal.Tone.INFO, getString(R.string.pin_title), getString(R.string.pin_body), input, ok = getString(R.string.pin_ok), cancel = getString(R.string.cancel)) {
            if (s.checkParentPin(input.text.toString().filter { it.isDigit() })) { s.openPairing(); refresh(); true }
            else {
                input.setText("")
                m.setError(s.pinLockLeft().takeIf { it > 0 }?.let { lockText(it) } ?: getString(R.string.pin_wrong))
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
        val days = resources.getStringArray(R.array.day_short)
        val L = mutableListOf<String>()
        val base = baseLimitMin(r, today)
        if (base <= 0) L += getString(R.string.r_no_limit)
        else {
            val left = dailyLeftMs(r, Usage.today(this, now).ms, today, apps.keys, prot)
            val limit = base + if (r.bonusDay == today) r.bonusMin else 0
            L += getString(R.string.r_today, ((left + 59_999) / 60_000).toInt(), limit)
        }
        L += if (r.bedtimeOn) getString(R.string.r_bed, r.bedStart, r.bedEnd) else getString(R.string.r_bed_none)
        val sch = r.schedules.filter { it.on }
        if (sch.isEmpty()) L += getString(R.string.r_sched_none)
        else sch.forEach { val ds = it.days.sorted().joinToString(", ") { d -> days[d - 1] }
            L += if (it.name.isBlank()) getString(R.string.r_sched, it.start, it.end, ds) else getString(R.string.r_sched_named, it.name, it.start, it.end, ds) }
        L += if (r.allow.isEmpty()) getString(R.string.r_allow_sys)
            else getString(R.string.r_allow, r.allow.joinToString { name(it) })
        L += getString(R.string.r_blocked, r.blocked.size)
        L += if (r.trackMin > 0) getString(R.string.r_track, r.trackMin) else getString(R.string.r_track_off)
        L += getString(if (!Acc.isOn(this)) R.string.r_view_off else if (r.showWatching) R.string.r_view_told else R.string.r_view)
        L += getString(R.string.r_listen)
        r.groups.forEach { g ->
            val h = g.hours.joinToString { w -> w.start + "–" + w.end + if (w.days.size < 7) " (" + w.days.sorted().joinToString(",") { d -> days[d - 1] } + ")" else "" }
            L += getString(R.string.r_group, g.name, listOfNotNull(g.min.takeIf { it > 0 }?.let { getString(R.string.r_group_min, it) }, h.takeIf { it.isNotEmpty() }?.let { getString(R.string.r_group_hours, it) }).ifEmpty { listOf(getString(R.string.no_limit)) }.joinToString(", ")) +
                " · " + g.pkgs.take(6).joinToString { Policy.labelOf(this, it) } + if (g.pkgs.size > 6) " …" else ""
        }
        if (r.zones.isNotEmpty()) L += getString(R.string.r_zones, r.zones.joinToString { it.name })
        if (r.chores.isNotEmpty()) L += getString(R.string.r_chores, r.chores.joinToString { getString(R.string.chore_item, it.name, it.min) })
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        L.forEach { box.addView(ui2.label("• $it", 15f, ui2.text).apply { setPadding(0, dp(4), 0, dp(4)) }) }
        Modal(this, ui2).show(R.drawable.li_shield, Modal.Tone.INFO, getString(R.string.main_my_rules), getString(R.string.r_sub), ScrollView(this).apply { addView(box) }, ok = getString(R.string.close))
    }

    /** The last 20 log lines, newest first, one heading per day, an icon per kind (`k`, same map as the web's LOG_IC). */
    private fun showLog() {
        val a = Store.of(this).logs()
        val u = ui2
        val bad = Color.parseColor(if (u.dark) "#f87171" else "#dc2626")
        val hm = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
        val dayFmt = java.text.SimpleDateFormat("d/M", java.util.Locale.US)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (a.length() == 0) box.addView(u.label(getString(R.string.log_empty), 15f, u.sub))
        val today = Usage.midnightAgo(0); val yest = Usage.midnightAgo(1)
        var lastDay = -1L
        for (i in a.length() - 1 downTo maxOf(0, a.length() - 20)) {
            val o = a.optJSONObject(i) ?: continue
            val t = o.optLong("t")
            val day = if (t >= today) today else if (t >= yest) yest else java.util.Calendar.getInstance().apply { timeInMillis = t; set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0) }.timeInMillis
            if (day != lastDay) {
                lastDay = day
                box.addView(u.label(when (day) { today -> getString(R.string.home_today); yest -> getString(R.string.log_yesterday); else -> dayFmt.format(java.util.Date(t)) },
                    13f, u.sub, true).apply { setPadding(0, dp(if (box.childCount == 0) 0 else 12), 0, dp(4)) })
            }
            val (icon, isBad) = LOG_IC[o.optString("k")] ?: (R.drawable.li_circle to false)
            val c = if (isBad) bad else u.accent
            box.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(5), 0, dp(5))
                addView(android.widget.ImageView(context).apply {
                    background = u.shape(Color.argb(if (u.dark) 55 else 30, Color.red(c), Color.green(c), Color.blue(c)), 10)
                    scaleType = android.widget.ImageView.ScaleType.CENTER; setImageDrawable(Ic.get(context, icon, c, dp(16)))
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(dp(30), dp(30)))
                addView(u.label(noEmoji(o.optString("m")), 14f, u.text), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10); marginEnd = dp(8) })
                addView(u.label(hm.format(java.util.Date(t)), 12f, u.sub))
            })
        }
        Modal(this, u).show(R.drawable.li_scroll_text, Modal.Tone.INFO, getString(R.string.log_title), getString(R.string.log_sub), ScrollView(this).apply { addView(box) }, ok = getString(R.string.close))
    }

    private val LOG_IC: Map<String, Pair<Int, Boolean>> = mapOf(
        "pin_wrong" to (R.drawable.li_key_round to true), "unlock" to (R.drawable.li_lock_open to false), "paired" to (R.drawable.li_link to false),
        "tz" to (R.drawable.li_globe to false), "loc_fixed" to (R.drawable.li_map_pin to false), "usage_off" to (R.drawable.li_triangle_alert to true),
        "new_app" to (R.drawable.li_package to false), "suspended" to (R.drawable.li_pause to false), "unsuspended" to (R.drawable.li_play to false),
        "recover_open" to (R.drawable.li_refresh_cw to false), "recover_req" to (R.drawable.li_refresh_cw to false), "zone" to (R.drawable.li_map_pin to false),
        "cmd" to (R.drawable.li_send to false), "cleared" to (R.drawable.li_eraser to false), "totp_off" to (R.drawable.li_shield to true),
        "totp_on" to (R.drawable.li_shield to false), "release_fail" to (R.drawable.li_octagon_x to true), "released" to (R.drawable.li_unlink to true),
        "sos" to (R.drawable.li_siren to true), "acc_settings" to (R.drawable.li_triangle_alert to true), "acc_rebind" to (R.drawable.li_refresh_cw to false),
        "acc_reenabled" to (R.drawable.li_refresh_cw to false), "view_start" to (R.drawable.li_eye to false), "control" to (R.drawable.li_pointer to false),
        "proj_denied" to (R.drawable.li_eye_off to true), "listen_start" to (R.drawable.li_ear to false), "listen_done" to (R.drawable.li_ear to false),
        "listen_err" to (R.drawable.li_ear to true), "ring_stopped" to (R.drawable.li_bell to false), "dns_fallback" to (R.drawable.li_wifi_off to true),
        "admin_open" to (R.drawable.li_settings to false), "checkin" to (R.drawable.li_hand to false), "rules" to (R.drawable.li_list to false),
        "dev_off" to (R.drawable.li_settings to false)) + listOf("setting_vol", "setting_bright", "setting_auto_on", "setting_auto_off", "setting_timeout", "setting_dnd_on", "setting_dnd_off").associateWith { R.drawable.li_sliders_horizontal to false }

    private fun noEmoji(s: String) = s.replace(Regex("[\\p{So}\\p{Cs}\\uFE0F\\u200D]"), "").trimStart()

    /**
     * v0.9.7 status + check-in labels. Lines: grayscale on (wind-down), the parent's note while something is locked.
     * Buttons: idle "Con tới nơi rồi", after a send "Con tới nơi rồi · đã gửi 14:05" (today only).
     */
    private fun comfortRefresh(s: Store, line: (Int, String, String) -> Unit) {
        val paired = s.everPaired
        ciArrived.visibility = if (paired) View.VISIBLE else View.GONE; ciPickup.visibility = ciArrived.visibility
        val now = System.currentTimeMillis()
        fun label(ck: String, base: Int): String {
            val t = s.lastCheckin(ck)
            return if (t > 0 && Guard.day(t) == Guard.day(now)) getString(R.string.main_ci_sent, getString(base), java.text.SimpleDateFormat("HH:mm", java.util.Locale.ROOT).format(java.util.Date(t)))
                else getString(base)
        }
        ciArrived.text = label("arrived", R.string.main_ci_arrived); ciPickup.text = label("pickup", R.string.main_ci_pickup)
        if (GrayFx.applied) line(R.drawable.li_timer, Ic.INFO, getString(R.string.st_gray))
        val note = Notes.clean(s.rules.blockNote, Notes.NOTE_MAX)
        val locked = Guard.running?.currentState()?.optJSONArray("apps")?.let { a -> (0 until a.length()).any { a.optJSONObject(it)?.optBoolean("suspended") == true || a.optJSONObject(it)?.isNull("blocked") == false } } == true
        if (note.isNotEmpty() && locked) line(R.drawable.li_message_circle, Ic.INFO, getString(R.string.policy_note, note))
    }

    private val acts by lazy { KidActions(this, ui2) { refresh() } }
    private fun askCheckin(ck: String) = acts.askCheckin(ck)
    private fun askSos() = acts.askSos()
    private fun askMore() = acts.askMore()

    /** A parent reads 6 digits from Google Authenticator (works with no network on either phone). */
    private fun askUnlock() {
        val s = Store.of(this)
        fun lockText(left: Long) = ((left + 59_999) / 60_000).toInt().let { resources.getQuantityString(R.plurals.unl_locked, it, it) }
        s.unlockLockLeft().takeIf { it > 0 }?.let { Modal(this, ui2).show(R.drawable.li_triangle_alert, Modal.Tone.WARN, getString(R.string.locked_title), lockText(it)); return }
        val m = Modal(this, ui2)
        val input = m.field(getString(R.string.unl_hint), 6, pin = true).apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER; hint = getString(R.string.unl_hint) }
        m.show(R.drawable.li_shield, Modal.Tone.INFO, getString(R.string.unl_title), getString(R.string.unl_body, s.rules.unlockMin), input, ok = getString(R.string.unl_ok), cancel = getString(R.string.cancel)) {
            val min = s.tryUnlock(input.text.toString())
            if (min > 0) { Guard.poke(this); refresh(); ui.post { Modal(this, ui2).show(R.drawable.li_shield, Modal.Tone.OK, getString(R.string.unl_done), resources.getQuantityString(R.plurals.unl_done_body, min, min)) }; true }
            else { input.setText(""); m.setError(s.unlockLockLeft().takeIf { it > 0 }?.let { lockText(it) } ?: getString(R.string.unl_wrong)); false }
        }
        input.requestFocus()
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
    private val h = Handler(Looper.getMainLooper())
    private var textView: TextView? = null
    private val wakeOff = Runnable { screenOnFlag(false) }

    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(Lang.wrap(base))   // in-app language (Android 8-12)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(pad, pad, pad, pad); setBackgroundColor(Color.parseColor("#1e3a8a")) }
        col.addView(TextView(this).apply { text = getString(R.string.msg_title); textSize = 18f; setTextColor(Color.parseColor("#bfdbfe")); gravity = Gravity.CENTER; Ic.lead(this, R.drawable.li_mail) })
        textView = TextView(this).apply { text = intent.getStringExtra("text") ?: ""; textSize = 30f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setPadding(0, pad, 0, pad) }
        col.addView(textView)
        col.addView(Button(this).apply { text = getString(R.string.msg_read); Ic.lead(this, R.drawable.li_thumbs_up); setOnClickListener {
            runCatching { getSystemService(NotificationManager::class.java).cancel(NID) }; finish() } })
        setContentView(col)
        wakeScreen()
    }

    /** singleTop: a second message while this one is open replaces the text (it used to keep showing the first). */
    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        setIntent(i)
        textView?.text = i.getStringExtra("text") ?: ""
        wakeScreen()
    }

    /**
     * Turn the screen on for a new message only. A permanent turnScreenOn (the old manifest attribute) re-lit the screen
     * every time the activity came back over the lock screen, so the parent's `lock` while a message was open did nothing.
     */
    private fun wakeScreen() { h.removeCallbacks(wakeOff); screenOnFlag(true); h.postDelayed(wakeOff, 3_000) }

    private fun screenOnFlag(on: Boolean) {
        if (android.os.Build.VERSION.SDK_INT >= 27) setTurnScreenOn(on)
        else @Suppress("DEPRECATION") if (on) window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
    }

    /** On screen now: keep the message in the shade (Home without "Got it"), but drop the full-screen intent. */
    // delayed: on a second message show() posts its full-screen notice right after startActivity, often after this resume
    override fun onResume() { super.onResume(); h.removeCallbacks(quiet); h.postDelayed(quiet, 1_500) }
    private val quiet = Runnable { if (!isFinishing) notice(this, textView?.text?.toString() ?: "", fullScreen = false) }

    override fun onDestroy() { h.removeCallbacksAndMessages(null); super.onDestroy() }

    companion object {
        private const val NID = 3
        fun show(ctx: Context, text: String) {
            val i = Intent(ctx, MessageActivity::class.java).putExtra("text", text)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            runCatching { ctx.startActivity(i) }
            // background activity starts can be blocked: a heads-up notification with a full-screen intent shows it anyway
            notice(ctx, text, fullScreen = true)
        }

        /**
         * [fullScreen] only until the message is on screen: SystemUI fires a pending full-screen intent again whenever the
         * keyguard comes up, so a parent's `lock` re-woke the phone at once while the message was open.
         */
        private fun notice(ctx: Context, text: String, fullScreen: Boolean) = runCatching {
            val i = Intent(ctx, MessageActivity::class.java).putExtra("text", text)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("msg", ctx.getString(R.string.ch_msg), NotificationManager.IMPORTANCE_HIGH))
            val pi = PendingIntent.getActivity(ctx, 3, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            nm.notify(NID, Notification.Builder(ctx, "msg").setSmallIcon(android.R.drawable.ic_lock_lock).setContentTitle(ctx.getString(R.string.msg_title))
                .setContentText(text).setStyle(Notification.BigTextStyle().bigText(text)).setCategory(Notification.CATEGORY_MESSAGE)
                .setOnlyAlertOnce(!fullScreen).setAutoCancel(true).setContentIntent(pi)
                .apply { if (fullScreen) setFullScreenIntent(pi, true) }.build())
        }
    }
}
