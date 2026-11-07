package dev.kidguard

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import dev.kidguard.HomeLayout.Src
import dev.kidguard.HomeActivity.AppE
import dev.kidguard.HomeActivity.DragSt
import dev.kidguard.HomeActivity.Mode
import dev.kidguard.HomeActivity.View0
import dev.kidguard.HomeActivity.Row

// App and folder icons, launching, the paused-app sheet's helpers (split out of HomeActivity; extension functions on it).

// ---- app icon ----

internal fun HomeActivity.iconPx(): Int = dp((preset.iconDp * (0.8f + 0.1f * ((model?.style?.icon ?: 3) - 1))).toInt())

internal fun HomeActivity.appIcon(a: AppE, paused: Boolean, dv: View0, r: Rules, mode: Mode, item: HItem? = null, folder: HItem? = null, slot: Int = -1): View {
    val list = mode == Mode.DRAWER || mode == Mode.PANEL     // on an opaque surface: pal.text labels, a "còn x phút" line
    val px = if (list) dp(52) else iconPx()
    val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP; setPadding(0, dp(4), 0, dp(2)) }
    val frame = FrameLayout(this)
    val bmp = IconCache.get(this, a.pkg, a.cls, px, model?.style?.shape != "square")
    frame.addView(object : View(this) {
        override fun onDraw(c: Canvas) {
            bmp ?: return
            if (paused) { val p = IconCache.greyPaint(); p.alpha = 115; c.drawBitmap(bmp, 0f, 0f, p) } else c.drawBitmap(bmp, 0f, 0f, null)
        }
    }, FrameLayout.LayoutParams(px, px))
    val reason = dv.want[a.pkg]
    if (paused) {
        // clock badge: warn fill + glyph picked for it (HomeStyle tests both), a ring of the sheet colour keeps it off the grey icon
        val bs = (px * 0.36f).toInt().coerceAtLeast(dp(18))
        frame.addView(ImageView(this).apply {
            setImageDrawable(Ic.get(this@appIcon, R.drawable.li_clock, pal.onWarn, bs))
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(pal.warn); setStroke(dp(2), pal.sheet) }
            val pad = (bs * 0.2f).toInt(); setPadding(pad, pad, pad, pad)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, FrameLayout.LayoutParams(bs, bs, Gravity.TOP or Gravity.END).apply { setMargins(0, -bs / 4, -bs / 4, 0) })
        frame.clipChildren = false
    }
    box.clipChildren = false
    box.addView(frame, LinearLayout.LayoutParams(px + dp(4), px + dp(2)).apply { gravity = Gravity.CENTER_HORIZONTAL })
    val showLabel = list || (model?.style?.labels ?: true)
    if (showLabel) {
        val lbl = text(a.label, if (preset.id == "be" && !list) 14f else 12f, if (list) pal.text else Color.WHITE).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; gravity = Gravity.CENTER; setPadding(dp(4), dp(1), dp(4), dp(1))
        }
        box.addView(lbl, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4); gravity = Gravity.CENTER_HORIZONTAL })
        if (!list) inkViews += lbl
    }
    if (list) {
        val sub = when {
            paused -> reopenShort(r, reason ?: "", a.pkg, dv)
            else -> LauncherLogic.appLeftMs(r, dv.usage, a.pkg).takeIf { it in 0 until 60 * 60_000L }?.let { getString(R.string.hm_min_left, ((it + 59_999) / 60_000).toInt()) }
        }
        if (sub != null) box.addView(text(sub, 11f, if (paused) pal.warnText else pal.sub).apply { maxLines = 1; gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(1); gravity = Gravity.CENTER_HORIZONTAL })
    }
    box.contentDescription = if (paused) getString(R.string.hm_paused_desc, a.label, BasicLogic.reasonText(reason ?: "", Store.of(this).tr)) else a.label
    kit.tap(box, 16f, list && pal.dark || !list)
    box.setOnClickListener { if (debounce()) { if (paused) pausedSheet(a, reason ?: "", dv, r) else launch(a, box) } }
    // drag source: where the icon lives
    val src: () -> DragSt = {
        when (mode) {
            Mode.HOME -> DragSt(Src.Item(item!!.id), null, "app", 1, 1, item.id, box)
            Mode.DOCK -> DragSt(Src.Dock(slot), null, "app", 1, 1, null, box)
            Mode.PANEL -> DragSt(Src.InFolder(folder!!.id, a.pkg), null, "app", 1, 1, null, null)
            Mode.DRAWER -> DragSt(null, a.pkg, "app", 1, 1, null, null)
        }
    }
    arm(box, { appMenu(a, box, mode, item) }, src, immediate = mode == Mode.PANEL)
    return box
}

internal fun HomeActivity.folderName(f: HItem): String = f.name.ifBlank { getString(R.string.hm_f_default) }

internal fun HomeActivity.folderIcon(f: HItem, vis: List<AppE>, show: Map<String, LauncherLogic.Show>): View {
    val px = iconPx()
    val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP; setPadding(0, dp(4), 0, dp(2)) }
    val square = model?.style?.shape == "square"
    val mini = (px * 0.36f).toInt()
    val grid = object : View(this) {
        val bmps = vis.take(4).map { IconCache.get(this@folderIcon, it.pkg, it.cls, mini, !square) to (show[it.pkg] == LauncherLogic.Show.PAUSED) }
        override fun onDraw(c: Canvas) {
            val gap = (px - 2 * mini) / 3f
            for ((i, b) in bmps.withIndex()) {
                val bm = b.first ?: continue
                val x = gap + (i % 2) * (mini + gap); val y = gap + (i / 2) * (mini + gap)
                if (b.second) { val p = IconCache.greyPaint(); p.alpha = 115; c.drawBitmap(bm, x, y, p) } else c.drawBitmap(bm, x, y, null)
            }
        }
    }.apply { background = if (square) fill(pal.card, px * 0.22f / resources.displayMetrics.density) else GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(pal.card) } }
    box.addView(grid, LinearLayout.LayoutParams(px, px).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(1); bottomMargin = dp(1) })
    if (model?.style?.labels != false) {
        val lbl = text(folderName(f), if (preset.id == "be") 14f else 12f, Color.WHITE).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; gravity = Gravity.CENTER; setPadding(dp(4), dp(1), dp(4), dp(1)) }
        box.addView(lbl, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4); gravity = Gravity.CENTER_HORIZONTAL })
        inkViews += lbl
    }
    box.contentDescription = getString(R.string.hm_f_desc, folderName(f), vis.size)
    kit.tap(box, 16f, true)
    box.setOnClickListener { if (debounce()) openFolder(f.id) }
    arm(box, {
        menu(listOf(
            Row(R.drawable.li_pencil, getString(R.string.hm_f_rename), true) { renameFolder(f.id) },
            Row(R.drawable.li_circle_minus, getString(R.string.hm_unpin), true) { model?.let { mm -> HomeLayout.take(mm, Src.Item(f.id))?.let { update(HomeLayout.dropRemove(it)) } } },
        ), box)
    }, { DragSt(Src.Item(f.id), null, "folder", 1, 1, f.id, box) })
    return box
}

internal fun HomeActivity.debounce(): Boolean {
    val now = android.os.SystemClock.elapsedRealtime()
    if (now - lastTap < 600) return false
    lastTap = now; return true
}

internal fun HomeActivity.launch(a: AppE, from: View) {
    val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(ComponentName(a.pkg, a.cls))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
    val opts = ActivityOptions.makeScaleUpAnimation(from, 0, 0, from.width, from.height).toBundle()
    runCatching { startActivity(i, opts) }.recoverCatching { packageManager.getLaunchIntentForPackage(a.pkg)?.let { startActivity(it) } ?: error("none") }
        .onFailure { Toast.makeText(this, R.string.hm_open_fail, Toast.LENGTH_SHORT).show() }
}

internal fun HomeActivity.hhmm(t: Long) = SimpleDateFormat("HH:mm", Locale.ROOT).format(Date(t))

/** "Mở 17:00" / "Hết giờ" under a greyed app in the drawer. */
internal fun HomeActivity.reopenShort(r: Rules, reason: String, pkg: String, dv: View0): String = when (val o = LauncherLogic.reopen(r, reason, pkg, dv.minute, dv.dow, dv.now)) {
    is LauncherLogic.Reopen.AtMinute -> getString(R.string.hm_opens, HomeLogic.clock(o.minute))
    is LauncherLogic.Reopen.AtMs -> getString(R.string.hm_opens, hhmm(o.ms))
    else -> getString(R.string.hm_out)
}

/** "06:30 sáng mai" / "lúc 17:00" / "vào ngày mai", or null. */
internal fun HomeActivity.reopenWhen(r: Rules, reason: String, pkg: String, dv: View0): String? = when (val o = LauncherLogic.reopen(r, reason, pkg, dv.minute, dv.dow, dv.now)) {
    is LauncherLogic.Reopen.AtMinute -> if (o.minute <= dv.minute) getString(R.string.hm_tomorrow_at, HomeLogic.clock(o.minute)) else getString(R.string.hm_today_at, HomeLogic.clock(o.minute))
    is LauncherLogic.Reopen.AtMs -> getString(R.string.hm_today_at, hhmm(o.ms))
    LauncherLogic.Reopen.Tomorrow -> getString(R.string.hm_tomorrow)
    else -> null
}
