package dev.kidguard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import dev.kidguard.HomeActivity.View0

// Planning widgets: "Khi nào được chơi" (when), "Lời nhắn bố mẹ" (msg). Design system: HomeWidgets.kt (wCard, wHead...).
// The phone keeps no message history: a parent `message` is a pop-up (Guard.kt "message" -> MessageActivity) and is not stored.
// What persists is the parent's block note (Rules.blockNote) and the child's pending requests (Store.requests()); the msg widget shows those.

/** Day bar 6:00-22:00 on one canvas: [blocks] in [block] colour over the [free] track, the "now" marker on top. */
internal class DayBar(ctx: Context, private val free: Int, private val block: Int, private val mark: Int, private val label: Int, private val labels: Boolean) : View(ctx) {
    var blocks: List<Pair<Int, Int>> = emptyList()
        set(v) { field = v; invalidate() }
    var minute = -1
        set(v) { if (field != v) { field = v; invalidate() } }
    private val d = ctx.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private fun x(m: Int, w: Float) = (m.coerceIn(FROM, TO) - FROM) / (TO - FROM).toFloat() * w

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val bh = 10 * d
        val top = (if (labels) 4 * d else (height - bh) / 2f).coerceAtLeast(0f)
        rect.set(0f, top, w, top + bh)
        p.style = Paint.Style.FILL; p.color = free
        c.drawRoundRect(rect, bh / 2, bh / 2, p)
        c.save(); c.clipRect(rect)
        p.color = block
        for ((a, b) in blocks) { if (b <= FROM || a >= TO) continue; c.drawRect(x(a, w), top, x(b, w), top + bh, p) }
        c.restore()
        if (minute in FROM..TO) {
            val mx = x(minute, w).coerceIn(3 * d, w - 3 * d)
            p.color = mark; c.drawRoundRect(mx - 2 * d, top - 3 * d, mx + 2 * d, top + bh + 3 * d, 2 * d, 2 * d, p)
        }
        if (labels) {
            p.color = label; p.textSize = 10 * d; p.textAlign = Paint.Align.CENTER
            for (h in listOf(6, 12, 18, 22)) c.drawText(h.toString(), x(h * 60, w).coerceIn(6 * d, w - 6 * d), top + bh + 14 * d, p)
        }
    }

    override fun onMeasure(wSpec: Int, hSpec: Int) = setMeasuredDimension(MeasureSpec.getSize(wSpec), ((if (labels) 34 else 20) * d).toInt())   // d.toInt() cut 2.75 to 2: hour labels clipped

    companion object { const val FROM = 6 * 60; const val TO = 22 * 60 }
}

private class GroupRow(val g: AppGroup, val text: android.widget.TextView, val fill: View, val rest: View)

internal fun HomeActivity.whenWidget(r: Rules, dv: View0, block: String?, w: Int, h: Int): View {
    val big = h >= 2
    val bar = DayBar(this, pal.solid, pal.warn, pal.text, pal.sub, big).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
    val card = wCard()
    if (big) card.addView(wHead(R.drawable.li_clock, getString(R.string.hm_w_when)))
    card.addView(bar, LinearLayout.LayoutParams(-1, -2))
    val line = wMain("", 1).apply { textSize = if (big) 16f else 14f }
    card.addView(line, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
    val next = if (big) wSub("").also { card.addView(it) } else null
    val rows = ArrayList<GroupRow>()
    if (big) {
        for (g in r.groups.filter { it.min > 0 && it.pkgs.isNotEmpty() }.sortedBy { PlanLogic.groupLeftMs(it.min, it.pkgs.sumOf { p -> dv.usage[p] ?: 0L }) }.take(3)) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, 0) }
            val t = wSub("")
            val track = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; background = fill(pal.stroke, 2f) }
            val f = View(this).apply { background = fill(pal.solid, 2f) }; val rest = View(this)
            track.addView(f, LinearLayout.LayoutParams(0, -1, 1f)); track.addView(rest, LinearLayout.LayoutParams(0, -1, 0f))
            row.addView(t); row.addView(track, LinearLayout.LayoutParams(-1, dp(4)).apply { topMargin = dp(3) })
            card.addView(row)
            rows += GroupRow(g, t, f, rest)
        }
    }
    fun update(v: View0, blk: String?) {
        val evs = LauncherLogic.today(r, v.minute, v.dow)
        bar.blocks = PlanLogic.blocks(evs); bar.minute = v.minute
        val re = PlanLogic.reopenAt(evs)
        val txt = when {
            blk != null -> reopenWhen(r, blk, "", v)?.let { getString(R.string.hm_reopen, it) } ?: BasicLogic.reasonText(blk, Store.of(this).tr).replaceFirstChar { it.titlecase() }
            re != null -> getString(R.string.hm_reopen, getString(if (re >= 1440) R.string.hm_tomorrow_at else R.string.hm_today_at, HomeLogic.clock(re % 1440)))
            v.leftMs == 0L -> getString(R.string.hm_out)
            v.leftMs > 0 -> getString(R.string.hm_wp_left, dur(v.leftMs))
            else -> getString(R.string.hm_wp_nolimit)
        }
        if (line.text.toString() != txt) line.text = txt
        line.setTextColor(if (blk != null || re != null) pal.warnText else pal.text)
        var cd = txt
        if (next != null) {
            val ne = LauncherLogic.nextEvent(r, v.minute, v.dow)
            val nt = ne?.let { getString(R.string.hm_wp_next, HomeLogic.clock(it.first), it.second.ifEmpty { getString(R.string.hm_wp_bed) }) } ?: ""
            if (next.text.toString() != nt) next.text = nt
            next.visibility = if (nt.isEmpty()) View.GONE else View.VISIBLE
            if (nt.isNotEmpty()) cd += ". $nt"
        }
        for (gr in rows) {
            val used = gr.g.pkgs.sumOf { v.usage[it] ?: 0L }
            val left = PlanLogic.groupLeftMs(gr.g.min, used)
            val gt = if (left == 0L) getString(R.string.hm_wp_group_out, gr.g.name) else getString(R.string.hm_wp_group_left, gr.g.name, dur(left))
            if (gr.text.text.toString() != gt) gr.text.text = gt
            val fr = PlanLogic.groupFrac(gr.g.min, used)
            (gr.fill.layoutParams as LinearLayout.LayoutParams).weight = fr.coerceAtLeast(0.001f)
            (gr.rest.layoutParams as LinearLayout.LayoutParams).weight = 1f - fr
            gr.fill.requestLayout()
            cd += ". $gt"
        }
        card.contentDescription = cd
    }
    update(dv, block)
    tickers += { v -> update(v, null) }
    return centred(card).also { wOpen(card) }
}

internal fun HomeActivity.msgWidget(s: Store, w: Int, h: Int): View {
    val note = Notes.clean(s.rules.blockNote, Notes.NOTE_MAX)
    val req = s.requests().let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.maxByOrNull { it.optLong("t") } }
    val title = getString(R.string.hm_w_msg)
    val card = wCard()
    val small = h < 2
    if (!small) card.addView(wHead(R.drawable.li_message_circle, title))
    var cd = title
    if (note.isEmpty() && req == null) {
        card.addView(wEmpty(getString(R.string.hm_wp_msg_none)))
        card.contentDescription = "$title. ${getString(R.string.hm_wp_msg_none)}"
        return centred(card).also { wOpen(card) }
    }
    if (note.isNotEmpty()) {
        val from = getString(R.string.hm_wp_msg_from)
        if (small) card.addView(text(from.uppercase(), 11f, pal.sub, true).apply { letterSpacing = 0.08f; maxLines = 1 })
        card.addView(wMain(note, if (small) 2 else 4), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
        if (!small) card.addView(wSub(from), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        cd += ". $from: $note"
    }
    if (req != null) {
        val m = req.optInt("min")
        val ask = if (m > 0) getString(R.string.hm_wp_msg_ask, m) else getString(R.string.hm_wp_msg_ask0)
        val t = getString(R.string.hm_wp_msg_wait, ask + req.optString("note").trim().let { if (it.isEmpty()) "" else " · $it" })
        card.addView(wSub(t, if (small) 1 else 2), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(if (note.isEmpty()) 2 else 6) })
        cd += ". $t"
    }
    card.contentDescription = cd
    return centred(card).also { wOpen(card) }
}

internal fun HomeActivity.whenPreview(c: LinearLayout) {
    c.addView(DayBar(this, pal.solid, pal.warn, pal.text, pal.sub, false).apply { blocks = listOf(8 * 60 to 11 * 60, 21 * 60 to 1440); minute = 14 * 60 }, LinearLayout.LayoutParams(dp(64), dp(20)))
    c.addView(text(getString(R.string.hm_wp_left, dur(65 * 60_000L)), 10f, pal.accentText, true).apply { maxLines = 1 })
}

internal fun HomeActivity.msgPreview(c: LinearLayout) {
    c.addView(text(getString(R.string.hm_wp_msg_from), 10f, pal.sub).apply { Ic.lead(this, R.drawable.li_message_circle, pal.accentText); compoundDrawablePadding = dp(4); maxLines = 1 })
    for (wd in listOf(56, 40)) c.addView(View(this).apply { background = fill(pal.stroke, 2f) }, LinearLayout.LayoutParams(dp(wd), dp(4)).apply { topMargin = dp(4) })
}
