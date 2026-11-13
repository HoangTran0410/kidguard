package dev.kidguard

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * "Thống kê thông báo" (from the chart button of NotifLogActivity), built like Digital Wellbeing / iOS Screen Time
 * notification stats: range 7 / 30 / 90 days, summary tiles (per day, change vs the period before, busiest hour, share
 * opened), a bar per day, a weekday x hour heat map and the apps by count, the rarely opened noisy ones marked. An app
 * row opens the same screen for that app ([PKG]): its channels (hạng mục) with counts and the last seen importance, and
 * the system notification settings of the app or of one channel (Android owns those switches; Settings locked by the
 * parents = buttons off). Local only, like the history.
 */
class NotifStatsActivity : Activity() {
    companion object { const val PKG = "pkg" }

    private lateinit var k: HomeKit
    private lateinit var pal: Palette
    private lateinit var u: Ui
    private fun dp(v: Int) = k.dp(v)
    private val loc: Locale by lazy { resources.configuration.locales[0] ?: Locale.getDefault() }

    private var pkg: String? = null
    private var range = 7
    private var gen = 0
    private var allApps = false
    private lateinit var col: LinearLayout
    private lateinit var title: TextView
    private lateinit var headIcon: ImageView

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
        pkg = intent.getStringExtra(PKG)?.ifEmpty { null }
        range = b?.getInt("range") ?: 0
        if (range !in NotifStats.ranges(NotifLog.keepDays(this))) range = NotifStats.ranges(NotifLog.keepDays(this)).first()
        setContentView(frame())
    }

    override fun onSaveInstanceState(o: Bundle) { super.onSaveInstanceState(o); o.putInt("range", range) }

    // back from the system settings page: the counts did not change, but the channel "last seen" may next time
    override fun onResume() { super.onResume(); reload() }

    private fun frame(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(pal.bg) }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(8), dp(12), 0) }
        head.addView(iconBtn(R.drawable.li_arrow_left, getString(R.string.hm_c_back), 24) { finish() })
        headIcon = ImageView(this).apply { visibility = View.GONE }
        head.addView(headIcon, LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginStart = dp(8) })
        title = k.text(getString(R.string.nst_title), 22f, pal.text, true).apply { setPadding(dp(8), 0, 0, 0); maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        head.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(head, LinearLayout.LayoutParams(-1, -2))
        pkg?.let { p -> headIcon.visibility = View.VISIBLE; icon(p, dp(32)) { headIcon.setImageBitmap(it) } }
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(4), dp(16), dp(28)) }
        scroll.addView(col)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }

    private fun iconBtn(icon: Int, desc: String, size: Int, go: () -> Unit) = ImageView(this).apply {
        setImageDrawable(Ic.get(this@NotifStatsActivity, icon, pal.text, dp(size))); val p = (dp(44) - dp(size)) / 2; setPadding(p, p, p, p)
        contentDescription = desc; k.tap(this, -1f) { go() }
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
    }

    private fun icon(p: String, px: Int, set: (android.graphics.Bitmap) -> Unit) {
        IconCache.peek(p, "", px, "round")?.let(set) ?: IconCache.async(this, p, "", px, "round") { b -> if (b != null) set(b) }
    }

    // ---- data ----

    private fun reload() {
        val g = ++gen
        val today = Calendar.getInstance()
        val days = NotifStats.days(today, range)
        val before = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, -range) }
        val prev = NotifStats.days(before, range)
        NotifLog.stats(this, days.first(), days.last(), prev.first(), prev.last(), pkg) { r -> if (g == gen && !isFinishing) render(r, days) }
    }

    private fun render(r: NotifStats.Raw, days: List<Int>) {
        pkg?.let { title.text = r.name.ifEmpty { IconCache.label(this, it) } }
        col.removeAllViews()
        col.addView(rangeChips(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        val s = NotifStats.summary(r, days)
        col.addView(tiles(s), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        if (pkg != null) { actions(); pkg?.takeIf { !own(it) }?.let { holdCard(it) } }
        if (s.total == 0) {
            col.addView(k.text(getString(R.string.nst_empty, range), 15f, pal.sub).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.2f) },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(32); leftMargin = dp(24); rightMargin = dp(24) })
            // no notifications is no reason to hide the way to the time screen
            col.addView(k.pill(getString(R.string.ust_title), R.drawable.li_timer, false) { UsageStatsActivity.open(this, pkg) },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })
            return
        }
        if (pkg != null && r.chans.isNotEmpty()) channels(r.chans, s.total)
        section(getString(R.string.nst_daily))
        col.addView(card(bars(NotifStats.daily(r.cells, days), days)), LinearLayout.LayoutParams(-1, -2))
        section(getString(R.string.nst_heat))
        col.addView(card(heat(NotifStats.heat(r.cells))), LinearLayout.LayoutParams(-1, -2))
        if (pkg == null) apps(r.apps, NotifStats.covered(days, r.cells))
        col.addView(k.pill(getString(R.string.ust_title), R.drawable.li_timer, false) { UsageStatsActivity.open(this, pkg) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })
    }

    private fun rangeChips(): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (n in NotifStats.ranges(NotifLog.keepDays(this))) {
            val on = n == range
            row.addView(k.chip(getString(R.string.nst_days, n), if (on) pal.onSolid else pal.text, if (on) pal.solid else pal.sheet, null).apply {
                textSize = 14f; setPadding(dp(14), dp(9), dp(14), dp(9))
                if (!on) background = GradientDrawable().apply { setColor(pal.sheet); cornerRadius = k.dpf(999f); setStroke(dp(1), pal.stroke) }
                k.tap(this, 999f) { if (range != n) { range = n; reload() } }
            }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        }
        return row
    }

    private fun section(t: String) {
        col.addView(k.text(t, 13f, pal.sub, true).apply { setPadding(dp(4), 0, 0, 0) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18); bottomMargin = dp(8) })
    }

    private fun card(v: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; background = k.sheetCard(20f); setPadding(dp(14), dp(12), dp(14), dp(12))
        addView(v, LinearLayout.LayoutParams(-1, -2))
    }

    private fun hours(h: Int) = getString(R.string.nst_hour_range, h, (h + 1) % 24)
    private fun wdName(i: Int) = getString(R.string.nst_wd_long).split(',')[i].trim()

    // ---- summary: 2 x 2 tiles ----

    private fun tiles(s: NotifStats.Summary): View {
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun tile(label: String, value: String, note: String?, noteColor: Int = pal.sub) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = k.sheetCard(18f); setPadding(dp(14), dp(12), dp(14), dp(12))
            addView(k.text(label, 12f, pal.sub, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            addView(k.text(value, 22f, pal.text, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
            addView(k.text(note ?: " ", 12f, noteColor).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
        }
        fun row(a: View, b: View) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(a, LinearLayout.LayoutParams(0, -1, 1f).apply { marginEnd = dp(5) }); addView(b, LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = dp(5) })
        }
        val per = if (s.perDay >= 10 || s.perDay == 0f) Math.round(s.perDay).toString() else String.format(loc, "%.1f", s.perDay)
        val ch = s.change
        val chText = when { ch == null -> "–"; ch > 0 -> "↑ $ch%"; ch < 0 -> "↓ ${-ch}%"; else -> "0%" }
        grid.addView(row(
            tile(getString(R.string.nst_total), String.format(loc, "%,d", s.total), getString(R.string.nst_per_day, per)),
            tile(getString(R.string.nst_change), chText, getString(R.string.nst_change_vs, range), if (ch != null && ch >= 20) pal.warnText else pal.sub),
        ), LinearLayout.LayoutParams(-1, -2))
        grid.addView(row(
            tile(getString(R.string.nst_peak), if (s.peakHour < 0) "–" else hours(s.peakHour), if (s.peakDay < 0) null else getString(R.string.nst_peak_day, wdName(s.peakDay))),
            tile(getString(R.string.nst_opened), if (s.openRate < 0) "–" else "${s.openRate}%", getString(R.string.nst_opened_note)),
        ), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        return grid
    }

    // ---- one app: settings buttons + channels ----

    private fun own(p: String) = p == packageName || p.startsWith("dev.kidguard")

    /** Settings open only if the parents allow it (else Acc would bounce the page right away). */
    private fun settingsOk(): Boolean {
        if (Flavor.HOME) return true
        val managed = Policy.isOwner(this) || Store.of(this).everPaired
        return LauncherLogic.settingsOk(Store.of(this).rules, owner = managed, paired = false)
    }

    private fun actions() {
        val p = pkg ?: return
        val ok = settingsOk() && !own(p)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(k.pill(getString(R.string.nst_settings), R.drawable.li_settings, true) { if (ok) openSettings(p, null) }.apply { maxLines = 1; if (!ok) alpha = 0.4f },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(5) })
        // "History": "Xem thông báo" wrapped to 2 lines on a 393 dp phone (Poco) beside the icon
        row.addView(k.pill(getString(R.string.nst_see), R.drawable.li_list, false) {
            startActivity(Intent(this, NotifLogActivity::class.java).putExtra(NotifLogActivity.PKG, p))
        }.apply { maxLines = 1 }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(5) })
        col.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        val note = when { own(p) -> null; !ok -> getString(R.string.nst_settings_locked); else -> getString(R.string.nst_settings_note) }
        if (note != null) col.addView(k.text(note, 12f, pal.sub).apply { setPadding(dp(4), 0, dp(4), 0); setLineSpacing(0f, 1.15f) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    }

    /**
     * "Giữ thông báo": normal / bundle at NotifHold.SLOTS / mute 1 h / mute until 7:00, as radio rows (one choice, its
     * effect in the row's second line), then what is waiting and when it shows. Calls / alarms are never held.
     */
    private fun holdCard(p: String) {
        section(getString(R.string.nst_hold))
        val now = System.currentTimeMillis()
        val cur = NotifHold.get(this, p)
        val can = NotifDots.granted(this)
        val hm = SimpleDateFormat("HH:mm", loc)
        fun whenText(t: Long): String {
            val same = Calendar.getInstance().apply { timeInMillis = t }.get(Calendar.DAY_OF_YEAR) == Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
            return if (same) hm.format(t) else getString(R.string.nst_hold_tomorrow, hm.format(t))
        }
        val hour = now + 3600_000L
        val morning = NotifHold.nextAt(now, NotifHold.MORNING)
        val mute = cur?.mode == NotifHold.MUTE
        val muteHour = mute && cur!!.until < morning - 60_000L
        class Opt(val title: String, val sub: String, val on: Boolean, val set: NotifHold.Hold?)
        val opts = listOf(
            Opt(getString(R.string.nst_hold_off), getString(R.string.nst_hold_off_sub), cur == null, null),
            Opt(getString(R.string.nst_hold_bundle), getString(R.string.nst_hold_bundle_sub), cur?.mode == NotifHold.BUNDLE, NotifHold.Hold(NotifHold.BUNDLE)),
            Opt(getString(R.string.nst_hold_hour), getString(R.string.nst_hold_until, whenText(if (muteHour) cur!!.until else hour)), muteHour, NotifHold.Hold(NotifHold.MUTE, hour)),
            Opt(getString(R.string.nst_hold_morning), getString(R.string.nst_hold_until, whenText(if (mute && !muteHour) cur!!.until else morning)),
                mute && !muteHour, NotifHold.Hold(NotifHold.MUTE, morning)),
        )
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = k.sheetCard(20f) }
        opts.forEachIndexed { i, o ->
            if (i > 0) box.addView(View(this).apply { setBackgroundColor(pal.stroke) }, LinearLayout.LayoutParams(-1, dp(1)).apply { leftMargin = dp(52) })
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(11), dp(14), dp(11)) }
            // a radio mark: thick accent ring when chosen
            row.addView(View(this).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke(dp(if (o.on) 6 else 2), if (o.on) pal.solid else pal.sub) }
            }, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(16) })
            val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            body.addView(k.text(o.title, 15f, pal.text, o.on))
            body.addView(k.text(o.sub, 12f, pal.sub).apply { setPadding(0, dp(2), 0, 0) })
            row.addView(body, LinearLayout.LayoutParams(0, -2, 1f))
            row.contentDescription = o.title + ", " + o.sub
            if (!can) row.alpha = 0.4f
            else k.tap(row, 20f) { if (!o.on) { NotifHold.set(this, p, o.set); reload() } }
            box.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        col.addView(box, LinearLayout.LayoutParams(-1, -2))
        val wait = NotifHold.waiting(this, p, now)
        val note = buildList {
            if (!can) add(getString(R.string.nst_hold_need))
            if (wait.isNotEmpty()) add(resources.getQuantityString(R.plurals.nst_hold_waiting, wait.size, wait.size, whenText(wait.first())))
            add(getString(R.string.nst_hold_note))
        }.joinToString("\n")
        col.addView(k.text(note, 12f, pal.sub).apply { setPadding(dp(4), 0, dp(4), 0); setLineSpacing(0f, 1.15f) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    }

    /** The system page: one channel if [channel] is set (the OEM may not have it: the app page then). */
    private fun openSettings(p: String, channel: String?) {
        val app = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, p)
        val ch = channel?.takeIf { it.isNotEmpty() }?.let { Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, p).putExtra(Settings.EXTRA_CHANNEL_ID, it) }
        for (i in listOfNotNull(ch, app, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", p, null)))) {
            if (runCatching { startActivity(i) }.isSuccess) return
        }
    }

    private fun importance(i: Int): String? = when (i) {
        1 -> getString(R.string.nst_imp_min); 2 -> getString(R.string.nst_imp_low); 3 -> getString(R.string.nst_imp_default)
        4, 5 -> getString(R.string.nst_imp_high); else -> null
    }

    private fun channels(list: List<NotifStats.ChanRaw>, total: Int) {
        val p = pkg ?: return
        val ok = settingsOk() && !own(p)
        section(getString(R.string.nst_channels))
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = k.sheetCard(20f) }
        list.forEachIndexed { i, c ->
            if (i > 0) box.addView(View(this).apply { setBackgroundColor(pal.stroke) }, LinearLayout.LayoutParams(-1, dp(1)).apply { leftMargin = dp(14) })
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(12), dp(10), dp(12)) }
            val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            body.addView(k.text(c.name.ifEmpty { c.id.ifEmpty { getString(R.string.nst_no_channel) } }, 15f, pal.text, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
            val rate = NotifStats.openRate(c.clicked, c.n)
            val bits = listOfNotNull(resources.getQuantityString(R.plurals.nlog_total, c.n, c.n), if (rate >= 0) getString(R.string.nst_opened_n, rate) else null, importance(c.importance))
            body.addView(k.text(bits.joinToString(" · "), 13f, pal.sub).apply { maxLines = 2 }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
            body.addView(share(c.n, total), LinearLayout.LayoutParams(-1, dp(4)).apply { topMargin = dp(6) })
            row.addView(body, LinearLayout.LayoutParams(0, -2, 1f))
            if (ok) {
                row.addView(ImageView(this).apply { setImageDrawable(Ic.get(this@NotifStatsActivity, R.drawable.li_chevron_right, pal.sub, dp(20))) }, LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginStart = dp(8) })
                k.tap(row, 20f) { openSettings(p, c.id) }
            }
            box.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        col.addView(box, LinearLayout.LayoutParams(-1, -2))
        col.addView(k.text(getString(R.string.nst_channels_note), 12f, pal.sub).apply { setPadding(dp(4), 0, dp(4), 0); setLineSpacing(0f, 1.15f) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    }

    /** A thin bar: [n] of [max]. */
    private fun share(n: Int, max: Int) = object : View(this) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            val r = height / 2f
            p.color = pal.stroke; c.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, p)
            if (max > 0 && n > 0) { p.color = pal.solid; c.drawRoundRect(0f, 0f, (width * n / max.toFloat()).coerceAtLeast(height.toFloat()), height.toFloat(), r, r, p) }
        }
    }

    // ---- apps ----

    private fun apps(list: List<NotifStats.AppRaw>, days: Int) {
        if (list.isEmpty()) return
        section(getString(R.string.nst_apps))
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = k.sheetCard(20f) }
        val max = list.first().n
        val shown = if (allApps) list else list.take(15)
        val holds = NotifHold.all(this)
        shown.forEachIndexed { i, a ->
            if (i > 0) box.addView(View(this).apply { setBackgroundColor(pal.stroke) }, LinearLayout.LayoutParams(-1, dp(1)).apply { leftMargin = dp(64) })
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(11), dp(14), dp(11)) }
            val ic = ImageView(this)
            row.addView(ic, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(14) })
            icon(a.pkg, dp(36)) { ic.setImageBitmap(it) }
            val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            top.addView(k.text(a.app.ifEmpty { a.pkg }, 15f, pal.text, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f))
            top.addView(k.text(String.format(loc, "%,d", a.n), 15f, pal.text, true).apply { setPadding(dp(8), 0, 0, 0) })
            body.addView(top)
            body.addView(share(a.n, max), LinearLayout.LayoutParams(-1, dp(4)).apply { topMargin = dp(6) })
            val rate = NotifStats.openRate(a.clicked, a.n)
            val per = NotifStats.perDay(a.n, days)
            val bits = listOfNotNull(
                getString(R.string.nst_per_day, if (per >= 10 || per < 0.05f) Math.round(per).toString() else String.format(loc, "%.1f", per)),
                if (rate >= 0) getString(R.string.nst_opened_n, rate) else null,
                NotifStats.peak(a.hours).takeIf { it >= 0 }?.let { getString(R.string.nst_mostly, "${it}h") },   // "17h": "17–18 giờ" cut the line
            )
            // wraps instead of an ellipsis (owner: the peak hour was cut off)
            body.addView(k.text(bits.joinToString(" · "), 12f, pal.sub).apply { setLineSpacing(0f, 1.15f) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
            val h = holds[a.pkg]
            // held: says so (in the accent); else the noisy mark
            if (h != null) body.addView(k.text(getString(if (h.mode == NotifHold.BUNDLE) R.string.nst_held_bundle else R.string.nst_held_mute), 12f, pal.solid, true),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
            else if (NotifStats.noisy(a.n, a.clicked, days)) body.addView(k.text(getString(R.string.nst_noisy), 12f, pal.warnText, true), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
            row.addView(body, LinearLayout.LayoutParams(0, -2, 1f))
            k.tap(row, 20f) { startActivity(Intent(this, NotifStatsActivity::class.java).putExtra(PKG, a.pkg)) }
            box.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        col.addView(box, LinearLayout.LayoutParams(-1, -2))
        if (!allApps && list.size > shown.size) col.addView(k.pill(getString(R.string.nst_all_apps, list.size), null, false) { allApps = true; reload() },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
    }

    // ---- charts ----

    private var downX = 0f; private var downY = 0f; private var scrubbing = false
    /**
     * Scrub vs page scroll: a finger that moves sideways first takes the chart (then it may roam in 2D, the page holds
     * still); one that moves up / down first is the ScrollView's (it intercepts past its own slop and sends CANCEL).
     */
    private fun claim(v: View, e: MotionEvent) {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) { downX = e.x; downY = e.y; scrubbing = false; return }
        val dx = Math.abs(e.x - downX); val dy = Math.abs(e.y - downY)
        if (!scrubbing && dx > android.view.ViewConfiguration.get(this).scaledTouchSlop / 2f && dx > dy) {
            scrubbing = true; v.parent.requestDisallowInterceptTouchEvent(true)
        }
    }


    /** A bar per day, today solid; touch a bar = its date and count in the caption. */
    private fun bars(v: IntArray, days: List<Int>): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val avg = v.sum().toFloat() / v.size
        val idle = getString(R.string.nst_bars_max, v.maxOrNull() ?: 0)
        val cap = k.text(idle, 13f, pal.sub)
        box.addView(cap)
        val df = SimpleDateFormat("EEE d/M", loc)
        fun dateOf(d: Int) = Calendar.getInstance().apply { clear(); set(d / 10000, d / 100 % 100 - 1, d % 100) }.time
        val chart = object : View(this) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG); val r = RectF(); var sel = -1; var was = -1; var wasCap: CharSequence = ""
            override fun onDraw(c: Canvas) {
                val max = (v.maxOrNull() ?: 0).coerceAtLeast(1)
                val slot = width / v.size.toFloat(); val gap = (slot * 0.25f).coerceAtMost(dp(6).toFloat())
                val h = height.toFloat()
                for (i in v.indices) {
                    val bh = if (v[i] == 0) 0f else (h * v[i] / max).coerceAtLeast(dp(3).toFloat())
                    r.set(i * slot + gap / 2, h - bh, (i + 1) * slot - gap / 2, h)
                    p.color = if (i == sel || (sel < 0 && i == v.lastIndex)) pal.solid else (0x66 shl 24) or (pal.solid and 0xFFFFFF)
                    val rad = (r.width() / 2).coerceAtMost(dp(4).toFloat())
                    c.drawRoundRect(r, rad, rad, p)
                }
                // the average as a dashed line
                if (avg > 0) {
                    p.color = pal.sub; p.strokeWidth = dp(1).toFloat(); p.pathEffect = android.graphics.DashPathEffect(floatArrayOf(dp(4).toFloat(), dp(4).toFloat()), 0f)
                    val y = h - h * avg / max; c.drawLine(0f, y, width.toFloat(), y, p); p.pathEffect = null
                }
            }
            override fun onTouchEvent(e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                        if (e.actionMasked == MotionEvent.ACTION_DOWN) { was = sel; wasCap = cap.text }
                        claim(this, e)
                        val i = (e.x / (width / v.size.toFloat())).toInt().coerceIn(0, v.lastIndex)
                        if (i != sel) { sel = i; invalidate(); cap.text = df.format(dateOf(days[i])).replaceFirstChar { it.titlecase(loc) } + ": " + resources.getQuantityString(R.plurals.nlog_total, v[i], v[i]) }
                    }
                    // the last bar stays picked with its numbers; a page scroll that began here puts the old pick back
                    MotionEvent.ACTION_UP -> scrubbing = false
                    MotionEvent.ACTION_CANCEL -> { scrubbing = false; sel = was; invalidate(); cap.text = wasCap }
                }
                return true
            }
        }
        box.addView(chart, LinearLayout.LayoutParams(-1, dp(120)).apply { topMargin = dp(10) })
        // labels: weekdays for 7 days, else the first / middle / last date
        val lab = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val sd = SimpleDateFormat("d/M", loc)
        if (v.size <= 7) for (d in days) lab.addView(k.text(getString(R.string.nlog_wd).split(',')[NotifStats.weekday(d)].trim(), 11f, pal.sub).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, -2, 1f))
        else listOf(0, days.size / 2, days.lastIndex).forEachIndexed { i, at ->
            lab.addView(k.text(sd.format(dateOf(days[at])), 11f, pal.sub).apply { gravity = when (i) { 0 -> Gravity.START; 1 -> Gravity.CENTER; else -> Gravity.END } }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        box.addView(lab, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        box.addView(k.text(getString(R.string.nst_bars_avg), 11f, pal.sub), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        return box
    }

    /** Weekday x hour: Monday on top, hours 0..23 across; touch a cell = its count in the caption. */
    private fun heat(m: Array<IntArray>): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val idle = getString(R.string.nst_heat_hint)
        val cap = k.text(idle, 13f, pal.sub)
        box.addView(cap)
        val wd = getString(R.string.nlog_wd).split(',').map { it.trim() }
        val max = m.maxOf { it.maxOrNull() ?: 0 }
        val alphas = intArrayOf(0, 0x38, 0x70, 0xA8, 0xFF)
        val chart = object : View(this) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = k.dpf(10f) * resources.configuration.fontScale.coerceAtMost(1.3f) }
            val r = RectF(); var sr = -1; var sc = -1; var was = -1; var wasCap: CharSequence = ""
            val labW get() = dp(26).toFloat()
            val labH get() = dp(16).toFloat()
            fun cw() = (width - labW) / 24f
            fun ch() = (height - labH) / 7f
            // square cells: the height follows the width (24 columns), whatever the phone width
            override fun onMeasure(w: Int, h: Int) {
                val wd = MeasureSpec.getSize(w)
                setMeasuredDimension(wd, (labH + 7 * (wd - labW) / 24f).toInt())
            }
            override fun onDraw(c: Canvas) {
                val cw = cw(); val chh = ch(); val g = dp(2).toFloat()
                p.textAlign = Paint.Align.LEFT; p.color = pal.sub
                for (row in 0 until 7) {
                    p.color = pal.sub
                    c.drawText(wd[row], 0f, row * chh + chh / 2 + p.textSize / 3, p)
                    for (h in 0 until 24) {
                        val n = m[row][h]; val lv = NotifStats.level(n, max)
                        r.set(labW + h * cw + g / 2, row * chh + g / 2, labW + (h + 1) * cw - g / 2, (row + 1) * chh - g / 2)
                        p.color = if (lv == 0) pal.stroke else (alphas[lv] shl 24) or (pal.solid and 0xFFFFFF)
                        c.drawRoundRect(r, g, g, p)
                        if (row == sr && h == sc) { p.style = Paint.Style.STROKE; p.strokeWidth = g; p.color = pal.text; c.drawRoundRect(r, g, g, p); p.style = Paint.Style.FILL }
                    }
                }
                p.color = pal.sub; p.textAlign = Paint.Align.CENTER
                for (h in listOf(0, 6, 12, 18)) c.drawText("${h}h", labW + h * cw + cw / 2, height - dp(2).toFloat(), p)
            }
            override fun onTouchEvent(e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                        if (e.actionMasked == MotionEvent.ACTION_DOWN) { was = sr * 24 + sc; wasCap = cap.text }
                        claim(this, e)
                        val h = ((e.x - labW) / cw()).toInt().coerceIn(0, 23); val row = (e.y / ch()).toInt().coerceIn(0, 6)
                        if (h != sc || row != sr) { sc = h; sr = row; invalidate(); cap.text = wdName(row) + ", " + hours(h) + ": " + resources.getQuantityString(R.plurals.nlog_total, m[row][h], m[row][h]) }
                    }
                    // the last cell stays picked with its numbers; a page scroll that began here puts the old pick back
                    MotionEvent.ACTION_UP -> scrubbing = false
                    MotionEvent.ACTION_CANCEL -> { scrubbing = false; sr = if (was < 0) -1 else was / 24; sc = if (was < 0) -1 else was % 24; invalidate(); cap.text = wasCap }
                }
                return true
            }
        }
        box.addView(chart, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        // legend: less .. more
        val leg = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL or Gravity.END }
        leg.addView(k.text(getString(R.string.nst_less), 11f, pal.sub).apply { setPadding(0, 0, dp(6), 0) })
        for (lv in 0..4) leg.addView(View(this).apply {
            background = GradientDrawable().apply { cornerRadius = k.dpf(2f); setColor(if (lv == 0) pal.stroke else (alphas[lv] shl 24) or (pal.solid and 0xFFFFFF)) }
        }, LinearLayout.LayoutParams(dp(12), dp(12)).apply { marginEnd = dp(3) })
        leg.addView(k.text(getString(R.string.nst_more), 11f, pal.sub).apply { setPadding(dp(3), 0, 0, 0) })
        box.addView(leg, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        return box
    }
}
