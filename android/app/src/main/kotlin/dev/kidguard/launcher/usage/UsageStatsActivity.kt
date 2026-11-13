package dev.kidguard

import android.app.Activity
import android.content.Context
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
 * "Thời gian dùng app": the sibling of the notification stats screen (NotifStatsActivity), same header, chips, tiles, cards.
 * List: today / 7 days, total, a bar per day (touch = that day) and the apps by time. An app row opens the same screen
 * for that app ([PKG]): today, average, its limit and time left, opens, 7 bars, link to its notification stats.
 * Numbers: [Usage.range] per day off the main thread, [UsageStatsLogic] for the sums. Needs usage access.
 */
class UsageStatsActivity : Activity() {
    companion object {
        const val PKG = "pkg"
        private const val FROM_LIST = "fromList"
        /** Opens the screen; [pkg] != null = the detail of that app. Safe from any context (service, widget, menu). */
        fun open(ctx: Context, pkg: String? = null) {
            val i = Intent(ctx, UsageStatsActivity::class.java)
            pkg?.let { i.putExtra(PKG, it) }
            if (ctx !is Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { ctx.startActivity(i) }
        }
    }

    /** [days]: foreground ms per package, oldest first, last = today. */
    private class Data(val days: List<Map<String, Long>>, val dayStarts: List<Long>, val opens: Map<String, Int>, val skips: Set<String>, val at: Long, val labels: Map<String, String>)

    private lateinit var k: HomeKit
    private lateinit var pal: Palette
    private val loc: Locale by lazy { resources.configuration.locales[0] ?: Locale.getDefault() }
    private fun dp(v: Int) = k.dp(v)

    private var pkg: String? = null
    private var today = false          // list: false = 7 days
    private var gen = 0
    /** Last read: the Today / 7 days switch and "all apps" only redraw from it (re-reading 7 days of events lagged, owner 2026-11-13). */
    private var data: Data? = null
    private var allApps = false
    private lateinit var col: LinearLayout
    private lateinit var title: TextView
    private lateinit var sub: TextView
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
        window.statusBarColor = pal.bg; window.navigationBarColor = pal.bg
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (dark) 0 else (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or if (Build.VERSION.SDK_INT >= 26) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0)
        pkg = intent.getStringExtra(PKG)?.ifEmpty { null }
        today = b?.getBoolean("today") ?: false
        setContentView(frame())
    }

    override fun onSaveInstanceState(o: Bundle) { super.onSaveInstanceState(o); o.putBoolean("today", today) }

    // back from the usage access page: the permission may have changed
    override fun onResume() { super.onResume(); reload() }

    private fun frame(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(pal.bg) }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(8), dp(12), 0) }
        head.addView(ImageView(this).apply {
            setImageDrawable(Ic.get(this@UsageStatsActivity, R.drawable.li_arrow_left, pal.text, dp(24))); val p = (dp(44) - dp(24)) / 2; setPadding(p, p, p, p)
            contentDescription = getString(R.string.hm_c_back); k.tap(this, -1f) { finish() }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        headIcon = ImageView(this).apply { visibility = View.GONE }
        head.addView(headIcon, LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginStart = dp(8) })
        title = k.text(getString(R.string.ust_title), 22f, pal.text, true).apply { setPadding(dp(8), 0, 0, 0); maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        // one app: its name, and under it what this screen is (owner 2026-11-13: "MoMo" alone did not say "usage time")
        val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        names.addView(title)
        sub = k.text(getString(R.string.ust_title), 13f, pal.sub).apply { setPadding(dp(8), 0, 0, 0); visibility = View.GONE }
        names.addView(sub)
        head.addView(names, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(head, LinearLayout.LayoutParams(-1, -2))
        pkg?.let { p ->
            headIcon.visibility = View.VISIBLE; title.text = lbl(p); sub.visibility = View.VISIBLE
            IconCache.peek(p, "", dp(32), "round")?.let { headIcon.setImageBitmap(it) } ?: IconCache.async(this, p, "", dp(32), "round") { b -> if (b != null) headIcon.setImageBitmap(b) }
        }
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(4), dp(16), dp(28)) }
        scroll.addView(col)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }

    // ---- data (off the main thread) ----

    /** Redraw from the last read when it is fresh (< 60 s), else read again. */
    private fun lbl(p: String) = data?.labels?.get(p) ?: IconCache.label(this, p)
    private fun redraw() { val d = data; if (d != null && System.currentTimeMillis() - d.at < 60_000) render(d) else reload() }

    private fun reload() {
        val g = ++gen
        if (!Usage.granted(this)) { renderNeed(); return }
        val app = applicationContext
        Thread {
            val now = System.currentTimeMillis()
            val n = UsageStatsLogic.DAYS
            val starts = List(n) { Usage.midnightAgo(n - 1 - it, now) }
            val days = List(n) { i -> Usage.range(app, starts[i], if (i == n - 1) now else starts[i + 1]).ms }
            val opens = runCatching { Usage.opens(app, starts.last(), now) }.getOrDefault(emptyMap())
            // which packages to leave out, once here: getLaunchIntentForPackage per app per day on the UI thread was the lag
            val all = days.flatMap { it.keys }.toSet()
            val skips = all.filterTo(HashSet()) { skip(it) }
            val labels = (all - skips).associateWith { IconCache.label(app, it) }
            runOnUiThread { if (g == gen && !isFinishing) { val d = Data(days, starts, opens, skips, now, labels); data = d; render(d) } }
        }.start()
    }

    private fun own(p: String) = p == packageName || p.startsWith("dev.kidguard")
    private fun skip(p: String) = own(p) || packageManager.getLaunchIntentForPackage(p) == null

    private fun dur(ms: Long): String = HomeLogic.hm(ms).let { (h, m) ->
        if (h == 0) getString(R.string.home_m, m) else if (m == 0) getString(R.string.home_h, h) else getString(R.string.home_hm, h, m)
    }

    // ---- render ----

    private fun renderNeed() {
        col.removeAllViews()
        col.addView(k.text(getString(R.string.ust_need), 15f, pal.sub).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.2f) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(40); leftMargin = dp(24); rightMargin = dp(24) })
        if (!settingsOk()) {
            col.addView(k.text(getString(R.string.ust_need_locked), 13f, pal.sub).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            return
        }
        col.addView(k.pill(getString(R.string.ust_need_btn), R.drawable.li_settings, true) {
            val pkgUri = android.net.Uri.fromParts("package", packageName, null)
            for (i in listOf(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, pkgUri), Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))) if (runCatching { startActivity(i) }.isSuccess) return@pill
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })
    }

    /** Settings open only if the parents allow it (else Acc would bounce the page right away); same test as the notification stats. */
    private fun settingsOk(): Boolean {
        if (Flavor.HOME) return true
        val managed = Policy.isOwner(this) || Store.of(this).everPaired
        return LauncherLogic.settingsOk(Store.of(this).rules, owner = managed, paired = false)
    }

    private fun render(d: Data) {
        col.removeAllViews()
        val p = pkg
        if (p == null) renderList(d) else renderApp(d, p)
    }

    private fun renderList(d: Data) {
        col.addView(chips(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        val totals = UsageStatsLogic.totals(d.days) { it in d.skips }
        val n = if (today) 1 else UsageStatsLogic.DAYS
        val rows = UsageStatsLogic.rows(d.days, n) { it in d.skips }
        val sum = if (today) totals.last() else totals.sum()
        val avg = UsageStatsLogic.avg(totals)
        val yest = totals[totals.size - 2]
        col.addView(tiles(
            Triple(getString(R.string.ust_total), dur(sum), getString(if (today) R.string.ust_today else R.string.ust_last7)),
            if (today) Triple(getString(R.string.ust_yesterday), dur(yest), null) else Triple(getString(R.string.ust_per_day), dur(avg), null),
            if (today) Triple(getString(R.string.ust_most), rows.firstOrNull()?.let { lbl(it.pkg) } ?: "–", rows.firstOrNull()?.let { dur(it.ms) })
            else Triple(getString(R.string.ust_most_day), UsageStatsLogic.peak(totals).takeIf { it >= 0 }?.let { dayName(d, it) } ?: "–", UsageStatsLogic.peak(totals).takeIf { it >= 0 }?.let { dur(totals[it]) }),
            Triple(getString(R.string.ust_apps_n), rows.size.toString(), null),
        ), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        if (rows.isEmpty()) col.addView(emptyText(if (today) R.string.ust_empty_today else R.string.ust_empty_week),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24); leftMargin = dp(24); rightMargin = dp(24) })
        section(getString(R.string.ust_daily))
        col.addView(card(bars(totals, d)), LinearLayout.LayoutParams(-1, -2))
        if (rows.isNotEmpty()) apps(rows, d)
        col.addView(k.pill(getString(R.string.nst_title), R.drawable.li_bell, false) { startActivity(Intent(this, NotifStatsActivity::class.java)) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })
    }

    private fun renderApp(d: Data, p: String) {
        val s = UsageStatsLogic.series(d.days, p)
        val todayMs = s.last()
        val per = if (s.sum() == 0L) 0L else s.sum() / UsageStatsLogic.covered(s)
        val rules = if (Flavor.HOME) null else Store.of(this).rules
        val limit = rules?.appMin?.get(p) ?: 0
        val left = rules?.let { LauncherLogic.appLeftMs(it, d.days.last(), p) } ?: -1L
        val opens = d.opens[p] ?: 0
        col.addView(tiles(
            Triple(getString(R.string.ust_today), dur(todayMs), null),
            Triple(getString(R.string.ust_per_day), dur(per), getString(R.string.ust_last7)),
            if (left >= 0) Triple(getString(R.string.ust_left), dur(left), if (limit > 0) getString(R.string.ust_limit_note, dur(limit * 60_000L)) else null)
            else Triple(getString(R.string.ust_limit), getString(R.string.ust_none), null),
            Triple(getString(R.string.ust_opened), resources.getQuantityString(R.plurals.ust_times, opens, opens), getString(R.string.ust_today)),
        ), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        if (s.all { it == 0L }) col.addView(emptyText(R.string.ust_empty_week), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24); leftMargin = dp(24); rightMargin = dp(24) })
        section(getString(R.string.ust_daily))
        col.addView(card(bars(s, d)), LinearLayout.LayoutParams(-1, -2))
        // every app's usage: back to the list when it opened this one, else open the list (from the app menu / notif stats)
        col.addView(k.pill(getString(R.string.ust_all), R.drawable.li_chart_column, false) {
            if (intent.getBooleanExtra(FROM_LIST, false)) finish() else startActivity(Intent(this, UsageStatsActivity::class.java))
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })
        col.addView(k.pill(getString(R.string.nst_title), R.drawable.li_bell, false) {
            startActivity(Intent(this, NotifStatsActivity::class.java).putExtra(NotifStatsActivity.PKG, p))
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
    }

    private fun emptyText(res: Int) = k.text(getString(res), 15f, pal.sub).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.2f) }

    private fun chips(): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((isToday, label) in listOf(true to getString(R.string.ust_today), false to getString(R.string.ust_week))) {
            val on = isToday == today
            row.addView(k.chip(label, if (on) pal.onSolid else pal.text, if (on) pal.solid else pal.sheet, null).apply {
                textSize = 14f; setPadding(dp(14), dp(9), dp(14), dp(9))
                if (!on) background = GradientDrawable().apply { setColor(pal.sheet); cornerRadius = k.dpf(999f); setStroke(dp(1), pal.stroke) }
                k.tap(this, 999f) { if (today != isToday) { today = isToday; allApps = false; redraw() } }
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

    /** 2 x 2 tiles (label, value, note). */
    private fun tiles(a: Triple<String, String, String?>, b: Triple<String, String, String?>, c: Triple<String, String, String?>, e: Triple<String, String, String?>): View {
        fun tile(t: Triple<String, String, String?>) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = k.sheetCard(18f); setPadding(dp(14), dp(12), dp(14), dp(12))
            addView(k.text(t.first, 12f, pal.sub, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            addView(k.text(t.second, 22f, pal.text, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
            addView(k.text(t.third ?: " ", 12f, pal.sub).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
        }
        fun row(x: View, y: View) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(x, LinearLayout.LayoutParams(0, -1, 1f).apply { marginEnd = dp(5) }); addView(y, LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = dp(5) })
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(row(tile(a), tile(b)), LinearLayout.LayoutParams(-1, -2))
            addView(row(tile(c), tile(e)), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        }
    }

    /** A thin bar: [ms] of [max]. */
    private fun share(ms: Long, max: Long) = object : View(this) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            val r = height / 2f
            p.color = pal.stroke; c.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, p)
            val f = UsageStatsLogic.pct(ms, max)
            if (f > 0) { p.color = pal.solid; c.drawRoundRect(0f, 0f, (width * f / 100f).coerceAtLeast(height.toFloat()), height.toFloat(), r, r, p) }
        }
    }

    private fun apps(rows: List<UsageStatsLogic.Row>, d: Data) {
        section(getString(R.string.ust_apps))
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = k.sheetCard(20f) }
        val max = rows.first().ms
        val shown = if (allApps) rows else rows.take(15)
        shown.forEachIndexed { i, a ->
            if (i > 0) box.addView(View(this).apply { setBackgroundColor(pal.stroke) }, LinearLayout.LayoutParams(-1, dp(1)).apply { leftMargin = dp(64) })
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(11), dp(14), dp(11)) }
            val ic = ImageView(this)
            row.addView(ic, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(14) })
            IconCache.peek(a.pkg, "", dp(36), "round")?.let { ic.setImageBitmap(it) } ?: IconCache.async(this, a.pkg, "", dp(36), "round") { b -> if (b != null) ic.setImageBitmap(b) }
            val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            top.addView(k.text(lbl(a.pkg), 15f, pal.text, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f))
            top.addView(k.text(dur(a.ms), 15f, pal.text, true).apply { setPadding(dp(8), 0, 0, 0) })
            body.addView(top)
            body.addView(share(a.ms, max), LinearLayout.LayoutParams(-1, dp(4)).apply { topMargin = dp(6) })
            d.opens[a.pkg]?.takeIf { today }?.let { n ->
                body.addView(k.text(resources.getQuantityString(R.plurals.ust_opens, n, n), 12f, pal.sub), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
            }
            row.addView(body, LinearLayout.LayoutParams(0, -2, 1f))
            k.tap(row, 20f) { startActivity(Intent(this, UsageStatsActivity::class.java).putExtra(PKG, a.pkg).putExtra(FROM_LIST, true)) }
            box.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        col.addView(box, LinearLayout.LayoutParams(-1, -2))
        if (!allApps && rows.size > shown.size) col.addView(k.pill(getString(R.string.ust_all_apps, rows.size), null, false) { allApps = true; redraw() },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
    }

    // ---- bars ----

    private fun cal(d: Data, i: Int) = Calendar.getInstance().apply { timeInMillis = d.dayStarts[i] }
    private fun dayName(d: Data, i: Int) = getString(R.string.nst_wd_long).split(',')[(cal(d, i).get(Calendar.DAY_OF_WEEK) + 5) % 7].trim()

    private var downX = 0f; private var downY = 0f
    /** Sideways finger = the chart (page holds still); up / down = the ScrollView's. */
    private fun claim(v: View, e: MotionEvent) {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) { downX = e.x; downY = e.y; return }
        val dx = Math.abs(e.x - downX); val dy = Math.abs(e.y - downY)
        if (dx > android.view.ViewConfiguration.get(this).scaledTouchSlop / 2f && dx > dy) v.parent.requestDisallowInterceptTouchEvent(true)
    }

    /** A bar per day, today solid; touch a bar = its date and time in the caption. */
    private fun bars(v: LongArray, d: Data): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val avg = if (v.isEmpty()) 0L else v.sum() / v.size
        val cap = k.text(getString(R.string.ust_bars_hint), 13f, pal.sub)
        box.addView(cap)
        val df = SimpleDateFormat("EEE d/M", loc)
        val chart = object : View(this) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG); val r = RectF(); var sel = -1; var was = -1; var wasCap: CharSequence = ""
            override fun onDraw(c: Canvas) {
                val max = (v.maxOrNull() ?: 0L).coerceAtLeast(1L)
                val slot = width / v.size.toFloat(); val gap = (slot * 0.25f).coerceAtMost(dp(6).toFloat())
                val h = height.toFloat()
                for (i in v.indices) {
                    val bh = if (v[i] == 0L) 0f else (h * v[i] / max).coerceAtLeast(dp(3).toFloat())
                    r.set(i * slot + gap / 2, h - bh, (i + 1) * slot - gap / 2, h)
                    p.color = if (i == sel || (sel < 0 && i == v.lastIndex)) pal.solid else (0x66 shl 24) or (pal.solid and 0xFFFFFF)
                    val rad = (r.width() / 2).coerceAtMost(dp(4).toFloat())
                    c.drawRoundRect(r, rad, rad, p)
                }
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
                        if (i != sel) { sel = i; invalidate(); cap.text = df.format(cal(d, i).time).replaceFirstChar { it.titlecase(loc) } + ": " + dur(v[i]) }
                    }
                    MotionEvent.ACTION_CANCEL -> { sel = was; invalidate(); cap.text = wasCap }
                }
                return true
            }
        }
        chart.contentDescription = getString(R.string.ust_daily)
        box.addView(chart, LinearLayout.LayoutParams(-1, dp(120)).apply { topMargin = dp(10) })
        val lab = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val wd = getString(R.string.nlog_wd).split(',')
        for (i in v.indices) lab.addView(k.text(wd[(cal(d, i).get(Calendar.DAY_OF_WEEK) + 5) % 7].trim(), 11f, pal.sub).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, -2, 1f))
        box.addView(lab, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        box.addView(k.text(getString(R.string.nst_bars_avg), 11f, pal.sub), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        return box
    }
}
