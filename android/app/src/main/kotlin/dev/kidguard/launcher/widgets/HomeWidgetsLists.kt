package dev.kidguard

import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import dev.kidguard.HomeActivity.View0
import dev.kidguard.HomeActivity.AppE
import java.text.SimpleDateFormat
import java.util.Date

// List widgets: homework, latest note, apps used today. Design system: HomeWidgets.kt (wCard, wHead...).

private val noteFmt by lazy(LazyThreadSafetyMode.NONE) { SimpleDateFormat("d/M HH:mm", java.util.Locale.getDefault()) }

/** Homework: up to 3 rows, open first; a tap on a row ticks it (update() saves and re-renders). */
internal fun HomeActivity.todoWidget(m: HomeModel, w: Int, h: Int): View {
    val all = KidNotes.todos(m.todo)
    val card = wCard()
    val done = all.count { it.done }
    card.addView(wHead(R.drawable.li_list_checks, getString(R.string.hm_w_todo), if (all.isEmpty()) null else "$done/${all.size}"))
    if (all.isEmpty()) {
        card.addView(wEmpty(getString(R.string.hm_w_todo_empty)))
        wOpen(card)
        card.contentDescription = getString(R.string.hm_w_todo)
        return centred(card)
    }
    val rows = all.sortedBy { it.done }.take(3)
    for (td in rows) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(4), 0, dp(4)) }
        row.addView(ImageView(this).apply {
            if (td.done) { setImageDrawable(Ic.get(this@todoWidget, R.drawable.li_check, pal.onSolid, dp(14))); background = fill(pal.solid, 6f) }
            else background = GradientDrawable().apply { setColor(pal.card); cornerRadius = dpf(6f); setStroke(dp(2), pal.sub) }
            setPadding(dp(3), dp(3), dp(3), dp(3)); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { rightMargin = dp(10) })
        row.addView(text(td.text, 14f, if (td.done) pal.sub else pal.text, !td.done).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            if (td.done) paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        }, LinearLayout.LayoutParams(0, -2, 1f))
        row.contentDescription = td.text
        kit.tap(row, 12f) { model?.let { mm -> update(mm.copy(todo = KidNotes.todosJson(KidNotes.toggle(KidNotes.todos(mm.todo), td.id)))) } }
        card.addView(row, LinearLayout.LayoutParams(-1, -2))
    }
    if (all.size > rows.size) card.addView(wSub(getString(R.string.hm_w_more, all.size - rows.size)).apply { setPadding(0, dp(2), 0, 0) })
    return centred(card)
}

/** Latest note (newest by time). */
internal fun HomeActivity.noteWidget(m: HomeModel, w: Int, h: Int): View {
    val notes = KidNotes.notes(m.notes)
    val card = wCard()
    card.addView(wHead(R.drawable.li_sticky_note, getString(R.string.hm_w_note), if (notes.size > 1) notes.size.toString() else null))
    val n = notes.firstOrNull()
    if (n == null) card.addView(wEmpty(getString(R.string.hm_t_no_notes)))
    else {
        card.addView(text(n.text, 15f, pal.text).apply { maxLines = if (h >= 2) 5 else 4; ellipsize = TextUtils.TruncateAt.END })
        if (n.t > 0) card.addView(wSub(noteFmt.format(Date(n.t))).apply { setPadding(0, dp(4), 0, 0) })
    }
    card.contentDescription = getString(R.string.hm_w_note) + (n?.let { ". " + it.text } ?: "")
    wOpen(card)
    return centred(card)
}

private fun HomeActivity.usageIcon(a: AppE, size: Int): ImageView {
    val bmp = IconCache.get(this, a.pkg, a.cls, dp(size), model?.style?.shape ?: "soft")
    return ImageView(this).apply { bmp?.let { setImageBitmap(it) }; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
}

/** Apps used today, most used first. 4x1: total + 3 icons; 4x2: rows with a proportional bar. */
internal fun HomeActivity.usageWidget(dv: View0, all: Map<String, AppE>, w: Int, h: Int): View {
    val top = dv.usage.entries.filter { it.value >= 60_000 && it.key != packageName && it.key in all }.sortedByDescending { it.value }
    val total = top.sumOf { it.value }
    val title = getString(R.string.hm_w_usage)
    if (top.isEmpty()) {
        val card = wCard()
        card.addView(wHead(R.drawable.li_timer, title))
        card.addView(wEmpty(getString(R.string.hm_w_usage_none)))
        return centred(card)
    }
    if (h < 2) {
        val card = wCard(false)
        val sum = text(dur(total), 20f, pal.accentText, true).apply { maxLines = 1; setPadding(0, dp(2), 0, 0) }
        val row = DropRow(this, sum)
        card.addView(row, LinearLayout.LayoutParams(-1, -2))
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(text(title.uppercase(), 11f, pal.sub, true).apply { letterSpacing = 0.08f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        left.addView(sum)
        row.addView(left, LinearLayout.LayoutParams(0, -2, 1f))
        for (e in top.take(3)) {
            val a = all[e.key] ?: continue
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
            col.addView(usageIcon(a, 28), LinearLayout.LayoutParams(dp(28), dp(28)))
            // short form, never ellipsized ("1 …" said nothing); DropRow drops a column instead when it does not fit
            col.addView(text(durShort(e.value), 12f, pal.sub).apply { maxLines = 1; setPadding(0, dp(2), 0, 0) })
            col.contentDescription = "${a.label}. ${dur(e.value)}"
            row.addView(col, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(10) })
        }
        card.contentDescription = "$title. ${dur(total)}"
        return centred(card)
    }
    val card = wCard()
    card.addView(wHead(R.drawable.li_timer, title, dur(total)))
    val max = top[0].value.toFloat()
    for (e in top.take(4)) {
        val a = all[e.key] ?: continue
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(4), 0, dp(4)) }
        row.addView(usageIcon(a, 24), LinearLayout.LayoutParams(dp(24), dp(24)).apply { rightMargin = dp(10) })
        val mid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        mid.addView(wMain(a.label).apply { textSize = 14f })
        val f = (e.value / max).coerceIn(0.03f, 1f)
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; background = fill(pal.stroke, 2f) }
        bar.addView(View(this).apply { background = fill(pal.solid, 2f) }, LinearLayout.LayoutParams(0, -1, f))
        if (f < 1f) bar.addView(View(this), LinearLayout.LayoutParams(0, -1, 1f - f))
        mid.addView(bar, LinearLayout.LayoutParams(-1, dp(4)).apply { topMargin = dp(3) })
        row.addView(mid, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(wSub(dur(e.value)).apply { setPadding(dp(10), 0, 0, 0) })
        row.contentDescription = "${a.label}. ${dur(e.value)}"
        card.addView(row, LinearLayout.LayoutParams(-1, -2))
    }
    return centred(card)
}

private fun HomeActivity.bar(wDp: Int, color: Int = pal.sub) = View(this).apply { background = fill(color, 2f) }
    .let { v -> v to LinearLayout.LayoutParams(dp(wDp), dp(4)).apply { topMargin = dp(4) } }

internal fun HomeActivity.todoPreview(c: LinearLayout) {
    c.addView(text(getString(R.string.hm_w_todo), 10f, pal.sub, true).apply { Ic.lead(this, R.drawable.li_list_checks, pal.accentText); compoundDrawablePadding = dp(4) })
    for (done in listOf(true, false)) {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        r.addView(View(this).apply { background = if (done) fill(pal.solid, 3f) else GradientDrawable().apply { cornerRadius = dpf(3f); setStroke(dp(1), pal.sub) } }, LinearLayout.LayoutParams(dp(8), dp(8)).apply { rightMargin = dp(4) })
        r.addView(View(this).apply { background = fill(if (done) pal.stroke else pal.sub, 2f) }, LinearLayout.LayoutParams(dp(if (done) 36 else 48), dp(4)))
        c.addView(r, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
    }
}

internal fun HomeActivity.notePreview(c: LinearLayout) {
    c.addView(text(getString(R.string.hm_w_note), 10f, pal.sub, true).apply { Ic.lead(this, R.drawable.li_sticky_note, pal.accentText); compoundDrawablePadding = dp(4) })
    for (wd in listOf(64, 56, 36)) { val (v, lp) = bar(wd); c.addView(v, lp) }
}

internal fun HomeActivity.usagePreview(c: LinearLayout) {
    c.addView(text(getString(R.string.hm_w_usage), 10f, pal.sub, true).apply { Ic.lead(this, R.drawable.li_timer, pal.accentText); compoundDrawablePadding = dp(4); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
    for (wd in listOf(60, 40, 24)) { val (v, lp) = bar(wd, pal.solid); c.addView(v, lp) }
}
