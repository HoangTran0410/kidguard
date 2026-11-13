package dev.kidguard

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout

/**
 * Why a Settings page was just closed (date/time, uninstall, accessibility ...): a small card over the home screen with
 * what is blocked, why, who can change it, and OK. Drawn as an accessibility overlay window (TYPE_ACCESSIBILITY_OVERLAY)
 * from the still-bound service: no Activity start (no manifest entry, no background-start limit, nothing in Recents), and
 * NOT_FOCUSABLE so it never takes focus or the keyboard from the page underneath. Not repeated: while the card is up, or
 * within [QUIET_MS] after it went, the caller shows only the short toast (a page re-opened in a loop must not look like a
 * flashing screen). Auto-dismisses after [SHOW_MS]. Not in the Home flavor (no parental parts).
 */
internal object BlockNotice {
    private const val SHOW_MS = 15_000L
    private const val QUIET_MS = 20_000L
    private val main = Handler(Looper.getMainLooper())
    private var view: View? = null
    private var wm: WindowManager? = null
    private var goneAt = 0L
    private var hide: Runnable? = null

    /** True = the card is shown now. False = caller falls back to the toast (repeat, HOME flavor, no permission). */
    fun show(svc: AccessibilityService, titleRes: Int, bodyRes: Int): Boolean {
        if (Flavor.HOME) return false
        val now = android.os.SystemClock.elapsedRealtime()
        if (view != null || now - goneAt < QUIET_MS) return false
        return runCatching {
            val u = Ui(svc)
            val card = LinearLayout(svc).apply {
                orientation = LinearLayout.VERTICAL
                background = u.shape(u.card, 20, u.stroke, 1)
                setPadding(u.dp(20), u.dp(20), u.dp(20), u.dp(16))
                addView(u.label(svc.getString(titleRes), 18f, u.text, true))
                addView(u.label(svc.getString(bodyRes), 15f, u.sub).apply { setLineSpacing(0f, 1.15f); setPadding(0, u.dp(10), 0, 0) })
                addView(u.label(svc.getString(R.string.bn_who), 13f, u.sub).apply { setPadding(0, u.dp(10), 0, 0) })
                addView(u.button(svc.getString(R.string.bn_ok), null, true) { dismiss() })
            }
            val root = FrameLayout(svc).apply {
                setBackgroundColor(0x99000000.toInt())
                setPadding(u.dp(24), 0, u.dp(24), 0)
                addView(card, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
                setOnClickListener { dismiss() }   // a tap outside the card closes it too (no 15 s trap)
            }
            card.isClickable = true
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
}
