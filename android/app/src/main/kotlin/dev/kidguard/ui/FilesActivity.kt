package dev.kidguard

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * Kid screen "File với bố mẹ" (kid flavor only): two lists of the shared folder (Files.kt), newest first,
 * "Đã gửi bố mẹ" (outbox: waiting / fetched) and "Bố mẹ gửi" (Download/KidGuard). A tap opens the file with the system viewer
 * (FilesProvider uri); "Gửi file" runs the system picker (ShareInActivity.pick). Local only, same look as the other kid screens.
 */
class FilesActivity : Activity() {
    private lateinit var k: HomeKit
    private lateinit var pal: Palette
    private lateinit var col: LinearLayout
    private fun dp(v: Int) = k.dp(v)

    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(Lang.wrap(base))
    override fun getResources(): android.content.res.Resources = Lang.res(baseContext) ?: super.getResources()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        val st = HomeFile.load(this)?.style ?: HStyle()
        val sys = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val dark = st.theme == "dark" || (st.theme == "auto" && sys)
        val preset = HomeStyle.preset(st.preset)
        pal = HomeStyle.palette(st.accent.ifEmpty { if (dark) preset.accentDark else preset.accentLight }, dark)
        k = HomeKit(this, pal)
        window.statusBarColor = pal.bg; window.navigationBarColor = pal.bg
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (dark) 0 else (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or if (Build.VERSION.SDK_INT >= 26) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(pal.bg) }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(8), dp(12), 0) }
        head.addView(ImageView(this).apply {
            setImageDrawable(Ic.get(this@FilesActivity, R.drawable.li_arrow_left, pal.text, dp(24))); val p = (dp(44) - dp(24)) / 2; setPadding(p, p, p, p)
            contentDescription = getString(R.string.hm_c_back); k.tap(this, -1f) { finish() }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        head.addView(k.text(getString(R.string.files_title), 22f, pal.text, true).apply { setPadding(dp(8), 0, 0, 0); maxLines = 1; ellipsize = TextUtils.TruncateAt.END },
            LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(head, LinearLayout.LayoutParams(-1, -2))
        root.addView(k.pill(getString(R.string.files_send), R.drawable.li_send, true) { ShareInActivity.pick(this) },
            LinearLayout.LayoutParams(-1, -2).apply { leftMargin = dp(16); rightMargin = dp(16); topMargin = dp(12) })
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(4), dp(16), dp(28)) }
        scroll.addView(col)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    // back from the picker / a viewer: statuses may have changed
    override fun onResume() { super.onResume(); render() }

    private fun render() {
        col.removeAllViews()
        val all = Files.all(this).sortedByDescending { it.at }
        section(R.string.files_out, all.filter { it.dir == FilesLogic.OUT }, R.string.files_empty_out)
        section(R.string.files_in, all.filter { it.dir == FilesLogic.IN }, R.string.files_empty_in)
    }

    private fun section(title: Int, list: List<FilesLogic.Entry>, empty: Int) {
        col.addView(k.text(getString(title).uppercase(), 12f, pal.sub, true).apply { letterSpacing = 0.1f; setPadding(dp(4), dp(20), 0, dp(6)) })
        if (list.isEmpty()) { col.addView(k.text(getString(empty), 14f, pal.sub).apply { setPadding(dp(4), dp(4), 0, dp(4)) }); return }
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = k.sheetCard() }
        list.forEach { card.addView(row(it)) }
        col.addView(card, LinearLayout.LayoutParams(-1, -2))
    }

    private fun status(e: FilesLogic.Entry) = getString(when (e.st) {
        FilesLogic.FETCHED -> R.string.files_st_fetched
        FilesLogic.NEW -> R.string.files_st_new
        FilesLogic.OPENED -> R.string.files_st_opened
        FilesLogic.GONE -> R.string.files_st_gone
        else -> R.string.files_st_delivered
    })

    private fun row(e: FilesLogic.Entry): View {
        val gone = e.st == FilesLogic.GONE
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(10)) }
        r.addView(k.badge(if (e.dir == FilesLogic.OUT) R.drawable.li_file_up else R.drawable.li_file_down, pal.onTint, pal.tint, 40, 22, true))
        val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(8), 0) }
        t.addView(k.text(e.name, 15f, if (gone) pal.sub else pal.text, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE })
        val ago = DateUtils.getRelativeTimeSpanString(e.at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
        t.addView(k.text(Formatter.formatShortFileSize(this, e.size) + " · " + ago, 12.5f, pal.sub).apply { setPadding(0, dp(2), 0, 0) })
        r.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
        r.addView(k.chip(status(e), if (gone) pal.sub else pal.onTint, pal.tint))
        if (!gone) k.tap(r, 16f) { open(e) }
        return r
    }

    private fun open(e: FilesLogic.Entry) {
        val v = Files.viewIntent(this, e.id) ?: return Toasts.show(applicationContext, getString(R.string.files_gone), true)
        try { startActivity(v) } catch (x: ActivityNotFoundException) { Toasts.show(applicationContext, getString(R.string.files_no_app), true) }
        catch (x: Exception) { Toasts.show(applicationContext, getString(R.string.files_gone), true) }
        render()
    }
}
