package dev.kidguard

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Outline
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One remembered notification (NotifLog): a card drawn like the real one, then every timestamp / id we kept.
 * "Open again" sends the held contentIntent (memory only, gone after a process death) or falls back to the app.
 * Local only, the parent never sees it.
 */
class NotifDetailActivity : Activity() {
    companion object { const val KEY = "key" }

    private lateinit var k: HomeKit
    private lateinit var pal: Palette
    private lateinit var u: Ui
    private fun dp(v: Int) = k.dp(v)
    private val full = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

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
        val key = intent.getStringExtra(KEY).orEmpty()
        NotifLog.read(this) { l -> if (!isDestroyed && !isFinishing) show(l.firstOrNull { it.key == key } ?: NotifEntry("", 0, "", "", "", "")) }
    }

    private fun scaffold(): Pair<ScrollView, LinearLayout> {
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; setBackgroundColor(pal.bg) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(28)) }
        scroll.addView(col)
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(ImageView(this).apply {
            setImageDrawable(Ic.get(this@NotifDetailActivity, R.drawable.li_arrow_left, pal.text, dp(24))); setPadding(dp(10), dp(10), dp(10), dp(10))
            contentDescription = getString(R.string.hm_c_back); k.tap(this, -1f) { finish() }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        head.addView(k.text(getString(R.string.nlog_detail_title), 24f, pal.text, true).apply { setPadding(dp(8), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
        col.addView(head, LinearLayout.LayoutParams(-1, -2))
        return scroll to col
    }

    /** [e] null = still loading (header only); blank key = the entry is gone. */
    private fun show(e: NotifEntry?) {
        val (scroll, col) = scaffold()
        setContentView(scroll)
        if (e == null) return
        if (e.key.isEmpty()) {
            col.addView(k.text(getString(R.string.nlog_missing), 15f, pal.sub).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(80) })
            return
        }
        col.addView(notifCard(e), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        col.addView(u.button(getString(R.string.nlog_reopen), R.drawable.li_send, true) { reopen(e) })
        val body = copyText(e)
        if (body.isNotEmpty()) col.addView(u.button(getString(R.string.nlog_copy), R.drawable.li_file, false) {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("notification", body))
            Toast.makeText(this, R.string.nlog_copied, Toast.LENGTH_SHORT).show()
        })

        col.addView(k.text(getString(R.string.nlog_details), 13f, pal.sub, true).apply { setPadding(dp(4), 0, 0, 0) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(22); bottomMargin = dp(8) })
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = k.sheetCard(24f); setPadding(dp(16), dp(4), dp(16), dp(4)) }
        var first = true
        fun row(label: Int, value: String) {
            if (value.isEmpty()) return
            if (!first) card.addView(View(this).apply { setBackgroundColor(pal.stroke) }, LinearLayout.LayoutParams(-1, dp(1)))
            first = false
            val r = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(11), 0, dp(11)) }
            r.addView(k.text(getString(label), 12f, pal.sub))
            r.addView(k.text(value, 14f, pal.text).apply { setTextIsSelectable(true); setLineSpacing(0f, 1.15f) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
            card.addView(r)
        }
        fun at(ms: Long) = if (ms > 0) full.format(Date(ms)) else ""
        row(R.string.nlog_d_posted, at(e.postTime.takeIf { it > 0 } ?: e.time))
        row(R.string.nlog_d_when, at(e.whenMs))
        if (e.removedAt > 0) {
            row(if (e.reason == NotifLogic.R_CLICK) R.string.nlog_d_clicked else R.string.nlog_d_removed, at(e.removedAt))
            row(R.string.nlog_d_reason, getString(reasonRes(e.reason)))
        }
        row(R.string.nlog_d_channel, e.channel)
        row(R.string.nlog_d_category, e.category)
        row(R.string.nlog_d_group, e.group)
        row(R.string.nlog_d_progress, e.progress)
        row(R.string.nlog_d_priority, if (e.priority != 0 || e.importance != 0) "${e.priority} / ${e.importance}" else "")
        row(R.string.nlog_d_package, e.pkg)
        row(R.string.nlog_d_key, e.key)
        col.addView(card, LinearLayout.LayoutParams(-1, -2))
    }

    private fun reasonRes(r: String) = when (r) {
        NotifLogic.R_CLICK -> R.string.nlog_r_click; NotifLogic.R_SWIPE -> R.string.nlog_r_swipe; NotifLogic.R_CLEAR -> R.string.nlog_r_clear
        NotifLogic.R_APP -> R.string.nlog_r_app; NotifLogic.R_TIMEOUT -> R.string.nlog_r_timeout; else -> R.string.nlog_r_other
    }

    /** Everything readable in one string (what "Copy text" puts on the clipboard). */
    private fun copyText(e: NotifEntry): String = buildString {
        if (e.title.isNotEmpty()) appendLine(e.title)
        val main = e.bigText.ifEmpty { e.text }
        if (main.isNotEmpty()) appendLine(main)
        e.lines.forEach { appendLine(it) }
        e.msgs.forEach { appendLine(if (it.sender.isEmpty()) it.text else "${it.sender}: ${it.text}") }
    }.trim()

    // ---- the card that looks like the real notification ----

    private fun notifCard(e: NotifEntry): View {
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = k.sheetCard(26f); setPadding(dp(16), dp(14), dp(16), dp(14)) }
        // header: small app icon, app name · sub text · relative time
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val small = ImageView(this)
        head.addView(small, LinearLayout.LayoutParams(dp(20), dp(20)).apply { rightMargin = dp(8) })
        val px = dp(20)
        IconCache.peek(e.pkg, "", px, true)?.let { small.setImageBitmap(it) }
            ?: IconCache.async(this, e.pkg, "", px, true) { b -> if (b != null) small.setImageBitmap(b) }
        val t0 = e.whenMs.takeIf { it > 0 } ?: e.postTime.takeIf { it > 0 } ?: e.time
        val rel = DateUtils.getRelativeTimeSpanString(t0, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()
        val header = listOf(e.app.ifEmpty { e.pkg }, e.subText, rel).filter { it.isNotEmpty() }.joinToString(" · ")
        head.addView(k.text(header, 12f, pal.sub).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(head)

        // title / text on the left, large icon on the right
        val mid = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val txt = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (e.title.isNotEmpty()) txt.addView(k.text(e.title, 16f, pal.text, true).apply { setLineSpacing(0f, 1.1f) })
        val main = e.bigText.ifEmpty { e.text }
        if (main.isNotEmpty()) txt.addView(k.text(main, 14f, pal.text).apply { setLineSpacing(0f, 1.15f) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
        e.lines.forEach { txt.addView(k.text(it, 14f, pal.sub).apply { setLineSpacing(0f, 1.15f) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) }) }
        e.msgs.forEach { m ->
            val line = if (m.sender.isEmpty()) m.text else "${m.sender}: ${m.text}"
            val sp = android.text.SpannableString(line)
            if (m.sender.isNotEmpty()) sp.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, m.sender.length + 1, 0)
            txt.addView(k.text(sp, 14f, pal.text).apply { setLineSpacing(0f, 1.15f) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
        }
        if (e.summary.isNotEmpty()) txt.addView(k.text(e.summary, 12f, pal.sub), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        mid.addView(txt, LinearLayout.LayoutParams(0, -2, 1f).apply { topMargin = dp(8) })
        val large = if (e.largeIcon.isNotEmpty()) ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; round(this, 12) } else null
        if (large != null) mid.addView(large, LinearLayout.LayoutParams(dp(56), dp(56)).apply { leftMargin = dp(12); topMargin = dp(8) })
        card.addView(mid)

        // big picture below
        val pic = if (e.picture.isNotEmpty()) ImageView(this).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER; round(this, 14) } else null
        if (pic != null) card.addView(pic, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        // action labels as text buttons (they do nothing here: the original actions are gone)
        if (e.actions.isNotEmpty()) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            e.actions.forEach { a -> row.addView(k.text(a, 14f, u.accent, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(6), dp(18), dp(6)) }) }
            card.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }

        // small files, decoded off the main thread
        if (large != null || pic != null) {
            val app = applicationContext
            Thread {
                val lb = if (large != null) decode(NotifLog.imageFile(app, e.largeIcon)) else null
                val pb = if (pic != null) decode(NotifLog.imageFile(app, e.picture)) else null
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    if (large != null) { if (lb != null) large.setImageBitmap(lb) else large.visibility = View.GONE }
                    if (pic != null) { if (pb != null) pic.setImageBitmap(pb) else pic.visibility = View.GONE }
                }
            }.start()
        }
        return card
    }

    private fun decode(f: java.io.File): Bitmap? = runCatching { BitmapFactory.decodeFile(f.path) }.getOrNull()

    private fun round(v: View, radiusDp: Int) {
        val r = dp(radiusDp).toFloat()
        v.clipToOutline = true
        v.outlineProvider = object : ViewOutlineProvider() { override fun getOutline(view: View, o: Outline) { o.setRoundRect(0, 0, view.width, view.height, r) } }
    }

    // ---- open again ----

    private fun reopen(e: NotifEntry) {
        val pi = NotifLog.pendingFor(e.key)
        if (pi != null && NotifLog.send(this, pi)) { Toast.makeText(this, R.string.nlog_opened_pi, Toast.LENGTH_SHORT).show(); return }
        val i = packageManager.getLaunchIntentForPackage(e.pkg)
        if (i != null && runCatching { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) Toast.makeText(this, R.string.nlog_opened_app, Toast.LENGTH_SHORT).show()
        else Toast.makeText(this, R.string.nlog_gone, Toast.LENGTH_SHORT).show()
    }
}
