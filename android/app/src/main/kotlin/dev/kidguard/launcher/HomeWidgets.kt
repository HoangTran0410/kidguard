package dev.kidguard

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import dev.kidguard.HomeLayout.Src
import dev.kidguard.HomeActivity.DragSt
import dev.kidguard.HomeActivity.View0
import dev.kidguard.HomeActivity.Row

// Home widgets and the widget picker (split out of HomeActivity; extension functions on it).

// ---- widgets ----

/** Long-press on a widget (or one of its buttons): menu (sizes, remove), and a drag moves it. */
internal fun HomeActivity.armWidget(v: View, it: HItem) {
    val sizes = if (it.type == HomeLayout.APP) awSizes(it) else HomeLayout.SIZES[it.type].orEmpty().map { (w, h) -> minOf(w, model?.cols ?: w) to h }.distinct()
    val showMenu = {
        menu(sizes.filter { s -> s != it.w to it.h }.map { (w, h) ->
            Row(R.drawable.li_layout_dashboard, getString(R.string.hm_w_size, w, h), true) { model?.let { mm -> HomeLayout.resizeWidget(mm, it.id, w, h)?.let { n -> update(n) } ?: noRoom(null) } }
        } + Row(R.drawable.li_circle_minus, getString(R.string.hm_unpin), true) { model?.let { mm -> HomeLayout.take(mm, Src.Item(it.id))?.let { t -> update(HomeLayout.dropRemove(t)) } } }, v)
    }
    val src = { DragSt(Src.Item(it.id), null, "widget", it.w, it.h, it.id, v) }
    fun walk(x: View) {
        if (x === v || (x.isClickable && v !is KgHostView)) arm(x, showMenu, src, dragView = v)
        if (x is ViewGroup && v !is KgHostView) for (i in 0 until x.childCount) walk(x.getChildAt(i))
    }
    walk(v)
    v.isLongClickable = true
}

internal fun HomeActivity.widgetLabel(type: String) = getString(when (type) {
    "time" -> R.string.hm_w_time; "quick" -> R.string.hm_w_quick; "next" -> R.string.hm_w_next; "clock" -> R.string.hm_w_clock
    "analog" -> R.string.hm_w_analog; "month" -> R.string.hm_w_month; "battery" -> R.string.hm_w_battery
    "todo" -> R.string.hm_w_todo; "note" -> R.string.hm_w_note; "usage" -> R.string.hm_w_usage
    "when" -> R.string.hm_w_when; "earn" -> R.string.hm_w_earn; "msg" -> R.string.hm_w_msg; "class" -> R.string.hm_w_class; else -> R.string.hm_w_counts
})

internal fun HomeActivity.widgetDesc(type: String) = getString(when (type) {
    "time" -> R.string.hm_w_time_d; "quick" -> R.string.hm_w_quick_d; "next" -> R.string.hm_w_next_d; "clock" -> R.string.hm_w_clock_d
    "analog" -> R.string.hm_w_analog_d; "month" -> R.string.hm_w_month_d; "battery" -> R.string.hm_w_battery_d
    "todo" -> R.string.hm_w_todo_d; "note" -> R.string.hm_w_note_d; "usage" -> R.string.hm_w_usage_d
    "when" -> R.string.hm_w_when_d; "earn" -> R.string.hm_w_earn_d; "msg" -> R.string.hm_w_msg_d; "class" -> R.string.hm_w_class_d; else -> R.string.hm_w_counts_d
})

// ---- widget design system: every card widget is built from these, so they all look like one family ----
// Card: pal.card fill, radius 22, padding 14 / 12 dp, wraps its content and sits centred in its cells (centred()).
// Type scale: head 11sp pal.sub UPPERCASE (letterSpacing 0.08) - value 20-26sp bold (pal.accentText, pal.warnText when low)
//   - primary line 15-16sp bold pal.text - secondary 12-13sp pal.sub. Icons: Lucide via Ic.get, 16-18 dp, pal.accentText
//   (or kit.badge(icon, pal.onTint, pal.tint, 40, 20) for a leading round badge). Never raw colours: only pal.*.
// A tap that "opens more" goes to the "Hôm nay" page (wOpen). Live values (minute, battery) update in place through
// tickers, never by a rebuild.

/** The widget card: vertical (or horizontal) box with the shared fill and padding. */
internal fun HomeActivity.wCard(vertical: Boolean = true) = LinearLayout(this).apply {
    orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL; background = card(); setPadding(dp(14), dp(12), dp(14), dp(12))
}

/** Card heading: small accent icon + UPPERCASE label in pal.sub; [end] = optional right-hand text (a count). */
internal fun HomeActivity.wHead(icon: Int, label: String, end: String? = null): View {
    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, dp(6)) }
    row.addView(ImageView(this).apply { setImageDrawable(Ic.get(this@wHead, icon, pal.accentText, dp(14))); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, LinearLayout.LayoutParams(dp(14), dp(14)))
    row.addView(text(label.uppercase(), 11f, pal.sub, true).apply { letterSpacing = 0.08f; setPadding(dp(6), 0, dp(4), 0); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f))
    if (end != null) row.addView(text(end, 12f, pal.sub, true))
    return row
}

/** Primary / secondary / empty lines of the scale above. */
internal fun HomeActivity.wMain(t: String, lines: Int = 1) = text(t, 15f, pal.text, true).apply { maxLines = lines; ellipsize = android.text.TextUtils.TruncateAt.END }
internal fun HomeActivity.wSub(t: String, lines: Int = 1) = text(t, 12f, pal.sub).apply { maxLines = lines; ellipsize = android.text.TextUtils.TruncateAt.END }
internal fun HomeActivity.wEmpty(t: String) = text(t, 13f, pal.sub).apply { maxLines = 2; setPadding(0, dp(2), 0, dp(2)) }

/** Tap = open the "Hôm nay" page (ripple in the card's corner radius). */
internal fun HomeActivity.wOpen(v: View) = kit.tap(v, 22f) { pager.snapTo(0) }

/** Clock widgets on the current render: the minute tick only changes their text, no rebuild (it used to rebuild the home every minute). */

internal fun HomeActivity.tickClocks(dv: View0) {
    val now = HomeLogic.clock(dv.minute)
    for ((t, d) in clocks) if (t.text != now) { t.text = now; (t.parent as? View)?.contentDescription = "$now. ${d.text}" }
    for (t in tickers) t(dv)
}

internal fun HomeActivity.clockWidget(r: Rules, dv: View0, w: Int, h: Int, hideNext: Boolean): View {
    val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL or Gravity.START; setPadding(dp(14), dp(4), dp(8), dp(4)) }
    val big = when (preset.clock) { "big" -> 60f; "thin" -> 46f; else -> 54f } * (if (w <= 3 && preset.cols == 5) 0.85f else 1f) * (if (h >= 2) 1.4f else 1f)
    val t = text(HomeLogic.clock(dv.minute), big, Color.WHITE).apply {
        typeface = Typeface.create(if (preset.clock == "thin") "sans-serif-light" else "sans-serif", Typeface.NORMAL); setPadding(dp(6), 0, dp(6), 0)
    }
    box.addView(t, LinearLayout.LayoutParams(-2, -2))
    val date = SimpleDateFormat("EEEE, d MMMM", resources.configuration.locales[0]).format(Date(dv.now)).replaceFirstChar { it.titlecase() }
    val d = text(date, if (h >= 2) 17f else 15f, Color.WHITE, true).apply { setPadding(dp(6), dp(2), dp(6), dp(2)) }
    box.addView(d, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(2) })
    inkViews += t; inkViews += d
    clocks += t to d
    // one place for "what's next": the "Lịch tiếp theo" widget when it is on the home, else here
    if (!hideNext) LauncherLogic.nextEvent(r, dv.minute, dv.dow)?.let { (min, name) ->
        val n = text(if (name.isEmpty()) getString(R.string.hm_next_bed, HomeLogic.clock(min)) else getString(R.string.hm_next, name, HomeLogic.clock(min)), 13f, Color.WHITE)
            .apply { setPadding(dp(6), dp(2), dp(6), dp(2)); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        box.addView(n, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(2) })
        inkViews += n
    }
    box.contentDescription = "${t.text}. ${d.text}"
    return centred(box)
}

internal fun HomeActivity.dur(ms: Long) = HomeLogic.hm(ms).let { (h, m) -> if (h == 0) getString(R.string.home_m, m) else if (m == 0) getString(R.string.home_h, h) else getString(R.string.home_hm, h, m) }

/** The card wraps its content and sits in the middle of its cells (the mock's height, not the cell's). */
internal fun HomeActivity.centred(v: View): View = FitCell(this).apply { clipChildren = false; addView(v, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL)) }

/**
 * Holds one widget card in its fixed-size cell. With a big system / kid font the card can want more height than the cell;
 * then it is laid out wider (fewer wrapped lines) and scaled down about its centre until it fits (never below 60%),
 * instead of spilling over the neighbouring cells. At normal sizes it is a plain centred frame.
 */
internal class FitCell(c: android.content.Context) : FrameLayout(c) {
    override fun onMeasure(w: Int, h: Int) {
        val child = getChildAt(0)
        val ws = MeasureSpec.getSize(w); val hs = MeasureSpec.getSize(h)
        if (child == null || child.visibility == View.GONE || MeasureSpec.getMode(h) != MeasureSpec.EXACTLY || ws == 0 || hs == 0) { super.onMeasure(w, h); return }
        var s = 1f
        var cw = ws; var ch = 0
        for (i in 0 until 3) {
            cw = (ws / s).toInt()
            child.measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            ch = child.measuredHeight
            val ns = if (ch * s > hs) (hs.toFloat() / ch).coerceAtLeast(0.6f) else 1f
            if (ns >= s - 0.01f && i > 0) break
            s = minOf(s, ns).coerceAtLeast(0.6f)
            if (s == 1f) break
        }
        cw = (ws / s).toInt()
        child.measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        ch = child.measuredHeight
        scale = s
        setMeasuredDimension(ws, hs)
    }
    private var scale = 1f
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val child = getChildAt(0) ?: return
        if (child.visibility == View.GONE) return
        val cw = child.measuredWidth; val ch = child.measuredHeight
        val top = ((b - t) - ch) / 2
        child.layout(0, top, cw, top + ch)
        child.pivotX = 0f; child.pivotY = ch / 2f
        child.scaleX = scale; child.scaleY = scale
    }
}

internal fun HomeActivity.timeWidget(r: Rules, dv: View0, block: String?, s: Store, w: Int): View {
    val small = w <= 2
    val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(12)) }
    val canAsk = HomeLogic.canAsk(s.everPaired, dv.leftMs, block)
    if (block != null) {
        // out of time: amber card (opaque tint: its own text passes on any wallpaper), reason + when it opens + "bấm Xin giờ"
        box.background = fill(pal.warnTint, 22f)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val icon = when (block) { "bedtime" -> R.drawable.li_moon; "schedule" -> R.drawable.li_calendar; "paused" -> R.drawable.li_circle_pause; else -> R.drawable.li_timer }
        if (!small) row.addView(ImageView(this).apply { setImageDrawable(Ic.get(this@timeWidget, icon, pal.onWarn, dp(18))); background = fill(pal.warn); setPadding(dp(8), dp(8), dp(8), dp(8)) },
            LinearLayout.LayoutParams(dp(34), dp(34)).apply { rightMargin = dp(12) })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(text(BasicLogic.reasonText(block, s.tr).replaceFirstChar { it.titlecase() }, if (small) 15f else 18f, pal.text, true).apply { maxLines = 2 })
        val wh = reopenWhen(r, block, "", dv)?.let { getString(R.string.hm_reopen, it) }
        val line = when { wh != null && canAsk -> getString(R.string.hm_reopen_ask, wh); wh != null -> wh; canAsk -> getString(R.string.main_more); else -> null }
        if (line != null) col.addView(text(line, 13f, pal.warnText).apply { setPadding(0, dp(3), 0, 0); maxLines = 2 })
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        box.addView(row)
    } else {
        box.background = card()
        box.addView(text(getString(R.string.home_left).uppercase(), 11f, pal.sub, true).apply { letterSpacing = 0.08f })
        if (dv.leftMs < 0) {
            box.addView(text(getString(R.string.tl_none), if (small) 18f else 22f, pal.text, true).apply { setPadding(0, dp(4), 0, 0) })
            if (r.bedtimeOn && !small) box.addView(text(getString(R.string.home_bed, r.bedStart, r.bedEnd), 13f, pal.sub).apply { setPadding(0, dp(3), 0, 0) })
        } else {
            val low = dv.leftMs < 10 * 60_000L
            box.addView(text(dur(dv.leftMs), if (small) 20f else 26f, if (low) pal.warnText else pal.accentText, true).apply { setPadding(0, dp(4), 0, dp(8)); maxLines = 1 })
            val limitMs = (baseLimitMin(r, dv.today) + if (r.bonusDay == dv.today) r.bonusMin else 0) * 60_000L
            val used = dv.usage.filterKeys { it in dv.apps && it !in dv.prot && it !in r.allow }.values.sum()
            val bar = FrameLayout(this).apply { background = fill(pal.stroke, 3f) }
            val f = if (limitMs > 0) (used.toFloat() / limitMs).coerceIn(0.02f, 1f) else 0f
            bar.addView(View(this).apply { background = fill(if (low) pal.warn else pal.solid, 3f) }, FrameLayout.LayoutParams(-1, -1))
            bar.addOnLayoutChangeListener { v, l, _, rr, _, _, _, _, _ -> (v as FrameLayout).getChildAt(0).let { c -> val want = ((rr - l) * f).toInt(); if (c.layoutParams.width != want) { c.layoutParams.width = want; c.post { c.requestLayout() } } } }
            box.addView(bar, LinearLayout.LayoutParams(-1, dp(6)))
            if (!small) {
                val ln = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(7), 0, 0) }
                ln.addView(text(getString(R.string.home_used, dur(used)), 12f, pal.sub), LinearLayout.LayoutParams(0, -2, 1f))
                ln.addView(text(getString(R.string.home_limit, dur(limitMs)), 12f, pal.sub))
                box.addView(ln)
            }
        }
    }
    if (canAsk) kit.tap(box, 22f) { if (debounce()) acts.askMore() }
    box.contentDescription = (box.getChildAt(0) as? TextView)?.text?.toString()
    return centred(box)
}

internal fun HomeActivity.quickWidget(s: Store, w: Int): View {
    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; background = card(); setPadding(dp(6), dp(10), dp(6), dp(10)) }
    val paired = s.everPaired
    val today = Guard.day(System.currentTimeMillis())
    fun ci(ck: String, base: Int): String {
        val t = s.lastCheckin(ck)
        return if (t > 0 && Guard.day(t) == today) getString(R.string.hm_q_sent_at, hhmm(t)) else getString(base)
    }
    // glyph colour comes from its own fill (HomeStyle: onDanger / onTint / onSolid), never the mock's purple-on-purple
    val items = listOf(
        Triple(R.drawable.li_siren, Pair(pal.danger, pal.onDanger), Pair(if (s.sos().length() > 0) getString(R.string.hm_q_sent) else getString(R.string.home_sos), pal.dangerText)) to { acts.askSos() },
        Triple(R.drawable.li_map_pin, Pair(pal.tint, pal.onTint), Pair(ci("arrived", R.string.hm_q_arrived), pal.text)) to { acts.askCheckin("arrived") },
        Triple(R.drawable.li_car, Pair(pal.tint, pal.onTint), Pair(ci("pickup", R.string.hm_q_pickup), pal.text)) to { acts.askCheckin("pickup") },
        Triple(R.drawable.li_timer, Pair(pal.solid, pal.onSolid), Pair(if (s.requests().length() > 0) getString(R.string.hm_q_wait) else getString(R.string.hm_q_more), pal.text)) to { acts.askMore() },
    )
    val circle = if (w >= 4 && preset.id == "be") 56 else 48
    for ((spec, run) in items) {
        val (icon, fills, lab) = spec
        val b = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(2), dp(4), dp(2), dp(4))
            addView(ImageView(context).apply {
                setImageDrawable(Ic.get(context, icon, fills.second, dp(22))); background = fill(fills.first)
                val p = dp((circle - 22) / 2); setPadding(p, p, p, p)
            }, LinearLayout.LayoutParams(dp(circle), dp(circle)))
            addView(text(lab.first, 12f, lab.second, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(6), 0, 0); gravity = Gravity.CENTER })
            contentDescription = lab.first
            // ripple in a rounded mask + no grey focus box (the SOS button used to keep one after a tap)
            kit.tap(this, 14f) {
                if (!debounce()) return@tap
                if (!paired) Modal(this@quickWidget, u).show(R.drawable.li_link, Modal.Tone.INFO, getString(R.string.hm_pair_title), getString(R.string.hm_pair_body),
                    ok = getString(R.string.hm_open_kg), cancel = getString(R.string.cancel)) { openMain(); true }
                else run()
            }
        }
        row.addView(b, LinearLayout.LayoutParams(0, -2, 1f))
    }
    return centred(row)
}

/** "Lịch tiếp theo" 4x1: what comes next today (or now), from LauncherLogic.today; a tap opens "Hôm nay". */
internal fun HomeActivity.nextWidget(r: Rules, dv: View0): View {
    val box = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = card(); setPadding(dp(14), dp(12), dp(14), dp(12)) }
    val ev = LauncherLogic.today(r, dv.minute, dv.dow).firstOrNull { it.state == "now" || it.state == "next" }
    box.addView(kit.badge(when (ev?.kind) { "bed" -> R.drawable.li_moon; "group" -> R.drawable.li_timer; else -> R.drawable.li_calendar }, pal.onTint, pal.tint, 40, 20))
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(4), 0) }
    if (ev == null) col.addView(text(getString(R.string.hm_t_no_more), 15f, pal.text, true))
    else {
        col.addView(text((if (ev.state == "now") getString(R.string.hm_t_now) else getString(R.string.hm_w_next_at, HomeLogic.clock(ev.start))).uppercase(), 11f, pal.accentText, true).apply { letterSpacing = 0.06f })
        val name = when (ev.kind) { "bed" -> getString(R.string.hm_t_bed); "group" -> getString(R.string.hm_t_group, ev.name); else -> ev.name.ifBlank { getString(R.string.hm_t_lock) } }
        col.addView(text(name, 16f, pal.text, true).apply { setPadding(0, dp(3), 0, 0); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
    }
    box.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
    box.addView(ImageView(this).apply { setImageDrawable(Ic.get(this@nextWidget, R.drawable.li_chevron_right, pal.sub, dp(18))); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, LinearLayout.LayoutParams(dp(18), dp(18)))
    kit.tap(box, 22f) { pager.snapTo(0) }
    box.contentDescription = getString(R.string.hm_w_next) + ": " + ((col.getChildAt(col.childCount - 1) as? TextView)?.text ?: "")
    return centred(box)
}

/** "Việc · Ghi chú · File" 2x2: counts from the "Hôm nay" page; a tap opens it. */
internal fun HomeActivity.countsWidget(r: Rules, m: HomeModel): View {
    val (chores, notes, files) = TodayPage.counts(this, r, m)
    val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL; background = card(); setPadding(dp(14), dp(12), dp(14), dp(12)) }
    box.addView(text(getString(R.string.hm_t_title), 14f, pal.text, true).apply { setPadding(0, 0, 0, dp(6)) })
    for ((ic, lab, n) in listOf(Triple(R.drawable.li_list_checks, R.string.hm_w_c_chores, chores), Triple(R.drawable.li_sticky_note, R.string.hm_w_c_notes, notes), Triple(R.drawable.li_file, R.string.hm_w_c_files, files))) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(5), 0, dp(5)) }
        row.addView(ImageView(this).apply { setImageDrawable(Ic.get(this@countsWidget, ic, pal.accentText, dp(16))) }, LinearLayout.LayoutParams(dp(16), dp(16)))
        row.addView(text(getString(lab), 13f, pal.text).apply { setPadding(dp(8), 0, dp(4), 0); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(text(n.toString(), 14f, pal.text, true))
        box.addView(row)
    }
    kit.tap(box, 22f) { pager.snapTo(0) }
    box.contentDescription = "${getString(R.string.hm_w_c_chores)} $chores, ${getString(R.string.hm_w_c_notes)} $notes, ${getString(R.string.hm_w_c_files)} $files"
    return centred(box)
}

// ---- widget picker ----

/** Our widgets with their sizes. Tap a size: placed (or resized when it is on the home already). Hold and drag: drop it anywhere. */
internal fun HomeActivity.openPicker() {
    val m = model ?: return
    val lap = HomeActivity.Lap()
    popup?.dismiss()
    val scrim = FrameLayout(this).apply { setBackgroundColor(Color.argb(if (pal.dark) 140 else 80, 0, 0, 0)); isClickable = true; setOnClickListener { closeOverlay() }; tag = "picker" }
    val sheet = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; isClickable = true; elevation = dpf(12f)
        background = GradientDrawable().apply { setColor(pal.sheet); cornerRadii = floatArrayOf(dpf(28f), dpf(28f), dpf(28f), dpf(28f), 0f, 0f, 0f, 0f) }
        setPadding(dp(20) + insL, dp(10), dp(20) + insR, dp(12) + imeB)
    }
    sheet.addView(View(this).apply { background = fill(pal.stroke) }, LinearLayout.LayoutParams(dp(36), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(12) })
    sheet.addView(text(getString(R.string.hm_w_title), 22f, pal.text, true))
    sheet.addView(text(getString(R.string.hm_w_sub), 14f, pal.sub).apply { setPadding(0, dp(4), 0, dp(6)); includeFontPadding = true })
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val sc = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(list) }
    fun addRow(i: Int, type: String) {
        if (i > 0) list.addView(View(this).apply { setBackgroundColor(pal.stroke) }, LinearLayout.LayoutParams(-1, dp(1)))
        val placed = m.pages.flatten().firstOrNull { it.kind == "widget" && it.type == type }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(12), 0, dp(12)) }
        val pv = preview(type)
        row.addView(pv, LinearLayout.LayoutParams(dp(124), dp(76)))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, 0, 0) }
        col.addView(text(widgetLabel(type), 16f, pal.text, true))
        col.addView(text(widgetDesc(type), 13f, pal.sub).apply { setPadding(0, dp(2), 0, dp(6)); includeFontPadding = true })
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val sizes = HomeLayout.SIZES[type].orEmpty().map { (w, h) -> minOf(w, m.cols) to h }.distinct()
        for ((w, h) in sizes) {
            val cur = placed != null && placed.w == w && placed.h == h
            chips.addView(kit.chip(if (cur) getString(R.string.hm_w_on_home, w, h) else "$w×$h", if (cur) pal.onSolid else pal.onTint, if (cur) pal.solid else pal.tint, if (cur) R.drawable.li_check else null).apply {
                kit.tap(this, 999f) { if (!cur) pickSize(type, w, h, placed) }
                contentDescription = if (cur) getString(R.string.hm_w_on_home, w, h) else getString(R.string.hm_w_add_size, widgetLabel(type), w, h)
            }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6) })
        }
        col.addView(chips)
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        // hold the row to drag a new widget in (only one of each kind: a placed one is moved on the home itself)
        if (placed == null) arm(row, {}, { DragSt(Src.NewWidget(type, sizes[0].first, sizes[0].second), null, "widget", sizes[0].first, sizes[0].second, null, pv) }, immediate = true, dragView = pv)
        kit.tap(row, 16f) { if (placed == null) pickSize(type, sizes[0].first, sizes[0].second, null) else toast(getString(R.string.hm_w_already)) }
        list.addView(row, LinearLayout.LayoutParams(-1, -2))
    }
    // the first rows fill the sheet; the rest (below the fold) are built on the next frames, so the sheet slides up at once
    val types = HomeLayout.SIZES.keys.toList()
    val first = 4
    for (i in 0 until minOf(first, types.size)) addRow(i, types[i])
    lap("rows")
    fun more(from: Int) { if (from >= types.size) { if (overlay === scrim) awPickerSection(list) { overlay === scrim }; return }; if (overlay !== scrim) return; for (i in from until minOf(from + 3, types.size)) addRow(i, types[i]); list.post { more(from + 3) } }
    list.post { more(first) }
    sheet.addView(sc, LinearLayout.LayoutParams(-1, -2))
    scrim.addView(sheet, FrameLayout.LayoutParams(-1, (root.height * 0.62f).toInt(), Gravity.BOTTOM))
    showOverlay(scrim); lap("show")
    sheet.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener { override fun onPreDraw(): Boolean { sheet.viewTreeObserver.removeOnPreDrawListener(this); lap("layout"); lap.done("picker"); return true } })
    sheet.translationY = dpf(80f); sheet.alpha = 0f
    sheet.animate().translationY(0f).alpha(1f).setDuration(180).start()
}

internal fun HomeActivity.pickSize(type: String, w: Int, h: Int, placed: HItem?) {
    val m = model ?: return
    val hp = (pager.page - 1).coerceAtLeast(0)
    val n = if (placed != null) HomeLayout.resizeWidget(m, placed.id, w, h) else HomeLayout.placeWidget(m, type, w, h, hp)
    if (n == null) { noRoom(null); return }
    closeOverlay()
    update(n)
    HomeLayout.find(n, placed?.id ?: n.pages.flatten().first { it.kind == "widget" && it.type == type }.id)?.first?.let { pi -> pager.post { pager.snapTo(pi + 1) } }
    toast(getString(R.string.hm_w_added, widgetLabel(type)))
}

/** A miniature on a tint block: a sheet card with the widget's main glyph and line (all tested pairs). */
internal fun HomeActivity.preview(type: String): View {
    val box = FrameLayout(this).apply { background = fill(pal.tint, 16f); setPadding(dp(8), dp(8), dp(8), dp(8)); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
    val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; background = fill(pal.sheet, 10f); setPadding(dp(8), dp(4), dp(8), dp(4)) }
    when (type) {
        "time" -> {
            c.addView(text(dur(65 * 60_000L), 13f, pal.accentText, true))
            c.addView(View(this).apply { background = fill(pal.solid, 2f) }, LinearLayout.LayoutParams(dp(60), dp(4)).apply { topMargin = dp(5) })
        }
        "quick" -> {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            for ((ic, f, g) in listOf(Triple(R.drawable.li_siren, pal.danger, pal.onDanger), Triple(R.drawable.li_map_pin, pal.tint, pal.onTint), Triple(R.drawable.li_car, pal.tint, pal.onTint), Triple(R.drawable.li_timer, pal.solid, pal.onSolid)))
                r.addView(kit.badge(ic, g, f, 20, 12), LinearLayout.LayoutParams(dp(20), dp(20)).apply { setMargins(dp(2), 0, dp(2), 0) })
            c.addView(r)
        }
        "next" -> {
            c.addView(text(HomeLogic.clock(17 * 60), 10f, pal.sub))
            c.addView(text(getString(R.string.hm_t_next), 12f, pal.text, true).apply { Ic.lead(this, R.drawable.li_calendar, pal.accentText); compoundDrawablePadding = dp(4) })
        }
        "clock" -> {
            c.addView(text(HomeLogic.clock(lastView?.minute ?: 980), 22f, pal.text, true))
            c.addView(text(SimpleDateFormat("EEE d/M", resources.configuration.locales[0]).format(Date()), 10f, pal.sub))
        }
        "analog" -> analogPreview(c)
        "month" -> monthPreview(c)
        "battery" -> batteryPreview(c)
        "todo" -> todoPreview(c)
        "note" -> notePreview(c)
        "usage" -> usagePreview(c)
        "when" -> whenPreview(c)
        "earn" -> earnPreview(c)
        "msg" -> msgPreview(c)
        "class" -> classPreview(c)
        else -> for ((ic, lab) in listOf(R.drawable.li_list_checks to R.string.hm_w_c_chores, R.drawable.li_sticky_note to R.string.hm_w_c_notes, R.drawable.li_file to R.string.hm_w_c_files))
            c.addView(text(getString(lab), 10f, pal.text).apply { Ic.lead(this, ic, pal.accentText); compoundDrawablePadding = dp(4); maxLines = 1 })
    }
    box.addView(c, FrameLayout.LayoutParams(-1, -1))
    return box
}
