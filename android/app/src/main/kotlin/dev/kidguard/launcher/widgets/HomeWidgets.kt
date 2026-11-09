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
    val sizes = if (it.type == HomeLayout.APP) awSizes(it) else HomeLayout.SIZES[it.type].orEmpty().map { (w, h) -> HomeLayout.span(w, model?.cols ?: w) to h }.distinct()
    val showMenu = {
        // sizes as one row of chips under a "Cỡ" header (was one menu row per size)
        val chips = sizes.map { (w, h) ->
            MenuChip("$w×$h", w == it.w && h == it.h) { model?.let { mm -> HomeLayout.resizeWidget(mm, it.id, w, h)?.let { n -> update(n) } ?: noRoom(null) } }
        }
        menu(listOf(
            Row(R.drawable.li_circle_minus, getString(R.string.hm_unpin), true) { model?.let { mm -> HomeLayout.take(mm, Src.Item(it.id))?.let { t -> update(HomeLayout.dropRemove(t)) } } },
            Row(R.drawable.li_layout_dashboard, getString(R.string.hm_edit_home), true) { enterEdit(it.id) },
        ), v, head = if (chips.size > 1) MenuHead(getString(R.string.hm_w_size_h), chips = chips) else null)
    }
    val src = { DragSt(Src.Item(it.id), null, "widget", it.w, it.h, it.id, v) }
    fun walk(x: View) {
        // a tappable card (not its small buttons) sinks like an app icon does, so every tap target answers the same way
        if (x === v || (x.isClickable && v !is KgHostView)) arm(x, showMenu, src, dragView = v, press = if (x.isClickable && x is ViewGroup) x else null)
        if (x is ViewGroup && v !is KgHostView) for (i in 0 until x.childCount) walk(x.getChildAt(i))
    }
    walk(v)
    v.isLongClickable = true
}

internal fun HomeActivity.widgetLabel(type: String) = getString(when (type) {
    "time" -> R.string.hm_w_time; "quick" -> R.string.hm_w_quick; "next" -> R.string.hm_w_next; "clock" -> R.string.hm_w_clock
    "analog" -> R.string.hm_w_analog; "month" -> R.string.hm_w_month; "battery" -> R.string.hm_w_battery; "lock" -> R.string.q_lock
    "todo" -> R.string.hm_w_todo; "note" -> R.string.hm_w_note; "usage" -> R.string.hm_w_usage
    "when" -> R.string.hm_w_when; "earn" -> R.string.hm_w_earn; "msg" -> R.string.hm_w_msg; "class" -> R.string.hm_w_class; "timer" -> R.string.hm_w_timer; "streak" -> R.string.hm_w_streak; "media" -> R.string.hm_w_media; else -> R.string.hm_w_counts
})

internal fun HomeActivity.widgetDesc(type: String) = getString(when (type) {
    "time" -> R.string.hm_w_time_d; "quick" -> R.string.hm_w_quick_d; "next" -> R.string.hm_w_next_d; "clock" -> R.string.hm_w_clock_d
    "analog" -> R.string.hm_w_analog_d; "month" -> R.string.hm_w_month_d; "battery" -> R.string.hm_w_battery_d; "lock" -> R.string.hm_w_lock_d
    "todo" -> R.string.hm_w_todo_d; "note" -> R.string.hm_w_note_d; "usage" -> R.string.hm_w_usage_d
    "when" -> R.string.hm_w_when_d; "earn" -> R.string.hm_w_earn_d; "msg" -> R.string.hm_w_msg_d; "class" -> R.string.hm_w_class_d; "timer" -> R.string.hm_w_timer_d; "streak" -> R.string.hm_w_streak_d; "media" -> R.string.hm_w_media_d; else -> R.string.hm_w_counts_d
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
    today?.tick(dv.minute)
}

internal fun HomeActivity.clockWidget(r: Rules, dv: View0, w: Int, h: Int, hideNext: Boolean): View {
    val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL or Gravity.START; setPadding(dp(14), dp(4), dp(8), dp(4)) }
    val big = when (preset.clock) { "big" -> 60f; "thin" -> 46f; else -> 54f } * (if (w <= 3 && preset.cols == 5) 0.85f else 1f) * (if (h >= 2) 1.4f else 1f)
    // one line, scaled down to the cell (FitText): a 3-of-5-cell clock at 2 rows (75 sp) broke into "00:2 / 0" (owner's POCO)
    val t = FitText(this, big, 0.4f).apply { text = HomeLogic.clock(dv.minute); setTextColor(Color.WHITE) }.apply {
        typeface = Typeface.create(if (preset.clock == "thin") "sans-serif-light" else "sans-serif", Typeface.NORMAL); setPadding(dp(6), 0, dp(6), 0)
    }
    box.addView(t, LinearLayout.LayoutParams(-2, -2))
    val date = SimpleDateFormat("EEEE, d MMMM", resources.configuration.locales[0]).format(Date(dv.now)).replaceFirstChar { it.titlecase() }
    val d = FitText(this, if (h >= 2) 17f else 15f, 0.75f).apply {
        full = date; text = date; short = SimpleDateFormat("EEE, d/M", resources.configuration.locales[0]).format(Date(dv.now)).replaceFirstChar { it.titlecase() }; setTextColor(Color.WHITE); typeface = HomeActivity.tf(true); setPadding(dp(6), dp(2), dp(6), dp(2)) }
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

/**
 * One-line text that shrinks (down to [minScale] of [sp]) to fit the width its parent offers, instead of wrapping or
 * being cut. Re-measured on every layout, so a wider cell (more columns -> fewer, or a resize) gets the full size back.
 */
internal class FitText(c: android.content.Context, private val sp: Float, private val minScale: Float) : TextView(c) {
    /** Shorter wording used when [full] would need more than [minScale] (a date: "Th 3, 10/11"). */
    var full: String = ""; var short: String? = null
    private val probe = android.graphics.Paint()
    init { setSingleLine(); includeFontPadding = false; setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, sp) }
    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val px = sp * resources.displayMetrics.scaledDensity
        var target = px
        if (MeasureSpec.getMode(wSpec) != MeasureSpec.UNSPECIFIED) {
            val avail = MeasureSpec.getSize(wSpec) - paddingLeft - paddingRight
            probe.set(paint); probe.textSize = px
            val f = full.ifEmpty { text.toString() }
            var tw = probe.measureText(f)
            val use = if (short != null && avail > 0 && tw * minScale > avail) short!! else f
            if (use !== f) tw = probe.measureText(use)
            if (text.toString() != use) text = use
            if (tw > avail && avail > 0) target = px * maxOf(minScale, avail / tw * 0.98f)
        }
        // only when it changes: setTextSize requests a layout, and the target is stable for a given width (no loop)
        if (Math.abs(textSize - target) > 0.5f) setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, target)
        super.onMeasure(wSpec, hSpec)
    }
}

/** Short form for tight spots (under an icon): "1g 20p" / "1h 20m". */
internal fun HomeActivity.durShort(ms: Long) = HomeLogic.hm(ms).let { (h, m) -> if (h == 0) getString(R.string.home_m_s, m) else if (m == 0) getString(R.string.home_h_s, h) else getString(R.string.home_hm_s, h, m) }

/**
 * Horizontal row whose first child takes the rest (weight 1) and must fit whole: trailing children are dropped (GONE)
 * until it does. The 4x1 usage card on a 5-column grid cut every time to "1 …" (owner's POCO).
 */
internal class DropRow(c: android.content.Context, private val must: android.widget.TextView) : LinearLayout(c) {
    init { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    override fun onMeasure(w: Int, h: Int) {
        for (i in 1 until childCount) getChildAt(i).visibility = View.VISIBLE
        val need = kotlin.math.ceil(must.paint.measureText(must.text.toString())).toInt() + must.paddingLeft + must.paddingRight
        super.onMeasure(w, h)
        var last = childCount - 1
        while (last >= 1 && must.measuredWidth < need) { getChildAt(last--).visibility = View.GONE; super.onMeasure(w, h) }
    }
}

/**
 * Centred horizontal group that never overflows its parent: when its natural width does not fit, [drop] is hidden, then
 * [shrink]'s text is scaled down (to 60%). Used by the battery card.
 */
internal class FitRow(c: android.content.Context, private val drop: View) : LinearLayout(c) {
    var shrink: android.widget.TextView? = null
    private var baseSize = 0f
    init { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    override fun onMeasure(w: Int, h: Int) {
        val avail = MeasureSpec.getSize(w)
        val t = shrink
        if (t != null && baseSize == 0f) baseSize = t.textSize
        if (t != null && t.textSize != baseSize) t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, baseSize)
        drop.visibility = View.VISIBLE
        if (MeasureSpec.getMode(w) != MeasureSpec.UNSPECIFIED && t != null) {
            // decided on the number alone: the status line under it wraps into whatever width is left
            val lp = drop.layoutParams as MarginLayoutParams
            val g = lp.width + lp.leftMargin + lp.rightMargin
            val tw = t.paint.measureText(t.text.toString()) + t.paddingLeft + t.paddingRight + paddingLeft + paddingRight
            if (g + tw > avail) drop.visibility = View.GONE
            if (tw > avail) t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, (baseSize * avail / tw).coerceAtLeast(baseSize * 0.6f))
        }
        super.onMeasure(w, h)
    }
}

internal fun HomeActivity.dur(ms: Long) = HomeLogic.hm(ms).let { (h, m) -> if (h == 0) getString(R.string.home_m, m) else if (m == 0) getString(R.string.home_h, h) else getString(R.string.home_hm, h, m) }

/** The card fills its cells (content centred in it); taller content is scaled down to fit ([FitCell]). */
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
        // shorter than its cells: the card grows to fill them (POCO / One UI: a widget's background is its whole area, so a
        // 2×2 clock and a 2×2 calendar side by side are the same size); its content stays centred (wCard's gravity)
        if (ch * s < hs) child.measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec((hs / s).toInt(), MeasureSpec.EXACTLY))
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
        orientation = LinearLayout.VERTICAL; isClickable = true; elevation = shadow(12f)
        background = GradientDrawable().apply { setColor(pal.sheet); cornerRadii = floatArrayOf(dpf(28f), dpf(28f), dpf(28f), dpf(28f), 0f, 0f, 0f, 0f) }
        setPadding(dp(20) + insL, dp(10), dp(20) + insR, dp(12) + imeB)
    }
    sheet.addView(View(this).apply { background = fill(pal.stroke) }, LinearLayout.LayoutParams(dp(36), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(12) })
    sheet.addView(text(getString(R.string.hm_w_title), 22f, pal.text, true))
    // search (POCO's picker has one on top): our widgets by name / description, other apps' by app or widget name
    val search = android.widget.EditText(this)
    val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = fill(pickSurf(), 22f); setPadding(dp(14), 0, dp(6), 0) }
    bar.addView(ImageView(this).apply { setImageDrawable(Ic.get(context, R.drawable.li_search, pal.sub, dp(18))); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, LinearLayout.LayoutParams(dp(18), dp(18)))
    search.apply {
        hint = getString(R.string.hm_w_search); setHintTextColor(pal.sub); setTextColor(pal.text); background = null; setSingleLine()
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f); imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH; setPadding(dp(10), dp(11), dp(10), dp(11))
    }
    bar.addView(search, LinearLayout.LayoutParams(0, -2, 1f))
    val clear = ImageView(this).apply {
        setImageDrawable(Ic.get(context, R.drawable.li_x, pal.sub, dp(18))); setPadding(dp(10), dp(10), dp(10), dp(10)); contentDescription = getString(R.string.close); visibility = View.GONE
        kit.tap(this, -1f) { search.setText("") }
    }
    bar.addView(clear, LinearLayout.LayoutParams(dp(40), dp(40)))
    sheet.addView(bar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8); bottomMargin = dp(4) })
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(8)) }
    val sc = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(list) }
    // KidGuard's own widgets as ONE app card, like every other app's: a 2-column grid, one tile per widget (not per size:
    // one preview each, two sizes of the same widget side by side read as duplicates), its sizes as small pills under the
    // name. Tap the tile = its first size, tap a pill = that size; the size on the home is the filled pill. Hold the preview
    // to drag the widget onto the home (only one of each kind: a placed one is resized from here instead).
    val types = HomeLayout.SIZES.keys.filter { Flavor.widget(it) }
    val sq = m.style.shape != "square"
    var kg: LinearLayout? = null
    var kgRow: LinearLayout? = null
    var kgN = 0
    fun kgCard(): LinearLayout = kg ?: LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = fill(pickSurf(), 22f); setPadding(dp(14), dp(12), dp(14), dp(10)) }.also { c ->
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, dp(8)) }
        head.addView(ImageView(this).apply { setImageBitmap(IconCache.get(this@openPicker, packageName, "", dp(26), sq)) }, LinearLayout.LayoutParams(dp(26), dp(26)))
        head.addView(text("KidGuard", 15f, pal.text, true).apply { setPadding(dp(10), 0, 0, 0) })
        c.addView(head)
        list.addView(c, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        kg = c
    }
    fun addCard(type: String) {
        val card = kgCard()
        val placed = m.pages.flatten().firstOrNull { it.kind == "widget" && it.type == type }
        val sizes = HomeLayout.SIZES[type].orEmpty().map { (w, h) -> HomeLayout.span(w, m.cols) to h }.distinct()
        val (w0, h0) = sizes.first()
        val tile = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(4), dp(6), dp(4), dp(8)) }
        // the first size's preview, fitted into a fixed-height slot so every tile lines up (POCO's grid)
        // the plate is the whole slot (every tile the same size, POCO); the mock, without its own plates, is scaled to fill it
        val pv = ScaleBox(this, dp(124), dp(84), maxH = dp(104), pad = dp(8))   // one base the plate's shape: every mock ~100 %, none shrunk to a corner
            .apply { background = fill(pickSurf(), 18f); addView(preview(type).apply { setPadding(0, 0, 0, 0); background = null; (this as? ViewGroup)?.getChildAt(0)?.background = null }) }
        tile.addView(pv, LinearLayout.LayoutParams(-1, -2))
        tile.addView(text(widgetLabel(type), 13f, pal.text, true).apply { gravity = Gravity.CENTER; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(6), 0, dp(6)) }, LinearLayout.LayoutParams(-1, -2))
        val pills = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        for ((w, h) in sizes) {
            val cur = placed != null && placed.w == w && placed.h == h
            pills.addView(kit.chip("$w×$h", if (cur) pal.onSolid else pal.accentText, if (cur) pal.solid else pal.tint).apply {
                // three sizes in a half-width tile: narrower pills, never wrapped ("4×4" broke into 3 lines)
                val px = if (sizes.size >= 3) 8 else 12
                setPadding(dp(px), dp(5), dp(px), dp(5)); maxLines = 1; isSingleLine = true
                contentDescription = if (cur) getString(R.string.hm_w_on_home, w, h) else getString(R.string.hm_w_add_size, widgetLabel(type), w, h)
                kit.tap(this, 999f) { if (cur) toast(getString(R.string.hm_w_already)) else pickSize(type, w, h, placed) }
            }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(if (sizes.size >= 3) 2 else 3); marginEnd = dp(if (sizes.size >= 3) 2 else 3) })
        }
        tile.addView(pills, LinearLayout.LayoutParams(-1, -2))
        val curFirst = placed != null && placed.w == w0 && placed.h == h0
        tile.contentDescription = getString(R.string.hm_w_add_size, widgetLabel(type), w0, h0)
        kit.tap(tile, 18f) { if (curFirst) toast(getString(R.string.hm_w_already)) else pickSize(type, w0, h0, placed) }
        if (placed == null) arm(tile, {}, { DragSt(Src.NewWidget(type, w0, h0), null, "widget", w0, h0, null, pv) }, immediate = true, dragView = pv)
        val row = kgRow?.takeIf { kgN % 2 == 1 } ?: LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; weightSum = 2f }.also { card.addView(it, LinearLayout.LayoutParams(-1, -2)); kgRow = it }
        row.addView(tile, LinearLayout.LayoutParams(0, -2, 1f))
        kgN++
    }
    // Lazy: the cards that fill the sheet are built now; the rest after the slide-up (no work under the animation), one a
    // frame. Other apps' widgets (75 apps on a Poco) load only once the list is scrolled near its end, or for a search.
    var gen = 0
    var awOn = false
    var shown = emptyList<String>(); var built = 0
    var query = ""
    fun aw() { if (!awOn && overlay === scrim) { awOn = true; val g = gen; awPickerSection(list, query) { overlay === scrim && gen == g } } }
    fun more(g: Int) {
        if (overlay !== scrim || g != gen) return
        if (built >= shown.size) { if (query.isNotEmpty() || list.height <= sc.height) aw(); return }
        // a card costs ~20 ms on a slow phone (more than a frame): one card a frame was ~17 janky frames in a row, so the
        // rest are built in two lumps (one janky frame each) once the slide-up has settled
        val upTo = if (built < 8) minOf(8, shown.size) else shown.size
        while (built < upTo) addCard(shown[built++])
        list.postDelayed({ more(g) }, 120)
    }
    fun refill(first: Int, delay: Long) {
        val g = ++gen; awOn = false; list.removeAllViews(); sc.scrollTo(0, 0); kg = null; kgRow = null; kgN = 0
        val fq = LauncherLogic.fold(query).trim()
        shown = if (fq.isEmpty()) types else types.filter { LauncherLogic.fold(widgetLabel(it) + " " + widgetDesc(it)).contains(fq) }
        built = 0
        while (built < minOf(first, shown.size)) addCard(shown[built++])
        if (delay > 0) list.postDelayed({ more(g) }, delay) else list.postOnAnimation { more(g) }
    }
    refill(0, 200)   // nothing now (a row is ~25 ms on an Oppo A5s, before the first frame); the cards come in two lumps after the slide-up
    lap("rows")
    sc.setOnScrollChangeListener { _, _, y, _, _ -> if (built >= shown.size && y + sc.height * 2 > list.height) aw() }
    val typed = Runnable { if (overlay === scrim) refill(6, 0) }   // closed within 180 ms of a keystroke: build nothing
    search.addTextChangedListener(object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(e: android.text.Editable?) {
            query = e?.toString().orEmpty(); clear.visibility = if (query.isEmpty()) View.GONE else View.VISIBLE
            list.removeCallbacks(typed); list.postDelayed(typed, 180)
        }
    })
    sheet.addView(sc, LinearLayout.LayoutParams(-1, -2))
    scrim.addView(sheet, FrameLayout.LayoutParams(-1, (root.height * 0.8f).toInt(), Gravity.BOTTOM))
    showOverlay(scrim); lap("show")
    sheet.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener { override fun onPreDraw(): Boolean { sheet.viewTreeObserver.removeOnPreDrawListener(this); lap("layout"); lap.done("picker"); return true } })
    sheet.translationY = dpf(80f); sheet.alpha = 0f
    Motion.enter(sheet, ease = Motion.STD, layer = true)
}

/** The keyboard (the picker's search) lifts the sheet's content above it and grows the sheet to the top, so the results
 *  are not left in a sliver between the search field and the keyboard. */
internal fun HomeActivity.pickerIme() {
    val sheet = (overlay?.takeIf { it.tag == "picker" } as? ViewGroup)?.getChildAt(0) ?: return
    sheet.setPadding(sheet.paddingLeft, sheet.paddingTop, sheet.paddingRight, dp(12) + imeB)
    val up = imeB > insB + dp(40)
    (sheet.layoutParams as? FrameLayout.LayoutParams)?.let { it.height = if (up) root.height - insT - dp(16) else (root.height * 0.8f).toInt(); sheet.layoutParams = it }
}

/** The picker's neutral card (a faint white / black veil over the sheet): POCO's grey plates, no colour cast. */
internal fun HomeActivity.pickSurf(): Int = if (pal.dark) 0x17FFFFFF else 0x0E000000

internal fun HomeActivity.pickSize(type: String, w: Int, h: Int, placed: HItem?) {
    val m = model ?: return
    val hp = (pager.page - 1).coerceIn(0, (m.pages.size - 1).coerceAtLeast(0))   // on the library page: the last real page
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
                r.addView(kit.badge(ic, g, f, 26, 15), LinearLayout.LayoutParams(dp(26), dp(26)).apply { setMargins(dp(2), 0, dp(2), 0) })
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
        "lock" -> lockPreview(c)
        "todo" -> todoPreview(c)
        "note" -> notePreview(c)
        "usage" -> usagePreview(c)
        "when" -> whenPreview(c)
        "earn" -> earnPreview(c)
        "msg" -> msgPreview(c)
        "class" -> classPreview(c)
        "timer" -> timerPreview(c)
        "streak" -> streakPreview(c)
        "media" -> mediaPreview(c)
        else -> for ((ic, lab) in listOf(R.drawable.li_list_checks to R.string.hm_w_c_chores, R.drawable.li_sticky_note to R.string.hm_w_c_notes, R.drawable.li_file to R.string.hm_w_c_files))
            c.addView(text(getString(lab), 10f, pal.text).apply { Ic.lead(this, ic, pal.accentText); compoundDrawablePadding = dp(4); maxLines = 1 })
    }
    box.addView(c, FrameLayout.LayoutParams(-1, -1))
    return box
}

/** Lays its one child out at a fixed base size and scales it to its own width (widget picker previews: the mock stays in
 *  proportion and its text grows with the tile). Height follows the base aspect. */
internal class ScaleBox(c: android.content.Context, private val bw: Int, private val bh: Int, private val maxH: Int = 0, private val pad: Int = 0) : FrameLayout(c) {
    init { clipChildren = true }
    /** Fit inside the width and, with [maxH], a fixed-height slot (centred in it), [pad] in from the edges; up to 140 % (a small mock grows to fill its plate). */
    private fun scale(ws: Int) = ((ws - 2f * pad) / bw).let { if (maxH > 0) minOf(it, (maxH - 2f * pad) / bh) else it }.coerceAtMost(1.4f)
    override fun onMeasure(w: Int, h: Int) {
        val ws = MeasureSpec.getSize(w).takeIf { it > 0 } ?: bw
        getChildAt(0)?.measure(MeasureSpec.makeMeasureSpec(bw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(bh, MeasureSpec.EXACTLY))
        setMeasuredDimension(ws, if (maxH > 0) maxH else (bh * scale(ws)).toInt())
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val ch = getChildAt(0) ?: return
        ch.layout(0, 0, bw, bh); ch.pivotX = 0f; ch.pivotY = 0f
        val s = scale(r - l); ch.scaleX = s; ch.scaleY = s
        ch.translationX = ((r - l) - bw * s) / 2; ch.translationY = ((b - t) - bh * s) / 2
    }
}
