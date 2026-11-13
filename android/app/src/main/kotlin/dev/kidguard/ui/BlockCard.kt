package dev.kidguard

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout

/**
 * Basic mode (accessibility, no Device Owner): the card a child sees when a blocked app is sent back to Home, instead of
 * an app that just vanishes. Why (the parent's note or the phone-wide reason with its reopen time), "Xin thêm giờ" /
 * "Xin mở <app>" when a yes can change something, and OK. Same overlay mechanics as [BlockNotice]. Shown at most once
 * while up and not again within [QUIET_MS] (a page re-opened in a loop must not flash). Not in the Home flavor.
 * Called from Acc.bounceApp: `BlockCard.show(service, label, pkg, code)`; false = the caller posts its notification as before.
 */
internal object BlockCard {
    private const val SHOW_MS = 15_000L
    private const val QUIET_MS = 10_000L
    private val main = Handler(Looper.getMainLooper())
    private var view: View? = null
    private var wm: WindowManager? = null
    private var goneAt = 0L
    private var hide: Runnable? = null

    fun show(svc: AccessibilityService, label: String, pkg: String, code: String): Boolean {
        if (Flavor.HOME) return false
        val now = android.os.SystemClock.elapsedRealtime()
        if (view != null || now - goneAt < QUIET_MS) return false
        return runCatching {
            val s = Store.of(svc)
            val u = Ui(svc)
            val nw = KidNow.now(svc, s)
            val phoneWide = code in setOf("bedtime", "schedule", "daily", "paused")
            val why = (if (phoneWide) KidNow.line(svc, nw) else null)
                ?: Notes.clean(s.rules.blockNote, Notes.NOTE_MAX).let { n -> if (n.isEmpty()) svc.getString(R.string.policy_support) else svc.getString(R.string.policy_note, n) }
            val appAsk = code == "blocked" || code == "new"
            val canAsk = s.everPaired && KidNow.canAsk(nw) && code != "settings" && (appAsk || HomeLogic.canAsk(true, 0L, code))
            val pending = s.openRequest()?.let { svc.getString(R.string.req_pending_at, KidNow.hhmm(it.optLong("at"))) }
            val card = LinearLayout(svc).apply {
                orientation = LinearLayout.VERTICAL
                background = u.shape(u.card, 20, u.stroke, 1)
                setPadding(u.dp(20), u.dp(20), u.dp(20), u.dp(16))
                addView(u.label(svc.getString(R.string.notif_blocked, label, BasicLogic.reasonText(code, ResTr(svc))), 18f, u.text, true))
                addView(u.label(why, 15f, u.sub).apply { setLineSpacing(0f, 1.15f); setPadding(0, u.dp(10), 0, 0) })
                if (pending != null) addView(u.label(pending, 14f, u.text, true).apply { setPadding(0, u.dp(10), 0, 0) })
                if (canAsk && pending == null) addView(u.button(if (appAsk) svc.getString(R.string.req_app_ask, label) else svc.getString(R.string.bc_ask), R.drawable.li_timer, true) {
                    dismiss()
                    runCatching { svc.startActivity(Quick.moreIntent(svc).apply { if (appAsk) putExtra(EXTRA_ASK_PKG, pkg).putExtra(EXTRA_ASK_LABEL, label) }
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = u.dp(14) })
                addView(u.button(svc.getString(R.string.bn_ok), null, !(canAsk && pending == null)) { dismiss() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = u.dp(8) })
            }
            val root = FrameLayout(svc).apply {
                setBackgroundColor(0x99000000.toInt())
                setPadding(u.dp(24), 0, u.dp(24), 0)
                addView(card, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
                setOnClickListener { dismiss() }
            }
            card.isClickable = true
            // focusable only through its buttons: the app underneath keeps the keyboard / focus (same flags as BlockNotice)
            val lp = WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT)
            val m = svc.getSystemService(WindowManager::class.java)
            m.addView(root, lp)
            view = root; wm = m
            hide = Runnable { dismiss() }.also { main.postDelayed(it, SHOW_MS) }
            true
        }.getOrDefault(false)
    }

    fun dismiss() {
        hide?.let { main.removeCallbacks(it) }; hide = null
        val v = view ?: return
        view = null; goneAt = android.os.SystemClock.elapsedRealtime()
        runCatching { wm?.removeViewImmediate(v) }
    }

    /** Extras on the MainActivity intent when the child asks to open a blocked app (handled in MainActivity.onResume). */
    const val EXTRA_ASK_PKG = "kg.ask.pkg"
    const val EXTRA_ASK_LABEL = "kg.ask.label"
}
