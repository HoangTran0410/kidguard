package dev.kidguard

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Full-screen "turn location back on" card (basic mode without adb: nothing can switch it on for the kid). Same look as
 * the parent's message card. It blocks nothing: Back / Home leave it, it comes back after [LocNagLogic.POP_EVERY], never
 * during a call, on a locked screen or with an SOS waiting. "Later" = 10 min, 3 a day. Owner 2026-11-19.
 */
class LocNagActivity : Activity() {
    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(Lang.wrap(base))
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!LocNag.active(this)) { finish(); return }
        cur = java.lang.ref.WeakReference(this)
        val ui = Ui(this)
        val root = FrameLayout(this).apply { setBackgroundColor(ui.bg); fitsSystemWindows = true }
        val card = ui.cardBox(24).apply { gravity = Gravity.CENTER_HORIZONTAL; background = ui.shape(ui.card, 28, ui.stroke, 1); elevation = ui.dpf(6)
            layoutParams = FrameLayout.LayoutParams(-1, -2, Gravity.CENTER).apply { val m = ui.dp(20); setMargins(m, m, m, m) } }
        card.addView(ImageView(this).apply {
            setImageResource(R.drawable.li_map_pin); imageTintList = android.content.res.ColorStateList.valueOf(ui.accent)
            background = ui.shape(ui.tint, 22); val p = ui.dp(16); setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(ui.dp(64), ui.dp(64)) })
        card.addView(TextView(this).apply { text = getString(R.string.locnag_t); textSize = 26f; setTextColor(ui.text); gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(0, ui.dp(16), 0, ui.dp(8)) })
        card.addView(ui.label(getString(R.string.locnag_b), 16f).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, ui.dp(20)) })
        card.addView(ui.button(getString(R.string.locnag_on), R.drawable.li_map_pin, true) { LocNag.openSettings(this); finish() }.apply {
            background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(Color.argb(60, 255, 255, 255)), ui.shape(ui.primary, 27), null) })
        val left = LocNag.snoozesLeft(this)
        if (left > 0) card.addView(ui.button(getString(R.string.locnag_later, left), R.drawable.li_clock, false) { LocNag.snooze(this); finish() }.apply {
            (layoutParams as? LinearLayout.LayoutParams ?: LinearLayout.LayoutParams(-1, -2)).let { it.topMargin = ui.dp(10); layoutParams = it } })
        else card.addView(ui.label(getString(R.string.locnag_none), 13f).apply { gravity = Gravity.CENTER; setPadding(0, ui.dp(12), 0, 0) })
        root.addView(card)
        setContentView(root)
        window.statusBarColor = ui.bg; window.navigationBarColor = ui.bg
        @Suppress("DEPRECATION") if (!ui.dark) window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or (if (android.os.Build.VERSION.SDK_INT >= 26) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0)
        card.alpha = 0f; card.scaleX = 0.94f; card.scaleY = 0.94f; card.translationY = ui.dpf(12)
        Motion.enter(card, Motion.SLOW, Motion.STD)
    }

    override fun onResume() { super.onResume(); if (!LocNag.active(this)) finish() }   // turned on meanwhile
    override fun onDestroy() { if (cur?.get() === this) cur = null; super.onDestroy() }

    companion object {
        @Volatile private var cur: java.lang.ref.WeakReference<LocNagActivity>? = null
        /** Location is back on (or released): the card goes by itself. */
        fun close() { cur?.get()?.let { a -> a.runOnUiThread { if (!a.isFinishing) a.finish() } } }
    }
}
