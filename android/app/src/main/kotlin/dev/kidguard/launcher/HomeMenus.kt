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
        LinearLayout.LayoutParams(-1, dp(52)).apply { setMargins(dp(12), dp(16), dp(12), 0) })
    body.addView(kit.pill(getString(R.string.hm_later), null, false) { sh.dismiss() }, LinearLayout.LayoutParams(-1, dp(52)).apply { setMargins(dp(12), dp(8), dp(12), 0) })
    sh.show()
}

// ---- menus ----


internal fun HomeActivity.menu(rows: List<Row>, anchor: View?, x: Int = 0, y: Int = 0) {
    popup?.dismiss()
    val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = fill(pal.sheet, 16f); setPadding(0, dp(6), 0, dp(6)); elevation = dpf(8f) }
    val pw = PopupWindow(box, dp(250), ViewGroup.LayoutParams.WRAP_CONTENT, true)
    for (r in rows) box.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12))
        val c = if (r.enabled) pal.text else pal.sub
        addView(ImageView(context).apply { setImageDrawable(Ic.get(context, r.icon, if (r.enabled) pal.onTint else pal.sub, dp(18))); background = fill(if (r.enabled) pal.tint else pal.bg); setPadding(dp(6), dp(6), dp(6), dp(6)) },
            LinearLayout.LayoutParams(dp(30), dp(30)))
        addView(text(r.label, 15f, c).apply { setPadding(dp(12), 0, 0, 0) })
        isEnabled = r.enabled
        kit.tap(this, 10f)
        if (r.enabled) setOnClickListener { pw.dismiss(); r.run() }
        contentDescription = r.label
    }, LinearLayout.LayoutParams(-1, -2))
    pw.elevation = dpf(8f)
    pw.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
    pw.setOnDismissListener { if (popup === pw) popup = null }
    popup = pw
    // place it ourselves: showAsDropDown on an item near the bottom cut the last rows off (Android 8 doesn't flip it)
    box.measure(View.MeasureSpec.makeMeasureSpec(dp(250), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
    val h = box.measuredHeight
    val rl = IntArray(2).also { root.getLocationInWindow(it) }
    val (px, py) = if (anchor != null) {
        val l = IntArray(2).also { anchor.getLocationInWindow(it) }
        val below = l[1] - rl[1] + anchor.height - dp(6)
        l[0] - rl[0] to (if (below + h <= root.height - insB - dp(8)) below else l[1] - rl[1] - h + dp(6))
    } else x - rl[0] to y - rl[1]
    pw.showAtLocation(root, Gravity.TOP or Gravity.START, px.coerceAtMost(root.width - dp(260)).coerceAtLeast(dp(8)) + rl[0],
        py.coerceAtMost(root.height - insB - h - dp(8)).coerceAtLeast(insT + dp(8)) + rl[1])
}

internal fun HomeActivity.appMenu(a: AppE, v: View, mode: Mode, item: HItem?) {
    val m = model ?: return
    val placed = a.pkg in m.onHome()
    menu(listOfNotNull(
        Row(R.drawable.li_pin, getString(if (placed) R.string.hm_pinned else R.string.hm_pin), !placed) { update(HomeLayout.pin(m, a.pkg)); if (drawer != null) closeDrawer() }.takeIf { mode == Mode.DRAWER },
        Row(R.drawable.li_circle_minus, getString(R.string.hm_unpin), true) { update(HomeLayout.remove(m, a.pkg)) }.takeIf { placed || mode != Mode.DRAWER },
        Row(R.drawable.li_folder_plus, getString(R.string.hm_f_add), true) { addToFolderSheet(a) },
        // the app fills its whole cell, so most long-presses land on one: give the empty-space menu a way in from here
        Row(R.drawable.li_house, getString(R.string.hm_home_menu), true) {
            val l = IntArray(2); v.getLocationInWindow(l); emptyMenu(l[0], l[1] + v.height / 2)
        }.takeIf { mode != Mode.DRAWER },
    ), v)
}

internal fun HomeActivity.emptyMenu(x: Int, y: Int) = menu(listOf(
    Row(R.drawable.li_layout_dashboard, getString(R.string.hm_w_title), true) { openPicker() },
    Row(R.drawable.li_sliders_horizontal, getString(R.string.hm_customize), true) { runCatching { startActivity(Intent(this, CustomActivity::class.java)) } },
    Row(R.drawable.li_shield, getString(R.string.hm_open_kg), true) { openMain() },
), null, x, y)

internal fun HomeActivity.update(m: HomeModel) { HomeFile.save(this, m); model = m; key = ""; render(true) }

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
internal fun HomeActivity.openFolder(id: String) {
    val m = model ?: return
    val f = HomeLayout.find(m, id)?.second?.takeIf { it.kind == "folder" } ?: return
    val dv = lastView ?: return
    val r = Store.of(this).rules
    val all = launcherApps()
    val vis = f.apps.mapNotNull { p -> all[p]?.takeIf { lastShow[p] != null && lastShow[p] != LauncherLogic.Show.HIDE } }
    if (vis.isEmpty()) return
    popup?.dismiss()
    val scrim = FrameLayout(this).apply { setBackgroundColor(Color.argb(if (pal.dark) 140 else 90, 0, 0, 0)); isClickable = true; setOnClickListener { closeOverlay() }; tag = "folder:$id" }
    val panel = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; background = fill(pal.sheet, 28f); setPadding(dp(12), dp(14), dp(12), dp(16)); elevation = dpf(12f); isClickable = true
    }
    val title = text(folderName(f), 20f, pal.text, true).apply {
        gravity = Gravity.CENTER; setPadding(dp(12), dp(6), dp(12), dp(10))
        setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, Ic.get(this@openFolder, R.drawable.li_pencil, pal.sub, dp(18)), null); compoundDrawablePadding = dp(8)
        contentDescription = getString(R.string.hm_f_rename) + ": " + folderName(f)
        kit.tap(this, 12f) { renameFolder(id) }
    }
    panel.addView(title, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL })
    val cols = 4
    var row: LinearLayout? = null
    for ((i, a) in vis.withIndex()) {
        if (i % cols == 0) { row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }; panel.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) }) }
        row!!.addView(appIcon(a, lastShow[a.pkg] == LauncherLogic.Show.PAUSED, dv, r, Mode.PANEL, folder = f), LinearLayout.LayoutParams(0, -2, 1f))
    }
    row?.let { rw -> while (rw.childCount < cols) rw.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f)) }
    panel.addView(text(getString(R.string.hm_f_drag_out), 12f, pal.sub).apply { gravity = Gravity.CENTER; setPadding(dp(8), dp(10), dp(8), 0) }, LinearLayout.LayoutParams(-1, -2))
    scrim.addView(panel, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { setMargins(dp(16) + insL, insT + (root.height * 0.22f).toInt(), dp(16) + insR, 0) })
    showOverlay(scrim)
    panel.scaleX = 0.9f; panel.scaleY = 0.9f; panel.alpha = 0f
    panel.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(160).start()
    announce(folderName(f))
}

internal fun HomeActivity.showOverlay(v: View) {
    closeOverlay()
    overlay = v
    root.addView(v, FrameLayout.LayoutParams(-1, -1))
}

internal fun HomeActivity.closeOverlay() {
    val v = overlay ?: return
    overlay = null
    root.removeView(v)
    if (drag == null) main.post { render(false) }
}
