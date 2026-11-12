package dev.kidguard

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.view.animation.DecelerateInterpolator
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import dev.kidguard.HomeActivity.AppE
import dev.kidguard.HomeActivity.Mode
import dev.kidguard.HomeActivity.View0
import dev.kidguard.HomeActivity.Row
import dev.kidguard.HomeActivity.Drawer

// Dock, wallpaper ink, system bars, the app drawer's open / close (split out of HomeActivity; extension functions on it).

// ---- dock ----

internal fun HomeActivity.buildDock(m: HomeModel, all: Map<String, AppE>, show: Map<String, LauncherLogic.Show>, dv: View0, r: Rules) {
    dock.removeAllViews(); dockViews.clear()
    // the card surface (HomeStyle.cardWorst): dock labels in pal.text pass on any wallpaper
    dock.background = if (m.style.dockBg) GradientDrawable().apply { setColor(pal.card); cornerRadius = dpf(28f) } else null
    dock.setPadding(dp(6), dp(8), dp(6), dp(8))
    val px = iconPx()   // same size as the app icons beside it (was 90 %: "Gọi" looked smaller)
    for ((i, slot) in m.dock.withIndex()) {
        val cell = FrameLayout(this).apply { clipChildren = false }   // notification badges poke out of the icons
        val v: View? = if (i == 0) {
            val d = dialer()
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, dp(4), 0, dp(2)) }   // as appIcon
            val bmp = d?.let { p -> IconCache.get(this, p, all[p]?.cls ?: "", px, m.style.shape) }
            box.addView(ImageView(this).apply {
                if (bmp != null) setImageBitmap(bmp) else { setImageDrawable(Ic.get(context, R.drawable.li_phone, pal.onSolid, dp(26))); background = fill(pal.solid); setPadding(dp(14), dp(14), dp(14), dp(14)) }
            }, LinearLayout.LayoutParams(px, px).apply { bottomMargin = dp(2) })
            if (preset.dockLabels && m.style.dockLabels) iconLabel(getString(R.string.hm_phone), false, if (m.style.dockBg) pal.text else Color.WHITE, 1).let {
                if (m.style.dockBg) box.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) }) else addLabel(box, it, 4)   // as app icons
            }
            box.contentDescription = getString(R.string.hm_phone)
            kit.tap(box, 16f, true) {
                if (!debounce("dial")) return@tap
                runCatching { startActivity(Intent(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    .onFailure { Toasts.show(this, R.string.hm_no_dialer) }
            }
            box.isLongClickable = true
            box.setOnLongClickListener { sosMenu(box); true }
            box
        } else all[slot]?.takeIf { show[it.pkg] != LauncherLogic.Show.HIDE }?.let { a ->
            val paused = show[a.pkg] == LauncherLogic.Show.PAUSED
            appIcon(a, paused, dv, r, Mode.DOCK, slot = i).also { v ->
                if (!preset.dockLabels || !m.style.dockLabels) (v as LinearLayout).getChildAt(1)?.let { (v as LinearLayout).removeView(it); inkViews.remove(it) }
                else if (m.style.dockBg) (v as LinearLayout).getChildAt(1)?.let { inkViews.remove(it); (it as TextView).setTextColor(pal.text) }
                // one line in the dock (the grid allows two), same size as the grid's labels
                (v as LinearLayout).getChildAt(1)?.let { (it as? IconLabel)?.most = 1 }
            }
        }
        if (v != null) cell.addView(v, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        // an empty slot takes no room, so the icons left spread out evenly (POCO / One UI); a drag opens the slots again ([startDrag])
        else if (i > 0) cell.visibility = View.GONE
        dock.addView(cell, LinearLayout.LayoutParams(0, -2, 1f))
        dockViews += cell
    }
    // One size for the whole dock, decided now (not after layout, which raced the rebuilds): the grid's label size,
    // shrunk together (down to 70 %) when the longest name would not fit its narrower cell.
    val ls = dockViews.mapNotNull { c -> ((c as? ViewGroup)?.getChildAt(0) as? ViewGroup)?.let { b -> (0 until b.childCount).map { b.getChildAt(it) }.firstOrNull { it is IconLabel } as? IconLabel } }
    if (ls.isNotEmpty()) {
        val base = labelPx(false)
        val cellW = (resources.displayMetrics.widthPixels - dp(24) - dp(12)) / m.dock.size.coerceAtLeast(1) - dp(8)
        val p = android.graphics.Paint(ls[0].paint).apply { textSize = base }
        val sc = ls.minOf { l -> p.measureText(l.text.toString()).let { if (it <= 0f) 1f else cellW / it } }.coerceIn(0.7f, 1f)
        ls.forEach { it.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, base * sc) }
    }
}

internal fun HomeActivity.sosMenu(anchor: View) {
    fun call(n: String) = runCatching { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$n")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    menu(listOfNotNull(
        // Home has no parent to ask: its long-press menu is the emergency numbers only
        if (Flavor.HOME) null else Row(R.drawable.li_siren, getString(R.string.main_sos), true) { if (Store.of(this).everPaired) acts.askSos() else call("112") },
        Row(R.drawable.li_phone, getString(R.string.hm_112), true) { call("112") },
        Row(R.drawable.li_shield, getString(R.string.hm_113), true) { call("113") },
        Row(R.drawable.li_triangle_alert, getString(R.string.hm_114), true) { call("114") },
        Row(R.drawable.li_phone, getString(R.string.hm_115), true) { call("115") },
    ), null, anchor.let { val l = IntArray(2); it.getLocationInWindow(l); l[0] }, anchor.let { val l = IntArray(2); it.getLocationInWindow(l); (l[1] - dp(300)).coerceAtLeast(dp(40)) })
}

// ---- adaptive text on the wallpaper ----

/**
 * One ink for everything that sits on the wallpaper (labels, clock, dots, hint), picked once per wallpaper from the
 * whole home area, like Pixel / iOS: every label looks the same, no per-label pills, nothing re-sampled per position
 * or page. Contrast on busy spots comes from the text shadow (stronger when the wallpaper is mid-tone).
 */
internal fun HomeActivity.inkPass() {
    val bmp = wall ?: return
    if (isFinishing) return
    val w = root.width.takeIf { it > 0 } ?: return
    val ink = inkCache.getOrPut("grid") { HomeStyle.ink(Wallpaper.sample(bmp, Rect(0, insT, w, bmp.height - insB), 48), Contrast.LARGE) }
    inkCache["dots"] = ink
    val busy = ink.ratio < Contrast.TEXT
    val shadow = if (busy) Contrast.alpha(ink.shadow, 0.85) else ink.shadow
    for (v in inkViews.toList()) {
        when (v) {
            is TextView -> { v.setTextColor(ink.text); v.setShadowLayer(dpf(if (busy) 4f else 3f), 0f, dpf(1f), shadow); v.background = null }
            is ImageView -> { v.drawable?.setTint(ink.text); v.background = null }
        }
    }
    buildDots()
    styleCells()
    bars()
}

/** Status / nav bar icons: on the -1 page they follow its surface, on the home the wallpaper behind them. */
internal fun HomeActivity.bars() {
    val bmp = wall ?: return
    val w = root.width.takeIf { it > 0 } ?: return
    val darkTop: Boolean; val darkBottom: Boolean
    if (pager.page == 0) { darkTop = !pal.dark; darkBottom = !pal.dark }
    else {
        // the wallpaper does not scroll: sample it once per wallpaper, not on every page change
        val k = "$wallKey|bars"
        val v = barsCache?.takeIf { it.first == k }?.second ?: Pair(
            HomeStyle.ink(Wallpaper.sample(bmp, Rect(0, 0, w, dp(28)))).darkText,
            HomeStyle.ink(Wallpaper.sample(bmp, Rect(0, bmp.height - dp(48), w, bmp.height))).darkText).also { barsCache = k to it }
        darkTop = v.first; darkBottom = v.second
    }
    @Suppress("DEPRECATION")
    window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
        (if (darkTop) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR else 0) or (if (Build.VERSION.SDK_INT >= 26 && darkBottom) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0)
}


// ---- drawer ----

/** Built once and kept (hidden) between opens, so a swipe can show it under the finger at once; rebuilt when the colours change. */

internal fun HomeActivity.drawerView(): Drawer = drawerCache?.also { it.refill() } ?: Drawer(this).also { d ->
    drawerCache = d
    d.setPadding(insL, insT, insR, imeB); d.visibility = View.INVISIBLE   // laid out while hidden: GONE made the first swipe frame measure the whole grid
    root.addView(d, FrameLayout.LayoutParams(-1, -1))
}

/**
 * First drawer after a cold start: its icons are rendered off the main thread first (~7 ms each on a POCO, cold; the
 * drawer built them on the main thread: 254 ms build + 35-83 ms frames under the swiping finger), then it is built.
 */
internal fun HomeActivity.drawerWarm(apps: Collection<AppE>) {
    if (drawerWarming) return
    drawerWarming = true
    val px = iconPx(); val shp = model?.style?.shape ?: "square"
    val vis = apps.filter { lastShow[it.pkg] != LauncherLogic.Show.HIDE }.sortedBy { it.sortKey }
    Thread {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)   // never ahead of a swipe
        for (a in vis) runCatching { IconCache.get(this, a.pkg, a.cls, px, shp) }
        runOnUiThread { drawerWarming = false; if (drawerCache == null && !isFinishing && !busy()) drawerView() }
    }.start()
}

/**
 * Brings the kept (hidden) drawer up to date while the main thread is idle. Coming home from an app changes the
 * "recent" row (and maybe pauses / apps): refill() then rebuilt the whole grid in the swipe's first frame, measured
 * 94-103 ms build / 183-200 ms frame on an Oppo A5s vs ~5 ms when nothing changed. No-op when nothing changed.
 */
internal fun HomeActivity.drawerPrep() {
    val d = drawerCache ?: return
    if (drawer != null) return
    android.os.Looper.myQueue().addIdleHandler { if (drawer == null && drawerCache === d && !isFinishing && !busy()) d.refill(); false }
}

/** A finger or a button starts moving the drawer: the home goes into one GPU layer so fading it costs nothing per frame. */
internal fun HomeActivity.drawerBegin(): Boolean {
    drawerAnim?.cancel(); drawerAnim = null
    if (drawer == null) {
        if (drag != null) return false
        popup?.dismiss(); closeOverlay()
        drawer = drawerView(); drawerP = 0f
    }
    content.visibility = View.VISIBLE
    content.setLayerType(View.LAYER_TYPE_HARDWARE, null)
    return true
}

/** Pixel: the drawer's top rides the finger 1:1, the home fades and shrinks a little behind it. */
internal fun HomeActivity.drawerAt(p: Float) {
    val d = drawer ?: return
    drawerP = p.coerceIn(0f, 1f)
    d.visibility = View.VISIBLE
    d.translationY = (1 - drawerP) * root.height
    content.alpha = 1 - drawerP
    val sc = 1 - 0.06f * drawerP; content.scaleX = sc; content.scaleY = sc
}

/** Finger up: a fling either way wins, else moving it 40 % of the way flips it ([wasOpen]: where the finger started). [vy] px/s, + = down. */
internal fun HomeActivity.drawerRelease(vy: Float, minFling: Float, wasOpen: Boolean) =
    drawerSettle(LauncherLogic.drawerOpens(vy, minFling, drawerP, wasOpen), vy)

internal fun HomeActivity.drawerSettle(open: Boolean, vy: Float = 0f) {
    drawer ?: return
    drawerAnim?.cancel()
    val to = if (open) 1f else 0f
    val px = abs(to - drawerP) * root.height
    val ms = (px / abs(vy).coerceAtLeast(dpf(2200f)) * 1000).toLong().coerceIn(110, 300)   // faster when flung
    var cancelled = false
    drawerAnim = ValueAnimator.ofFloat(drawerP, to).apply {
        duration = ms; interpolator = DecelerateInterpolator(1.5f)
        addUpdateListener { drawerAt(it.animatedValue as Float) }
        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationCancel(a: Animator) { cancelled = true }
            override fun onAnimationEnd(a: Animator) { if (!cancelled) drawerDone(open) }
        })
        start()
    }
}

internal fun HomeActivity.drawerDone(open: Boolean) {
    val d = drawer ?: return
    drawerAnim = null
    val kb = kbOnOpen; kbOnOpen = false
    if (open && kb) d.search.let { s -> s.requestFocus(); (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(s, 0) }
    content.setLayerType(View.LAYER_TYPE_NONE, null)
    if (open) {
        // the drawer is opaque: stop drawing the home and the wallpaper under it while it scrolls
        content.visibility = View.INVISIBLE   // the wallpaper surface stays: it costs the app nothing and hiding it would destroy it
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            (if (!pal.dark) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or (if (Build.VERSION.SDK_INT >= 26) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0) else 0)
    } else {
        drawer = null
        d.visibility = View.INVISIBLE
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(d.windowToken, 0)
        if (d.search.text.isNotEmpty()) d.search.setText("")
        d.scroll.scrollTo(0, 0)
        content.alpha = 1f; content.scaleX = 1f; content.scaleY = 1f
        bars()
    }
}

internal fun HomeActivity.openDrawer() {
    if (drawer == null) { if (!drawerBegin()) return; drawerAt(0f); kbOnOpen = Gest.drawerKb(this) } else drawerBegin()
    drawerSettle(true)
}

internal fun HomeActivity.closeDrawer() {
    drawer ?: return
    drawer?.unlight()
    // the keyboard starts sliding away now, not after the drawer is gone
    drawer?.search?.let { s -> s.clearFocus(); (getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(s.windowToken, 0) }
    drawerBegin(); drawerSettle(false)
}
