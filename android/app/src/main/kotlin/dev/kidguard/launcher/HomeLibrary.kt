package dev.kidguard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import dev.kidguard.HomeActivity.AppE
import dev.kidguard.HomeActivity.Mode

/**
 * The App Library (iOS): the last home page groups every visible app — suggestions (recently used), recently added,
 * then the app's own Play category (ApplicationInfo.category). Pure grouping here, unit-tested; the page below.
 */
object LibraryLogic {
    // ApplicationInfo.CATEGORY_* (API 26; ACCESSIBILITY 31), literal so the logic stays Android-free
    const val GAME = 0; const val AUDIO = 1; const val VIDEO = 2; const val IMAGE = 3; const val SOCIAL = 4
    const val NEWS = 5; const val MAPS = 6; const val PRODUCTIVITY = 7; const val ACCESSIBILITY = 8
    const val TOP = 8
    val ORDER = listOf("social", "game", "photo", "music", "news", "maps", "tools", "system", "other")

    class App(val pkg: String, val key: String, val cat: Int, val sys: Boolean)

    /** A category group key; an uncategorised preinstalled app is a "system" utility (clock, files, settings...). */
    fun key(cat: Int, sys: Boolean) = when (cat) {
        SOCIAL -> "social"; GAME -> "game"; IMAGE, VIDEO -> "photo"; AUDIO -> "music"; NEWS -> "news"; MAPS -> "maps"
        PRODUCTIVITY, ACCESSIBILITY -> "tools"; else -> if (sys) "system" else "other"
    }

    /** Groups in page order, each a list of packages: "sug" + "new" first (at most [TOP]), then the categories A–Z inside. */
    fun groups(apps: List<App>, recent: List<String>, installed: Map<String, Long>): List<Pair<String, List<String>>> {
        val have = apps.mapTo(HashSet()) { it.pkg }
        val out = ArrayList<Pair<String, List<String>>>()
        recent.filter { it in have }.distinct().take(TOP).takeIf { it.isNotEmpty() }?.let { out += "sug" to it }
        apps.filter { !it.sys && installed[it.pkg] != null }.sortedByDescending { installed[it.pkg] }.take(TOP).map { it.pkg }
            .takeIf { it.isNotEmpty() }?.let { out += "new" to it }
        val g = apps.sortedBy { it.key }.groupBy { key(it.cat, it.sys) }
        for (k in ORDER) g[k]?.takeIf { it.isNotEmpty() }?.let { out += k to it.map { a -> a.pkg } }
        return out
    }
}

internal fun HomeActivity.libName(k: String) = getString(when (k) {
    "sug" -> R.string.hm_lib_sug; "new" -> R.string.hm_lib_new; "social" -> R.string.hm_lib_social; "game" -> R.string.hm_lib_game
    "photo" -> R.string.hm_lib_photo; "music" -> R.string.hm_lib_music; "news" -> R.string.hm_lib_news; "maps" -> R.string.hm_lib_maps
    "tools" -> R.string.hm_lib_tools; "system" -> R.string.hm_lib_system; else -> R.string.hm_lib_other
})

/** Takes the library out of the pager (a drag or edit mode works on real pages only); [libraryBack] puts it back. */
internal fun HomeActivity.libraryOut() {
    val l = library ?: return
    if (l.parent !== pager) return
    val on = onLibrary()
    pager.removeView(l)
    if (on) pager.jump(pager.childCount - 1)
    buildDots()
}

internal fun HomeActivity.libraryBack() {
    val l = library ?: return
    if (l.parent != null || drag != null || editing) return
    pager.addView(l); padPages(); buildDots()
}

/** Fills the page the first time it comes into view (scrolling past it never builds ~150 icons on every render). */
internal fun HomeActivity.libraryFill() {
    val l = library ?: return
    if (l.filled) return
    l.filled = true
    val all = launcherApps()
    val set = all.keys
    val stale = installed?.first != set
    val px = iconPx(); val round = model?.style?.shape != "square"
    val vis = all.values.filter { lastShow[it.pkg] != null && lastShow[it.pkg] != LauncherLogic.Show.HIDE }
    // off the main thread: first install times (a binder call per app) and the icons not on the home yet (~7 ms each:
    // a row of the library built on the main thread was a 100 ms frame right after a cold start). Then one fill.
    Thread {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)   // never ahead of a swipe
        val t = if (stale) HashMap<String, Long>().also { m -> for (p in set) runCatching { m[p] = packageManager.getPackageInfo(p, 0).firstInstallTime } } else null
        for (a in vis) runCatching { IconCache.get(this, a.pkg, a.cls, px, round) }
        runOnUiThread { if (t != null) installed = set to t; if (library === l) l.fill(all) }
    }.start()
}

internal class LibraryPage(private val act: HomeActivity) : ScrollView(act) {
    var filled = false
    private var fillGen = 0
    private val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }

    init {
        isVerticalScrollBarEnabled = false; overScrollMode = OVER_SCROLL_NEVER
        // clipped at the top only (cards never scroll under the status bar icons); like TodayPage, the bottom room is
        // padding INSIDE the content, so cards run under the navigation bar and the last row still scrolls clear of it
        // (a bottom padding on the ScrollView itself cut the page off above the nav bar like a cropped box)
        clipToPadding = true
        addView(col, LayoutParams(-1, -2))
    }

    fun pad(top: Int, bottom: Int) {
        if (paddingTop != top || paddingBottom != 0) setPadding(0, top, 0, 0)
        if (col.paddingBottom != bottom) col.setPadding(0, 0, 0, bottom)
    }

    fun fill(all: Map<String, AppE>) = with(act) {
        col.removeAllViews()
        val vis = all.values.filter { lastShow[it.pkg] != null && lastShow[it.pkg] != LauncherLogic.Show.HIDE }
        val by = vis.associateBy { it.pkg }
        val groups = LibraryLogic.groups(vis.map { LibraryLogic.App(it.pkg, it.sortKey, it.cat, it.sys) }, recent, installed?.second.orEmpty())
        val ink = inkCache["grid"]
        val side = dp(18)
        // search: opens the drawer's search (one search for all apps)
        col.addView(kit.text(getString(R.string.hm_lib_title), 16f, pal.sub).apply {
            gravity = Gravity.CENTER; background = GlassCard(pal.card, dpf(14f), dpf(1f), pal.dark); setPadding(dp(14), dp(10), dp(14), dp(10))
            setCompoundDrawablesRelativeWithIntrinsicBounds(Ic.get(act, R.drawable.li_search, pal.sub, dp(18)), null, null, null); compoundDrawablePadding = dp(8)
            kit.tap(this, 14f) { openDrawer(); drawer?.search?.let { s -> s.requestFocus(); s.post { getSystemService(InputMethodManager::class.java)?.showSoftInput(s, 0) } } }
        }, LinearLayout.LayoutParams(-1, -2).apply { setMargins(side, dp(4), side, dp(14)) })
        // one row of 2 groups per main-loop message, not ~100 icon views in one frame (a 200 ms hitch on a cold start
        // when the page filled mid-swipe); a newer fill drops the rest of an older one
        val list = groups.mapNotNull { g -> g.second.mapNotNull { by[it] }.takeIf { it.isNotEmpty() }?.let { g.first to it } }
        val gen = ++fillGen
        fun step(i: Int) {
            if (gen != fillGen || i >= list.size) return
            // a folder opening / closing gets the frames first (opening one right after a cold start hitched 100 ms)
            if (overlay != null) { main.postDelayed({ step(i) }, 250); return }
            val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
            col.addView(row, LinearLayout.LayoutParams(-1, -2).apply { setMargins(side - dp(7), 0, side - dp(7), dp(10)) })
            for ((k, apps) in list.subList(i, minOf(i + 2, list.size))) {
                val cell = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
                cell.addView(groupCard(k, apps), LinearLayout.LayoutParams(-1, -2))
                cell.addView(text(libName(k), 13f, ink?.text ?: Color.WHITE).apply {
                    gravity = Gravity.CENTER; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(5), 0, 0)
                    ink?.let { setShadowLayer(dpf(3f), 0f, dpf(1f), it.shadow) }
                }, LinearLayout.LayoutParams(-1, -2))
                row.addView(cell, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(7), 0, dp(7), 0) })
            }
            if (row.childCount == 1) row.addView(View(act), LinearLayout.LayoutParams(0, 1, 1f).apply { setMargins(dp(7), 0, dp(7), 0) })
            main.post { step(i + 2) }
        }
        step(0)
    }

    /** A square glass card: 4 big icons, or 3 big + a 2×2 of small ones that opens the whole group. */
    private fun groupCard(k: String, apps: List<AppE>): View = with(act) {
        val card = Square(act).apply { background = GlassCard(pal.card, dpf(24f), dpf(1f), pal.dark); setPadding(dp(8), dp(8), dp(8), dp(8)) }
        val grid = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        card.addView(grid, FrameLayout.LayoutParams(-1, -1))
        val more = apps.size > 4
        val big = if (more) apps.take(3) else apps
        val dv = lastView
        val r = Store.of(act).rules
        for (y in 0..1) {
            val line = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
            grid.addView(line, LinearLayout.LayoutParams(-1, 0, 1f))
            for (x in 0..1) {
                val i = y * 2 + x
                val slot = Fit(act)
                when {
                    i < big.size && dv != null -> {
                        val a = big[i]
                        val v = appIcon(a, lastShow[a.pkg] == LauncherLogic.Show.PAUSED, dv, r, Mode.DRAWER) as LinearLayout
                        while (v.childCount > 1) v.removeViewAt(1)   // no names under the icons (iOS); the group name is under the card
                        v.contentDescription = a.label
                        slot.addView(v, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
                    }
                    i == 3 && more -> slot.addView(minis(apps.drop(3).take(4)).apply {
                        contentDescription = getString(R.string.hm_lib_more, apps.size, libName(k))
                        kit.tap(this, 16f) { openLibGroup(libName(k), apps, card) }
                        foreground = null   // sinks like the app icons beside it ([pressed]), no ripple
                        setOnTouchListener { v, e -> when (e.actionMasked) { android.view.MotionEvent.ACTION_DOWN -> pressed(v, true)
                            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> pressed(v, false) }; false }
                    }, FrameLayout.LayoutParams(-1, -1))
                }
                line.addView(slot, LinearLayout.LayoutParams(0, -1, 1f))
            }
        }
        card
    }

    /** Up to 4 small icons in a 2×2, drawn from the home-size cached bitmaps. */
    private fun minis(apps: List<AppE>): View = object : View(act) {
        val bm = apps.map { IconCache.get(act, it.pkg, it.cls, act.iconPx(), act.model?.style?.shape != "square") }
        val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val rr = RectF()
        override fun onDraw(c: Canvas) {
            val s = minOf(width, height) * 0.36f; val gap = minOf(width, height) * 0.07f
            val x0 = (width - 2 * s - gap) / 2; val y0 = (height - 2 * s - gap) / 2
            for ((i, b) in bm.withIndex()) {
                b ?: continue
                val x = x0 + (i % 2) * (s + gap); val y = y0 + (i / 2) * (s + gap)
                rr.set(x, y, x + s, y + s); c.drawBitmap(b, null, rr, p)
            }
        }
    }

    /** Height = width. */
    internal class Square(c: Context) : FrameLayout(c) {
        override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, w)
    }

    /** Scales its one child (an icon view at the drawer's 52 dp) to ~84 % of the slot, centred. */
    private class Fit(c: Context) : FrameLayout(c) {
        init { clipChildren = false }
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            super.onLayout(changed, l, t, r, b)
            val v = getChildAt(0) ?: return
            if (v.width == 0 || v.layoutParams.width == -1) return
            val s = minOf((r - l) * 0.84f / v.width, (b - t) * 0.84f / v.height).coerceIn(0.5f, 2f)
            v.scaleX = s; v.scaleY = s
        }
    }
}

/** One library group in full: the home folder's look (big name, glass card, 3×3 pages), zooming out of its card. */
internal fun HomeActivity.openLibGroup(title: String, apps: List<AppE>, from: View? = null) {
    val dv = lastView ?: return
    val r = Store.of(this).rules
    folderSheet(title, null, null, apps.map { a -> appIcon(a, lastShow[a.pkg] == LauncherLogic.Show.PAUSED, dv, r, Mode.DRAWER) to a.pkg }, from, null)
}
