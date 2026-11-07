package dev.kidguard

import android.annotation.SuppressLint
import android.animation.ValueAnimator
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.util.Log
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.provider.Telephony
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.OverScroller
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import java.util.Calendar
import kotlin.math.abs
import dev.kidguard.HomeLayout.Src

/**
 * v0.9.9 kid launcher (protocol "v0.9.9: kid launcher"). Turned on by the `launcher` rule (LauncherSafe).
 * Pager: [-1 "Hôm nay"] [page 1] [page 2 …]; an R x C grid per page, a dock with the phone fixed in slot 1, an A–Z
 * drawer (swipe up). Long-press an app: menu, then drag (folders: drop an app on another); long-press empty space:
 * Tiện ích / Tuỳ chỉnh / Sắp xếp / KidGuard. Every render filters through the Guard's decide() result: blocked / new /
 * hidden apps are never shown (grid, folders, dock, drawer), paused ones are greyed with a clock badge.
 * Pure parts: LauncherLogic, HomeLayout, HomeStyle (contrast), KidNotes. Text on the wallpaper adapts (HomeStyle.ink).
 */
class HomeActivity : Activity() {
    companion object { @Volatile var alive = 0; const val FOLDER_MS = 500L }

    internal class AppE(val pkg: String, val cls: String, val label: String) {
        val sortKey by lazy { LauncherLogic.sortKey(label) }   // once per app list: sortedBy calls its selector per comparison
    }

    /** What is being dragged: a grid / dock / folder / picker source, or [pkg] from the drawer. */
    internal class DragSt(val src: Src?, val pkg: String?, val kind: String, val w: Int, val h: Int, val id: String?, val view: View?)

    internal class CellLP(val x: Int, val y: Int, val w: Int, val h: Int) : ViewGroup.LayoutParams(-1, -1)

    internal enum class Mode { HOME, DOCK, DRAWER, PANEL }

    internal val main = Handler(Looper.getMainLooper())
    internal lateinit var u: Ui
    internal var pal: Palette = HomeStyle.palette("blue", false)
    internal lateinit var kit: HomeKit
    internal lateinit var preset: HomeStyle.Preset
    internal lateinit var acts: KidActions
    internal var dark = false
    internal lateinit var root: FrameLayout
    internal lateinit var wallView: ImageView
    internal var wall: Bitmap? = null
    internal var wallKey = ""
    internal lateinit var content: SwipeUp
    internal lateinit var chrome: LinearLayout
    /** Floats over the pages: the edit / drop bar on top, the drag tip above the dots. Its contents never move the grid. */
    internal lateinit var floatBox: FrameLayout
    internal lateinit var bannerBox: FrameLayout
    internal lateinit var topBar: FrameLayout
    internal lateinit var bottom: LinearLayout
    internal lateinit var tip: TextView
    internal lateinit var pager: Pager
    internal lateinit var dots: LinearLayout
    internal lateinit var hint: ImageView
    internal lateinit var dock: LinearLayout
    internal var today: TodayPage? = null
    internal var drawer: Drawer? = null
    internal var popup: PopupWindow? = null
    internal var overlay: View? = null            // folder panel or widget picker (views, not dialogs: a drag survives them)
    internal var apps: Map<String, AppE>? = null
    internal var model: HomeModel? = null
    internal var key = ""
    internal var lastTap = 0L
    internal var drag: DragSt? = null
    internal var tempPage = false
    internal var insT = 0; internal var insB = 0; internal var insL = 0; internal var insR = 0
    internal val inkViews = ArrayList<View>()      // TextViews / dot rows that sit right on the wallpaper
    internal val inkCache = HashMap<String, Ink>()
    internal val itemViews = HashMap<String, View>()   // grid item id -> its view on the current render
    internal val dockViews = ArrayList<View>()
    internal val listener: () -> Unit = { render(false) }
    internal var timeRx: BroadcastReceiver? = null
    internal var pkgRx: BroadcastReceiver? = null
    internal var lastView: View0? = null
    internal var lastShow: Map<String, LauncherLogic.Show> = emptyMap()

    internal fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    internal fun dpf(v: Float) = v * resources.displayMetrics.density

    override fun getResources(): android.content.res.Resources = Lang.res(baseContext) ?: super.getResources()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        LauncherSafe.watch(this)
        alive++
        if (LauncherSafe.takeReset(this)) { HomeFile.reset(this); IconCache.clear() }
        u = Ui(this, pal); kit = HomeKit(this, pal)
        acts = KidActions(this, u) { key = ""; render(true) }
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = Color.TRANSPARENT; window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 29) { window.isNavigationBarContrastEnforced = false; window.isStatusBarContrastEnforced = false }
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        root = FrameLayout(this)
        wallView = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_XY; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        root.addView(wallView, FrameLayout.LayoutParams(-1, -1))
        content = SwipeUp(this)
        pager = Pager(this)
        content.addView(pager, FrameLayout.LayoutParams(-1, -1))
        // chrome over the pages: banner at the top, tip + dots + hint + dock at the bottom; touches in between fall through
        chrome = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        bannerBox = FrameLayout(this)
        topBar = FrameLayout(this).apply { visibility = View.GONE; clipChildren = false; clipToPadding = false }   // the remove circle leans out of it
        bottom = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        tip = TextView(this).apply { visibility = View.GONE; setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); setPadding(dp(16), dp(9), dp(16), dp(9)); gravity = Gravity.CENTER }
        dots = LinearLayout(this).apply { gravity = Gravity.CENTER; orientation = LinearLayout.HORIZONTAL }
        hint = ImageView(this).apply {
            contentDescription = getString(R.string.hm_all_apps); setPadding(dp(10), dp(2), dp(10), dp(2))
            setOnClickListener { openDrawer() }
        }
        dock = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        chrome.addView(bannerBox, LinearLayout.LayoutParams(-1, -2))
        chrome.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))
        bottom.addView(dots, LinearLayout.LayoutParams(-1, dp(18)))
        bottom.addView(hint, LinearLayout.LayoutParams(-2, dp(22)))
        bottom.addView(dock, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(12), dp(4), dp(12), dp(10)) })
        chrome.addView(bottom, LinearLayout.LayoutParams(-1, -2))
        content.addView(chrome, FrameLayout.LayoutParams(-1, -1))
        floatBox = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
        floatBox.addView(topBar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        floatBox.addView(tip, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        content.addView(floatBox, FrameLayout.LayoutParams(-1, -1))
        root.addView(content, FrameLayout.LayoutParams(-1, -1))
        root.setOnApplyWindowInsetsListener { _, ins ->
            @Suppress("DEPRECATION") run { insL = ins.systemWindowInsetLeft; insT = ins.systemWindowInsetTop; insR = ins.systemWindowInsetRight; insB = ins.systemWindowInsetBottom }
            chrome.setPadding(insL, insT, insR, insB)
            floatBox.setPadding(insL, insT, insR, insB)
            drawerCache?.setPadding(insL, insT, insR, insB)
            today?.pad(insT, insB)
            padPages()
            ins
        }
        chrome.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> padPages() }
        root.addOnLayoutChangeListener { _, l, t, r, btm, ol, ot, or_, ob -> if (r - l != or_ - ol || btm - t != ob - ot) { ensureWall(); main.post { inkPass() } } }
        root.setOnDragListener { _, e -> onDrag(e) }
        setContentView(root)
        render(true)
        pager.post { pager.jump(1) }
    }

    override fun onDestroy() { alive--; super.onDestroy() }

    override fun onResume() {
        super.onResume()
        Guard.listeners.add(listener)
        timeRx = object : BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) { render(false) } }
        registerReceiver(timeRx, IntentFilter().apply { addAction(Intent.ACTION_TIME_TICK); addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_TIMEZONE_CHANGED) })
        if (pkgRx == null) {
            // the key covers the app set, labels and pauses: the Guard re-suspending the same apps (every poke) must not rebuild the home
            pkgRx = object : BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) { apps = null; Policy.invalidate(); render(false) } }
            registerReceiver(pkgRx, IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED); addAction(Intent.ACTION_PACKAGE_CHANGED)
                addAction(Intent.ACTION_PACKAGES_SUSPENDED); addAction(Intent.ACTION_PACKAGES_UNSUSPENDED); addDataScheme("package")
            })
        }
        Guard.poke(this)
        model = null          // Tuỳ chỉnh may have changed home.json (HomeFile's cache)
        apps = null           // package changes while stopped are not broadcast to us
        render(false)         // rebuilds only when the key changed: coming back from an app is free
    }

    override fun onPause() {
        Guard.listeners.remove(listener)
        timeRx?.let { runCatching { unregisterReceiver(it) } }; timeRx = null
        popup?.dismiss()
        super.onPause()
    }

    override fun onStop() {
        pkgRx?.let { runCatching { unregisterReceiver(it) } }; pkgRx = null
        super.onStop()
    }

    /** HOME pressed while we are in front: close what is open (one thing at a time), else back to page 1. */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        popup?.dismiss()
        when {
            overlay != null -> closeOverlay()
            drawer != null -> closeDrawer()
            else -> pager.snapTo(1)
        }
    }

    @Deprecated("still the way back works on API 26-32")
    override fun onBackPressed() {
        when {
            overlay != null -> closeOverlay()
            drawer != null -> closeDrawer()
            pager.page != 1 -> pager.snapTo(1)
        }
    }

    // ---------------- data ----------------

    internal fun launcherApps(): Map<String, AppE> {
        apps?.let { return it }
        val pm = packageManager
        val out = LinkedHashMap<String, AppE>()
        @Suppress("DEPRECATION")
        for (ri in pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)) {
            val ai = ri.activityInfo ?: continue
            if (ai.packageName !in out) out[ai.packageName] = AppE(ai.packageName, ai.name, ri.loadLabel(pm).toString())
        }
        return out.also { apps = it }
    }

    internal class View0(val want: Map<String, String>, val leftMs: Long, val usage: Map<String, Long>, val minute: Int, val dow: Int, val now: Long,
                        val apps: Set<String>, val prot: Set<String>, val today: String)

    /** The Guard's last decision, or the same decide() run here when the Guard has not ticked yet. */
    internal fun decision(r: Rules, s: Store): View0 {
        val now = System.currentTimeMillis(); val today = Guard.day(now)
        val cal = Calendar.getInstance()
        val minute = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        Guard.snap?.takeIf { now - it.now < 30_000 }?.let { return View0(it.want, it.leftMs, it.usageMs, minute, it.dow, now, it.apps, it.prot, today) }
        val (all, prot) = Policy.cached(this)
        val use = runCatching { Usage.today(this, now).ms }.getOrDefault(emptyMap())
        val want = decide(r, use, minute, now, today, all.keys, prot, unlockUntil = s.unlockUntil)
        return View0(want, dailyLeftMs(r, use, today, all.keys, prot), use, minute, Rules.isoDow(today), now, all.keys, prot, today)
    }

    internal fun resolvePkg(i: Intent): String? = runCatching {
        packageManager.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName?.takeIf { it != "android" && !it.contains("resolver") }
    }.getOrNull()

    internal fun dialer(): String? = resolvePkg(Intent(Intent.ACTION_DIAL))

    internal fun firstModel(r: Rules, visible: List<AppE>): HomeModel {
        val vis = visible.map { it.pkg }.toSet()
        val dockApps = listOfNotNull(
            runCatching { Telephony.Sms.getDefaultSmsPackage(this) }.getOrNull(),
            resolvePkg(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)),
            resolvePkg(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))),
        ).filter { it in vis }
        val d = dialer()
        return HomeLayout.fresh(r.launcher.style.ifEmpty { "teen" }, visible.map { it.pkg }.filter { it != d }, dockApps)
    }

    // ---------------- render ----------------

    internal fun busy() = drag != null || overlay != null || popup?.isShowing == true || pager.dragging

    internal fun render(force: Boolean) {
        val t0 = android.os.SystemClock.elapsedRealtime()
        try { render0(force) } finally {
            val ms = android.os.SystemClock.elapsedRealtime() - t0
            if (ms > 16) android.util.Log.i("KG.perf", "home render ${ms}ms force=$force")
        }
    }

    internal class Lap { val t = android.os.SystemClock.elapsedRealtime(); var l = t; val sb = StringBuilder()
        operator fun invoke(n: String) { val x = android.os.SystemClock.elapsedRealtime(); sb.append(n).append('=').append(x - l).append(' '); l = x }
        fun done(n: String) { if (android.os.SystemClock.elapsedRealtime() - t > 16) android.util.Log.i("KG.perf", "$n $sb") } }

    internal fun render0(force: Boolean) {
        if (isFinishing || isDestroyed) return
        val s = Store.of(this); val r = s.rules
        val lap = Lap()
        val all = launcherApps(); lap("apps")
        val dv = decision(r, s); lap("decision")
        val show = all.keys.associateWith { LauncherLogic.show(it, dv.want, r, packageName) }; lap("show")
        val visible = all.values.filter { show[it.pkg] != LauncherLogic.Show.HIDE }.sortedBy { it.sortKey }; lap("sort")
        var m = model ?: HomeFile.load(this) ?: firstModel(r, visible); lap("model")
        m = HomeLayout.prune(m) { it !in all && !Policy.installed(this, it) }; lap("prune")
        val dial = dialer()
        m = HomeLayout.appendNew(m, visible.map { it.pkg }.filter { it != dial })
        if (m != model) { HomeFile.save(this, m); model = m }
        preset = HomeStyle.preset(m.style.preset)
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        dark = m.style.theme == "dark" || (m.style.theme == "auto" && night)
        val accent = m.style.accent.ifEmpty { if (dark) preset.accentDark else preset.accentLight }
        val np = HomeStyle.palette(accent, dark)
        if (np != pal || today == null) {
            // the launcher's own accent and light / dark for its modals and sheets ("Xin giờ" & co)
            pal = np; u = Ui(this, pal); kit = HomeKit(this, pal, m.style.font.toFloat())
            // the kept drawer has the old colours baked in: drop it unless it is on screen
            drawerCache?.takeIf { drawer == null }?.let { root.removeView(it); drawerCache = null }
            acts = KidActions(this, u) { key = ""; render(true) }
            today = TodayPage(this, kit, u, acts, { model }, { update(it) }, { openMain() }).also { it.onRefresh = { key = ""; render(true) }; it.pad(insT, insB) }
        } else if (kit.font != m.style.font.toFloat()) kit = HomeKit(this, pal, m.style.font.toFloat())
        val block = QuickLogic.phoneBlock(dv.want)
        lap("palette")
        val banner = LauncherLogic.banner(Policy.isOwner(this), Guard.running != null, Usage.granted(this), locationOn(), Acc.isOn(this))
        val files = runCatching { Files.all(this) }.getOrDefault(emptyList()).joinToString(",") { it.id + it.st }
        // not the minute itself: what it changes (next event, today's schedule states, the date); the clock text is set in place below
        val timeKey = listOf(dv.today, LauncherLogic.nextEvent(r, dv.minute, dv.dow), LauncherLogic.today(r, dv.minute, dv.dow).map { it.state })
        val k = listOf(m.toJson().toString(), all.values.joinToString(",") { it.label }, show.toString(), dv.want.toString(), block, banner, timeKey, dv.leftMs / 60_000, s.everPaired,
            s.sos().length(), s.lastCheckin("arrived"), s.lastCheckin("pickup"), s.requests().toString(), r.blockNote, r.launcher, dark, files,
            r.chores, r.schedules, r.groups.map { it.hours }, r.bedtimeOn, r.bedStart, r.bedEnd, r.lockContact).joinToString("|")
        lap("key")
        if (!force && k == key) { tickClocks(dv); lap.done("same"); return }
        if (busy()) { main.postDelayed({ render(false) }, 1500); return }   // never rebuild under a finger / an open panel
        key = k
        lastView = dv; lastShow = show
        tempPage = false
        ensureWall()
        buildBanner(banner)
        buildTopBar()
        buildPages(m, all, show, dv, r, s, block)
        buildDock(m, all, show, dv, r)
        hint.setImageDrawable(Ic.get(this, R.drawable.li_chevron_up, Color.WHITE, dp(18)))
        inkViews += hint
        today?.fill(r, dv.minute, dv.dow, dv.now)
        lap("build"); lap.done("rebuild")
        // no-op unless what it shows changed; the first one is built while idle, so the first swipe up has it ready under the finger
        drawerCache?.let { d -> main.post { d.refill() } } ?: main.postDelayed({ if (drawerCache == null && !isFinishing && !busy()) drawerView() }, 800)
        main.post { inkPass(); chromeFade() }
    }

    internal fun ensureWall() {
        val w = root.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val h = root.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        val st = model?.style ?: HStyle()
        val p = if (::preset.isInitialized) preset else HomeStyle.preset(st.preset)
        val photo = st.wallKind == "photo"
        val id = st.wall.takeIf { it.isNotEmpty() } ?: if (dark) p.wallDark else p.wallLight
        val k = (if (photo) "photo:" + java.io.File(filesDir, Wallpaper.PHOTO).lastModified() + ":" + st.dim else id) + "@${w}x$h"
        if (k == wallKey) return
        wallKey = k; inkCache.clear()
        val old = wall
        wall = (if (photo) Wallpaper.photo(this, w, h)?.also { Wallpaper.dim(it, st.dim) } else null) ?: Wallpaper.draw(id, w, h)
        wallView.setImageBitmap(wall)
        old?.recycle()
    }

    internal fun locationOn(): Boolean = runCatching {
        val lm = getSystemService(LocationManager::class.java)
        if (Build.VERSION.SDK_INT >= 28) lm.isLocationEnabled
        else @Suppress("DEPRECATION") Settings.Secure.getInt(contentResolver, Settings.Secure.LOCATION_MODE) != Settings.Secure.LOCATION_MODE_OFF
    }.getOrDefault(true)

    internal fun card(radius: Int = 22) = GradientDrawable().apply { setColor(pal.card); cornerRadius = dpf(radius.toFloat()) }
    internal fun fill(color: Int, radius: Float = 999f) = GradientDrawable().apply { setColor(color); cornerRadius = dpf(radius) }
    internal fun text(t: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t; setTextSize(TypedValue.COMPLEX_UNIT_SP, sp * (model?.style?.font?.toFloat() ?: 1f)); setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        includeFontPadding = false
    }

    internal fun buildBanner(code: String?) {
        bannerBox.removeAllViews()
        if (code == null) return
        val msg = when (code) { "owner" -> R.string.hm_b_owner; "usage" -> R.string.hm_b_usage; "location" -> R.string.hm_b_location; "acc" -> R.string.hm_b_acc; else -> R.string.hm_b_guard }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = fill(pal.sheet, 18f); setPadding(dp(12), dp(8), dp(8), dp(8)); elevation = dpf(2f)
        }
        row.addView(ImageView(this).apply { setImageDrawable(Ic.get(this@HomeActivity, R.drawable.li_triangle_alert, pal.onWarn, dp(16))); background = fill(pal.warn); setPadding(dp(5), dp(5), dp(5), dp(5)) },
            LinearLayout.LayoutParams(dp(26), dp(26)))
        row.addView(text(getString(msg), 14f, pal.text).apply { setPadding(dp(10), 0, dp(8), 0) }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(TextView(this).apply {
            text = getString(R.string.hm_fix); setTextColor(pal.onSolid); background = fill(pal.solid); setPadding(dp(14), dp(7), dp(14), dp(7))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            kit.tap(this, 999f, true) { openMain() }
        })
        bannerBox.addView(row, FrameLayout.LayoutParams(-1, -2).apply { setMargins(dp(14), dp(6), dp(14), dp(2)) })
    }

    /** While dragging: the round remove target. Hidden otherwise. */
    internal fun buildTopBar() {
        topBar.removeAllViews()
        val dragging = drag != null
        topBar.visibility = if (dragging) View.VISIBLE else View.GONE
        removeBtn = null; removeHot = false
        if (!dragging) return
        // a small faded circle; it leans toward the item as it comes near and turns red only inside the drop zone
        val b = ImageView(this).apply {
            setImageDrawable(Ic.get(this@HomeActivity, R.drawable.li_x, pal.text, dp(24))); scaleType = ImageView.ScaleType.CENTER
            background = fill(pal.sheet); alpha = 0.5f; elevation = dpf(3f); contentDescription = getString(R.string.hm_unpin)
        }
        removeBtn = b
        topBar.addView(b, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(10); bottomMargin = dp(4) })
    }

    internal fun announce(t: String) = root.announceForAccessibility(t)

    /**
     * Home pages leave room for the chrome (status bar + banner on top, dots + dock below). Frozen while an
     * item is dragged: the drop bar and the tip float over the grid, so the grid neither shrinks nor jumps.
     */
    internal fun padPages() {
        if (!::pager.isInitialized) return
        (tip.layoutParams as? FrameLayout.LayoutParams)?.let { lp -> val b = bottom.height + dp(8); if (lp.bottomMargin != b) { lp.bottomMargin = b; tip.layoutParams = lp } }
        if (drag != null) return
        val top = insT + bannerBox.height + (if (topBar.visibility == View.VISIBLE) topBar.height else 0)
        val bot = insB + bottom.height
        for (i in 1 until pager.childCount) {
            val c = pager.getChildAt(i)
            if (c.paddingTop != top || c.paddingBottom != bot) c.setPadding(0, top, 0, bot)
        }
    }

    internal fun buildPages(m: HomeModel, all: Map<String, AppE>, show: Map<String, LauncherLogic.Show>, dv: View0, r: Rules, s: Store, block: String?) {
        inkViews.clear(); itemViews.clear(); clocks.clear()
        val keep = pager.page
        pager.removeAllViews()
        today?.view?.let { (it.parent as? ViewGroup)?.removeView(it); pager.addView(it) }
        val hasNext = HomeLayout.hasWidget(m, "next")
        for ((pi, p) in m.pages.withIndex()) {
            val cell = CellLayout(this, m.cols, m.rows)
            for (it in p) {
                val v: View = when (it.kind) {
                    "widget" -> when (it.type) {
                        "clock" -> clockWidget(r, dv, it.w, it.h, hasNext)
                        "time" -> timeWidget(r, dv, block, s, it.w)
                        "quick" -> quickWidget(s, it.w)
                        "next" -> nextWidget(r, dv)
                        "counts" -> countsWidget(r, m)
                        else -> null
                    }?.also { v -> armWidget(v, it) }
                    "app" -> all[it.pkg]?.takeIf { a -> show[a.pkg] != LauncherLogic.Show.HIDE }?.let { a ->
                        appIcon(a, show[a.pkg] == LauncherLogic.Show.PAUSED, dv, r, Mode.HOME, it)
                    }
                    "folder" -> {
                        // never stored filtered: what shows is decided at each render; one visible app shows as that app
                        val vis = it.apps.mapNotNull { p -> all[p]?.takeIf { a -> show[a.pkg] != LauncherLogic.Show.HIDE } }
                        when (vis.size) {
                            0 -> null
                            1 -> appIcon(vis[0], show[vis[0].pkg] == LauncherLogic.Show.PAUSED, dv, r, Mode.HOME, it)
                            else -> folderIcon(it, vis, show)
                        }
                    }
                    else -> null
                } ?: continue
                itemViews[it.id] = v
                cell.addView(v, CellLP(it.x, it.y, it.w, it.h))
            }
            cell.page = pi
            pager.addView(cell)
        }
        styleCells()
        padPages()
        pager.page = keep.coerceIn(0, pager.childCount - 1).let { if (it == 0 && keep != 0) 1 else it }
        pager.onPage = { buildDots(); bars(); chromeFade() }
        buildDots()
        pager.post { pager.scrollTo(pager.page * pager.width, 0); chromeFade() }
    }

    /** While dragging: the page panel in the wallpaper's ink colour. */
    internal fun styleCells() {
        val on = drag != null
        val c = inkCache["grid"]?.text ?: Color.WHITE
        for (i in 1 until pager.childCount) (pager.getChildAt(i) as? CellLayout)?.let { it.grid = on; it.gridColor = c; it.invalidate() }
    }

    /** -1 page: the chrome (dots, dock, banner) slides away with the pages; status bar icons follow the page. */
    internal fun chromeFade() {
        val w = pager.width.takeIf { it > 0 } ?: return
        val f = (pager.scrollX.toFloat() / w).coerceIn(0f, 1f)     // 0 on "Hôm nay", 1 on page 1+
        chrome.alpha = f
        chrome.visibility = if (f < 0.02f) View.INVISIBLE else View.VISIBLE
        // mid-fade: one GPU layer, so the alpha does not redraw dock + dots + banner offscreen every frame
        val lt = if (f > 0.02f && f < 0.98f) View.LAYER_TYPE_HARDWARE else View.LAYER_TYPE_NONE
        if (chrome.layerType != lt) chrome.setLayerType(lt, null)
        chrome.translationX = (1 - f) * w * 0.3f
    }

    internal fun buildDots() {
        dots.removeAllViews()
        val n = pager.childCount - 1
        dots.contentDescription = getString(R.string.hm_page, pager.page.coerceAtLeast(1), n.coerceAtLeast(1))
        val ink = inkCache["dots"]
        val c = ink?.text ?: Color.WHITE
        // the -1 page: a small calendar glyph, like Pixel's "Discover" mark
        dots.addView(ImageView(this).apply { setImageDrawable(Ic.get(this@HomeActivity, R.drawable.li_calendar, c, dp(10))); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
            LinearLayout.LayoutParams(dp(10), dp(10)).apply { setMargins(dp(4), 0, dp(4), 0) })
        for (i in 1..n) dots.addView(View(this).apply {
            // active = a filled pill, others = a ring of the same ink: both keep full contrast (no faded dot)
            background = GradientDrawable().apply { cornerRadius = dpf(4f); if (i == pager.page) setColor(c) else { shape = GradientDrawable.OVAL; setStroke(dp(1).coerceAtLeast(2), c) } }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(if (i == pager.page) dp(16) else dp(7), dp(7)).apply { setMargins(dp(4), 0, dp(4), 0) })
        if (dots !in inkViews) inkViews += dots
        dots.background = null
    }

    // ---- state used by the split-out files (HomeDrag / HomeWidgets / HomeDock / HomeMenus) ----

    internal class Row(val icon: Int, val label: String, val enabled: Boolean, val run: () -> Unit)
    internal val clocks = ArrayList<Pair<TextView, TextView>>()
    internal var armed: View? = null
    internal var downX = 0f; internal var downY = 0f
    internal var flip: Runnable? = null
    internal var hoverId: String? = null          // folder target: the finger rested on its icon (see FOLDER_MS)
    internal var folderArm: Runnable? = null
    internal var folderReady = false
    internal var swapId: String? = null           // item sliding out of the way (preview of the re-arrange)
    internal var removeBtn: View? = null
    internal var removeHot = false
    internal var removeNear = false               // heading for the remove circle: no cell highlight under it
    internal var dropped = false                  // the drop changed the layout: endDrag leaves the views for the new render
    internal var land: Triple<DragSt, Float, Float>? = null
    internal var barsCache: Pair<String, Pair<Boolean, Boolean>>? = null
    internal var drawerCache: Drawer? = null
    internal var drawerP = 0f                       // 0 closed .. 1 open
    internal var drawerAnim: ValueAnimator? = null

    /** A–Z drawer like Pixel's: search on top, ONE continuous grid (the home's column count), letters only in the side scrubber. */
    internal inner class Drawer(ctx: Context) : LinearLayout(ctx) {
        val search = EditText(ctx)
        val list = LinearLayout(ctx).apply { orientation = VERTICAL }
        val rail = LinearLayout(ctx).apply { orientation = VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        val firstRow = LinkedHashMap<String, View>()     // letter -> the grid row holding its first app
        val bubble = TextView(ctx)
        val scroll = ScrollView(ctx)
        private var lastKey = ""
        // pull down while the list is at its top: the drawer follows the finger down, then closes or springs back (Pixel)
        private var py0 = 0f; private var px0 = 0f; private var pulling = false; private var pullFrom = 0f
        private var pvt: VelocityTracker? = null
        private val pslop = ViewConfiguration.get(ctx).scaledTouchSlop
        private val pfling = ViewConfiguration.get(ctx).scaledMinimumFlingVelocity * 4f

        override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
            ptrack(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { py0 = e.rawY; px0 = e.rawX; pulling = false }
                MotionEvent.ACTION_MOVE -> {
                    val dy = e.rawY - py0
                    val onRail = e.x >= width - paddingRight - dp(26)
                    if (!pulling && !onRail && drag == null && popup?.isShowing != true && drawerAnim == null && scroll.scrollY == 0 &&
                        dy > pslop && dy > abs(e.rawX - px0) * 1.5f) { pulling = true; pullFrom = e.rawY; drawerBegin() }
                }
            }
            return pulling
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            ptrack(e)
            if (!pulling) return super.onTouchEvent(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> drawerAt(1 - (e.rawY - pullFrom) / root.height)
                MotionEvent.ACTION_UP -> { pvt?.computeCurrentVelocity(1000); pulling = false; drawerRelease(pvt?.yVelocity ?: 0f, pfling, true) }
                MotionEvent.ACTION_CANCEL -> { pulling = false; drawerSettle(true) }
            }
            return true
        }

        private fun ptrack(e: MotionEvent) {
            if (e.actionMasked == MotionEvent.ACTION_DOWN) { pvt?.recycle(); pvt = VelocityTracker.obtain() }
            // screen coordinates: this view slides down with the finger, so its own (local) y barely moves and a quick
            // flick measured ~0 px/s: closing needed a 40 % drag while opening took a flick
            MotionEvent.obtain(e).let { r -> r.setLocation(e.rawX, e.rawY); pvt?.addMovement(r); r.recycle() }
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) { if (!pulling) { pvt?.recycle(); pvt = null } }
        }

        init {
            orientation = VERTICAL
            setBackgroundColor(pal.bg); isClickable = true
            val bar = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = fill(pal.sheet, 24f); setPadding(dp(14), 0, dp(6), 0); elevation = dpf(1f) }
            bar.addView(ImageView(ctx).apply { setImageDrawable(Ic.get(ctx, R.drawable.li_search, pal.sub, dp(20))); importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }, LayoutParams(dp(20), dp(20)))
            search.apply {
                hint = getString(R.string.hm_search); setHintTextColor(pal.sub); setTextColor(pal.text); background = null; setSingleLine()
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); imeOptions = EditorInfo.IME_ACTION_GO; setPadding(dp(10), dp(14), dp(10), dp(14))
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun afterTextChanged(s: Editable?) { refill() }
                })
                setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_GO) { firstHit?.performClick(); true } else false }
            }
            bar.addView(search, LayoutParams(0, -2, 1f))
            bar.addView(ImageView(ctx).apply {
                setImageDrawable(Ic.get(ctx, R.drawable.li_x, pal.sub, dp(20))); setPadding(dp(10), dp(10), dp(10), dp(10)); contentDescription = getString(R.string.close)
                kit.tap(this, -1f) { if (search.text.isNotEmpty()) search.setText("") else closeDrawer() }
            }, LayoutParams(dp(44), dp(44)))
            addView(bar, LayoutParams(-1, -2).apply { setMargins(dp(14), dp(12), dp(14), dp(8)) })
            val body = FrameLayout(ctx)
            val inner = LinearLayout(ctx).apply { orientation = HORIZONTAL }
            scroll.isVerticalScrollBarEnabled = false
            scroll.addView(list)
            inner.addView(scroll, LayoutParams(0, -1, 1f))
            inner.addView(rail, LayoutParams(dp(26), -1))
            body.addView(inner, FrameLayout.LayoutParams(-1, -1))
            // the scrub bubble: a big letter on the accent while the finger is on the rail
            bubble.apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f); setTextColor(pal.onSolid); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                gravity = Gravity.CENTER; background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(pal.solid) }; visibility = View.GONE; elevation = dpf(6f)
            }
            body.addView(bubble, FrameLayout.LayoutParams(dp(60), dp(60), Gravity.TOP or Gravity.END).apply { rightMargin = dp(44) })
            addView(body, LayoutParams(-1, 0, 1f))
            rail.setOnTouchListener { v, e ->
                if (rail.childCount == 0) return@setOnTouchListener false
                val i = (e.y / v.height * rail.childCount).toInt().coerceIn(0, rail.childCount - 1)
                val letter = (rail.getChildAt(i) as TextView).text.toString()
                firstRow[letter]?.let { scroll.scrollTo(0, it.top) }
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                        if (bubble.text != letter) { bubble.text = letter; v.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK) }
                        bubble.visibility = View.VISIBLE
                        bubble.translationY = (e.y - dp(30)).coerceIn(0f, (v.height - dp(60)).toFloat())
                        if (e.actionMasked == MotionEvent.ACTION_DOWN) v.performClick()
                    }
                    else -> bubble.visibility = View.GONE
                }
                true
            }
            rail.contentDescription = getString(R.string.hm_d_rail)
            refill()
        }

        var firstHit: View? = null

        /** Rebuilds the grid only when what it shows changed (apps, pauses, the query, columns); cheap to call on every render. */
        fun refill() {
            val s = Store.of(context); val r = s.rules
            val all = launcherApps(); val dv = decision(r, s)
            val q = search.text.toString()
            val shown = all.values.filter { LauncherLogic.show(it.pkg, dv.want, r, packageName) != LauncherLogic.Show.HIDE && LauncherLogic.matches(it.label, q) }
                .sortedBy { it.sortKey }
            val anyPaused = shown.any { dv.want[it.pkg] != null }
            val k = listOf(q, shown.joinToString(",") { it.pkg + "=" + it.label }, shown.map { dv.want[it.pkg] }, model?.cols, model?.onHome(), model?.style?.shape,
                model?.style?.icon, if (anyPaused) dv.minute else 0, if (anyPaused) dv.leftMs / 60_000 else 0, if (anyPaused) r.groups.map { it.hours } else 0).joinToString("|")
            if (k == lastKey) return
            lastKey = k
            list.removeAllViews(); rail.removeAllViews(); firstRow.clear(); firstHit = null
            if (shown.isEmpty()) {
                list.addView(text(getString(R.string.hm_no_result, q), 15f, pal.sub).apply { gravity = Gravity.CENTER; setPadding(dp(16), dp(40), dp(16), dp(16)) }, LayoutParams(-1, -2))
                return
            }
            val cols = (model?.cols ?: 4).coerceIn(3, 5)
            var row: LinearLayout? = null
            val onHome = model?.onHome() ?: emptySet()
            for ((n, a) in shown.withIndex()) {
                if (n % cols == 0) { row = LinearLayout(context).apply { orientation = HORIZONTAL; setPadding(dp(8), dp(6), dp(8), dp(6)) }; list.addView(row, LayoutParams(-1, -2)) }
                val l = LauncherLogic.letter(a.label)
                if (q.isBlank() && l !in firstRow) {
                    firstRow[l] = row!!
                    rail.addView(text(l, 11f, pal.sub, true).apply { gravity = Gravity.CENTER; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }, LayoutParams(-1, 0, 1f))
                }
                val paused = dv.want[a.pkg] != null
                val v = appIcon(a, paused, dv, r, if (a.pkg in onHome) Mode.DRAWER else Mode.DRAWER)
                if (firstHit == null) firstHit = v
                row!!.addView(v, LayoutParams(0, -2, 1f))
            }
            // pad the last row so cells keep their width
            row?.let { rw -> while (rw.childCount < cols) rw.addView(View(context), LayoutParams(0, 1, 1f)) }
            list.addView(View(context), LayoutParams(-1, dp(24)))
        }
    }

    // ---- custom views ----

    /**
     * Vertical swipes on the home pages (not on "Hôm nay", it scrolls). Up: the drawer follows the finger 1:1 and on release opens or
     * closes by fling or 40 % (Pixel). Down on empty space or a non-scrolling item: the notification shade, like Pixel / Nova.
     */
    internal inner class SwipeUp(ctx: Context) : FrameLayout(ctx) {
        private var x0 = 0f; private var y0 = 0f; private var up = false; private var downOk = false; private var from = 0f
        private var vt: VelocityTracker? = null
        private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
        private val minFling = ViewConfiguration.get(ctx).scaledMinimumFlingVelocity * 4f

        override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
            track(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { x0 = e.x; y0 = e.y; up = false; downOk = !scrollsAt(this, e.x.toInt(), e.y.toInt()) }
                MotionEvent.ACTION_MOVE -> {
                    val dy = e.y - y0; val dx = e.x - x0
                    val free = !pager.dragging && pager.page > 0 && drag == null && this@HomeActivity.overlay == null && popup?.isShowing != true
                    if (free && dy < -slop && abs(dy) > abs(dx) * 1.5f && drawerBegin()) { up = true; from = e.y; drawerAt(0f) }
                    else if (free && downOk && drawer == null && dy > slop * 2 && dy > abs(dx) * 1.5f) { downOk = false; expandShade() }
                }
            }
            return up
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            track(e)
            if (up) when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> drawerAt((from - e.y) / root.height)
                MotionEvent.ACTION_UP -> { vt?.computeCurrentVelocity(1000); up = false; drawerRelease(vt?.yVelocity ?: 0f, minFling, false) }
                MotionEvent.ACTION_CANCEL -> { up = false; drawerSettle(false) }
            }
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) { vt?.recycle(); vt = null }
            return up || e.actionMasked == MotionEvent.ACTION_DOWN
        }

        private fun track(e: MotionEvent) {
            if (e.actionMasked == MotionEvent.ACTION_DOWN) { vt?.recycle(); vt = VelocityTracker.obtain() }
            vt?.addMovement(e)
        }
    }

    /** True when a visible view under (x, y) of [g] can scroll vertically (a list widget): its own swipe, not the shade's. */
    internal fun scrollsAt(g: ViewGroup, x: Int, y: Int): Boolean {
        for (i in g.childCount - 1 downTo 0) {
            val c = g.getChildAt(i)
            if (c.visibility != View.VISIBLE) continue
            val cx = x + g.scrollX - c.left - c.translationX.toInt(); val cy = y + g.scrollY - c.top - c.translationY.toInt()
            if (cx < 0 || cy < 0 || cx >= c.width || cy >= c.height) continue
            if (c.canScrollVertically(1) || c.canScrollVertically(-1)) return true
            if (c is ViewGroup && scrollsAt(c, cx, cy)) return true
        }
        return false
    }

    /** Pull the notification shade down (hidden StatusBarManager API, what Nova uses; EXPAND_STATUS_BAR is a normal permission). */
    @SuppressLint("WrongConstant")
    internal fun expandShade() {
        runCatching {
            val sb = getSystemService("statusbar") ?: return
            Class.forName("android.app.StatusBarManager").getMethod("expandNotificationsPanel").invoke(sb)
        }.onFailure { Log.w("KidGuard", "expand shade: $it") }
    }

    /** Horizontal pages (0 = "Hôm nay"); long-press on empty space of a home page opens Tiện ích / Tuỳ chỉnh / Sắp xếp / KidGuard. */
    internal inner class Pager(ctx: Context) : ViewGroup(ctx) {
        var page = 1
        var dragging = false
        var onPage: () -> Unit = {}
        private val scroller = OverScroller(ctx)
        private var vt: VelocityTracker? = null
        private var x0 = 0f; private var y0 = 0f; private var lastX = 0f
        private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
        private val minFling = ViewConfiguration.get(ctx).scaledMinimumFlingVelocity * 3
        private val longPress = Runnable {
            if (!dragging && page > 0 && drag == null) {
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                val l = IntArray(2); getLocationInWindow(l); emptyMenu((l[0] + x0).toInt(), (l[1] + y0).toInt())
            }
        }

        override fun onMeasure(w: Int, h: Int) {
            val W = MeasureSpec.getSize(w); val H = MeasureSpec.getSize(h)
            for (i in 0 until childCount) getChildAt(i).measure(MeasureSpec.makeMeasureSpec(W, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(H, MeasureSpec.EXACTLY))
            setMeasuredDimension(W, H)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val w = r - l
            for (i in 0 until childCount) getChildAt(i).layout(i * w, 0, (i + 1) * w, b - t)
            if (changed && scroller.isFinished) scrollTo(page * w, 0)
        }

        override fun onScrollChanged(l: Int, t: Int, ol: Int, ot: Int) { super.onScrollChanged(l, t, ol, ot); chromeFade() }

        override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
            track(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { x0 = e.x; y0 = e.y; lastX = e.x; dragging = !scroller.isFinished; if (dragging) scroller.abortAnimation() }
                MotionEvent.ACTION_MOVE -> if (!dragging && abs(e.x - x0) > slop && abs(e.x - x0) > abs(e.y - y0)) { dragging = true; lastX = e.x; parent.requestDisallowInterceptTouchEvent(true) }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (!dragging) release()
            }
            return dragging
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            track(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { x0 = e.x; y0 = e.y; lastX = e.x; postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong()) }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(e.x - x0) > slop || abs(e.y - y0) > slop) removeCallbacks(longPress)
                    if (!dragging && abs(e.x - x0) > slop && abs(e.x - x0) > abs(e.y - y0)) { dragging = true; lastX = e.x; parent.requestDisallowInterceptTouchEvent(true) }
                    if (dragging) {
                        val max = (childCount - 1).coerceAtLeast(0) * width
                        val nx = scrollX + (lastX - e.x).toInt()
                        // rubber band past the first / last page
                        val adj = if (nx < 0 || nx > max) scrollX + ((lastX - e.x) / 3).toInt() else nx
                        scrollTo(adj, 0); lastX = e.x
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { removeCallbacks(longPress); if (dragging) settle(); release() }
            }
            return true
        }

        private fun settle() {
            vt?.computeCurrentVelocity(1000)
            val vx = vt?.xVelocity ?: 0f
            // a fling, or 30 % of the width either way (Pixel-like); a slow drag back below 30 % stays
            val dx = scrollX - page * width
            val target = when { vx < -minFling || (vx <= minFling && dx > width * 0.3f) -> page + 1; vx > minFling || (vx >= -minFling && dx < -width * 0.3f) -> page - 1; else -> page }
            snapTo(target)
        }

        private fun release() { dragging = false; vt?.recycle(); vt = null }

        fun snapTo(p: Int) {
            val n = (childCount - 1).coerceAtLeast(0)
            val old = page
            page = p.coerceIn(0, n)
            scroller.startScroll(scrollX, 0, page * width - scrollX, 0, 260)
            postInvalidateOnAnimation()
            if (page != old) {
                onPage()
                announceForAccessibility(if (page == 0) getString(R.string.hm_t_title) else getString(R.string.hm_page, page, n))
            }
        }

        /** No animation (first show, HOME from far away). */
        fun jump(p: Int) { scroller.abortAnimation(); page = p.coerceIn(0, (childCount - 1).coerceAtLeast(0)); scrollTo(page * width, 0); onPage() }

        override fun computeScroll() {
            if (scroller.computeScrollOffset()) { scrollTo(scroller.currX, 0); postInvalidateOnAnimation() }
        }

        private fun track(e: MotionEvent) {
            if (e.actionMasked == MotionEvent.ACTION_DOWN) { vt?.recycle(); vt = VelocityTracker.obtain() }
            vt?.addMovement(e)
        }
    }

    /** cols x rows grid inside its padding (the chrome); children carry their cell rectangle (LP). While dragging: one soft page panel + the drop cell. */
    internal inner class CellLayout(ctx: Context, val cols: Int, val rows: Int) : ViewGroup(ctx) {
        private val gap = dp(6)
        val side = dp(10)
        var page = 0
        var grid = false
        var gridColor = Color.WHITE
        var hl: IntArray? = null
        var hlOk = true
        private val panel = Paint(Paint.ANTI_ALIAS_FLAG)
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1).toFloat() }
        private val hlPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        init { clipChildren = false; clipToPadding = false; setWillNotDraw(false) }

        fun cellW() = (width - 2 * side) / cols
        fun cellH() = (height - paddingTop - paddingBottom) / rows

        override fun onMeasure(w: Int, h: Int) {
            val W = MeasureSpec.getSize(w); val H = MeasureSpec.getSize(h)
            val cw = (W - 2 * side) / cols; val ch = ((H - paddingTop - paddingBottom) / rows).coerceAtLeast(1)
            for (i in 0 until childCount) {
                val c = getChildAt(i); val lp = c.layoutParams as CellLP
                c.measure(MeasureSpec.makeMeasureSpec((cw * lp.w - gap).coerceAtLeast(0), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec((ch * lp.h - gap).coerceAtLeast(0), MeasureSpec.EXACTLY))
            }
            setMeasuredDimension(W, H)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val cw = (r - l - 2 * side) / cols; val ch = ((b - t - paddingTop - paddingBottom) / rows).coerceAtLeast(1)
            for (i in 0 until childCount) {
                val c = getChildAt(i); val lp = c.layoutParams as CellLP
                val x = side + lp.x * cw + gap / 2; val y = paddingTop + lp.y * ch + gap / 2
                c.layout(x, y, x + c.measuredWidth, y + c.measuredHeight)
            }
        }

        override fun onDraw(c: Canvas) {
            if (!grid) return
            val cw = cellW().toFloat(); val ch = cellH().toFloat()
            // One soft panel around the page (One UI's edit page), no per-cell outlines: those read as clutter. The cell
            // the item will land in lights up while dragging (hl below), like Pixel / One UI.
            panel.color = Color.argb(28, Color.red(gridColor), Color.green(gridColor), Color.blue(gridColor))
            edge.color = Color.argb(70, Color.red(gridColor), Color.green(gridColor), Color.blue(gridColor))
            val l0 = side.toFloat() - gap / 2f; val t0 = paddingTop - gap / 2f
            val r0 = side + cols * cw + gap / 2f; val b0 = paddingTop + rows * ch + gap / 2f
            kit.drawRound(c, l0, t0, r0, b0, panel, kit.dpf(22f)); kit.drawRound(c, l0, t0, r0, b0, edge, kit.dpf(22f))
            hl?.let { (x, y, w, h) ->
                // the drop cells: the card colour when it fits, the danger colour when it doesn't
                hlPaint.color = if (hlOk) pal.card else Color.argb(150, Color.red(pal.danger), Color.green(pal.danger), Color.blue(pal.danger))
                val l = side + x * cw + gap / 2f; val t = paddingTop + y * ch + gap / 2f
                kit.drawRound(c, l, t, l + w * cw - gap, t + h * ch - gap, hlPaint)
            }
        }
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        // typing on a hardware keyboard on the home screen opens search
        if (drawer == null && overlay == null && pager.page > 0 && e.action == KeyEvent.ACTION_DOWN && e.isPrintingKey) { openDrawer(); drawer?.search?.let { it.requestFocus(); it.append(e.unicodeChar.toChar().toString()) }; return true }
        return super.dispatchKeyEvent(e)
    }
}
