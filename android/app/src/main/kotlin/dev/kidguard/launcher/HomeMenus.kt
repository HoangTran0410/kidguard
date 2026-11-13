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
import android.widget.TextView
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
    val locked = reason == "settings"
    warnCard.addView(ImageView(this).apply { setImageDrawable(Ic.get(this@pausedSheet, if (locked) R.drawable.li_lock else R.drawable.li_clock, pal.onWarn, dp(18))); background = fill(pal.warn); setPadding(dp(8), dp(8), dp(8), dp(8)) },
        LinearLayout.LayoutParams(dp(34), dp(34)))
    val txt = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
    txt.addView(text(BasicLogic.reasonText(reason, s.tr).replaceFirstChar { it.titlecase() }, 16f, pal.text, true))
    if (locked) txt.addView(text(getString(R.string.hm_settings_ask), 14f, pal.warnText).apply { setPadding(0, dp(3), 0, 0); includeFontPadding = true })
    else reopenWhen(r, reason, a.pkg, dv)?.let { txt.addView(text(getString(R.string.hm_reopen, it), 14f, pal.warnText).apply { setPadding(0, dp(3), 0, 0) }) }
    warnCard.addView(txt, LinearLayout.LayoutParams(0, -2, 1f))
    body.addView(warnCard, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(12), dp(14), dp(12), 0) })
    val note = Notes.clean(r.blockNote, Notes.NOTE_MAX)
    if (note.isNotEmpty() && r.launcher.notes) {
        val nc = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = fill(pal.bg, 18f); setPadding(dp(14), dp(12), dp(14), dp(12)) }
        nc.addView(text(getString(R.string.hm_note), 12f, pal.sub, true))
        nc.addView(text(note, 15f, pal.text).apply { setPadding(0, dp(4), 0, 0); includeFontPadding = true })
        body.addView(nc, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(12), dp(10), dp(12), 0) })
    }
    if (!locked && HomeLogic.canAsk(s.everPaired, dv.leftMs, reason)) body.addView(kit.pill(getString(R.string.main_more), R.drawable.li_timer, true) { sh.dismiss(); main.post { acts.askMore() } },
        LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(12), dp(16), dp(12), 0) })
    body.addView(kit.pill(getString(R.string.hm_later), null, false) { sh.dismiss() }, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(12), dp(8), dp(12), 0) })
    sh.show()
}

// ---- menus ----


/**
 * Optional menu header: a title with small icon buttons on the right (e.g. app info), and/or one row of choice chips.
 * [info] fills a small line under the title (it may set the text later, from a background read).
 */
internal class MenuHead(val title: String?, val buttons: List<HeadBtn> = emptyList(), val chips: List<MenuChip> = emptyList(),
                        val info: ((TextView) -> Unit)? = null, val infoRun: (() -> Unit)? = null)
internal class HeadBtn(val icon: Int, val desc: String, val run: () -> Unit)
internal class MenuChip(val label: String, val on: Boolean, val run: () -> Unit)

/** A menu row; [danger] = red label (Uninstall). */
internal class MRow(val row: Row, val danger: Boolean = false)

internal fun HomeActivity.menu(rows: List<Row>, anchor: View?, x: Int = 0, y: Int = 0, head: MenuHead? = null) =
    menuGroups(listOf(rows.map { MRow(it) }), anchor, x, y, head)

/**
 * iOS 17 look: one translucent rounded panel, 44 dp rows (label left, icon at the right end), hairlines inside a group,
 * a thicker band between groups. Nothing is built that the first frame doesn't show.
 */
internal fun HomeActivity.menuGroups(groups: List<List<MRow>>, anchor: View?, x: Int = 0, y: Int = 0, head: MenuHead? = null) {
    popup?.dismiss()
    val t0 = Perf.now()
    val focus = menuFocus(anchor)
    val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    // 250dp, wider with a big system font, never wider than the screen; taller than the screen scrolls
    val mw = minOf(dp((250 * resources.configuration.fontScale.coerceIn(1f, 1.4f)).toInt()), root.width - dp(16))
    val maxH = root.height - insT - insB - dp(24)
    val panelBg = android.graphics.drawable.GradientDrawable().apply { setColor(pal.sheet and 0x00FFFFFF or (0xEE shl 24)); cornerRadius = dpf(14f) }
    val scroll = object : android.widget.ScrollView(this) {
        override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST))
    }.apply { isVerticalScrollBarEnabled = false; background = panelBg; elevation = shadow(8f); clipToOutline = true; addView(box) }
    val pw = PopupWindow(scroll, mw, ViewGroup.LayoutParams.WRAP_CONTENT, true)
    val hair = pal.sub and 0x00FFFFFF or 0x33000000
    if (head != null) menuHead(box, head, pw)
    var first = true
    for (g in groups) {
        if (g.isEmpty()) continue
        if (!first) box.addView(View(this).apply { setBackgroundColor(pal.sub and 0x00FFFFFF or 0x22000000) }, LinearLayout.LayoutParams(-1, dp(7)))
        first = false
        g.forEachIndexed { i, mr ->
            val r = mr.row
            if (i > 0) box.addView(View(this).apply { setBackgroundColor(hair) }, LinearLayout.LayoutParams(-1, 1).apply { leftMargin = dp(16) })
            val c = if (!r.enabled) pal.sub else if (mr.danger) pal.dangerText else pal.text
            box.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), 0, dp(14), 0); minimumHeight = dp(44)
                addView(text(r.label, 16f, c).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(4), dp(8), dp(4)) }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(ImageView(context).apply { setImageDrawable(Ic.get(context, r.icon, c, dp(20))); scaleType = ImageView.ScaleType.CENTER },
                    LinearLayout.LayoutParams(dp(22), dp(22)))
                isEnabled = r.enabled
                kit.tap(this, 0f)
                if (r.enabled) setOnClickListener { pw.dismiss(); r.run() }
                contentDescription = r.label
            }, LinearLayout.LayoutParams(-1, -2))
        }
    }
    pw.elevation = shadow(8f)
    pw.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
    pw.setOnDismissListener {
        if (popup === pw) popup = null
        // the lifted icon goes at once (a drag may be taking it), the dark layer fades
        if (focusView === focus && popup == null && focus.visibility == View.VISIBLE) {
            focus.icon = null; focus.invalidate()
            Motion.fade(focus, 0f) { if (popup == null) focus.visibility = View.INVISIBLE }
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
    Perf.firstDraw(scroll, "menu", t0)
    // grows out of the pressed icon (One UI / Pixel), not popped in place
    scroll.pivotX = (cx - fx).toFloat().coerceIn(0f, mw.toFloat()); scroll.pivotY = (cy - fy).toFloat().coerceIn(0f, h.toFloat())
    scroll.scaleX = 0.6f; scroll.scaleY = 0.6f; scroll.alpha = 0f
    Motion.enter(scroll)
}

/** Header of a long-press menu (Pixel / One UI): title + icon buttons, then chips, then a hairline before the rows. */
private fun HomeActivity.menuHead(box: LinearLayout, h: MenuHead, pw: PopupWindow) {
    if (h.title != null || h.buttons.isNotEmpty()) box.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(4), dp(8), dp(4))
        addView(text(h.title ?: "", 14f, pal.sub, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f))
        for (b in h.buttons) addView(ImageView(context).apply {
            setImageDrawable(Ic.get(context, b.icon, pal.text, dp(18))); scaleType = ImageView.ScaleType.CENTER
            contentDescription = b.desc; kit.tap(this, 20f) { pw.dismiss(); b.run() }
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
    }, LinearLayout.LayoutParams(-1, -2))
    h.info?.let { fill ->
        val tv = text("", 13f, pal.sub).apply { setPadding(dp(16), dp(10), dp(16), dp(10)); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; visibility = View.GONE }
        box.addView(tv, LinearLayout.LayoutParams(-1, -2))
        h.infoRun?.let { run -> kit.tap(tv, 0f) { pw.dismiss(); run() }; tv.contentDescription = getString(R.string.menu_usage_open) }
        fill(tv)
    }
    if (h.chips.isNotEmpty()) box.addView(android.widget.HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), dp(4), dp(12), dp(8))
            for (c in h.chips) addView(text(c.label, 14f, if (c.on) pal.onSolid else pal.text, c.on).apply {
                background = fill(if (c.on) pal.solid else pal.tint); setPadding(dp(14), dp(7), dp(14), dp(7)); gravity = Gravity.CENTER
                contentDescription = c.label; isSelected = c.on
                if (!c.on) kit.tap(this, 999f) { pw.dismiss(); c.run() }
            }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6) })
        })
    }, LinearLayout.LayoutParams(-1, -2))
    if (h.info != null || h.chips.isNotEmpty() || h.title != null || h.buttons.isNotEmpty())
        box.addView(View(this).apply { setBackgroundColor(pal.sub and 0x00FFFFFF or 0x22000000) }, LinearLayout.LayoutParams(-1, dp(7)))
}

internal fun HomeActivity.appMenu(a: AppE, v: View, mode: Mode, item: HItem?) {
    val m = model ?: return
    val placed = a.pkg in m.onHome()
    val rules = Store.of(this).rules
    // top level = what's used most (iOS: Edit Home Screen / Remove / Share / Remove App); the rest is behind "More…"
    val g1 = listOfNotNull(
        Row(R.drawable.li_pin, getString(if (placed) R.string.hm_pinned else R.string.hm_pin), !placed) { update(HomeLayout.pin(m, a.pkg)); if (drawer != null) closeDrawer() }.takeIf { mode == Mode.DRAWER },
        Row(R.drawable.li_circle_minus, getString(R.string.hm_unpin), true) { if (mode == Mode.PANEL) closeOverlay(); update(HomeLayout.remove(m, a.pkg)) }.takeIf { placed || mode != Mode.DRAWER },
        // one way into edit mode (the old "Chọn" + "Sửa màn hình chính"): on the grid it starts with this icon picked
        Row(R.drawable.li_move, getString(R.string.hm_edit_home), true) { if (mode == Mode.PANEL) closeOverlay(); enterEdit(item?.id.takeIf { mode == Mode.HOME }) }.takeIf { mode != Mode.DRAWER },
    )
    // the system's app details only when the parent lets the kid open Settings: otherwise this would be a way into it
    val info = Row(R.drawable.li_info, getString(R.string.hm_app_info), true) {
        runCatching { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${a.pkg}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }.takeIf { settingsOk(rules) }
    // rare ones, built only when "More…" is tapped
    val more = Row(R.drawable.li_ellipsis, getString(R.string.menu_more), true) {
        menuGroups(listOf(listOfNotNull(
            Row(R.drawable.li_folder_plus, getString(R.string.hm_f_add), true) { addToFolderSheet(a) },
            lookRow(a),
            // its notification stats + hold (gom / tạm im): only where the history is kept
            Row(R.drawable.li_chart_column, getString(R.string.hm_app_notifs), true) {
                if (mode == Mode.PANEL) closeOverlay()
                startActivity(Intent(this, NotifStatsActivity::class.java).putExtra(NotifStatsActivity.PKG, a.pkg))
            }.takeIf { NotifDots.granted(this) && !a.pkg.startsWith("dev.kidguard") },
        ).map { MRow(it) }), v)
    }
    val un = uninstallRow(a.pkg, rules)
    menuGroups(listOf(g1, listOfNotNull(info, more), listOfNotNull(un)).map { g -> g.map { if (it === un) MRow(it, true) else MRow(it) } }, v,
        head = MenuHead(null, info = { tv -> appInfoLine(a.pkg, tv) }, infoRun = { UsageStatsActivity.open(this, a.pkg) }))
}

/**
 * iOS "Remove App": red, last group. Hidden for KidGuard itself and for system apps that can't be removed; greyed with
 * the reason where the parent's rules (no installs, Settings closed) or the system (uninstall blocked) refuse.
 */
private fun HomeActivity.uninstallRow(pkg: String, r: Rules): Row? {
    if (pkg == packageName || pkg.startsWith("dev.kidguard")) return null
    val ai = runCatching { packageManager.getApplicationInfo(pkg, 0) }.getOrNull() ?: return null
    if (ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0 && ai.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0) return null
    val refused = !Flavor.HOME && (r.noInstall || !settingsOk(r)) ||
        runCatching { Policy.dpm(this).isUninstallBlocked(null, pkg) }.getOrDefault(false)
    if (refused) return Row(R.drawable.li_trash_2, getString(R.string.menu_uninstall_off), false) {}
    return Row(R.drawable.li_trash_2, getString(R.string.menu_uninstall), true) {
        runCatching { startActivity(Intent(Intent.ACTION_DELETE, android.net.Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

/**
 * "Hôm nay 45 phút · còn 15 phút · 12 thông báo" under the app's name (Digital Wellbeing / One UI show today's use
 * there): time from the Guard's last decision (no extra usage query), the tightest limit left (app, group, daily),
 * the notification count read from the history afterwards. Parts that don't apply are left out.
 */
private fun HomeActivity.appInfoLine(pkg: String, tv: TextView) {
    fun dur(ms: Long) = HomeLogic.hm(ms).let { (h, m) -> if (h == 0) getString(R.string.home_m, m) else if (m == 0) getString(R.string.home_h, h) else getString(R.string.home_hm, h, m) }
    val parts = mutableListOf<String>()
    runCatching {
        val s = Store.of(this); val r = s.rules
        val dv = decision(r, s)
        val used = dv.usage[pkg] ?: 0L
        if (used >= 60_000L) parts += getString(R.string.hm_used_today, dur(used))
        // monitor-only rules block nothing; apps on the allow list don't count against the daily limit
        if (!Flavor.HOME && pkg !in dv.prot && !r.monitor) {
            val left = listOf(LauncherLogic.appLeftMs(r, dv.usage, pkg), if (pkg in r.allow) -1L else dv.leftMs).filter { it >= 0 }.minOrNull()
            if (left != null) parts += getString(R.string.hm_left_today, dur(left))
        }
    }
    fun show() { tv.text = parts.joinToString(" · "); tv.visibility = if (parts.isEmpty()) View.GONE else View.VISIBLE }
    show()
    if (NotifDots.granted(this)) NotifLog.today(this, pkg) { n ->
        if (n > 0 && tv.isAttachedToWindow) { parts += resources.getQuantityString(R.plurals.hm_notifs_today, n, n); show() }
    }
}

/** Long-press on a pinned shortcut: take it off the home, or edit the home. */
internal fun HomeActivity.shortcutMenu(item: HItem, v: View) = menu(listOf(
    Row(R.drawable.li_circle_minus, getString(R.string.hm_unpin), true) { model?.let { m -> update(HomeLayout.removeIds(m, setOf(item.id))) } },
    Row(R.drawable.li_move, getString(R.string.hm_edit_home), true) { enterEdit(item.id) },
), v)

internal fun HomeActivity.emptyMenu(x: Int, y: Int) = menu(listOf(
    // Pixel's 3 (widgets / edit / style), most used first. Study mode and seen notifications live in the control center,
    // KidGuard in the drawer (LauncherLogic.show keeps it): 6 rows with two same-looking icons were hard to scan (owner)
    Row(R.drawable.li_layout_grid, getString(R.string.hm_w_title), true) { openPicker() },
    Row(R.drawable.li_move, getString(R.string.hm_edit_home), true) { enterEdit() },
    Row(R.drawable.li_sliders_horizontal, getString(R.string.hm_customize), true) { runCatching { startActivity(Intent(this, CustomActivity::class.java)) } },
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
    for ((it, e) in others) sh.body.addView(rowOf(ImageView(this).apply { setImageBitmap(IconCache.get(this@addToFolderSheet, e.pkg, e.cls, dp(40), model?.style?.shape ?: "soft")) }, e.label) {
        val name = autoName(e.pkg, a.pkg)
        HomeLayout.addTo(m, a.pkg, it.id, name)?.let { n -> update(n); toast(getString(R.string.hm_f_added, name)) }
    })
    if (folders.isEmpty() && others.isEmpty()) sh.body.addView(text(getString(R.string.hm_f_none), 15f, pal.sub).apply { setPadding(dp(16), dp(16), dp(16), dp(16)) })
    sh.show()
}

internal fun HomeActivity.toast(t: String) = Toasts.show(this, t)

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
    // (not for the control center: no text field there, and the IME call relayouted the window, 16 ms, Oppo A5s)
    if (v !== ccKeep?.scrim) runCatching { v.findFocus()?.clearFocus(); (getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0) }
    val ex = overlayExit; overlayExit = null
    // the control center stays in the root, hidden (CcKeep): nothing in the home changed, so no re-render either
    val done = if (v === ccKeep?.scrim) ({ v.visibility = View.INVISIBLE }) else ({ root.removeView(v); if (drag == null) main.post { render(false) } })
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
    Motion.fade(f, 1f)
    return f
}

