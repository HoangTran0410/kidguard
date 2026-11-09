package dev.kidguard

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
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
    // the drawer shows the home-size bitmap scaled to its 52 dp: one bitmap per app, not two. Two sizes for ~280 apps did not
    // fit the LRU (Poco: 32 MB), so every rebuild evicted and re-rendered ~350 icons (2 s, 244 frames lost after a bulk move)
    // lazy: an icon not rendered yet (cold start, new app) is drawn by a background thread and shows when ready
    val round = model?.style?.shape != "square"
    var bmp = IconCache.peek(a.pkg, a.cls, iconPx(), round)
    val dst = android.graphics.RectF(0f, 0f, px.toFloat(), px.toFloat())
    val fp = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG or android.graphics.Paint.ANTI_ALIAS_FLAG)
    val badge = if (paused) "off" else model?.style?.badge ?: "num"
    frame.clipChildren = false   // the badge pokes out of the icon
    frame.addView(object : View(this), DotHost {
        override val dotPkgs = listOf(a.pkg)
        override fun onDraw(c: Canvas) {
            val bmp = bmp ?: return
            val scaled = bmp.width != px
            if (paused) { val p = IconCache.greyPaint(); p.alpha = 115; if (scaled) c.drawBitmap(bmp, null, dst, p) else c.drawBitmap(bmp, 0f, 0f, p) }
            else if (scaled) c.drawBitmap(bmp, null, dst, fp) else c.drawBitmap(bmp, 0f, 0f, null)
            if (badge != "off") Dots.draw(c, px, Dots.count(dotPkgs), resources.displayMetrics.density, badge == "num")
        }
    }.also { v -> if (bmp == null) IconCache.async(this, a.pkg, a.cls, iconPx(), round) { b -> if (b != null) { bmp = b; v.invalidate() } } }, FrameLayout.LayoutParams(px, px))
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
    box.clipChildren = false; box.clipToPadding = false   // the badge pokes out above the padding
    box.addView(frame, LinearLayout.LayoutParams(px + dp(4), px + dp(2)).apply { gravity = Gravity.CENTER_HORIZONTAL })
    // the dock has its own switch ("Tên ứng dụng ở thanh dưới"); "Tên ứng dụng" is the grid's
    val showLabel = list || (if (mode == Mode.DOCK) model?.style?.dockLabels else model?.style?.labels) ?: true
    if (showLabel) {
        val lbl = iconLabel(a.label, list, if (list) pal.text else Color.WHITE)
        box.addView(lbl, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(if (list) 4 else 2) })   // grid: 2 dp, so 7 square rows fit an icon + a label line (POCO)
        if (!list) { if (model?.style?.solid == true) ReadAids.backLabel(lbl) else inkViews += lbl }
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
    if (!list) box.foreground = null   // the icon sinking on touch is the feedback ([pressed]), no grey ripple over it
    box.setOnClickListener { if (debounce()) { if (model?.style?.speak == true) ReadAids.say(this, a.label); if (paused) pausedSheet(a, reason ?: "", dv, r) else launch(a, box) } }
    // drag source: where the icon lives
    val src: () -> DragSt = {
        when (mode) {
            Mode.HOME -> DragSt(Src.Item(item!!.id), null, item.kind, 1, 1, item.id, box)   // a shortcut can't go into a folder
            Mode.DOCK -> DragSt(Src.Dock(slot), null, "app", 1, 1, null, box)
            Mode.PANEL -> DragSt(Src.InFolder(folder!!.id, a.pkg), null, "app", 1, 1, null, null)
            Mode.DRAWER -> DragSt(null, a.pkg, "app", 1, 1, null, null)
        }
    }
    arm(box, { if (item?.kind == HomeLayout.SHORTCUT) shortcutMenu(item, box) else appMenu(a, box, mode, item) }, src, press = if (list) null else frame)   // in a folder too: menu first, moving the finger drags (iOS)
    return box
}

/**
 * The one label style under every icon (grid, dock incl. "Gọi", drawer, folders): one size everywhere, full cell width,
 * up to 2 lines like One UI (with a 1.5 system font most names ended in "…" on one line). The dock is 1 line, same size.
 */
internal fun HomeActivity.iconLabel(t: String, list: Boolean, color: Int, lines: Int = 2): TextView = IconLabel(this, lines).apply {
    text = t; setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, labelPx(list))
    setTextColor(color); includeFontPadding = false; typeface = HomeActivity.tf(false)
    ellipsize = android.text.TextUtils.TruncateAt.END; gravity = Gravity.CENTER; setPadding(dp(4), dp(1), dp(4), dp(1))
    setLineSpacing(0f, 0.92f)
    letterSpacing = ReadAids.spacing(model?.style?.face ?: "sys")
}

/**
 * Label size follows the icon (Pixel: 12 sp under 48 dp, ~25 %), so a 5x6 grid with small icons gets small labels too.
 * The system font scale still counts, damped (sqrt, at most x1.25): at 1.5 the labels were as tall as half an icon.
 * The drawer keeps 12 sp: its icons are a fixed 52 dp.
 */
internal fun HomeActivity.labelPx(list: Boolean): Float {
    val user = (model?.style?.font?.toFloat() ?: 1f)
    val sys = kotlin.math.sqrt(resources.configuration.fontScale).coerceIn(0.9f, 1.25f)
    // iOS: 12 pt under a 60 pt icon (20 %), regular weight. 25 % made names as tall as a third of the icon and cut them
    val base = if (list) dp(52) * 0.21f else iconPx() * 0.2f
    return (base * sys * user).coerceIn(dpf(10f), dpf(13.5f) * sys * user)
}

/**
 * 2 lines only when they fit: the cell has room for both (5x6 with a big font does not) and no word is wider than the
 * label (else Android splits it mid-word, "Faceb/ook"); otherwise 1 line with "…".
 */
internal class IconLabel(ctx: android.content.Context, var most: Int) : TextView(ctx) {
    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w) - paddingLeft - paddingRight
        var n = most
        if (n > 1 && MeasureSpec.getMode(h) != MeasureSpec.UNSPECIFIED && MeasureSpec.getSize(h) - paddingTop - paddingBottom < lineHeight * 2) n = 1
        if (n > 1 && MeasureSpec.getMode(w) != MeasureSpec.UNSPECIFIED && text.split(' ').any { paint.measureText(it) > width }) n = 1
        if (n != maxLines) maxLines = n
        super.onMeasure(w, h)
    }
}

internal fun HomeActivity.folderName(f: HItem): String = f.name.ifBlank { getString(R.string.hm_f_default) }

internal fun HomeActivity.folderIcon(f: HItem, vis: List<AppE>, show: Map<String, LauncherLogic.Show>): View {
    val px = iconPx()
    val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP; setPadding(0, dp(4), 0, dp(2)) }
    val square = model?.style?.shape == "square"
    val mini = (px * 0.36f).toInt()
    val badge = model?.style?.badge ?: "num"
    val grid = object : View(this), DotHost {
        override val dotPkgs = vis.filter { show[it.pkg] != LauncherLogic.Show.PAUSED }.map { it.pkg }
        val bmps = vis.take(4).map { IconCache.get(this@folderIcon, it.pkg, it.cls, mini, !square) to (show[it.pkg] == LauncherLogic.Show.PAUSED) }
        override fun onDraw(c: Canvas) {
            val gap = (px - 2 * mini) / 3f
            for ((i, b) in bmps.withIndex()) {
                val bm = b.first ?: continue
                val x = gap + (i % 2) * (mini + gap); val y = gap + (i / 2) * (mini + gap)
                if (b.second) { val p = IconCache.greyPaint(); p.alpha = 115; c.drawBitmap(bm, x, y, p) } else c.drawBitmap(bm, x, y, null)
            }
            if (badge != "off") Dots.draw(c, px, Dots.count(dotPkgs), resources.displayMetrics.density, badge == "num")
        }
    }.apply {
        // the app-icon plate (same shape + glass rim). Dark: pal.card (#1e293b) matched common navy wallpapers, so the
        // folder had no edge; a frosted white fill reads on any wallpaper. Light keeps pal.card (already white-ish)
        background = android.graphics.drawable.BitmapDrawable(resources, IconCache.plate(px, !square, if (pal.dark) Color.argb(56, 255, 255, 255) else pal.card))
    }
    box.clipChildren = false; box.clipToPadding = false   // the badge pokes out above the padding
    box.addView(grid, LinearLayout.LayoutParams(px, px).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(1); bottomMargin = dp(1) })
    if (model?.style?.labels != false) {
        val lbl = iconLabel(folderName(f), false, Color.WHITE)
        box.addView(lbl, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        inkViews += lbl
    }
    box.contentDescription = getString(R.string.hm_f_desc, folderName(f), vis.size)
    kit.tap(box, 16f, true)
    box.foreground = null
    box.setOnClickListener { if (debounce()) openFolder(f.id, grid) }
    arm(box, {
        menu(listOf(
            Row(R.drawable.li_pencil, getString(R.string.hm_f_rename), true) { renameFolder(f.id) },
            Row(R.drawable.li_circle_minus, getString(R.string.hm_unpin), true) { model?.let { mm -> HomeLayout.take(mm, Src.Item(f.id))?.let { update(HomeLayout.dropRemove(it)) } } },
            Row(R.drawable.li_layout_dashboard, getString(R.string.hm_edit_home), true) { enterEdit(f.id) },
        ), box)
    }, { DragSt(Src.Item(f.id), null, "folder", 1, 1, f.id, box) }, press = grid)
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
    // the icon picture inside the cell (cell > box > icon), where the app grows from, like Launcher3's clip reveal
    val icon = ((from as? ViewGroup)?.getChildAt(0) as? ViewGroup)?.getChildAt(0) ?: from
    val fl = IntArray(2).also { from.getLocationOnScreen(it) }; val il = IntArray(2).also { icon.getLocationOnScreen(it) }
    i.sourceBounds = android.graphics.Rect(il[0], il[1], il[0] + icon.width, il[1] + icon.height)
    Recent.note(this, a.pkg)
    if (a.cls.startsWith(Shortcuts.PREFIX)) {
        if (!Flavor.HOME) runCatching { AccPause.pause(this, a.pkg) }
        val opts = ActivityOptions.makeClipRevealAnimation(from, il[0] - fl[0], il[1] - fl[1], icon.width, icon.height).toBundle()
        if (!Shortcuts.start(this, a.pkg, a.cls.removePrefix(Shortcuts.PREFIX), i.sourceBounds, opts)) Toast.makeText(this, R.string.hm_sc_fail, Toast.LENGTH_SHORT).show()
        return
    }
    // a bank app checks for accessibility services as it starts: ours goes off before it opens (AccPause)
    if (!Flavor.HOME) runCatching { AccPause.pause(this, a.pkg) }
    // The system's zoom: the app's real window grows out of the icon (Oppo, ColorOS 8.1). HyperOS grows it from the screen
    // centre for any third-party home and plays it only when the home window shows the system wallpaper (theme AppHome),
    // else it slides apps in from the side (measured on a Poco, HyperOS 3, same as a third-party iOS launcher). A zoom
    // drawn by us from the icon was tried and dropped: only a blank card (no preview of the app), and work on our thread.
    val opts = ActivityOptions.makeClipRevealAnimation(from, il[0] - fl[0], il[1] - fl[1], icon.width, icon.height).toBundle()
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
