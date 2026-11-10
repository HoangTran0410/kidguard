package dev.kidguard

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Kid launcher "Thông báo đã xem": the notifications of the last 7 days (NotifLog), newest first, grouped by day.
 * Tap a row = NotifDetailActivity (full content, times, "open again"). Local only, the parent never sees it.
 */
class NotifLogActivity : Activity() {
    private lateinit var k: HomeKit
    private lateinit var pal: Palette
    private lateinit var u: Ui
    private fun dp(v: Int) = k.dp(v)

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
        u = Ui(this, pal)
        window.statusBarColor = pal.bg; window.navigationBarColor = pal.bg
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (dark) 0 else (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or if (Build.VERSION.SDK_INT >= 26) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0)
        show(null)
    }

    override fun onResume() { super.onResume(); NotifLog.read(this) { if (!isDestroyed && !isFinishing) show(it) } }

    private fun show(list: List<NotifEntry>?) {
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; setBackgroundColor(pal.bg) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(28)) }
        scroll.addView(col)
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(ImageView(this).apply {
            setImageDrawable(Ic.get(this@NotifLogActivity, R.drawable.li_arrow_left, pal.text, dp(24))); setPadding(dp(10), dp(10), dp(10), dp(10))
            contentDescription = getString(R.string.hm_c_back); k.tap(this, -1f) { finish() }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        head.addView(k.text(getString(R.string.nlog_title), 24f, pal.text, true).apply { setPadding(dp(8), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
        if (!list.isNullOrEmpty()) head.addView(ImageView(this).apply {
            setImageDrawable(Ic.get(this@NotifLogActivity, R.drawable.li_trash_2, pal.text, dp(22))); setPadding(dp(11), dp(11), dp(11), dp(11))
            contentDescription = getString(R.string.nlog_clear); k.tap(this, -1f) { confirmClear() }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        col.addView(head, LinearLayout.LayoutParams(-1, -2))

        if (list != null && list.isEmpty()) {
            col.addView(k.text(getString(R.string.nlog_empty), 15f, pal.sub).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.2f) },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(80); leftMargin = dp(24); rightMargin = dp(24) })
        }
        if (!list.isNullOrEmpty()) {
            val today0 = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
            val tf = android.text.format.DateFormat.getTimeFormat(this)
            val df = SimpleDateFormat("EEE d/M", Locale(Lang.get(this)))
            var group: LinearLayout? = null
            var last = -1
            for (e in list) {
                val d = NotifLogic.daysAgo(e.time, today0)
                if (d != last) {
                    last = d
                    val name = when (d) { 0 -> getString(R.string.nlog_today); 1 -> getString(R.string.nlog_yesterday); else -> df.format(Date(e.time)) }
                    col.addView(k.text(name, 13f, pal.sub, true).apply { setPadding(dp(4), 0, 0, 0) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18); bottomMargin = dp(8) })
                    group = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = k.sheetCard(20f) }
                    col.addView(group, LinearLayout.LayoutParams(-1, -2))
                } else group!!.addView(View(this).apply { setBackgroundColor(pal.stroke) }, LinearLayout.LayoutParams(-1, dp(1)).apply { leftMargin = dp(72) })
                group!!.addView(row(e, tf.format(Date(e.time))), LinearLayout.LayoutParams(-1, -2))
            }
        }
        setContentView(scroll)
    }

    private fun row(e: NotifEntry, time: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; setPadding(dp(14), dp(12), dp(14), dp(12))
        val icon = ImageView(this@NotifLogActivity)
        addView(icon, LinearLayout.LayoutParams(dp(40), dp(40)).apply { rightMargin = dp(14) })
        val px = dp(40)
        IconCache.peek(e.pkg, "", px, true)?.let { icon.setImageBitmap(it) }
            ?: IconCache.async(this@NotifLogActivity, e.pkg, "", px, true) { b -> if (b != null) icon.setImageBitmap(b) }
        val body = LinearLayout(this@NotifLogActivity).apply { orientation = LinearLayout.VERTICAL }
        val top = LinearLayout(this@NotifLogActivity).apply { orientation = LinearLayout.HORIZONTAL }
        top.addView(k.text(e.app.ifEmpty { e.pkg }, 12f, pal.sub).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(k.text(time, 12f, pal.sub).apply { setPadding(dp(8), 0, 0, 0) })
        body.addView(top)
        if (e.title.isNotEmpty()) body.addView(k.text(e.title, 15f, pal.text, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
        if (e.text.isNotEmpty()) body.addView(k.text(e.text, 14f, pal.sub).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
        addView(body, LinearLayout.LayoutParams(0, -2, 1f))
        k.tap(this, 20f) { startActivity(Intent(this@NotifLogActivity, NotifDetailActivity::class.java).putExtra(NotifDetailActivity.KEY, e.key)) }
    }

    private fun confirmClear() {
        Modal(this, u).show(R.drawable.li_trash_2, Modal.Tone.DANGER, getString(R.string.nlog_clear), getString(R.string.nlog_clear_body),
            ok = getString(R.string.nlog_clear_ok), cancel = getString(R.string.cancel)) { NotifLog.clear(this); show(emptyList()); true }
    }
}
