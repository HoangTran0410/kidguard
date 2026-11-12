package dev.kidguard

import android.util.TypedValue

import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import dev.kidguard.HomeActivity.View0
import java.text.SimpleDateFormat
import java.util.Date

// Live widgets: analog clock, month, battery (canvas views in WidgetDraw.kt). Design system: HomeWidgets.kt (wCard, wHead...).

internal fun HomeActivity.analogWidget(dv: View0, w: Int, h: Int): View {
    val card = wCard().apply { gravity = Gravity.CENTER }
    val face = AnalogFace(this, pal.tint, pal.sub, pal.text, pal.accentText).apply { minute = dv.minute; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
    // the face takes all the room the card has (iOS: the dial is the widget), the date line stays under it
    card.addView(face, LinearLayout.LayoutParams(-1, 0, 1f).apply { gravity = Gravity.CENTER_HORIZONTAL })
    card.minimumHeight = dp(if (h >= 2) 150 else 100)
    val fmt = SimpleDateFormat("EEE d/M", resources.configuration.locales[0])
    val date = wSub(fmt.format(Date(dv.now))).apply { gravity = Gravity.CENTER }
    card.addView(date, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
    card.contentDescription = "${HomeLogic.clock(dv.minute)}. ${date.text}"
    tickers += { v ->
        face.minute = v.minute
        val t = fmt.format(Date(v.now))
        if (date.text.toString() != t) date.text = t
        card.contentDescription = "${HomeLogic.clock(v.minute)}. $t"
    }
    return centred(card).also { kit.tap(card, 22f) { openRelated(clockIntent(), card) } }
}

internal fun HomeActivity.monthWidget(dv: View0, w: Int, h: Int): View {
    val card = wCard().apply { setPadding(dp(8), dp(8), dp(8), dp(8)) }
    val grid = MonthGrid(this, pal.text, pal.sub, pal.solid, pal.onSolid, model?.style?.font?.toFloat() ?: 1f, pal.accentText).apply { day = dv.now; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
    card.addView(grid, LinearLayout.LayoutParams(-1, dp(if (h >= 2) 170 else 120)))
    val fmt = SimpleDateFormat("EEEE, d MMMM yyyy", resources.configuration.locales[0])
    card.contentDescription = fmt.format(Date(dv.now))
    tickers += { v -> grid.day = v.now; card.contentDescription = fmt.format(Date(v.now)) }
    return centred(card).also { kit.tap(card, 22f) { openRelated(calendarIntent(), card) } }
}

private class Bat(val pct: Int, val charging: Boolean)

private fun HomeActivity.readBattery(): Bat {
    val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0
    val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100)?.takeIf { it > 0 } ?: 100
    val st = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
    return Bat((level * 100 / scale).coerceIn(0, 100), st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL)
}

private fun HomeActivity.batLine(b: Bat) = getString(when {
    b.charging && b.pct >= 100 -> R.string.hm_w_bat_full
    b.charging -> R.string.hm_w_bat_charging
    b.pct < 20 -> R.string.hm_w_bat_low
    else -> R.string.hm_w_bat_ok
})

internal fun HomeActivity.batteryWidget(w: Int): View {
    val card = wCard(false)
    val b0 = readBattery()
    val glyph = BatteryGlyph(this, pal.sub, pal.solid, pal.warn).apply { level = b0.pct; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
    // half-width cell (2×1) at a big font scale: a smaller glyph and a 2-line status, so "Pin yếu, nhớ sạc nhé" is not cut to "Pin yếu, nh…"
    // one centred group that always fits the card: too narrow (2x1 on a 5-column grid, big font: "100%" was cut on the
    // right) -> the glyph goes first (the % says it all), then the number shrinks
    val row = FitRow(this, glyph)
    card.gravity = Gravity.CENTER
    card.addView(row, LinearLayout.LayoutParams(-2, -2))
    row.addView(glyph, LinearLayout.LayoutParams(dp(30), dp(17)).apply { rightMargin = dp(10) })
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
    val value = text("${b0.pct}%", 22f, if (b0.pct < 20 && !b0.charging) pal.warnText else pal.accentText, true).apply { maxLines = 1 }
    val line = wSub(batLine(b0), 2).apply { setAutoSizeTextTypeUniformWithConfiguration(8, 12, 1, TypedValue.COMPLEX_UNIT_SP); layoutParams = LinearLayout.LayoutParams(-1, dp(32)) }
    col.addView(value); col.addView(line)
    row.addView(col, LinearLayout.LayoutParams(-2, -2))
    row.shrink = value
    card.contentDescription = "${value.text}. ${line.text}"
    tickers += {
        val b = readBattery()
        glyph.level = b.pct
        val vt = "${b.pct}%"; if (value.text.toString() != vt) value.text = vt
        value.setTextColor(if (b.pct < 20 && !b.charging) pal.warnText else pal.accentText)
        val lt = batLine(b); if (line.text.toString() != lt) line.text = lt
        card.contentDescription = "$vt. $lt"
    }
    return centred(card).also { wOpen(card) }
}

internal fun HomeActivity.analogPreview(c: LinearLayout) {
    c.gravity = Gravity.CENTER
    c.addView(AnalogFace(this, pal.tint, pal.sub, pal.text, pal.accentText).apply { minute = 10 * 60 + 10 }, LinearLayout.LayoutParams(dp(72), dp(72)))
}

internal fun HomeActivity.monthPreview(c: LinearLayout) {
    c.gravity = Gravity.CENTER
    c.addView(MonthGrid(this, pal.text, pal.sub, pal.solid, pal.onSolid, 1f), LinearLayout.LayoutParams(-1, dp(74)))
}

internal fun HomeActivity.batteryPreview(c: LinearLayout) {
    c.gravity = Gravity.CENTER
    c.addView(BatteryGlyph(this, pal.sub, pal.solid, pal.warn).apply { level = 70 }, LinearLayout.LayoutParams(dp(32), dp(18)))
    c.addView(text("70%", 12f, pal.accentText, true).apply { setPadding(0, dp(3), 0, 0); gravity = Gravity.CENTER })
}

/** One tap turns the screen off (the same path as the "Khoá màn hình" home-screen widget / quick tile: [Quick.lock]). 1×1 = the round button only. */
internal fun HomeActivity.lockWidget(w: Int): View {
    fun disc(sz: Int) = android.widget.FrameLayout(this).apply {
        background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(pal.solid) }
        addView(android.widget.ImageView(context).apply { setImageDrawable(Ic.get(context, R.drawable.li_lock, pal.onSolid, dp(sz / 2))) },
            android.widget.FrameLayout.LayoutParams(dp(sz / 2), dp(sz / 2), Gravity.CENTER))
    }
    val label = getString(R.string.q_lock)
    if (w <= 1) {
        // laid out exactly like appIcon (same box padding, icon size, shape and label), so it lines up with the app icons around it
        val px = iconPx()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP; setPadding(0, dp(4), 0, dp(2))
            // the name may borrow the neighbours' width (shareLabels): without this it was cut on both sides ("Khoá màn hìn")
            clipChildren = false; clipToPadding = false }
        val shp = model?.style?.shape ?: "soft"
        val tile = android.widget.FrameLayout(this).apply {
            background = android.graphics.drawable.BitmapDrawable(resources, IconCache.plate(px, shp, pal.solid))
            addView(android.widget.ImageView(context).apply { setImageDrawable(Ic.get(context, R.drawable.li_lock, pal.onSolid, px / 2)) },
                android.widget.FrameLayout.LayoutParams(px / 2, px / 2, Gravity.CENTER))
        }
        val frame = android.widget.FrameLayout(this)
        frame.addView(tile, android.widget.FrameLayout.LayoutParams(px, px))
        box.addView(frame, LinearLayout.LayoutParams(px + dp(4), px + dp(2)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        if (model?.style?.labels != false) {
            addLabel(box, iconLabel(label, false, android.graphics.Color.WHITE), 2)
        }
        box.contentDescription = label
        kit.tap(box, 16f, true) { dev.kidguard.Quick.lockOrSay(this) }
        return box
    }
    val card = wCard(false)
    card.addView(disc(40), LinearLayout.LayoutParams(dp(40), dp(40)).apply { rightMargin = dp(12) })
    // shrinks to fit a half-width cell at a big font scale (font 1.6 on the Oppo cut "Khoá màn hình" to "Khoá màn")
    card.addView(text(label, 15f, pal.text, true).apply { maxLines = 2; gravity = Gravity.CENTER_VERTICAL; setAutoSizeTextTypeUniformWithConfiguration(9, 15, 1, TypedValue.COMPLEX_UNIT_SP) }, LinearLayout.LayoutParams(0, dp(44), 1f))
    card.contentDescription = label
    kit.tap(card, 22f) { dev.kidguard.Quick.lockOrSay(this) }
    return centred(card)
}

internal fun HomeActivity.lockPreview(c: LinearLayout) {
    c.gravity = Gravity.CENTER
    c.addView(android.widget.FrameLayout(this).apply {
        background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(pal.solid) }
        addView(android.widget.ImageView(context).apply { setImageDrawable(Ic.get(context, R.drawable.li_lock, pal.onSolid, dp(18))) },
            android.widget.FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
    }, LinearLayout.LayoutParams(dp(36), dp(36)))
}
