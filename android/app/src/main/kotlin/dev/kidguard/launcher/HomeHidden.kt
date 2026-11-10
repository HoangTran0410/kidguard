package dev.kidguard

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * Drawer ⋮ -> "Hidden apps": every app the drawer leaves out, as a grid with the reason under each (LauncherLogic.hiddenReason).
 * The kid's screen: a tap only tells the reason, nothing opens from here (else it would be a way around the rules).
 * System plumbing (Play services & co.) is one count line, not icons: nothing there to open.
 */
internal fun HomeActivity.hiddenList(): Pair<List<Pair<HomeActivity.AppE, String>>, Int> {
    val s = Store.of(this); val r = s.rules; val dv = decision(r, s)
    val study = focusAllowed(dv)
    var plumbing = 0
    val out = ArrayList<Pair<HomeActivity.AppE, String>>()
    for (a in launcherApps().values) {
        when (val why = LauncherLogic.hiddenReason(a.pkg, dv.want, r, packageName, tsManaged, study)) {
            null -> {}
            "plumbing" -> plumbing++
            else -> out += a to why
        }
    }
    return out.sortedBy { it.first.sortKey } to plumbing
}

private fun HomeActivity.reasonText(why: String): String = getString(when (why) {
    "ts" -> R.string.hm_hid_ts
    "parent" -> R.string.hm_hid_parent
    "blocked" -> R.string.hm_hid_blocked
    "new" -> R.string.hm_hid_new
    else -> R.string.hm_hid_study
})

internal fun HomeActivity.reasonLong(why: String): String = getString(when (why) {
    "ts" -> R.string.hm_hid_ts_l
    "parent" -> R.string.hm_hid_parent_l
    "blocked" -> R.string.hm_hid_blocked_l
    "new" -> R.string.hm_hid_new_l
    else -> R.string.hm_hid_study_l
})

internal fun HomeActivity.hiddenScreen() {
    val (apps, plumbing) = hiddenList()
    val panel = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setBackgroundColor(pal.bg); isClickable = true
        setPadding(insL, insT, insR, 0)
    }
    // top bar: back + title with the count
    val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(6), dp(8), dp(16), dp(8)) }
    bar.addView(ImageView(this).apply {
        setImageDrawable(Ic.get(this@hiddenScreen, R.drawable.li_arrow_left, pal.text, dp(22))); setPadding(dp(11), dp(11), dp(11), dp(11))
        contentDescription = getString(R.string.hm_c_back); kit.tap(this, -1f) { closeOverlay() }
    }, LinearLayout.LayoutParams(dp(44), dp(44)))
    bar.addView(text(getString(R.string.hm_hidden_t, apps.size), 20f, pal.text, true).apply { setPadding(dp(6), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
    panel.addView(bar, LinearLayout.LayoutParams(-1, -2))
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(4), dp(8), dp(24) + insB) }
    if (apps.isEmpty()) list.addView(text(getString(R.string.hm_hidden_none), 15f, pal.sub).apply { gravity = Gravity.CENTER; setPadding(dp(16), dp(40), dp(16), dp(16)) }, LinearLayout.LayoutParams(-1, -2))
    val cols = (model?.cols ?: 4).coerceIn(3, 5)
    for (rowApps in apps.chunked(cols)) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, dp(6)) }
        for ((a, why) in rowApps) row.addView(hiddenCell(a, why), LinearLayout.LayoutParams(0, -2, 1f))
        while (row.childCount < cols) row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        list.addView(row, LinearLayout.LayoutParams(-1, -2))
    }
    if (plumbing > 0) list.addView(text(resources.getQuantityString(R.plurals.hm_hidden_sys, plumbing, plumbing), 13f, pal.sub).apply {
        gravity = Gravity.CENTER; setPadding(dp(16), dp(20), dp(16), dp(8))
    }, LinearLayout.LayoutParams(-1, -2))
    panel.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
    showOverlay(panel)
    announce(getString(R.string.hm_hidden_t, apps.size))
}

/** Icon greyed (like a paused app), label, reason in small type under it; a tap says the reason in full. */
private fun HomeActivity.hiddenCell(a: HomeActivity.AppE, why: String): View {
    val px = dp(52)
    val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(2), dp(4), dp(2), dp(4)) }
    val round = model?.style?.shape != "square"
    var bmp = IconCache.peek(a.pkg, a.cls, iconPx(), round)
    val dst = RectF(0f, 0f, px.toFloat(), px.toFloat())
    box.addView(object : View(this) {
        override fun onDraw(c: Canvas) {
            val b = bmp ?: return
            val p = IconCache.greyPaint(); p.alpha = 115
            c.drawBitmap(b, null, dst, p)
        }
    }.also { v -> if (bmp == null) IconCache.async(this, a.pkg, a.cls, iconPx(), round) { b -> if (b != null) { bmp = b; v.invalidate() } } },
        LinearLayout.LayoutParams(px, px))
    box.addView(text(a.label, 12f, pal.text).apply { gravity = Gravity.CENTER; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(6), 0, 0) },
        LinearLayout.LayoutParams(-1, -2))
    val reason = reasonText(why)
    box.addView(text(reason, 11f, pal.sub).apply { gravity = Gravity.CENTER; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END },
        LinearLayout.LayoutParams(-1, -2))
    box.contentDescription = a.label + ": " + reason
    kit.tap(box, 12f) { toast(a.label + ": " + reasonLong(why)) }
    return box
}

/**
 * A widget's "open the real thing" (clock -> the clock app, month -> the calendar), through the same rules as its icon:
 * hidden -> the reason (toast), paused -> the "taking a break" sheet, else launched from [from]. Was: every widget tap
 * went to the "Hôm nay" page, which shows nothing about the time or the month.
 */
internal fun HomeActivity.openRelated(intent: android.content.Intent, from: View) {
    if (!debounce(intent.action + intent.categories)) return
    val all = launcherApps().values
    val pkgs = runCatching { packageManager.queryIntentActivities(intent, 0).map { it.activityInfo.packageName } }.getOrDefault(emptyList())
    val a = pkgs.firstNotNullOfOrNull { p -> all.firstOrNull { it.pkg == p } }
        ?: return android.widget.Toast.makeText(this, R.string.hm_no_app, android.widget.Toast.LENGTH_SHORT).show()
    val s = Store.of(this); val r = s.rules; val dv = decision(r, s)
    val why = LauncherLogic.hiddenReason(a.pkg, dv.want, r, packageName, tsManaged, focusAllowed(dv))
    if (why != null && why != "plumbing") return android.widget.Toast.makeText(this, a.label + ": " + reasonLong(why), android.widget.Toast.LENGTH_LONG).show()
    val reason = LauncherLogic.pauseReason(a.pkg, dv.want, settingsOk(r))
    if (reason != null) pausedSheet(a, reason, dv, r) else launch(a, from)
}

internal fun clockIntent() = android.content.Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS)
internal fun calendarIntent() = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_APP_CALENDAR)
