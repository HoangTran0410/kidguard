package dev.kidguard

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * Bottom sheet for the child screen's secondary actions ("Thêm"). Built once: [body] keeps its views (MainActivity
 * refresh() keeps updating them while it is closed), [show] / [dismiss] only toggle the dialog. Scrolls past 88% height.
 */
class Sheet(act: Activity, u: Ui, title: String) {
    val body = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    private val d = Dialog(act)

    init {
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(u.card); val r = u.dpf(28); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f) }
            setPadding(u.dp(12), u.dp(10), u.dp(12), u.dp(16))
        }
        box.addView(View(act).apply { background = u.shape(u.stroke, 2); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
            LinearLayout.LayoutParams(u.dp(36), u.dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = u.dp(10) })
        box.addView(u.label(title, 20f, u.text, true).apply { setPadding(u.dp(12), 0, u.dp(12), 0); accessibilityHeading(this) })
        val scroll = ScrollView(act).apply { isVerticalScrollBarEnabled = false; addView(body) }
        box.addView(scroll, LinearLayout.LayoutParams(-1, -2, 1f))   // weight + wrap: shrinks only when the sheet would not fit (big font, short screen)
        box.isClickable = true   // taps on the sheet itself don't reach the scrim
        // full-window dialog with its own scrim: the navigation bar then takes the sheet's color instead of a dimmed strip
        val scrim = android.widget.FrameLayout(act).apply {
            setBackgroundColor(Color.argb(128, 0, 0, 0)); setOnClickListener { d.dismiss() }
            setPadding(0, u.dp(40), 0, 0)   // never reaches the status bar
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(box, android.widget.FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        }
        // ColorOS (and edge-to-edge windows) draw this full-window dialog under the navigation bar: keep the last row above it
        // (no insets reach it there, so measure: whatever of the scrim lies below the visible frame is under the bar)
        scrim.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val r = android.graphics.Rect(); v.getWindowVisibleDisplayFrame(r)
            val at = IntArray(2); v.getLocationOnScreen(at)
            val under = (at[1] + v.height - r.bottom).coerceIn(0, u.dp(96))
            if (box.paddingBottom != u.dp(16) + under) v.post { box.setPadding(u.dp(12), u.dp(10), u.dp(12), u.dp(16) + under) }
        }
        d.setContentView(scrim)
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            setWindowAnimations(android.R.style.Animation_InputMethod)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND); addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            navigationBarColor = u.card; statusBarColor = Color.TRANSPARENT
            if (!u.dark && android.os.Build.VERSION.SDK_INT >= 27) decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
    }

    private fun accessibilityHeading(v: View) { if (android.os.Build.VERSION.SDK_INT >= 28) v.isAccessibilityHeading = true }

    val showing get() = d.isShowing
    fun show() { if (!d.isShowing) d.show() }
    fun dismiss() { if (d.isShowing) d.dismiss() }
}
