package dev.kidguard

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * "What your parent can see": the child's own list of everything the parent may read, built from the [Monitoring]
 * registry and the rules on this phone. Read-only on purpose (the parent decides, the child is told); no PIN needed to look.
 * Opened from the lasting KidGuard notification, the heads-up when a parent turns something on, and the main screen.
 */
class WatchActivity : Activity() {
    override fun attachBaseContext(base: Context) = super.attachBaseContext(Lang.wrap(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ui = Ui(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; val p = ui.dp(20); setPadding(p, ui.dp(24), p, ui.dp(32)) }
        col.addView(ui.label(getString(R.string.watch_screen_title), 24f, ui.text, bold = true))
        col.addView(ui.label(getString(R.string.watch_intro), 15f).apply { setPadding(0, ui.dp(8), 0, ui.dp(4)); setLineSpacing(0f, 1.15f) })
        val w = runCatching { Store.of(this).baseRules.watch }.getOrDefault(WatchRule())
        val on = Monitoring.active(w)
        if (on.isEmpty()) col.addView(ui.cardBox(16).apply { addView(ui.label(getString(R.string.watch_none), 16f, ui.text)) })
        for (c in on) col.addView(ui.cardBox(16).apply {
            addView(TextView(context).apply {
                text = getString(c.title(w)).replaceFirstChar { it.uppercase() } + "  ·  " + getString(R.string.watch_on)
                textSize = 16f; setTextColor(ui.accent); setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            addView(ui.label(getString(c.kid(w)), 15f, ui.text).apply { setPadding(0, ui.dp(8), 0, 0); setLineSpacing(0f, 1.15f) })
            addView(ui.label(getString(c.sees(w)), 13f).apply { setPadding(0, ui.dp(6), 0, 0) })
        })
        col.addView(ui.label(getString(R.string.watch_never), 14f).apply { setPadding(0, ui.dp(18), 0, 0); setLineSpacing(0f, 1.15f) })
        col.addView(ui.button(getString(R.string.msg_read), R.drawable.li_thumbs_up, true) { finish() }.apply { (layoutParams as LinearLayout.LayoutParams).topMargin = ui.dp(20) })
        setContentView(ScrollView(this).apply { setBackgroundColor(ui.bg); fitsSystemWindows = true; addView(col) })
        window.statusBarColor = ui.bg; window.navigationBarColor = ui.bg
        @Suppress("DEPRECATION") if (!ui.dark) window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or (if (android.os.Build.VERSION.SDK_INT >= 26) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0)
    }
}
