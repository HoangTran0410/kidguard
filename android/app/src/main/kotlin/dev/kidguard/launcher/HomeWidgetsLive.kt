package dev.kidguard

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
    card.addView(face, LinearLayout.LayoutParams(dp(112), dp(112)).apply { gravity = Gravity.CENTER_HORIZONTAL })
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
    return centred(card).also { wOpen(card) }
}

internal fun HomeActivity.monthWidget(dv: View0, w: Int, h: Int): View {
    val card = wCard().apply { setPadding(dp(6), dp(6), dp(6), dp(6)) }
    val grid = MonthGrid(this, pal.text, pal.sub, pal.solid, pal.onSolid, model?.style?.font?.toFloat() ?: 1f).apply { day = dv.now; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
    card.addView(grid, LinearLayout.LayoutParams(-1, dp(if (h >= 2) 170 else 120)))
    val fmt = SimpleDateFormat("EEEE, d MMMM yyyy", resources.configuration.locales[0])
    card.contentDescription = fmt.format(Date(dv.now))
    tickers += { v -> grid.day = v.now; card.contentDescription = fmt.format(Date(v.now)) }
    return centred(card).also { wOpen(card) }
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
    card.addView(glyph, LinearLayout.LayoutParams(dp(40), dp(22)).apply { rightMargin = dp(12) })
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
    val value = text("${b0.pct}%", 22f, if (b0.pct < 20 && !b0.charging) pal.warnText else pal.accentText, true).apply { maxLines = 1 }
    val line = wSub(batLine(b0))
    col.addView(value); col.addView(line)
    card.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
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
    c.addView(AnalogFace(this, pal.tint, pal.sub, pal.text, pal.accentText).apply { minute = 10 * 60 + 10 }, LinearLayout.LayoutParams(dp(40), dp(40)))
}

internal fun HomeActivity.monthPreview(c: LinearLayout) {
    c.gravity = Gravity.CENTER
    c.addView(MonthGrid(this, pal.text, pal.sub, pal.solid, pal.onSolid, 1f), LinearLayout.LayoutParams(-1, dp(52)))
}

internal fun HomeActivity.batteryPreview(c: LinearLayout) {
    c.gravity = Gravity.CENTER
    c.addView(BatteryGlyph(this, pal.sub, pal.solid, pal.warn).apply { level = 70 }, LinearLayout.LayoutParams(dp(32), dp(18)))
    c.addView(text("70%", 12f, pal.accentText, true).apply { setPadding(0, dp(3), 0, 0); gravity = Gravity.CENTER })
}
