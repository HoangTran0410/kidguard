package dev.kidguard

import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.Toast
import dev.kidguard.HomeActivity.AppE
import dev.kidguard.HomeActivity.Mode
import dev.kidguard.HomeActivity.View0
import dev.kidguard.HomeActivity.Row

// Paused-app sheet, long-press menus, folders, overlays (split out of HomeActivity; extension functions on it).

// ---- paused sheet ----

internal fun HomeActivity.pausedSheet(a: AppE, reason: String, dv: View0, r: Rules) {
    val s = Store.of(this)
    val sh = Sheet(this, u, getString(R.string.hm_paused_title, a.label))
    val body = sh.body
    val warnCard = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        background = fill(pal.warnTint, 18f); setPadding(dp(14), dp(12), dp(14), dp(12))
    }
    warnCard.addView(ImageView(this).apply { setImageDrawable(Ic.get(this@pausedSheet, R.drawable.li_clock, pal.onWarn, dp(18))); background = fill(pal.warn); setPadding(dp(8), dp(8), dp(8), dp(8)) },
        LinearLayout.LayoutParams(dp(34), dp(34)))
    val txt = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
    txt.addView(text(BasicLogic.reasonText(reason, s.tr).replaceFirstChar { it.titlecase() }, 16f, pal.text, true))
    reopenWhen(r, reason, a.pkg, dv)?.let { txt.addView(text(getString(R.string.hm_reopen, it), 14f, pal.warnText).apply { setPadding(0, dp(3), 0, 0) }) }
    warnCard.addView(txt, LinearLayout.LayoutParams(0, -2, 1f))
    body.addView(warnCard, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(12), dp(14), dp(12), 0) })
    val note = Notes.clean(r.blockNote, Notes.NOTE_MAX)
    if (note.isNotEmpty() && r.launcher.notes) {
        val nc = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = fill(pal.bg, 18f); setPadding(dp(14), dp(12), dp(14), dp(12)) }
        nc.addView(text(getString(R.string.hm_note), 12f, pal.sub, true))
        nc.addView(text(note, 15f, pal.text).apply { setPadding(0, dp(4), 0, 0); includeFontPadding = true })
        body.addView(nc, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(12), dp(10), dp(12), 0) })
    }
    if (HomeLogic.canAsk(s.everPaired, dv.leftMs, reason)) body.addView(kit.pill(getString(R.string.main_more), R.drawable.li_timer, true) { sh.dismiss(); main.post { acts.askMore() } },
        LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(12), dp(16), dp(12), 0) })
    body.addView(kit.pill(getString(R.string.hm_later), null, false) { sh.dismiss() }, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(12), dp(8), dp(12), 0) })
    sh.show()
}

// ---- menus ----


internal fun HomeActivity.menu(rows: List<Row>, anchor: View?, x: Int = 0, y: Int = 0) {
    popup?.dismiss()
    val focus = menuFocus(anchor)
    val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = fill(pal.sheet, 16f); setPadding(0, dp(6), 0, dp(6)); elevation = dpf(8f) }
    // 250dp, wider with a big system font, never wider than the screen; taller than the screen scrolls
    val mw = minOf(dp((250 * resources.configuration.fontScale.coerceIn(1f, 1.4f)).toInt()), root.width - dp(16))
    val maxH = root.height - insT - insB - dp(24)
    val scroll = object : android.widget.ScrollView(this) {
        override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST))
    }.apply { isVerticalScrollBarEnabled = false; background = fill(pal.sheet, 16f); elevation = shadow(8f); addView(box) }
    box.background = null; box.elevation = 0f
    val pw = PopupWindow(scroll, mw, ViewGroup.LayoutParams.WRAP_CONTENT, true)
    for (r in rows) box.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12))
        val c = if (r.enabled) pal.text else pal.sub
        addView(ImageView(context).apply { setImageDrawable(Ic.get(context, r.icon, if (r.enabled) pal.onTint else pal.sub, dp(18))); background = fill(if (r.enabled) pal.tint else pal.bg); setPadding(dp(6), dp(6), dp(6), dp(6)) },
            LinearLayout.LayoutParams(dp(30), dp(30)))
        addView(text(r.label, 15f, c).apply { setPadding(dp(12), 0, 0, 0); maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f))
        isEnabled = r.enabled
        kit.tap(this, 10f)
        if (r.enabled) setOnClickListener { pw.dismiss(); r.run() }
        contentDescription = r.label
    }, LinearLayout.LayoutParams(-1, -2))
    pw.elevation = shadow(8f)
    pw.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
    pw.setOnDismissListener {
        if (popup === pw) popup = null
        // the lifted icon goes at once (a drag may be taking it), the dark layer fades
        if (focusView === focus && popup == null && focus.visibility == View.VISIBLE) {
            focus.icon = null; focus.invalidate()
            focus.animate().alpha(0f).setDuration(160).withEndAction { if (popup == null) focus.visibility = View.INVISIBLE }.start()
        }
    }
    popup = pw
    // place it ourselves: showAsDropDown on an item near the bottom cut the last rows off (Android 8 doesn't flip it)
    scroll.measure(View.MeasureSpec.makeMeasureSpec(mw, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
    val h = scroll.measuredHeight
    val rl = IntArray(2).also { root.getLocationInWindow(it) }
    var cx = x; var cy = y   // where the menu grows from: the pressed item's centre, or the long-press point
    val (px, py) = if (anchor != null) {
        val l = IntArray(2).also { anchor.getLocationInWindow(it) }
        cx = l[0] + anchor.width / 2; cy = l[1] + anchor.height / 2
        val below = l[1] - rl[1] + anchor.height - dp(6)
        l[0] - rl[0] to (if (below + h <= root.height - insB - dp(8)) below else l[1] - rl[1] - h + dp(6))
    } else x - rl[0] to y - rl[1]
    val fx = px.coerceAtMost(root.width - mw - dp(8)).coerceAtLeast(dp(8)) + rl[0]
    val fy = py.coerceAtMost(root.height - insB - h - dp(8)).coerceAtLeast(insT + dp(8)) + rl[1]
    pw.animationStyle = 0   // our own zoom below, not the system fade
    pw.showAtLocation(root, Gravity.TOP or Gravity.START, fx, fy)
    // grows out of the pressed icon (One UI / Pixel), not popped in place
    scroll.pivotX = (cx - fx).toFloat().coerceIn(0f, mw.toFloat()); scroll.pivotY = (cy - fy).toFloat().coerceIn(0f, h.toFloat())
    scroll.scaleX = 0.6f; scroll.scaleY = 0.6f; scroll.alpha = 0f
    scroll.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(200).setInterpolator(android.view.animation.DecelerateInterpolator(2f)).start()
}

internal fun HomeActivity.appMenu(a: AppE, v: View, mode: Mode, item: HItem?) {
    val m = model ?: return
    val placed = a.pkg in m.onHome()
    menu(listOfNotNull(
        Row(R.drawable.li_pin, getString(if (placed) R.string.hm_pinned else R.string.hm_pin), !placed) { update(HomeLayout.pin(m, a.pkg)); if (drawer != null) closeDrawer() }.takeIf { mode == Mode.DRAWER },
        Row(R.drawable.li_circle_minus, getString(R.string.hm_unpin), true) { update(HomeLayout.remove(m, a.pkg)) }.takeIf { placed || mode != Mode.DRAWER },
        Row(R.drawable.li_folder_plus, getString(R.string.hm_f_add), true) { addToFolderSheet(a) },
        // edit mode with this icon already picked (One UI's "Chọn"): several apps at once from here
        Row(R.drawable.li_check, getString(R.string.hm_e_pick), true) { enterEdit(item?.id) }.takeIf { mode == Mode.HOME && item != null },
    ) + lookRows(a) + listOfNotNull(
        // the app fills its whole cell, so most long-presses land on one: give the empty-space menu a way in from here
        Row(R.drawable.li_layout_dashboard, getString(R.string.hm_edit_home), true) { if (mode == Mode.PANEL) closeOverlay(); enterEdit() }.takeIf { mode != Mode.DRAWER },
        // the system's app details (storage, notifications, permissions), last like POCO / One UI. Only when the parent lets
        // the kid open Settings: otherwise this would be a way into it.
        Row(R.drawable.li_info, getString(R.string.hm_app_info), true) {
            runCatching { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${a.pkg}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }.takeIf { settingsOk(Store.of(this).rules) },
    ), v)
}

/** Long-press on a pinned shortcut: take it off the home, or edit the home. */
internal fun HomeActivity.shortcutMenu(item: HItem, v: View) = menu(listOf(
    Row(R.drawable.li_circle_minus, getString(R.string.hm_unpin), true) { model?.let { m -> update(HomeLayout.removeIds(m, setOf(item.id))) } },
    Row(R.drawable.li_check, getString(R.string.hm_e_pick), true) { enterEdit(item.id) },
    Row(R.drawable.li_layout_dashboard, getString(R.string.hm_edit_home), true) { enterEdit() },
), v)

internal fun HomeActivity.emptyMenu(x: Int, y: Int) = menu(listOf(
    Row(R.drawable.li_check, getString(R.string.hm_e_enter), true) { enterEdit() },
    Row(R.drawable.li_layout_dashboard, getString(R.string.hm_w_title), true) { openPicker() },
    Row(R.drawable.li_sliders_horizontal, getString(R.string.hm_customize), true) { runCatching { startActivity(Intent(this, CustomActivity::class.java)) } },
    Row(R.drawable.li_shield, getString(R.string.hm_open_kg), true) { openMain() }.takeIf { !Flavor.HOME },
).filterNotNull(), null, x, y)

internal fun HomeActivity.update(m: HomeModel) { HomeFile.save(this, m); model = m; key = ""; reusePages = true; render(true) }

/** KidGuard Home: the system's "Default home app" screen (older / odd ROMs: the app's own default settings). */
internal fun HomeActivity.homeSettings() {
    // Xiaomi / Redmi / POCO (MIUI, HyperOS): another home turns gesture navigation into 3 buttons and nothing keeps the
    // gestures (the system drops its gesture view; checked on a POCO, HyperOS 3). Say so before the user picks.
    val xiaomi = android.os.Build.MANUFACTURER.lowercase() in setOf("xiaomi", "redmi", "poco")
    if (!xiaomi) { openHomeSettings(); return }
    Modal(this, u).show(R.drawable.li_info, Modal.Tone.WARN, getString(R.string.hm_xiaomi_nav_t), getString(R.string.hm_xiaomi_nav_b),
        ok = getString(R.string.hm_xiaomi_nav_ok), cancel = getString(R.string.cancel)) { openHomeSettings(); true }
}

private fun HomeActivity.openHomeSettings() = runCatching { startActivity(Intent(android.provider.Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    .recoverCatching { startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }

internal fun HomeActivity.openMain() = runCatching { startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }

// ---- folders ----

/** Auto name from the two apps' ApplicationInfo.category: Trò chơi / Mạng xã hội / Học tập / Giải trí, else Thư mục. */
internal fun HomeActivity.autoName(a: String, b: String): String {
    fun cat(p: String) = if (Build.VERSION.SDK_INT >= 26) runCatching { packageManager.getApplicationInfo(p, 0).category }.getOrDefault(-1) else -1
    return when (HomeLayout.folderKind(cat(a), cat(b))) {
        "games" -> getString(R.string.hm_f_games); "social" -> getString(R.string.hm_f_social); "study" -> getString(R.string.hm_f_study); "fun" -> getString(R.string.hm_f_fun)
        else -> getString(R.string.hm_f_default)
    }
}

/** "Thêm vào thư mục": the folders on the home, then "a new folder with" each other app on the grid. */
internal fun HomeActivity.addToFolderSheet(a: AppE) {
    val m = model ?: return
    val all = launcherApps()
    val sh = Sheet(this, u, getString(R.string.hm_f_add_title, a.label))
    fun rowOf(icon: View, label: String, run: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(10), dp(16), dp(10))
        addView(icon, LinearLayout.LayoutParams(dp(40), dp(40)))
        addView(text(label, 16f, pal.text).apply { setPadding(dp(14), 0, 0, 0); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f))
        kit.tap(this, 12f) { sh.dismiss(); run() }
        contentDescription = label
    }
    val folders = m.pages.flatten().filter { it.kind == "folder" && a.pkg !in it.apps && lastShow.let { s -> it.apps.any { p -> s[p] != null && s[p] != LauncherLogic.Show.HIDE } } }
    for (f in folders) sh.body.addView(rowOf(kit.badge(R.drawable.li_folder, pal.onTint, pal.tint, 40, 20, square = true), folderName(f)) {
        HomeLayout.addTo(m, a.pkg, f.id, "")?.let { update(it); toast(getString(R.string.hm_f_added, folderName(f))) } ?: toast(getString(R.string.hm_f_full))
    })
    val others = m.pages.flatten().filter { it.kind == "app" && it.pkg != a.pkg && lastShow[it.pkg]?.let { s -> s != LauncherLogic.Show.HIDE } == true }
        .mapNotNull { it to (all[it.pkg] ?: return@mapNotNull null) }.sortedBy { it.second.sortKey }
    if (others.isNotEmpty()) sh.body.addView(text(getString(R.string.hm_f_new_with), 13f, pal.sub, true).apply { setPadding(dp(16), dp(14), dp(16), dp(4)) })
    for ((it, e) in others) sh.body.addView(rowOf(ImageView(this).apply { setImageBitmap(IconCache.get(this@addToFolderSheet, e.pkg, e.cls, dp(40), model?.style?.shape != "square")) }, e.label) {
        val name = autoName(e.pkg, a.pkg)
        HomeLayout.addTo(m, a.pkg, it.id, name)?.let { n -> update(n); toast(getString(R.string.hm_f_added, name)) }
    })
    if (folders.isEmpty() && others.isEmpty()) sh.body.addView(text(getString(R.string.hm_f_none), 15f, pal.sub).apply { setPadding(dp(16), dp(16), dp(16), dp(16)) })
    sh.show()
}

internal fun HomeActivity.toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

internal fun HomeActivity.renameFolder(id: String) {
    val m = model ?: return
    val f = HomeLayout.find(m, id)?.second ?: return
    val md = Modal(this, u)
    val field = md.field(getString(R.string.hm_f_name_hint), 40).apply { setText(folderName(f)); setSelection(text.length) }
    md.show(R.drawable.li_pencil, Modal.Tone.INFO, getString(R.string.hm_f_rename), content = field, ok = getString(R.string.hm_t_save), cancel = getString(R.string.cancel)) {
        val n = HomeLayout.rename(model ?: return@show true, id, field.text.toString())
        val wasOpen = overlay?.tag == "folder:$id"
        if (wasOpen) closeOverlay()
        update(n)
        if (wasOpen) main.post { openFolder(id) }
        true
    }
    field.requestFocus()
}

/** A rounded panel over the home: the folder's visible apps (paused ones greyed + badge). Drag one out to take it out. */
internal fun HomeActivity.openFolder(id: String, from: View? = null) {
    val m = model ?: return
    val f = HomeLayout.find(m, id)?.second?.takeIf { it.kind == "folder" } ?: return
    if (lastView == null) render(true)   // tapped before the first render finished: build it now instead of ignoring the tap
    val dv = lastView ?: return
    val r = Store.of(this).rules
    val all = launcherApps()
    val vis = f.apps.mapNotNull { p -> all[p]?.takeIf { lastShow[p] != null && lastShow[p] != LauncherLogic.Show.HIDE } }
    if (vis.isEmpty()) return
    folderSheet(folderName(f), "folder:$id", { renameFolder(id) }, vis.map { a -> appIcon(a, lastShow[a.pkg] == LauncherLogic.Show.PAUSED, dv, r, Mode.PANEL, folder = f) to a.pkg }, from, "folderGrid")
}

/**
 * The iOS folder look, shared by home folders and App Library groups: the name big above a square glass card of 3×3
 * apps per page (+ dots), a dark layer behind, the card zooming out of [from] and back into it. [gridTag] marks a
 * home folder's grid (re-arranging inside it); [rename] makes the name tappable.
 */
internal fun HomeActivity.folderSheet(name: String, tag: String?, rename: (() -> Unit)?, icons: List<Pair<View, String>>, from: View?, gridTag: String?) {
    popup?.dismiss()
    val scrim = FrameLayout(this).apply { isClickable = true; setOnClickListener { closeOverlay() }; this.tag = tag }
    // a dark layer that deepens with the zoom (no blur: an animated blur costs a GPU pass per frame, a plain alpha
    // costs nothing on any phone). Its own view, so fading it never fades the card flying back into the icon
    val dim = View(this).apply { setBackgroundColor(Color.argb(200, 0, 0, 0)) }   // 150 let the home show through the folder name (11-09)
    scrim.addView(dim, FrameLayout.LayoutParams(-1, -1))
    // no clipping: the card flies from / back into the icon, often below the panel (it was cut off at its bottom)
    scrim.clipChildren = false
    val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; clipChildren = false; clipToPadding = false }
    val title = text(name, 28f, Color.WHITE, true).apply {
        gravity = Gravity.CENTER; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(dp(12), dp(4), dp(12), dp(4))
        setShadowLayer(dpf(6f), 0f, dpf(1f), 0x66000000)
        if (rename != null) { contentDescription = getString(R.string.hm_f_rename) + ": " + name; kit.tap(this, 12f) { rename() } }
    }
    panel.addView(title, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(16) })
    val card = LibraryPage.Square(this).apply { background = GlassCard(pal.card, dpf(38f), dpf(1f), pal.dark); setPadding(dp(14), dp(16), dp(14), dp(16)); elevation = shadow(12f); isClickable = true }   // lite effects: no shadow re-rendered each frame of the zoom (janked an Oppo A5s)
    val grid = FolderGrid(this, 3, 3).apply { this.tag = gridTag }
    for ((v, key) in icons) grid.add(v.also { oneLine(it) }, key)
    card.addView(grid, FrameLayout.LayoutParams(-1, -1))
    panel.addView(card, LinearLayout.LayoutParams(-1, -2))
    var dots: View? = null
    if (grid.pages > 1) {
        val pd = PageDots(this).apply { set(grid.pages, Color.WHITE, null); pos = 1f }
        grid.onScroll = { pd.pos = 1 + it }
        dots = pd
        panel.addView(pd, LinearLayout.LayoutParams(-2, dp(18)).apply { topMargin = dp(14) })
    }
    val side = (minOf(root.width, root.height) * 0.11f).toInt()
    scrim.addView(panel, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL).apply { setMargins(side + insL, 0, side + insR, dp(40)) })
    showOverlay(scrim)
    folderZoom(dim, card, grid, title, from, dots)
    announce(name)
}

/** Folder labels on one line ("Phone Ma…", iOS): two lines at a big font ran into the next row of the 3×3 card. */
private fun oneLine(v: View) {
    if (v is IconLabel) v.most = 1 else if (v is android.widget.TextView) { v.maxLines = 1; v.ellipsize = android.text.TextUtils.TruncateAt.END }
    else if (v is android.view.ViewGroup) for (i in 0 until v.childCount) oneLine(v.getChildAt(i))
}

internal fun HomeActivity.showOverlay(v: View) {
    closeOverlay()
    overlay = v
    root.addView(v, FrameLayout.LayoutParams(-1, -1))
}

internal fun HomeActivity.closeOverlay() {
    val v = overlay ?: return
    overlay = null
    // the widget picker has a search box: its keyboard must not outlive the overlay
    runCatching { v.findFocus()?.clearFocus(); (getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0) }
    val ex = overlayExit; overlayExit = null
    val done = { root.removeView(v); if (drag == null) main.post { render(false) } }
    if (ex != null) ex(done) else done()
}

/**
 * iOS long-press: the home goes dark behind the menu and the pressed icon stays lit above it (a picture of it in the
 * same place). One view kept in the root and only shown / hidden, drawing both itself: adding and removing a view
 * there re-laid out the whole home each time (Oppo A5s: 30 % janky frames per menu, 10-15 % without it).
 */
internal class MenuFocus(ctx: android.content.Context) : View(ctx) {
    private val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
    var icon: android.graphics.Bitmap? = null
    var ix = 0f; var iy = 0f
    init { visibility = INVISIBLE; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO; outlineProvider = null; translationZ = 1000f }
    override fun hasOverlappingRendering() = false
    override fun onDraw(c: android.graphics.Canvas) {
        c.drawColor(Color.argb(120, 0, 0, 0))
        icon?.let { c.drawBitmap(it, ix, iy, paint) }
    }
}

internal fun HomeActivity.menuFocus(anchor: View?): MenuFocus {
    val f = focusView ?: MenuFocus(this).also { focusView = it; root.addView(it, FrameLayout.LayoutParams(-1, -1)) }
    f.animate().cancel()
    val a = anchor?.takeIf { it.isAttachedToWindow && it.width > 0 && it.height > 0 }
    f.icon = a?.let {
        val b = android.graphics.Bitmap.createBitmap(it.width, it.height, android.graphics.Bitmap.Config.ARGB_8888)
        runCatching { it.draw(android.graphics.Canvas(b)) }
        val l = IntArray(2); it.getLocationInWindow(l); val r = IntArray(2); f.getLocationInWindow(r)
        f.ix = (l[0] - r[0]).toFloat(); f.iy = (l[1] - r[1]).toFloat()
        b
    }
    // light effects (weak phones): no dark layer; it cost an Oppo A5s ~4 more slow frames per menu (25 % janky vs 12 %)
    if (liteFx) { f.icon = null; f.visibility = View.INVISIBLE; return f }
    f.alpha = 0f; f.visibility = View.VISIBLE; f.invalidate()
    f.animate().alpha(1f).setDuration(180).start()
    return f
}
