package dev.kidguard

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Kid launcher "Thông báo đã xem" (NotifLog, SQLite): a month calendar with the count per day (tap a day, tap a second
 * one for a range), filters for app / type / time of day, a text search (no accents needed), and the matching
 * notifications newest first, grouped by day, loaded [NotifQ.PAGE] at a time. Menu: keep 7 / 30 / 90 days, clear all.
 * Tap a row = NotifDetailActivity. Local only, the parent never sees it.
 */
class NotifLogActivity : Activity() {
    private lateinit var k: HomeKit
    private lateinit var pal: Palette
    private lateinit var u: Ui
    private fun dp(v: Int) = k.dp(v)
    private val loc: Locale by lazy { resources.configuration.locales[0] ?: Locale.getDefault() }

    private var f = NotifFilter()
    private var ym = Calendar.getInstance().let { it.get(Calendar.YEAR) * 100 + it.get(Calendar.MONTH) + 1 }   // shown month yyyymm
    private var ov: NotifLog.Overview? = null
    private val rows = ArrayList<Pair<Long, NotifEntry>>()
    private var more = false
    private var gen = 0   // drops answers of an older query
    private var searchOpen = false

    private lateinit var col: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var search: EditText
    private val h = Handler(Looper.getMainLooper())

    override fun getResources(): android.content.res.Resources = Lang.res(baseContext) ?: super.getResources()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
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
        setContentView(frame())
    }

    override fun onResume() { super.onResume(); reload() }

    // ---- frame: header (+ search field) above one scrolling column ----

    private fun frame(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(pal.bg) }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(8), dp(12), 0) }
        head.addView(iconBtn(R.drawable.li_arrow_left, getString(R.string.hm_c_back), 24) { finish() })
        head.addView(k.text(getString(R.string.nlog_title), 22f, pal.text, true).apply { setPadding(dp(8), 0, 0, 0); maxLines = 1; ellipsize = TextUtils.TruncateAt.END },
            LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(iconBtn(R.drawable.li_search, getString(R.string.nlog_search_hint), 22) { toggleSearch() })
        head.addView(iconBtn(R.drawable.li_ellipsis_vertical, getString(R.string.nlog_options), 22) { options() })
        root.addView(head, LinearLayout.LayoutParams(-1, -2))
        search = EditText(this).apply {
            hint = getString(R.string.nlog_search_hint); setSingleLine(); textSize = 16f
            setTextColor(pal.text); setHintTextColor(pal.sub)
            background = GradientDrawable().apply { setColor(pal.sheet); cornerRadius = k.dpf(16f); setStroke(dp(1), pal.stroke) }
            setPadding(dp(16), dp(12), dp(16), dp(12)); visibility = View.GONE
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    h.removeCallbacksAndMessages("q")
                    h.postAtTime({ val q = s?.toString().orEmpty(); if (q != f.q) { f = f.copy(q = q); reload() } }, "q", android.os.SystemClock.uptimeMillis() + 300)
                }
            })
        }
        root.addView(search, LinearLayout.LayoutParams(-1, -2).apply { leftMargin = dp(16); rightMargin = dp(16); topMargin = dp(8) })
        scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(4), dp(16), dp(28)) }
        scroll.addView(col)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }

    private fun iconBtn(icon: Int, desc: String, size: Int, go: () -> Unit) = ImageView(this).apply {
        setImageDrawable(Ic.get(this@NotifLogActivity, icon, pal.text, dp(size))); val p = (dp(44) - dp(size)) / 2; setPadding(p, p, p, p)
        contentDescription = desc; k.tap(this, -1f) { go() }
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
    }

    private fun toggleSearch() {
        val im = getSystemService(InputMethodManager::class.java)
        searchOpen = !searchOpen
        if (searchOpen) { search.visibility = View.VISIBLE; search.requestFocus(); im.showSoftInput(search, 0) }
        else {
            im.hideSoftInputFromWindow(search.windowToken, 0); search.visibility = View.GONE
            if (search.text.isNotEmpty()) search.setText("")
        }
    }

    // ---- data ----

    private fun monthRange() = (ym * 100 + 1) to (ym * 100 + 31)

    private fun reload() {
        val g = ++gen
        val (m0, m1) = monthRange()
        NotifLog.overview(this, f, m0, m1) { o ->
            if (g != gen || isDestroyed || isFinishing) return@overview
            NotifLog.page(this, f, 0) { l ->
                if (g != gen || isDestroyed || isFinishing) return@page
                ov = o; rows.clear(); rows += l; more = l.size >= NotifQ.PAGE
                render()
            }
        }
    }

    private fun loadMore() {
        val g = gen
        NotifLog.page(this, f, rows.size) { l ->
            if (g != gen || isDestroyed || isFinishing) return@page
            rows += l; more = l.size >= NotifQ.PAGE
            val y = scroll.scrollY; render(); scroll.post { scroll.scrollTo(0, y) }
        }
    }

    private fun monthOnly() {   // month arrows: only the calendar counts change
        val g = gen
        val (m0, m1) = monthRange()
        NotifLog.overview(this, f, m0, m1) { o ->
            if (g != gen || isDestroyed || isFinishing) return@overview
            ov = o; val y = scroll.scrollY; render(); scroll.post { scroll.scrollTo(0, y) }
        }
    }

    // ---- render ----

    private fun render() {
        val o = ov ?: return
        col.removeAllViews()
        col.addView(calendar(o), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        col.addView(chips(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        val filtered = f != NotifFilter()
        val sum = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        sum.addView(k.text(resources.getQuantityString(R.plurals.nlog_total, o.total, o.total), 13f, pal.sub, true).apply { setPadding(dp(4), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
        if (filtered) sum.addView(k.text(getString(R.string.nlog_reset), 13f, pal.accentText, true).apply {
            setPadding(dp(10), dp(8), dp(10), dp(8)); k.tap(this, 12f) { resetFilters() }
        })
        col.addView(sum, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        if (rows.isEmpty()) {
            val msg = if (filtered) getString(R.string.nlog_nomatch) else getString(R.string.nlog_empty, NotifLog.keepDays(this))
            col.addView(k.text(msg, 15f, pal.sub).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.2f) },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(40); leftMargin = dp(24); rightMargin = dp(24) })
            return
        }
        val today0 = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
        val tf = android.text.format.DateFormat.getTimeFormat(this)
        val df = SimpleDateFormat("EEEE d/M", loc)
        var group: LinearLayout? = null
        var last = Int.MIN_VALUE
        for ((id, e) in rows) {
            val d = NotifLogic.daysAgo(e.time, today0)
            if (d != last) {
                last = d
                val name = when (d) { 0 -> getString(R.string.nlog_today); 1 -> getString(R.string.nlog_yesterday); else -> df.format(Date(e.time)).replaceFirstChar { it.titlecase(loc) } }
                col.addView(k.text(name, 13f, pal.sub, true).apply { setPadding(dp(4), 0, 0, 0) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18); bottomMargin = dp(8) })
                group = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = k.sheetCard(20f) }
                col.addView(group, LinearLayout.LayoutParams(-1, -2))
            } else group!!.addView(View(this).apply { setBackgroundColor(pal.stroke) }, LinearLayout.LayoutParams(-1, dp(1)).apply { leftMargin = dp(72) })
            group!!.addView(row(id, e, tf.format(Date(e.time))), LinearLayout.LayoutParams(-1, -2))
        }
        if (more) col.addView(k.pill(getString(R.string.nlog_more), null, false) { loadMore() },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
    }

    private fun row(id: Long, e: NotifEntry, time: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; setPadding(dp(14), dp(12), dp(14), dp(12))
        val icon = ImageView(this@NotifLogActivity)
        addView(icon, LinearLayout.LayoutParams(dp(40), dp(40)).apply { rightMargin = dp(14) })
        val px = dp(40)
        IconCache.peek(e.pkg, "", px, "round")?.let { icon.setImageBitmap(it) }
            ?: IconCache.async(this@NotifLogActivity, e.pkg, "", px, "round") { b -> if (b != null) icon.setImageBitmap(b) }
        val body = LinearLayout(this@NotifLogActivity).apply { orientation = LinearLayout.VERTICAL }
        val top = LinearLayout(this@NotifLogActivity).apply { orientation = LinearLayout.HORIZONTAL }
        top.addView(k.text(e.app.ifEmpty { e.pkg }, 12f, pal.sub).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(k.text(time, 12f, pal.sub).apply { setPadding(dp(8), 0, 0, 0) })
        body.addView(top)
        if (e.title.isNotEmpty()) body.addView(k.text(e.title, 15f, pal.text, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
        if (e.text.isNotEmpty()) body.addView(k.text(e.text, 14f, pal.sub).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
        addView(body, LinearLayout.LayoutParams(0, -2, 1f))
        k.tap(this, 20f) {
            startActivity(Intent(this@NotifLogActivity, NotifDetailActivity::class.java).putExtra(NotifDetailActivity.ID, id).putExtra(NotifDetailActivity.KEY, e.key))
        }
    }

    // ---- calendar ----

    private fun calendar(o: NotifLog.Overview): View {
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = k.sheetCard(20f); setPadding(dp(8), dp(6), dp(8), dp(10)) }
        val c = Calendar.getInstance().apply { clear(); set(ym / 100, ym % 100 - 1, 1) }
        val now = Calendar.getInstance()
        val today = NotifQ.day(now)
        val nowYm = now.get(Calendar.YEAR) * 100 + now.get(Calendar.MONTH) + 1
        val oldYm = if (o.oldest > 0) NotifQ.day(o.oldest) / 100 else nowYm
        // ‹ month ›
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        bar.addView(iconBtn(R.drawable.li_chevron_right, getString(R.string.nlog_prev_month), 22) { shiftMonth(-1) }.apply {
            rotation = 180f; if (ym <= oldYm) { alpha = 0.3f; isEnabled = false }
        })
        val month = SimpleDateFormat("LLLL yyyy", loc).format(c.time).replaceFirstChar { it.titlecase(loc) }
        val monthTotal = o.days.values.sum()
        bar.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            addView(k.text(month, 16f, pal.text, true).apply { gravity = Gravity.CENTER })
            addView(k.text(resources.getQuantityString(R.plurals.nlog_total, monthTotal, monthTotal), 12f, pal.sub).apply { gravity = Gravity.CENTER; setPadding(0, dp(2), 0, 0) })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(iconBtn(R.drawable.li_chevron_right, getString(R.string.nlog_next_month), 22) { shiftMonth(1) }.apply {
            if (ym >= nowYm) { alpha = 0.3f; isEnabled = false }
        })
        card.addView(bar, LinearLayout.LayoutParams(-1, -2))
        // weekday names, Monday first
        val wd = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (n in getString(R.string.nlog_wd).split(',')) wd.addView(k.text(n.trim(), 11f, pal.sub, true).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(wd, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6); bottomMargin = dp(4) })
        val lead = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7
        val n = c.getActualMaximum(Calendar.DAY_OF_MONTH)
        val max = o.days.values.maxOrNull() ?: 0
        var cell = 0
        var week: LinearLayout? = null
        val total = ((lead + n + 6) / 7) * 7
        while (cell < total) {
            if (cell % 7 == 0) { week = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }; card.addView(week, LinearLayout.LayoutParams(-1, dp(42))) }
            val dom = cell - lead + 1
            val lp = LinearLayout.LayoutParams(0, -1, 1f).apply { setMargins(dp(2), dp(1), dp(2), dp(1)) }
            if (dom < 1 || dom > n) week!!.addView(View(this), lp)
            else week!!.addView(dayCell(ym * 100 + dom, dom, o.days[ym * 100 + dom] ?: 0, max, today), lp)
            cell++
        }
        return card
    }

    private fun dayCell(day: Int, dom: Int, cnt: Int, max: Int, today: Int): View {
        val sel = f.d0 > 0 && day in f.d0..f.d1
        val future = day > today
        val shade = NotifQ.shade(cnt, max)
        val fill = when {
            sel -> pal.solid
            // heat: a light tint only, so the busiest day never looks like the chosen (solid) one
            shade > 0 -> (intArrayOf(0, 0x16, 0x2C, 0x46, 0x64)[shade] shl 24) or (pal.solid and 0xFFFFFF)
            else -> 0
        }
        val fg = if (sel) pal.onSolid else pal.text
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(fill); cornerRadius = k.dpf(12f)
                if (day == today && !sel) setStroke(dp(2), pal.solid)
            }
            addView(k.text(dom.toString(), 14f, fg, day == today || sel).apply { gravity = Gravity.CENTER })
            if (cnt > 0) addView(k.text(if (cnt > 999) "999+" else cnt.toString(), 10f, if (sel) pal.onSolid else pal.sub).apply {
                gravity = Gravity.CENTER; setPadding(0, dp(2), 0, 0)
            })
            if (future) alpha = 0.35f
            else k.tap(this, 12f) { val (a, b) = NotifQ.tap(f.d0, f.d1, day); f = if (a == 0) f.copy(d0 = 0, d1 = Int.MAX_VALUE) else f.copy(d0 = a, d1 = b); reload() }
            contentDescription = "$dom, " + resources.getQuantityString(R.plurals.nlog_total, cnt, cnt)
        }
    }

    private fun shiftMonth(d: Int) {
        var y = ym / 100; var m = ym % 100 + d
        if (m < 1) { m = 12; y-- } else if (m > 12) { m = 1; y++ }
        ym = y * 100 + m; monthOnly()
    }

    // ---- filter chips ----

    private fun chips(): View {
        val sc = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; clipToPadding = false }
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        sc.addView(r)
        fun add(t: String, on: Boolean, icon: Int?, go: () -> Unit) {
            val v = k.chip(t, if (on) pal.onSolid else pal.text, if (on) pal.solid else pal.sheet, icon).apply {
                textSize = 14f; setPadding(dp(14), dp(9), dp(14), dp(9))
                if (!on) background = GradientDrawable().apply { setColor(pal.sheet); cornerRadius = k.dpf(999f); setStroke(dp(1), pal.stroke) }
                k.tap(this, 999f) { go() }
            }
            r.addView(v, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        }
        if (f.d0 > 0) {
            val a = dm(f.d0); val label = if (f.d0 == f.d1) a else "$a – ${dm(f.d1)}"
            add(label, true, R.drawable.li_x) { f = f.copy(d0 = 0, d1 = Int.MAX_VALUE); reload() }
        }
        val apps = f.pkgs.size
        add(when (apps) { 0 -> getString(R.string.nlog_f_app); 1 -> ov?.apps?.firstOrNull { it.first in f.pkgs }?.second?.ifEmpty { null } ?: getString(R.string.nlog_f_apps, 1)
            else -> getString(R.string.nlog_f_apps, apps) }, apps > 0, null) { pickApps() }
        add(when (f.kinds.size) { 0 -> getString(R.string.nlog_f_kind); 1 -> kindName(f.kinds.first()); else -> getString(R.string.nlog_f_kinds, f.kinds.size) },
            f.kinds.isNotEmpty(), null) { pickKinds() }
        add(if (f.allDay) getString(R.string.nlog_f_hours) else hm(f.m0) + " – " + hm(f.m1), !f.allDay, R.drawable.li_clock) { pickHours() }
        return sc
    }

    private fun dm(d: Int) = "${d % 100}/${d / 100 % 100}"
    private fun hm(m: Int) = String.format(Locale.US, "%02d:%02d", (m / 60) % 24, m % 60)

    private fun resetFilters() {
        f = NotifFilter(q = f.q)
        if (f.q.isNotEmpty()) { searchOpen = true; toggleSearch() }   // closes the field and clears the text (reloads)
        else reload()
    }

    private fun kindName(kd: String) = getString(when (kd) {
        NotifQ.K_MSG -> R.string.nlog_k_msg; NotifQ.K_CALL -> R.string.nlog_k_call; NotifQ.K_SOCIAL -> R.string.nlog_k_social
        NotifQ.K_TIME -> R.string.nlog_k_time; NotifQ.K_SYS -> R.string.nlog_k_sys; NotifQ.K_PROMO -> R.string.nlog_k_promo
        else -> R.string.nlog_k_other
    })

    /** A sheet row: [lead] view, label, count, check mark. */
    private fun checkRow(lead: View?, label: String, count: Int?, on: Boolean, go: (View) -> Unit): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(10)); minimumHeight = dp(52)
        if (lead != null) addView(lead, LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(12) })
        addView(k.text(label, 16f, pal.text).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f))
        if (count != null) addView(k.text(count.toString(), 14f, pal.sub).apply { setPadding(dp(8), 0, dp(8), 0) })
        val mark = ImageView(this@NotifLogActivity).apply { setImageDrawable(Ic.get(this@NotifLogActivity, R.drawable.li_check, pal.accentText, dp(22))); visibility = if (on) View.VISIBLE else View.INVISIBLE; tag = "mark" }
        addView(mark, LinearLayout.LayoutParams(dp(24), dp(24)))
        k.tap(this, 14f) { go(this) }
    }

    private fun setMark(row: View, on: Boolean) { row.findViewWithTag<View>("mark")?.visibility = if (on) View.VISIBLE else View.INVISIBLE }

    /** Multi-select sheet; the filter changes when it closes. */
    private fun multiSheet(title: String, items: List<Triple<String, String, Int>>, chosen: Set<String>, icons: Boolean, apply: (Set<String>) -> Unit) {
        val sh = Sheet(this, u, title)
        val pick = chosen.toMutableSet()
        val all = checkRow(null, getString(R.string.nlog_all), null, pick.isEmpty()) {}
        sh.body.addView(all)
        val views = ArrayList<Pair<String, View>>()
        all.setOnClickListener { pick.clear(); setMark(all, true); views.forEach { (_, v) -> setMark(v, false) } }
        for ((id, name, cnt) in items) {
            val lead = if (icons) ImageView(this).also { iv ->
                val px = dp(32)
                IconCache.peek(id, "", px, "round")?.let { iv.setImageBitmap(it) } ?: IconCache.async(this, id, "", px, "round") { b -> if (b != null) iv.setImageBitmap(b) }
            } else null
            val row = checkRow(lead, name.ifEmpty { id }, cnt, id in pick) { v ->
                if (!pick.remove(id)) pick += id
                setMark(v, id in pick); setMark(all, pick.isEmpty())
            }
            views += id to row
            sh.body.addView(row)
        }
        sh.onDismiss { if (pick != chosen) apply(pick.toSet()) }
        sh.show()
    }

    private fun pickApps() {
        val o = ov ?: return
        val list = o.apps.toMutableList()
        for (p in f.pkgs) if (list.none { it.first == p }) list += Triple(p, p, 0)   // chosen but no match under the other filters
        multiSheet(getString(R.string.nlog_f_app), list, f.pkgs, true) { f = f.copy(pkgs = it); reload() }
    }

    private fun pickKinds() {
        val o = ov ?: return
        val list = NotifQ.KINDS.filter { (o.kinds[it] ?: 0) > 0 || it in f.kinds }.map { Triple(it, kindName(it), o.kinds[it] ?: 0) }
        multiSheet(getString(R.string.nlog_f_kind), list, f.kinds, false) { f = f.copy(kinds = it); reload() }
    }

    private fun pickHours() {
        val sh = Sheet(this, u, getString(R.string.nlog_f_hours))
        val presets = listOf(
            Triple(R.string.nlog_h_all, 0, NotifQ.DAY_MIN), Triple(R.string.nlog_h_morning, 6 * 60, 12 * 60),
            Triple(R.string.nlog_h_afternoon, 12 * 60, 18 * 60), Triple(R.string.nlog_h_evening, 18 * 60, 22 * 60),
            Triple(R.string.nlog_h_night, 22 * 60, 6 * 60),
        )
        for ((s, a, b) in presets) sh.body.addView(checkRow(null, getString(s), null, f.m0 == a && f.m1 == b) {
            sh.dismiss(); if (f.m0 != a || f.m1 != b) { f = f.copy(m0 = a, m1 = b); reload() }
        })
        val custom = presets.none { f.m0 == it.second && f.m1 == it.third }
        sh.body.addView(checkRow(null, getString(R.string.nlog_h_custom) + if (custom) "  " + hm(f.m0) + " – " + hm(f.m1) else "", null, custom) {
            sh.dismiss()
            val is24 = android.text.format.DateFormat.is24HourFormat(this)
            val start = if (f.allDay) 8 * 60 else f.m0
            android.app.TimePickerDialog(this, { _, h0, mi0 ->
                val a = h0 * 60 + mi0
                val end = if (f.allDay) 17 * 60 else f.m1 % NotifQ.DAY_MIN
                android.app.TimePickerDialog(this, { _, h1, mi1 ->
                    var b = h1 * 60 + mi1
                    if (b == a) b = if (a == 0) NotifQ.DAY_MIN else a   // same time = the whole day from there
                    f = if (a == 0 && (b == 0 || b == NotifQ.DAY_MIN)) f.copy(m0 = 0, m1 = NotifQ.DAY_MIN) else f.copy(m0 = a, m1 = b)
                    reload()
                }, end / 60, end % 60, is24).apply { setTitle(R.string.nlog_to) }.show()
            }, start / 60, start % 60, is24).apply { setTitle(R.string.nlog_from) }.show()
        })
        sh.show()
    }

    // ---- options: keep for, clear ----

    private fun options() {
        val sh = Sheet(this, u, getString(R.string.nlog_options))
        sh.body.addView(k.text(getString(R.string.nlog_keep), 13f, pal.sub, true).apply { setPadding(dp(12), dp(8), dp(12), dp(4)) })
        val cur = NotifLog.keepDays(this)
        for (d in NotifQ.KEEP_DAYS) sh.body.addView(checkRow(null, getString(R.string.nlog_keep_n, d), null, d == cur) {
            sh.dismiss()
            if (d == cur) return@checkRow
            if (d < cur) Modal(this, u).show(R.drawable.li_trash_2, Modal.Tone.WARN, getString(R.string.nlog_keep_n, d), getString(R.string.nlog_keep_less, d),
                ok = getString(R.string.nlog_keep_ok), cancel = getString(R.string.cancel)) { NotifLog.setKeepDays(this, d); h.postDelayed({ reload() }, 300); true }
            else NotifLog.setKeepDays(this, d)
        })
        sh.body.addView(k.text(getString(R.string.nlog_keep_note), 13f, pal.sub).apply { setPadding(dp(12), dp(6), dp(12), dp(12)); setLineSpacing(0f, 1.15f) })
        sh.body.addView(k.pill(getString(R.string.nlog_clear), R.drawable.li_trash_2, false) { sh.dismiss(); confirmClear() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        sh.show()
    }

    private fun confirmClear() {
        Modal(this, u).show(R.drawable.li_trash_2, Modal.Tone.DANGER, getString(R.string.nlog_clear), getString(R.string.nlog_clear_body),
            ok = getString(R.string.nlog_clear_ok), cancel = getString(R.string.cancel)) { NotifLog.clear(this); h.postDelayed({ reload() }, 200); true }
    }
}
