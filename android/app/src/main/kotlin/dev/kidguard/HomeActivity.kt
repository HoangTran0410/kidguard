package dev.kidguard

import android.annotation.SuppressLint
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.ActivityOptions
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.animation.DecelerateInterpolator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.provider.Telephony
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.DragEvent
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.OverScroller
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
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
    companion object { @Volatile var alive = 0 }

    private class AppE(val pkg: String, val cls: String, val label: String)

    /** What is being dragged: a grid / dock / folder / picker source, or [pkg] from the drawer. */
    private class DragSt(val src: Src?, val pkg: String?, val kind: String, val w: Int, val h: Int, val id: String?, val view: View?)

    private class CellLP(val x: Int, val y: Int, val w: Int, val h: Int) : ViewGroup.LayoutParams(-1, -1)

    private enum class Mode { HOME, DOCK, DRAWER, PANEL }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var u: Ui
    private var pal: Palette = HomeStyle.palette("blue", false)
    private lateinit var kit: HomeKit
    private lateinit var preset: HomeStyle.Preset
    private lateinit var acts: KidActions
    private var dark = false
    private lateinit var root: FrameLayout
    private lateinit var wallView: ImageView
    private var wall: Bitmap? = null
    private var wallKey = ""
    private lateinit var content: SwipeUp
    private lateinit var chrome: LinearLayout
    private lateinit var bannerBox: FrameLayout
    private lateinit var topBar: FrameLayout
    private lateinit var bottom: LinearLayout
    private lateinit var tip: TextView
    private lateinit var pager: Pager
    private lateinit var dots: LinearLayout
    private lateinit var hint: ImageView
    private lateinit var dock: LinearLayout
    private var today: TodayPage? = null
    private var drawer: Drawer? = null
    private var popup: PopupWindow? = null
    private var overlay: View? = null            // folder panel or widget picker (views, not dialogs: a drag survives them)
    private var apps: Map<String, AppE>? = null
    private var model: HomeModel? = null
    private var key = ""
    private var lastTap = 0L
    private var editMode = false
    private var drag: DragSt? = null
    private var tempPage = false
    private var insT = 0; private var insB = 0; private var insL = 0; private var insR = 0
    private val inkViews = ArrayList<View>()      // TextViews / dot rows that sit right on the wallpaper
    private val inkCache = HashMap<String, Ink>()
    private val itemViews = HashMap<String, View>()   // grid item id -> its view on the current render
    private val dockViews = ArrayList<View>()
    private val listener: () -> Unit = { render(false) }
    private var timeRx: BroadcastReceiver? = null
    private var pkgRx: BroadcastReceiver? = null
    private var lastView: View0? = null
    private var lastShow: Map<String, LauncherLogic.Show> = emptyMap()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun dpf(v: Float) = v * resources.displayMetrics.density

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
        // chrome over the pages: banner / edit bar at the top, tip + dots + hint + dock at the bottom; touches in between fall through
        chrome = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        bannerBox = FrameLayout(this)
        topBar = FrameLayout(this).apply { visibility = View.GONE }
        bottom = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        tip = TextView(this).apply { visibility = View.GONE; setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); setPadding(dp(16), dp(9), dp(16), dp(9)); gravity = Gravity.CENTER }
        dots = LinearLayout(this).apply { gravity = Gravity.CENTER; orientation = LinearLayout.HORIZONTAL }
        hint = ImageView(this).apply {
            contentDescription = getString(R.string.hm_all_apps); setPadding(dp(10), dp(2), dp(10), dp(2))
            setOnClickListener { openDrawer() }
        }
        dock = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        chrome.addView(bannerBox, LinearLayout.LayoutParams(-1, -2))
        chrome.addView(topBar, LinearLayout.LayoutParams(-1, -2))
        chrome.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))
        bottom.addView(tip, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(8) })
        bottom.addView(dots, LinearLayout.LayoutParams(-1, dp(18)))
        bottom.addView(hint, LinearLayout.LayoutParams(-2, dp(22)))
        bottom.addView(dock, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(12), dp(4), dp(12), dp(10)) })
        chrome.addView(bottom, LinearLayout.LayoutParams(-1, -2))
        content.addView(chrome, FrameLayout.LayoutParams(-1, -1))
        root.addView(content, FrameLayout.LayoutParams(-1, -1))
        root.setOnApplyWindowInsetsListener { _, ins ->
            @Suppress("DEPRECATION") run { insL = ins.systemWindowInsetLeft; insT = ins.systemWindowInsetTop; insR = ins.systemWindowInsetRight; insB = ins.systemWindowInsetBottom }
            chrome.setPadding(insL, insT, insR, insB)
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
            editMode -> setEdit(false)
            else -> pager.snapTo(1)
        }
    }

    @Deprecated("still the way back works on API 26-32")
    override fun onBackPressed() {
        when {
            overlay != null -> closeOverlay()
            drawer != null -> closeDrawer()
            editMode -> setEdit(false)
            pager.page != 1 -> pager.snapTo(1)
        }
    }

    // ---------------- data ----------------

    private fun launcherApps(): Map<String, AppE> {
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

    private class View0(val want: Map<String, String>, val leftMs: Long, val usage: Map<String, Long>, val minute: Int, val dow: Int, val now: Long,
                        val apps: Set<String>, val prot: Set<String>, val today: String)

    /** The Guard's last decision, or the same decide() run here when the Guard has not ticked yet. */
    private fun decision(r: Rules, s: Store): View0 {
        val now = System.currentTimeMillis(); val today = Guard.day(now)
        val cal = Calendar.getInstance()
        val minute = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        Guard.snap?.takeIf { now - it.now < 30_000 }?.let { return View0(it.want, it.leftMs, it.usageMs, minute, it.dow, now, it.apps, it.prot, today) }
        val (all, prot) = Policy.cached(this)
        val use = runCatching { Usage.today(this, now).ms }.getOrDefault(emptyMap())
        val want = decide(r, use, minute, now, today, all.keys, prot, unlockUntil = s.unlockUntil)
        return View0(want, dailyLeftMs(r, use, today, all.keys, prot), use, minute, Rules.isoDow(today), now, all.keys, prot, today)
    }

    private fun resolvePkg(i: Intent): String? = runCatching {
        packageManager.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName?.takeIf { it != "android" && !it.contains("resolver") }
    }.getOrNull()

    private fun dialer(): String? = resolvePkg(Intent(Intent.ACTION_DIAL))

    private fun firstModel(r: Rules, visible: List<AppE>): HomeModel {
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

    private fun busy() = drag != null || overlay != null || popup?.isShowing == true || pager.dragging

    private fun render(force: Boolean) {
        if (isFinishing || isDestroyed) return
        val s = Store.of(this); val r = s.rules
        val all = launcherApps()
        val dv = decision(r, s)
        val show = all.keys.associateWith { LauncherLogic.show(it, dv.want, r, packageName) }
        val visible = all.values.filter { show[it.pkg] != LauncherLogic.Show.HIDE }.sortedBy { LauncherLogic.sortKey(it.label) }
        var m = model ?: HomeFile.load(this) ?: firstModel(r, visible)
        m = HomeLayout.prune(m) { it !in all && !Policy.installed(this, it) }
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
        val banner = LauncherLogic.banner(Policy.isOwner(this), Guard.running != null, Usage.granted(this), locationOn(), Acc.isOn(this))
        val files = runCatching { Files.all(this) }.getOrDefault(emptyList()).joinToString(",") { it.id + it.st }
        // not the minute itself: what it changes (next event, today's schedule states, the date); the clock text is set in place below
        val timeKey = listOf(dv.today, LauncherLogic.nextEvent(r, dv.minute, dv.dow), LauncherLogic.today(r, dv.minute, dv.dow).map { it.state })
        val k = listOf(m.toJson().toString(), all.values.joinToString(",") { it.label }, show.toString(), dv.want.toString(), block, banner, timeKey, dv.leftMs / 60_000, s.everPaired,
            s.sos().length(), s.lastCheckin("arrived"), s.lastCheckin("pickup"), s.requests().toString(), r.blockNote, r.launcher, dark, files,
            r.chores, r.schedules, r.groups.map { it.hours }, r.bedtimeOn, r.bedStart, r.bedEnd, r.lockContact, editMode).joinToString("|")
        if (!force && k == key) { tickClocks(dv); return }
        if (busy()) { main.postDelayed({ render(false) }, 1500); return }   // never rebuild under a finger / an open panel
        key = k
        lastView = dv; lastShow = show
        tempPage = false
        ensureWall()
        buildBanner(if (editMode) null else banner)
        buildTopBar()
        buildPages(m, all, show, dv, r, s, block)
        buildDock(m, all, show, dv, r)
        hint.setImageDrawable(Ic.get(this, R.drawable.li_chevron_up, Color.WHITE, dp(18)))
        inkViews += hint
        today?.fill(r, dv.minute, dv.dow, dv.now)
        // no-op unless what it shows changed; the first one is built while idle, so the first swipe up has it ready under the finger
        drawerCache?.let { d -> main.post { d.refill() } } ?: main.postDelayed({ if (drawerCache == null && !isFinishing && !busy()) drawerView() }, 800)
        main.post { inkPass(); chromeFade() }
    }

    private fun ensureWall() {
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

    private fun locationOn(): Boolean = runCatching {
        val lm = getSystemService(LocationManager::class.java)
        if (Build.VERSION.SDK_INT >= 28) lm.isLocationEnabled
        else @Suppress("DEPRECATION") Settings.Secure.getInt(contentResolver, Settings.Secure.LOCATION_MODE) != Settings.Secure.LOCATION_MODE_OFF
    }.getOrDefault(true)

    private fun card(radius: Int = 22) = GradientDrawable().apply { setColor(pal.card); cornerRadius = dpf(radius.toFloat()) }
    private fun fill(color: Int, radius: Float = 999f) = GradientDrawable().apply { setColor(color); cornerRadius = dpf(radius) }
    private fun text(t: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t; setTextSize(TypedValue.COMPLEX_UNIT_SP, sp * (model?.style?.font?.toFloat() ?: 1f)); setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        includeFontPadding = false
    }

    private fun buildBanner(code: String?) {
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

    /** Edit mode: a hint + "Xong"; while dragging: the "Bỏ khỏi màn chính" drop target. Hidden otherwise. */
    private fun buildTopBar() {
        topBar.removeAllViews()
        val dragging = drag != null
        topBar.visibility = if (editMode || dragging) View.VISIBLE else View.GONE
        if (!editMode && !dragging) return
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; elevation = dpf(2f) }
        if (dragging) {
            bar.background = fill(pal.danger, 18f); bar.gravity = Gravity.CENTER
            bar.addView(text(getString(R.string.hm_unpin), 16f, pal.onDanger, true).apply { Ic.lead(this, R.drawable.li_x, pal.onDanger); compoundDrawablePadding = dp(8); gravity = Gravity.CENTER_VERTICAL })
            bar.setPadding(dp(16), dp(15), dp(16), dp(15))
        } else {
            bar.background = fill(pal.sheet, 18f); bar.setPadding(dp(16), dp(8), dp(8), dp(8))
            bar.addView(text(getString(R.string.hm_e_hint), 14f, pal.text).apply { Ic.lead(this, R.drawable.li_move, pal.accentText); compoundDrawablePadding = dp(8); gravity = Gravity.CENTER_VERTICAL },
                LinearLayout.LayoutParams(0, -2, 1f))
            bar.addView(TextView(this).apply {
                text = getString(R.string.hm_e_done); setTextColor(pal.onSolid); background = fill(pal.solid); setPadding(dp(18), dp(9), dp(18), dp(9))
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                kit.tap(this, 999f, true) { setEdit(false) }
            })
        }
        topBar.addView(bar, FrameLayout.LayoutParams(-1, -2).apply { setMargins(dp(14), dp(6), dp(14), dp(4)) })
    }

    private fun setEdit(on: Boolean) {
        if (editMode == on) return
        editMode = on
        popup?.dismiss()
        key = ""; render(true)
        if (on) announce(getString(R.string.hm_e_hint))
    }

    private fun announce(t: String) = root.announceForAccessibility(t)

    /** Home pages leave room for the chrome (status bar + banner / edit bar on top, dots + dock below). */
    private fun padPages() {
        if (!::pager.isInitialized) return
        val top = insT + bannerBox.height + (if (topBar.visibility == View.VISIBLE) topBar.height else 0)
        val bot = insB + bottom.height
        for (i in 1 until pager.childCount) {
            val c = pager.getChildAt(i)
            if (c.paddingTop != top || c.paddingBottom != bot) c.setPadding(0, top, 0, bot)
        }
    }

    private fun buildPages(m: HomeModel, all: Map<String, AppE>, show: Map<String, LauncherLogic.Show>, dv: View0, r: Rules, s: Store, block: String?) {
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

    /** Edit / drag: dashed cell outlines in the wallpaper's ink colour. */
    private fun styleCells() {
        val on = editMode || drag != null
        val c = inkCache["grid"]?.text ?: Color.WHITE
        for (i in 1 until pager.childCount) (pager.getChildAt(i) as? CellLayout)?.let { it.grid = on; it.gridColor = c; it.invalidate() }
    }

    /** -1 page: the chrome (dots, dock, banner) slides away with the pages; status bar icons follow the page. */
    private fun chromeFade() {
        val w = pager.width.takeIf { it > 0 } ?: return
        val f = (pager.scrollX.toFloat() / w).coerceIn(0f, 1f)     // 0 on "Hôm nay", 1 on page 1+
        chrome.alpha = f
        chrome.visibility = if (f < 0.02f) View.INVISIBLE else View.VISIBLE
        // mid-fade: one GPU layer, so the alpha does not redraw dock + dots + banner offscreen every frame
        val lt = if (f > 0.02f && f < 0.98f) View.LAYER_TYPE_HARDWARE else View.LAYER_TYPE_NONE
        if (chrome.layerType != lt) chrome.setLayerType(lt, null)
        chrome.translationX = (1 - f) * w * 0.3f
    }

    private fun buildDots() {
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
        ink?.let { applyScrimTo(dots, it) }
    }

    // ---- app icon ----

    private fun iconPx(): Int = dp((preset.iconDp * (0.8f + 0.1f * ((model?.style?.icon ?: 3) - 1))).toInt())

    private fun appIcon(a: AppE, paused: Boolean, dv: View0, r: Rules, mode: Mode, item: HItem? = null, folder: HItem? = null, slot: Int = -1): View {
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
                setImageDrawable(Ic.get(this@HomeActivity, R.drawable.li_clock, pal.onWarn, bs))
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

    private fun folderName(f: HItem): String = f.name.ifBlank { getString(R.string.hm_f_default) }

    private fun folderIcon(f: HItem, vis: List<AppE>, show: Map<String, LauncherLogic.Show>): View {
        val px = iconPx()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP; setPadding(0, dp(4), 0, dp(2)) }
        val square = model?.style?.shape == "square"
        val mini = (px * 0.36f).toInt()
        val grid = object : View(this) {
            val bmps = vis.take(4).map { IconCache.get(this@HomeActivity, it.pkg, it.cls, mini, !square) to (show[it.pkg] == LauncherLogic.Show.PAUSED) }
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

    private fun debounce(): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastTap < 600) return false
        lastTap = now; return true
    }

    private fun launch(a: AppE, from: View) {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(ComponentName(a.pkg, a.cls))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        val opts = ActivityOptions.makeScaleUpAnimation(from, 0, 0, from.width, from.height).toBundle()
        runCatching { startActivity(i, opts) }.recoverCatching { packageManager.getLaunchIntentForPackage(a.pkg)?.let { startActivity(it) } ?: error("none") }
            .onFailure { Toast.makeText(this, R.string.hm_open_fail, Toast.LENGTH_SHORT).show() }
    }

    private fun hhmm(t: Long) = SimpleDateFormat("HH:mm", Locale.ROOT).format(Date(t))

    /** "Mở 17:00" / "Hết giờ" under a greyed app in the drawer. */
    private fun reopenShort(r: Rules, reason: String, pkg: String, dv: View0): String = when (val o = LauncherLogic.reopen(r, reason, pkg, dv.minute, dv.dow, dv.now)) {
        is LauncherLogic.Reopen.AtMinute -> getString(R.string.hm_opens, HomeLogic.clock(o.minute))
        is LauncherLogic.Reopen.AtMs -> getString(R.string.hm_opens, hhmm(o.ms))
        else -> getString(R.string.hm_out)
    }

    /** "06:30 sáng mai" / "lúc 17:00" / "vào ngày mai", or null. */
    private fun reopenWhen(r: Rules, reason: String, pkg: String, dv: View0): String? = when (val o = LauncherLogic.reopen(r, reason, pkg, dv.minute, dv.dow, dv.now)) {
        is LauncherLogic.Reopen.AtMinute -> if (o.minute <= dv.minute) getString(R.string.hm_tomorrow_at, HomeLogic.clock(o.minute)) else getString(R.string.hm_today_at, HomeLogic.clock(o.minute))
        is LauncherLogic.Reopen.AtMs -> getString(R.string.hm_today_at, hhmm(o.ms))
        LauncherLogic.Reopen.Tomorrow -> getString(R.string.hm_tomorrow)
        else -> null
    }

    // ---- paused sheet ----

    private fun pausedSheet(a: AppE, reason: String, dv: View0, r: Rules) {
        val s = Store.of(this)
        val sh = Sheet(this, u, getString(R.string.hm_paused_title, a.label))
        val body = sh.body
        val warnCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = fill(pal.warnTint, 18f); setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        warnCard.addView(ImageView(this).apply { setImageDrawable(Ic.get(this@HomeActivity, R.drawable.li_clock, pal.onWarn, dp(18))); background = fill(pal.warn); setPadding(dp(8), dp(8), dp(8), dp(8)) },
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

    private class Row(val icon: Int, val label: String, val enabled: Boolean, val run: () -> Unit)

    private fun menu(rows: List<Row>, anchor: View?, x: Int = 0, y: Int = 0) {
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
        if (anchor != null) pw.showAsDropDown(anchor, 0, -dp(6)) else pw.showAtLocation(root, Gravity.TOP or Gravity.START, x.coerceAtMost(root.width - dp(260)).coerceAtLeast(dp(8)), y)
    }

    private fun appMenu(a: AppE, v: View, mode: Mode, item: HItem?) {
        val m = model ?: return
        val placed = a.pkg in m.onHome()
        menu(listOfNotNull(
            Row(R.drawable.li_pin, getString(if (placed) R.string.hm_pinned else R.string.hm_pin), !placed) { update(HomeLayout.pin(m, a.pkg)); if (drawer != null) closeDrawer() }.takeIf { mode == Mode.DRAWER },
            Row(R.drawable.li_circle_minus, getString(R.string.hm_unpin), true) { update(HomeLayout.remove(m, a.pkg)) }.takeIf { placed || mode != Mode.DRAWER },
            Row(R.drawable.li_folder_plus, getString(R.string.hm_f_add), true) { addToFolderSheet(a) },
        ), v)
    }

    private fun emptyMenu(x: Int, y: Int) = menu(listOf(
        Row(R.drawable.li_layout_dashboard, getString(R.string.hm_w_title), true) { openPicker() },
        Row(R.drawable.li_sliders_horizontal, getString(R.string.hm_customize), true) { runCatching { startActivity(Intent(this, CustomActivity::class.java)) } },
        Row(R.drawable.li_layout_grid, getString(R.string.hm_e_arrange), true) { setEdit(true) },
        Row(R.drawable.li_shield, getString(R.string.hm_open_kg), true) { openMain() },
    ), null, x, y)

    private fun update(m: HomeModel) { HomeFile.save(this, m); model = m; key = ""; render(true) }

    private fun openMain() = runCatching { startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }

    // ---- folders ----

    /** Auto name from the two apps' ApplicationInfo.category: Trò chơi / Mạng xã hội / Học tập / Giải trí, else Thư mục. */
    private fun autoName(a: String, b: String): String {
        fun cat(p: String) = if (Build.VERSION.SDK_INT >= 26) runCatching { packageManager.getApplicationInfo(p, 0).category }.getOrDefault(-1) else -1
        return when (HomeLayout.folderKind(cat(a), cat(b))) {
            "games" -> getString(R.string.hm_f_games); "social" -> getString(R.string.hm_f_social); "study" -> getString(R.string.hm_f_study); "fun" -> getString(R.string.hm_f_fun)
            else -> getString(R.string.hm_f_default)
        }
    }

    /** "Thêm vào thư mục": the folders on the home, then "a new folder with" each other app on the grid. */
    private fun addToFolderSheet(a: AppE) {
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
            .mapNotNull { it to (all[it.pkg] ?: return@mapNotNull null) }.sortedBy { LauncherLogic.sortKey(it.second.label) }
        if (others.isNotEmpty()) sh.body.addView(text(getString(R.string.hm_f_new_with), 13f, pal.sub, true).apply { setPadding(dp(16), dp(14), dp(16), dp(4)) })
        for ((it, e) in others) sh.body.addView(rowOf(ImageView(this).apply { setImageBitmap(IconCache.get(this@HomeActivity, e.pkg, e.cls, dp(40), model?.style?.shape != "square")) }, e.label) {
            val name = autoName(e.pkg, a.pkg)
            HomeLayout.addTo(m, a.pkg, it.id, name)?.let { n -> update(n); toast(getString(R.string.hm_f_added, name)) }
        })
        if (folders.isEmpty() && others.isEmpty()) sh.body.addView(text(getString(R.string.hm_f_none), 15f, pal.sub).apply { setPadding(dp(16), dp(16), dp(16), dp(16)) })
        sh.show()
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    private fun renameFolder(id: String) {
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
    private fun openFolder(id: String) {
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
            setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, Ic.get(this@HomeActivity, R.drawable.li_pencil, pal.sub, dp(18)), null); compoundDrawablePadding = dp(8)
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

    private fun showOverlay(v: View) {
        closeOverlay()
        overlay = v
        root.addView(v, FrameLayout.LayoutParams(-1, -1))
    }

    private fun closeOverlay() {
        val v = overlay ?: return
        overlay = null
        root.removeView(v)
        if (drag == null) main.post { render(false) }
    }

    // ---- widgets ----

    /** Long-press on a widget (or one of its buttons): menu (sizes, remove), and a drag moves it. */
    private fun armWidget(v: View, it: HItem) {
        val sizes = HomeLayout.SIZES[it.type].orEmpty().map { (w, h) -> minOf(w, model?.cols ?: w) to h }.distinct()
        val showMenu = {
            menu(sizes.filter { s -> s != it.w to it.h }.map { (w, h) ->
                Row(R.drawable.li_layout_dashboard, getString(R.string.hm_w_size, w, h), true) { model?.let { mm -> HomeLayout.resizeWidget(mm, it.id, w, h)?.let { n -> update(n) } ?: noRoom(null) } }
            } + Row(R.drawable.li_circle_minus, getString(R.string.hm_unpin), true) { model?.let { mm -> HomeLayout.take(mm, Src.Item(it.id))?.let { t -> update(HomeLayout.dropRemove(t)) } } }, v)
        }
        val src = { DragSt(Src.Item(it.id), null, "widget", it.w, it.h, it.id, v) }
        fun walk(x: View) {
            if (x === v || x.isClickable) arm(x, showMenu, src, dragView = v)
            if (x is ViewGroup) for (i in 0 until x.childCount) walk(x.getChildAt(i))
        }
        walk(v)
        v.isLongClickable = true
    }

    private fun widgetLabel(type: String) = getString(when (type) {
        "time" -> R.string.hm_w_time; "quick" -> R.string.hm_w_quick; "next" -> R.string.hm_w_next; "clock" -> R.string.hm_w_clock; else -> R.string.hm_w_counts
    })

    /** Clock widgets on the current render: the minute tick only changes their text, no rebuild (it used to rebuild the home every minute). */
    private val clocks = ArrayList<Pair<TextView, TextView>>()

    private fun tickClocks(dv: View0) {
        val now = HomeLogic.clock(dv.minute)
        for ((t, d) in clocks) if (t.text != now) { t.text = now; (t.parent as? View)?.contentDescription = "$now. ${d.text}" }
    }

    private fun clockWidget(r: Rules, dv: View0, w: Int, h: Int, hideNext: Boolean): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL or Gravity.START; setPadding(dp(14), dp(4), dp(8), dp(4)) }
        val big = when (preset.clock) { "big" -> 60f; "thin" -> 46f; else -> 54f } * (if (w <= 3 && preset.cols == 5) 0.85f else 1f) * (if (h >= 2) 1.4f else 1f)
        val t = text(HomeLogic.clock(dv.minute), big, Color.WHITE).apply {
            typeface = Typeface.create(if (preset.clock == "thin") "sans-serif-light" else "sans-serif", Typeface.NORMAL); setPadding(dp(6), 0, dp(6), 0)
        }
        box.addView(t, LinearLayout.LayoutParams(-2, -2))
        val date = SimpleDateFormat("EEEE, d MMMM", resources.configuration.locales[0]).format(Date(dv.now)).replaceFirstChar { it.titlecase() }
        val d = text(date, if (h >= 2) 17f else 15f, Color.WHITE, true).apply { setPadding(dp(6), dp(2), dp(6), dp(2)) }
        box.addView(d, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(2) })
        inkViews += t; inkViews += d
        clocks += t to d
        // one place for "what's next": the "Lịch tiếp theo" widget when it is on the home, else here
        if (!hideNext) LauncherLogic.nextEvent(r, dv.minute, dv.dow)?.let { (min, name) ->
            val n = text(if (name.isEmpty()) getString(R.string.hm_next_bed, HomeLogic.clock(min)) else getString(R.string.hm_next, name, HomeLogic.clock(min)), 13f, Color.WHITE)
                .apply { setPadding(dp(6), dp(2), dp(6), dp(2)); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
            box.addView(n, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(2) })
            inkViews += n
        }
        box.contentDescription = "${t.text}. ${d.text}"
        return box
    }

    private fun dur(ms: Long) = HomeLogic.hm(ms).let { (h, m) -> if (h == 0) getString(R.string.home_m, m) else if (m == 0) getString(R.string.home_h, h) else getString(R.string.home_hm, h, m) }

    /** The card wraps its content and sits in the middle of its cells (the mock's height, not the cell's). */
    private fun centred(v: View): View = FrameLayout(this).apply { clipChildren = false; addView(v, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL)) }

    private fun timeWidget(r: Rules, dv: View0, block: String?, s: Store, w: Int): View {
        val small = w <= 2
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(12)) }
        val canAsk = HomeLogic.canAsk(s.everPaired, dv.leftMs, block)
        if (block != null) {
            // out of time: amber card (opaque tint: its own text passes on any wallpaper), reason + when it opens + "bấm Xin giờ"
            box.background = fill(pal.warnTint, 22f)
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val icon = when (block) { "bedtime" -> R.drawable.li_moon; "schedule" -> R.drawable.li_calendar; "paused" -> R.drawable.li_circle_pause; else -> R.drawable.li_timer }
            if (!small) row.addView(ImageView(this).apply { setImageDrawable(Ic.get(this@HomeActivity, icon, pal.onWarn, dp(18))); background = fill(pal.warn); setPadding(dp(8), dp(8), dp(8), dp(8)) },
                LinearLayout.LayoutParams(dp(34), dp(34)).apply { rightMargin = dp(12) })
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(text(BasicLogic.reasonText(block, s.tr).replaceFirstChar { it.titlecase() }, if (small) 15f else 18f, pal.text, true).apply { maxLines = 2 })
            val wh = reopenWhen(r, block, "", dv)?.let { getString(R.string.hm_reopen, it) }
            val line = when { wh != null && canAsk -> getString(R.string.hm_reopen_ask, wh); wh != null -> wh; canAsk -> getString(R.string.main_more); else -> null }
            if (line != null) col.addView(text(line, 13f, pal.warnText).apply { setPadding(0, dp(3), 0, 0); maxLines = 2 })
            row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
            box.addView(row)
        } else {
            box.background = card()
            box.addView(text(getString(R.string.home_left).uppercase(), 11f, pal.sub, true).apply { letterSpacing = 0.08f })
            if (dv.leftMs < 0) {
                box.addView(text(getString(R.string.tl_none), if (small) 18f else 22f, pal.text, true).apply { setPadding(0, dp(4), 0, 0) })
                if (r.bedtimeOn && !small) box.addView(text(getString(R.string.home_bed, r.bedStart, r.bedEnd), 13f, pal.sub).apply { setPadding(0, dp(3), 0, 0) })
            } else {
                val low = dv.leftMs < 10 * 60_000L
                box.addView(text(dur(dv.leftMs), if (small) 20f else 26f, if (low) pal.warnText else pal.accentText, true).apply { setPadding(0, dp(4), 0, dp(8)); maxLines = 1 })
                val limitMs = (baseLimitMin(r, dv.today) + if (r.bonusDay == dv.today) r.bonusMin else 0) * 60_000L
                val used = dv.usage.filterKeys { it in dv.apps && it !in dv.prot && it !in r.allow }.values.sum()
                val bar = FrameLayout(this).apply { background = fill(pal.stroke, 3f) }
                val f = if (limitMs > 0) (used.toFloat() / limitMs).coerceIn(0.02f, 1f) else 0f
                bar.addView(View(this).apply { background = fill(if (low) pal.warn else pal.solid, 3f) }, FrameLayout.LayoutParams(-1, -1))
                bar.addOnLayoutChangeListener { v, l, _, rr, _, _, _, _, _ -> (v as FrameLayout).getChildAt(0).let { c -> val want = ((rr - l) * f).toInt(); if (c.layoutParams.width != want) { c.layoutParams.width = want; c.post { c.requestLayout() } } } }
                box.addView(bar, LinearLayout.LayoutParams(-1, dp(6)))
                if (!small) {
                    val ln = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(7), 0, 0) }
                    ln.addView(text(getString(R.string.home_used, dur(used)), 12f, pal.sub), LinearLayout.LayoutParams(0, -2, 1f))
                    ln.addView(text(getString(R.string.home_limit, dur(limitMs)), 12f, pal.sub))
                    box.addView(ln)
                }
            }
        }
        if (canAsk) kit.tap(box, 22f) { if (debounce()) acts.askMore() }
        box.contentDescription = (box.getChildAt(0) as? TextView)?.text?.toString()
        return centred(box)
    }

    private fun quickWidget(s: Store, w: Int): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; background = card(); setPadding(dp(6), dp(10), dp(6), dp(10)) }
        val paired = s.everPaired
        val today = Guard.day(System.currentTimeMillis())
        fun ci(ck: String, base: Int): String {
            val t = s.lastCheckin(ck)
            return if (t > 0 && Guard.day(t) == today) getString(R.string.hm_q_sent_at, hhmm(t)) else getString(base)
        }
        // glyph colour comes from its own fill (HomeStyle: onDanger / onTint / onSolid), never the mock's purple-on-purple
        val items = listOf(
            Triple(R.drawable.li_siren, Pair(pal.danger, pal.onDanger), Pair(if (s.sos().length() > 0) getString(R.string.hm_q_sent) else getString(R.string.home_sos), pal.dangerText)) to { acts.askSos() },
            Triple(R.drawable.li_map_pin, Pair(pal.tint, pal.onTint), Pair(ci("arrived", R.string.hm_q_arrived), pal.text)) to { acts.askCheckin("arrived") },
            Triple(R.drawable.li_car, Pair(pal.tint, pal.onTint), Pair(ci("pickup", R.string.hm_q_pickup), pal.text)) to { acts.askCheckin("pickup") },
            Triple(R.drawable.li_timer, Pair(pal.solid, pal.onSolid), Pair(if (s.requests().length() > 0) getString(R.string.hm_q_wait) else getString(R.string.hm_q_more), pal.text)) to { acts.askMore() },
        )
        val circle = if (w >= 4 && preset.id == "be") 56 else 48
        for ((spec, run) in items) {
            val (icon, fills, lab) = spec
            val b = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(2), dp(4), dp(2), dp(4))
                addView(ImageView(context).apply {
                    setImageDrawable(Ic.get(context, icon, fills.second, dp(22))); background = fill(fills.first)
                    val p = dp((circle - 22) / 2); setPadding(p, p, p, p)
                }, LinearLayout.LayoutParams(dp(circle), dp(circle)))
                addView(text(lab.first, 12f, lab.second, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(6), 0, 0); gravity = Gravity.CENTER })
                contentDescription = lab.first
                // ripple in a rounded mask + no grey focus box (the SOS button used to keep one after a tap)
                kit.tap(this, 14f) {
                    if (!debounce()) return@tap
                    if (!paired) Modal(this@HomeActivity, u).show(R.drawable.li_link, Modal.Tone.INFO, getString(R.string.hm_pair_title), getString(R.string.hm_pair_body),
                        ok = getString(R.string.hm_open_kg), cancel = getString(R.string.cancel)) { openMain(); true }
                    else run()
                }
            }
            row.addView(b, LinearLayout.LayoutParams(0, -2, 1f))
        }
        return centred(row)
    }

    /** "Lịch tiếp theo" 4x1: what comes next today (or now), from LauncherLogic.today; a tap opens "Hôm nay". */
    private fun nextWidget(r: Rules, dv: View0): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = card(); setPadding(dp(14), dp(12), dp(14), dp(12)) }
        val ev = LauncherLogic.today(r, dv.minute, dv.dow).firstOrNull { it.state == "now" || it.state == "next" }
        box.addView(kit.badge(when (ev?.kind) { "bed" -> R.drawable.li_moon; "group" -> R.drawable.li_timer; else -> R.drawable.li_calendar }, pal.onTint, pal.tint, 40, 20))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(4), 0) }
        if (ev == null) col.addView(text(getString(R.string.hm_t_no_more), 15f, pal.text, true))
        else {
            col.addView(text((if (ev.state == "now") getString(R.string.hm_t_now) else getString(R.string.hm_w_next_at, HomeLogic.clock(ev.start))).uppercase(), 11f, pal.accentText, true).apply { letterSpacing = 0.06f })
            val name = when (ev.kind) { "bed" -> getString(R.string.hm_t_bed); "group" -> getString(R.string.hm_t_group, ev.name); else -> ev.name.ifBlank { getString(R.string.hm_t_lock) } }
            col.addView(text(name, 16f, pal.text, true).apply { setPadding(0, dp(3), 0, 0); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        }
        box.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        box.addView(ImageView(this).apply { setImageDrawable(Ic.get(this@HomeActivity, R.drawable.li_chevron_right, pal.sub, dp(18))); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, LinearLayout.LayoutParams(dp(18), dp(18)))
        kit.tap(box, 22f) { pager.snapTo(0) }
        box.contentDescription = getString(R.string.hm_w_next) + ": " + ((col.getChildAt(col.childCount - 1) as? TextView)?.text ?: "")
        return centred(box)
    }

    /** "Việc · Ghi chú · File" 2x2: counts from the "Hôm nay" page; a tap opens it. */
    private fun countsWidget(r: Rules, m: HomeModel): View {
        val (chores, notes, files) = TodayPage.counts(this, r, m)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL; background = card(); setPadding(dp(14), dp(12), dp(14), dp(12)) }
        box.addView(text(getString(R.string.hm_t_title), 14f, pal.text, true).apply { setPadding(0, 0, 0, dp(6)) })
        for ((ic, lab, n) in listOf(Triple(R.drawable.li_list_checks, R.string.hm_w_c_chores, chores), Triple(R.drawable.li_sticky_note, R.string.hm_w_c_notes, notes), Triple(R.drawable.li_file, R.string.hm_w_c_files, files))) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(5), 0, dp(5)) }
            row.addView(ImageView(this).apply { setImageDrawable(Ic.get(this@HomeActivity, ic, pal.accentText, dp(16))) }, LinearLayout.LayoutParams(dp(16), dp(16)))
            row.addView(text(getString(lab), 13f, pal.text).apply { setPadding(dp(8), 0, dp(4), 0); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(text(n.toString(), 14f, pal.text, true))
            box.addView(row)
        }
        kit.tap(box, 22f) { pager.snapTo(0) }
        box.contentDescription = "${getString(R.string.hm_w_c_chores)} $chores, ${getString(R.string.hm_w_c_notes)} $notes, ${getString(R.string.hm_w_c_files)} $files"
        return centred(box)
    }

    // ---- widget picker ----

    /** Our widgets with their sizes. Tap a size: placed (or resized when it is on the home already). Hold and drag: drop it anywhere. */
    private fun openPicker() {
        val m = model ?: return
        popup?.dismiss()
        val scrim = FrameLayout(this).apply { setBackgroundColor(Color.argb(if (pal.dark) 140 else 80, 0, 0, 0)); isClickable = true; setOnClickListener { closeOverlay() }; tag = "picker" }
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; isClickable = true; elevation = dpf(12f)
            background = GradientDrawable().apply { setColor(pal.sheet); cornerRadii = floatArrayOf(dpf(28f), dpf(28f), dpf(28f), dpf(28f), 0f, 0f, 0f, 0f) }
            setPadding(dp(20) + insL, dp(10), dp(20) + insR, dp(12) + insB)
        }
        sheet.addView(View(this).apply { background = fill(pal.stroke) }, LinearLayout.LayoutParams(dp(36), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(12) })
        sheet.addView(text(getString(R.string.hm_w_title), 22f, pal.text, true))
        sheet.addView(text(getString(R.string.hm_w_sub), 14f, pal.sub).apply { setPadding(0, dp(4), 0, dp(6)); includeFontPadding = true })
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val sc = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(list) }
        for ((i, type) in HomeLayout.SIZES.keys.withIndex()) {
            if (i > 0) list.addView(View(this).apply { setBackgroundColor(pal.stroke) }, LinearLayout.LayoutParams(-1, dp(1)))
            val placed = m.pages.flatten().firstOrNull { it.kind == "widget" && it.type == type }
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(12), 0, dp(12)) }
            val pv = preview(type)
            row.addView(pv, LinearLayout.LayoutParams(dp(124), dp(76)))
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, 0, 0) }
            col.addView(text(widgetLabel(type), 16f, pal.text, true))
            col.addView(text(getString(when (type) { "time" -> R.string.hm_w_time_d; "quick" -> R.string.hm_w_quick_d; "next" -> R.string.hm_w_next_d; "clock" -> R.string.hm_w_clock_d; else -> R.string.hm_w_counts_d }), 13f, pal.sub).apply { setPadding(0, dp(2), 0, dp(6)); includeFontPadding = true })
            val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val sizes = HomeLayout.SIZES[type].orEmpty().map { (w, h) -> minOf(w, m.cols) to h }.distinct()
            for ((w, h) in sizes) {
                val cur = placed != null && placed.w == w && placed.h == h
                chips.addView(kit.chip(if (cur) getString(R.string.hm_w_on_home, w, h) else "$w×$h", if (cur) pal.onSolid else pal.onTint, if (cur) pal.solid else pal.tint, if (cur) R.drawable.li_check else null).apply {
                    kit.tap(this, 999f) { if (!cur) pickSize(type, w, h, placed) }
                    contentDescription = if (cur) getString(R.string.hm_w_on_home, w, h) else getString(R.string.hm_w_add_size, widgetLabel(type), w, h)
                }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6) })
            }
            col.addView(chips)
            row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
            // hold the row to drag a new widget in (only one of each kind: a placed one is moved on the home itself)
            if (placed == null) arm(row, {}, { DragSt(Src.NewWidget(type, sizes[0].first, sizes[0].second), null, "widget", sizes[0].first, sizes[0].second, null, pv) }, immediate = true, dragView = pv)
            kit.tap(row, 16f) { if (placed == null) pickSize(type, sizes[0].first, sizes[0].second, null) else toast(getString(R.string.hm_w_already)) }
            list.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        sheet.addView(sc, LinearLayout.LayoutParams(-1, -2))
        scrim.addView(sheet, FrameLayout.LayoutParams(-1, (root.height * 0.62f).toInt(), Gravity.BOTTOM))
        showOverlay(scrim)
        sheet.translationY = dpf(80f); sheet.alpha = 0f
        sheet.animate().translationY(0f).alpha(1f).setDuration(180).start()
    }

    private fun pickSize(type: String, w: Int, h: Int, placed: HItem?) {
        val m = model ?: return
        val hp = (pager.page - 1).coerceAtLeast(0)
        val n = if (placed != null) HomeLayout.resizeWidget(m, placed.id, w, h) else HomeLayout.placeWidget(m, type, w, h, hp)
        if (n == null) { noRoom(null); return }
        closeOverlay()
        update(n)
        HomeLayout.find(n, placed?.id ?: n.pages.flatten().first { it.kind == "widget" && it.type == type }.id)?.first?.let { pi -> pager.post { pager.snapTo(pi + 1) } }
        toast(getString(R.string.hm_w_added, widgetLabel(type)))
    }

    /** A miniature on a tint block: a sheet card with the widget's main glyph and line (all tested pairs). */
    private fun preview(type: String): View {
        val box = FrameLayout(this).apply { background = fill(pal.tint, 16f); setPadding(dp(8), dp(8), dp(8), dp(8)); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; background = fill(pal.sheet, 10f); setPadding(dp(8), dp(4), dp(8), dp(4)) }
        when (type) {
            "time" -> {
                c.addView(text(dur(65 * 60_000L), 13f, pal.accentText, true))
                c.addView(View(this).apply { background = fill(pal.solid, 2f) }, LinearLayout.LayoutParams(dp(60), dp(4)).apply { topMargin = dp(5) })
            }
            "quick" -> {
                val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
                for ((ic, f, g) in listOf(Triple(R.drawable.li_siren, pal.danger, pal.onDanger), Triple(R.drawable.li_map_pin, pal.tint, pal.onTint), Triple(R.drawable.li_car, pal.tint, pal.onTint), Triple(R.drawable.li_timer, pal.solid, pal.onSolid)))
                    r.addView(kit.badge(ic, g, f, 20, 12), LinearLayout.LayoutParams(dp(20), dp(20)).apply { setMargins(dp(2), 0, dp(2), 0) })
                c.addView(r)
            }
            "next" -> {
                c.addView(text(HomeLogic.clock(17 * 60), 10f, pal.sub))
                c.addView(text(getString(R.string.hm_t_next), 12f, pal.text, true).apply { Ic.lead(this, R.drawable.li_calendar, pal.accentText); compoundDrawablePadding = dp(4) })
            }
            "clock" -> {
                c.addView(text(HomeLogic.clock(lastView?.minute ?: 980), 22f, pal.text, true))
                c.addView(text(SimpleDateFormat("EEE d/M", resources.configuration.locales[0]).format(Date()), 10f, pal.sub))
            }
            else -> for ((ic, lab) in listOf(R.drawable.li_list_checks to R.string.hm_w_c_chores, R.drawable.li_sticky_note to R.string.hm_w_c_notes, R.drawable.li_file to R.string.hm_w_c_files))
                c.addView(text(getString(lab), 10f, pal.text).apply { Ic.lead(this, ic, pal.accentText); compoundDrawablePadding = dp(4); maxLines = 1 })
        }
        box.addView(c, FrameLayout.LayoutParams(-1, -1))
        return box
    }

    // ---- drag and drop ----

    private var armed: View? = null
    private var downX = 0f; private var downY = 0f

    /**
     * Pixel-style press: long-press shows [onLong] (the menu); moving the finger 8 dp after that closes it and starts a drag.
     * In edit mode (and in a folder panel / the picker, [immediate]) the long-press starts the drag at once.
     */
    private fun arm(v: View, onLong: () -> Unit, src: () -> DragSt, immediate: Boolean = false, dragView: View = v) {
        v.isLongClickable = true
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; armed = null }
                MotionEvent.ACTION_MOVE -> if (armed === v && (abs(e.rawX - downX) > dp(8) || abs(e.rawY - downY) > dp(8))) { armed = null; popup?.dismiss(); startDrag(dragView, src()) }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> armed = null
            }
            false
        }
        v.setOnLongClickListener {
            v.parent?.requestDisallowInterceptTouchEvent(true)
            v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            if (editMode || immediate) startDrag(dragView, src()) else { armed = v; onLong() }
            true
        }
    }

    private fun startDrag(v: View, d: DragSt) {
        if (drag != null || !v.isAttachedToWindow) return
        val ok = runCatching { v.startDragAndDrop(null, View.DragShadowBuilder(v), d, 0) }.getOrDefault(false)
        if (!ok) return
        drag = d
        d.view?.visibility = View.INVISIBLE
        // leaving a panel / the drawer / the picker: the home takes the drop
        when {
            overlay != null -> { val o = overlay; overlay = null; o?.visibility = View.GONE; main.post { root.removeView(o) } }
            drawer != null -> closeDrawer()
        }
        if (pager.page == 0) pager.snapTo(1)
        bannerBox.visibility = View.GONE
        buildTopBar(); styleCells()
        main.post { padPages() }
    }

    private var flip: Runnable? = null
    private var hoverId: String? = null

    private fun pageView(): CellLayout? = pager.getChildAt(pager.page) as? CellLayout

    /** Finger -> (page index, anchor cell for the dragged size, the item under the finger). */
    private fun cellAt(x: Float, y: Float, d: DragSt): Triple<Int, Pair<Int, Int>, HItem?>? {
        val m = model ?: return null
        val cl = pageView() ?: return null
        val loc = IntArray(2); val rl = IntArray(2)
        cl.getLocationInWindow(loc); root.getLocationInWindow(rl)
        val lx = x - (loc[0] - rl[0]); val ly = y - (loc[1] - rl[1]) - cl.paddingTop
        val cw = cl.cellW(); val ch = cl.cellH()
        if (cw <= 0 || ch <= 0 || ly < -ch / 2 || ly > m.rows * ch + ch / 2) return null
        val fx = (lx - cl.side) / cw; val fy = ly / ch
        val w = minOf(d.w, m.cols); val h = minOf(d.h, m.rows)
        val ax = (fx - w / 2f).roundToInt().coerceIn(0, m.cols - w); val ay = (fy - h / 2f).roundToInt().coerceIn(0, m.rows - h)
        val pi = cl.page
        val under = m.pages.getOrNull(pi)?.firstOrNull { it.id != d.id && it.covers(fx.toInt().coerceIn(0, m.cols - 1), fy.toInt().coerceIn(0, m.rows - 1)) }
        return Triple(pi, ax to ay, under)
    }

    private fun inView(v: View, x: Float, y: Float): Boolean {
        if (!v.isShown) return false
        val loc = IntArray(2); val rl = IntArray(2); v.getLocationInWindow(loc); root.getLocationInWindow(rl)
        val l = loc[0] - rl[0]; val t = loc[1] - rl[1]
        return x >= l && x < l + v.width && y >= t && y < t + v.height
    }

    private fun dockSlot(x: Float, y: Float): Int {
        if (!inView(dock, x, y)) return -1
        for ((i, v) in dockViews.withIndex()) if (inView(v, x, y)) return i
        return -1
    }

    private fun onDrag(e: DragEvent): Boolean {
        val d = drag ?: (e.localState as? DragSt) ?: return false
        when (e.action) {
            DragEvent.ACTION_DRAG_STARTED -> return true
            DragEvent.ACTION_DRAG_LOCATION -> hover(e.x, e.y, d)
            DragEvent.ACTION_DRAG_EXITED -> clearHover()
            DragEvent.ACTION_DROP -> return drop(e.x, e.y, d)
            DragEvent.ACTION_DRAG_ENDED -> endDrag()
        }
        return true
    }

    private fun clearHover() {
        hoverId?.let { itemViews[it]?.background = null }
        hoverId = null
        tip.visibility = View.GONE
        pageView()?.let { it.hl = null; it.invalidate() }
        (topBar.getChildAt(0))?.let { it.scaleX = 1f; it.scaleY = 1f }
        for (v in dockViews) v.background = null
    }

    private fun hover(x: Float, y: Float, d: DragSt) {
        clearHover()
        val m = model ?: return
        // the remove target
        if (inView(topBar, x, y)) { topBar.getChildAt(0)?.let { it.scaleX = 1.04f; it.scaleY = 1.04f }; cancelFlip(); return }
        val slot = dockSlot(x, y)
        if (slot >= 0) {
            cancelFlip()
            if (slot > 0 && d.kind == "app") dockViews[slot].background = fill(pal.card, 18f)
            return
        }
        // the screen edges flip pages (past the last page: a new empty one)
        val edge = dp(22)
        if (x < edge || x > root.width - edge) {
            if (flip == null) {
                val dir = if (x < edge) -1 else 1
                flip = Runnable {
                    flip = null
                    val target = pager.page + dir
                    if (target < 1) return@Runnable
                    if (target >= pager.childCount) {
                        if (tempPage || (m.pages.lastOrNull()?.isEmpty() == true)) return@Runnable
                        tempPage = true
                        pager.addView(CellLayout(this, m.cols, m.rows).apply { page = m.pages.size })
                        styleCells(); padPages(); buildDots()
                    }
                    pager.snapTo(target)
                }.also { main.postDelayed(it, 500) }
            }
            return
        }
        cancelFlip()
        val (pi, a, under) = cellAt(x, y, d) ?: return
        val cl = pageView() ?: return
        if (under != null && d.kind == "app" && (under.kind == "app" || under.kind == "folder")) {
            hoverId = under.id
            itemViews[under.id]?.background = fill(pal.card, 20f)
            val name = if (under.kind == "folder") folderName(under) else launcherApps()[under.pkg]?.label ?: ""
            showTip(getString(if (under.kind == "folder") R.string.hm_f_drop_into else R.string.hm_f_drop_make, name))
        } else {
            val t = taken(m, d) ?: return
            val free = HomeLayout.free(t.m.pages.getOrNull(pi) ?: emptyList(), m.cols, m.rows, a.first, a.second, minOf(d.w, m.cols), minOf(d.h, m.rows))
            cl.hl = intArrayOf(a.first, a.second, minOf(d.w, m.cols), minOf(d.h, m.rows)); cl.hlOk = free; cl.invalidate()
        }
    }

    private fun showTip(t: String) {
        tip.text = t; tip.setTextColor(pal.bg); tip.background = fill(pal.text); tip.visibility = View.VISIBLE
    }

    private fun cancelFlip() { flip?.let { main.removeCallbacks(it) }; flip = null }

    private fun taken(m: HomeModel, d: DragSt): HomeLayout.Taken? =
        if (d.src != null) HomeLayout.take(m, d.src) else d.pkg?.let { HomeLayout.detach(m, it) }

    private fun drop(x: Float, y: Float, d: DragSt): Boolean {
        cancelFlip()
        val m = model ?: return false
        val t = taken(m, d) ?: return false
        val out: HomeModel? = when {
            inView(topBar, x, y) -> if (d.src is Src.NewWidget || (d.pkg != null && d.pkg !in m.onHome())) m else HomeLayout.dropRemove(t)
            dockSlot(x, y) >= 0 -> HomeLayout.dropDock(t, dockSlot(x, y))
            else -> cellAt(x, y, d)?.let { (pi, a, under) ->
                if (under != null && d.kind == "app" && (under.kind == "app" || under.kind == "folder"))
                    HomeLayout.dropOnto(t, under.id, if (under.kind == "app") autoName(under.pkg, t.item.pkg) else "")
                else HomeLayout.dropCell(t, pi, a.first, a.second)
            }
        }
        if (out == null) { noRoom(pageView()); return false }
        if (out !== m) { HomeFile.save(this, out); model = out }
        return true
    }

    private fun noRoom(v: View?) {
        (v ?: pageView())?.let { ObjectAnimator.ofFloat(it, "translationX", 0f, dpf(10f), -dpf(10f), dpf(6f), -dpf(6f), 0f).setDuration(320).start() }
        toast(getString(R.string.hm_e_no_room))
    }

    private fun endDrag() {
        if (drag == null) return
        drag?.view?.visibility = View.VISIBLE
        drag = null
        cancelFlip(); clearHover()
        bannerBox.visibility = View.VISIBLE
        key = ""
        main.post { render(true) }
    }

    // ---- dock ----

    private fun buildDock(m: HomeModel, all: Map<String, AppE>, show: Map<String, LauncherLogic.Show>, dv: View0, r: Rules) {
        dock.removeAllViews(); dockViews.clear()
        // the card surface (HomeStyle.cardWorst): dock labels in pal.text pass on any wallpaper
        dock.background = if (m.style.dockBg) GradientDrawable().apply { setColor(pal.card); cornerRadius = dpf(28f) } else null
        dock.setPadding(dp(6), dp(8), dp(6), dp(8))
        val px = (iconPx() * 0.9f).toInt().coerceAtMost(dp(60))
        for ((i, slot) in m.dock.withIndex()) {
            val cell = FrameLayout(this)
            val v: View? = if (i == 0) {
                val d = dialer()
                val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
                val bmp = d?.let { p -> IconCache.get(this, p, all[p]?.cls ?: "", px, m.style.shape != "square") }
                box.addView(ImageView(this).apply {
                    if (bmp != null) setImageBitmap(bmp) else { setImageDrawable(Ic.get(context, R.drawable.li_phone, pal.onSolid, dp(26))); background = fill(pal.solid); setPadding(dp(14), dp(14), dp(14), dp(14)) }
                }, LinearLayout.LayoutParams(px, px))
                if (preset.dockLabels) box.addView(dockLabel(getString(R.string.hm_phone), m.style.dockBg))
                box.contentDescription = getString(R.string.hm_phone)
                kit.tap(box, 16f, true) {
                    if (!debounce()) return@tap
                    runCatching { startActivity(Intent(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        .onFailure { Toast.makeText(this, R.string.hm_no_dialer, Toast.LENGTH_SHORT).show() }
                }
                box.isLongClickable = true
                box.setOnLongClickListener { sosMenu(box); true }
                box
            } else all[slot]?.takeIf { show[it.pkg] != LauncherLogic.Show.HIDE }?.let { a ->
                val paused = show[a.pkg] == LauncherLogic.Show.PAUSED
                appIcon(a, paused, dv, r, Mode.DOCK, slot = i).also { v ->
                    if (!preset.dockLabels) (v as LinearLayout).getChildAt(1)?.let { (v as LinearLayout).removeView(it); inkViews.remove(it) }
                    else if (m.style.dockBg) (v as LinearLayout).getChildAt(1)?.let { inkViews.remove(it); (it as TextView).setTextColor(pal.text) }
                }
            }
            if (v != null) cell.addView(v, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            dock.addView(cell, LinearLayout.LayoutParams(0, -2, 1f))
            dockViews += cell
        }
    }

    private fun dockLabel(t: String, onBg: Boolean) = text(t, 12f, if (onBg) pal.text else Color.WHITE).apply {
        gravity = Gravity.CENTER; setPadding(dp(4), dp(3), dp(4), 0); maxLines = 1
        if (!onBg) inkViews += this
    }

    private fun sosMenu(anchor: View) {
        fun call(n: String) = runCatching { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$n")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        menu(listOf(
            Row(R.drawable.li_siren, getString(R.string.main_sos), true) { if (Store.of(this).everPaired) acts.askSos() else call("112") },
            Row(R.drawable.li_phone, getString(R.string.hm_112), true) { call("112") },
            Row(R.drawable.li_shield, getString(R.string.hm_113), true) { call("113") },
            Row(R.drawable.li_triangle_alert, getString(R.string.hm_114), true) { call("114") },
            Row(R.drawable.li_phone, getString(R.string.hm_115), true) { call("115") },
        ), null, anchor.let { val l = IntArray(2); it.getLocationInWindow(l); l[0] }, anchor.let { val l = IntArray(2); it.getLocationInWindow(l); (l[1] - dp(300)).coerceAtLeast(dp(40)) })
    }

    // ---- adaptive text on the wallpaper ----

    /** For every text / dot row on the wallpaper: sample what is behind it, pick light or dark ink, add a scrim if needed. */
    private fun inkPass() {
        val bmp = wall ?: return
        if (isFinishing) return
        val w = root.width.takeIf { it > 0 } ?: return
        val loc = IntArray(2)
        fun rectOf(v: View): Rect? {
            if (v.width == 0 || v.windowToken == null) return null
            v.getLocationInWindow(loc)
            val x = ((loc[0] % w) + w) % w      // pages off screen: the wallpaper does not scroll, so the same x on any page
            return Rect(x, loc[1], x + v.width, loc[1] + v.height)
        }
        for (v in inkViews.toList()) {
            val rc = rectOf(v) ?: continue
            val k = if (v === dots) "dots" else "${rc.left},${rc.top},${rc.width()},${rc.height()}"
            val need = if (v === dots || v === hint) Contrast.LARGE else Contrast.TEXT
            val ink = inkCache.getOrPut(k) { HomeStyle.ink(Wallpaper.sample(bmp, rc), need) }
            if (v === dots) { inkCache["dots"] = ink; continue }
            when (v) {
                is TextView -> {
                    v.setTextColor(ink.text)
                    v.setShadowLayer(dpf(3f), 0f, dpf(1f), ink.shadow)
                    applyScrimTo(v, ink)
                }
                is ImageView -> { v.drawable?.setTint(ink.text); applyScrimTo(v, ink) }
            }
        }
        // edit-mode grid lines: one ink for the whole grid area (3:1 like any other non-text mark)
        inkCache.getOrPut("grid") { HomeStyle.ink(Wallpaper.sample(bmp, Rect(0, insT, w, bmp.height - insB)), Contrast.LARGE) }
        buildDots()
        styleCells()
        bars()
    }

    /** Status / nav bar icons: on the -1 page they follow its surface, on the home the wallpaper behind them. */
    private fun bars() {
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

    private var barsCache: Pair<String, Pair<Boolean, Boolean>>? = null

    private fun applyScrimTo(v: View, ink: Ink) {
        v.background = if (Contrast.a(ink.scrim) == 0) null else fill(ink.scrim, 10f)
    }

    // ---- drawer ----

    /** Built once and kept (hidden) between opens, so a swipe can show it under the finger at once; rebuilt when the colours change. */
    private var drawerCache: Drawer? = null
    private var drawerP = 0f                       // 0 closed .. 1 open
    private var drawerAnim: ValueAnimator? = null

    private fun drawerView(): Drawer = drawerCache?.also { it.refill() } ?: Drawer(this).also { d ->
        drawerCache = d
        d.setPadding(insL, insT, insR, insB); d.visibility = View.GONE
        root.addView(d, FrameLayout.LayoutParams(-1, -1))
    }

    /** A finger or a button starts moving the drawer: the home goes into one GPU layer so fading it costs nothing per frame. */
    private fun drawerBegin(): Boolean {
        drawerAnim?.cancel(); drawerAnim = null
        if (drawer == null) {
            if (drag != null) return false
            popup?.dismiss(); closeOverlay()
            if (editMode) setEdit(false)
            drawer = drawerView(); drawerP = 0f
        }
        content.visibility = View.VISIBLE; wallView.visibility = View.VISIBLE
        content.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        return true
    }

    /** Pixel: the drawer's top rides the finger 1:1, the home fades and shrinks a little behind it. */
    private fun drawerAt(p: Float) {
        val d = drawer ?: return
        drawerP = p.coerceIn(0f, 1f)
        d.visibility = View.VISIBLE
        d.translationY = (1 - drawerP) * root.height
        content.alpha = 1 - drawerP
        val sc = 1 - 0.06f * drawerP; content.scaleX = sc; content.scaleY = sc
    }

    /** Finger up: a fling either way wins, else moving it 40 % of the way flips it ([wasOpen]: where the finger started). [vy] px/s, + = down. */
    private fun drawerRelease(vy: Float, minFling: Float, wasOpen: Boolean) =
        drawerSettle(LauncherLogic.drawerOpens(vy, minFling, drawerP, wasOpen), vy)

    private fun drawerSettle(open: Boolean, vy: Float = 0f) {
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

    private fun drawerDone(open: Boolean) {
        val d = drawer ?: return
        drawerAnim = null
        content.setLayerType(View.LAYER_TYPE_NONE, null)
        if (open) {
            // the drawer is opaque: stop drawing the home and the wallpaper under it while it scrolls
            content.visibility = View.INVISIBLE; wallView.visibility = View.INVISIBLE
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                (if (!pal.dark) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or (if (Build.VERSION.SDK_INT >= 26) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0) else 0)
        } else {
            drawer = null
            d.visibility = View.GONE
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(d.windowToken, 0)
            if (d.search.text.isNotEmpty()) d.search.setText("")
            d.scroll.scrollTo(0, 0)
            content.alpha = 1f; content.scaleX = 1f; content.scaleY = 1f
            bars()
        }
    }

    private fun openDrawer() {
        if (drawer == null) { if (!drawerBegin()) return; drawerAt(0f) } else drawerBegin()
        drawerSettle(true)
    }

    private fun closeDrawer() {
        drawer ?: return
        drawerBegin(); drawerSettle(false)
    }

    /** A–Z drawer like Pixel's: search on top, ONE continuous grid (the home's column count), letters only in the side scrubber. */
    private inner class Drawer(ctx: Context) : LinearLayout(ctx) {
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
            pvt?.addMovement(e)
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
                .sortedBy { LauncherLogic.sortKey(it.label) }
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
    private inner class SwipeUp(ctx: Context) : FrameLayout(ctx) {
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
                    val free = !pager.dragging && pager.page > 0 && drag == null && this@HomeActivity.overlay == null && popup?.isShowing != true && !editMode
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
    private fun scrollsAt(g: ViewGroup, x: Int, y: Int): Boolean {
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
    private fun expandShade() {
        runCatching {
            val sb = getSystemService("statusbar") ?: return
            Class.forName("android.app.StatusBarManager").getMethod("expandNotificationsPanel").invoke(sb)
        }.onFailure { Log.w("KidGuard", "expand shade: $it") }
    }

    /** Horizontal pages (0 = "Hôm nay"); long-press on empty space of a home page opens Tiện ích / Tuỳ chỉnh / Sắp xếp / KidGuard. */
    private inner class Pager(ctx: Context) : ViewGroup(ctx) {
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

    /** cols x rows grid inside its padding (the chrome); children carry their cell rectangle (LP). Edit mode: dashed cells. */
    private inner class CellLayout(ctx: Context, val cols: Int, val rows: Int) : ViewGroup(ctx) {
        private val gap = dp(6)
        val side = dp(10)
        var page = 0
        var grid = false
        var gridColor = Color.WHITE
        var hl: IntArray? = null
        var hlOk = true
        private val dash by lazy { kit.dashPaint(gridColor) }
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
            dash.color = gridColor
            // widget cards are a little see-through: no dashes under a visible widget
            val busy = BooleanArray(cols * rows)
            for (i in 0 until childCount) {
                val v = getChildAt(i); val lp = v.layoutParams as CellLP
                if (v.visibility != View.VISIBLE || lp.w * lp.h < 2) continue
                for (yy in lp.y until (lp.y + lp.h).coerceAtMost(rows)) for (xx in lp.x until (lp.x + lp.w).coerceAtMost(cols)) busy[yy * cols + xx] = true
            }
            for (y in 0 until rows) for (x in 0 until cols) {
                if (busy[y * cols + x]) continue
                val l = side + x * cw + gap / 2f; val t = paddingTop + y * ch + gap / 2f
                kit.drawRound(c, l, t, l + cw - gap, t + ch - gap, dash)
            }
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
