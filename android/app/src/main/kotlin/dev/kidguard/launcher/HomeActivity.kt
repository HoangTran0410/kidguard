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
    companion object {
        /** Width of the drawer's A–Z rail (was 26: it ate into the grid). */
        const val RAIL_DP = 20
        @Volatile var alive = 0; const val FOLDER_MS = 500L
        /** Created once: Typeface.create looked the family up again for every bold label. */
        val MEDIUM: Typeface by lazy { Typeface.create("sans-serif-medium", Typeface.NORMAL) }
        /** Launcher font (style.face), families every Android ships: no font files in the APK. */
        val FACES = listOf("sys", "light", "medium", "condensed", "serif")
        @Volatile var face = "sys"
        private val faces = HashMap<String, Typeface>()
        /** The chosen face, regular or emphasised (titles, buttons). The system "bold font" setting still adds on top. */
        fun tf(bold: Boolean): Typeface = faces.getOrPut("$face$bold") {
            when (face) {
                "light" -> Typeface.create(if (bold) "sans-serif" else "sans-serif-light", Typeface.NORMAL)
                "medium" -> if (bold) Typeface.create("sans-serif", Typeface.BOLD) else MEDIUM
                "condensed" -> Typeface.create("sans-serif-condensed", if (bold) Typeface.BOLD else Typeface.NORMAL)
                "serif" -> Typeface.create("serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
                else -> if (bold) MEDIUM else Typeface.DEFAULT
            }
        }
    }

    internal class AppE(val pkg: String, val cls: String, val label: String) {
        val sortKey by lazy { LauncherLogic.sortKey(label) }   // once per app list: sortedBy calls its selector per comparison
        val folded by lazy { LauncherLogic.fold(label) }       // drawer search: once per app list, not per key typed
    }

    /** What is being dragged: a grid / dock / folder / picker source, or [pkg] from the drawer. */
    internal class DragSt(val src: Src?, val pkg: String?, val kind: String, val w: Int, val h: Int, val id: String?, val view: View?)

    internal class CellLP(val x: Int, val y: Int, val w: Int, val h: Int, val id: String = "") : ViewGroup.LayoutParams(-1, -1)

    internal enum class Mode { HOME, DOCK, DRAWER, PANEL }

    internal val main = Handler(Looper.getMainLooper())
    internal lateinit var u: Ui
    internal var pal: Palette = HomeStyle.palette("blue", false)
    internal lateinit var kit: HomeKit
    internal lateinit var preset: HomeStyle.Preset
    internal lateinit var acts: KidActions
    internal var dark = false
    internal lateinit var root: FrameLayout
    internal lateinit var wallView: WallSurface
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
    /** Edit mode (HomeEdit.kt): pages shrink, a tap picks / unpicks a grid item, bulk actions in the bottom bar. */
    internal var editing = false
    internal val picked = LinkedHashSet<String>()   // picked grid item ids
    internal var editTop: View? = null
    internal var editBottom: View? = null
    internal var insT = 0; internal var insB = 0; internal var insL = 0; internal var insR = 0
    /** Bottom inset with the keyboard (insB is the bars only): the drawer, Today and sheets with a text field follow the
     *  keyboard; the home pages never do (they shrank for the ~0.5 s the keyboard took to slide away after search). */
    internal var imeB = 0
    internal val inkViews = ArrayList<View>()      // TextViews / dot rows that sit right on the wallpaper
    internal val inkCache = HashMap<String, Ink>()
    internal val itemViews = HashMap<String, View>()   // grid item id -> its view on the current render
    internal val dockViews = ArrayList<View>()
    internal val listener: () -> Unit = { render(false) }
    internal var timeRx: BroadcastReceiver? = null
    internal var pkgRx: BroadcastReceiver? = null
    internal var lastView: View0? = null
    internal val aw = AwState()                    // other apps' widgets (HomeAppWidgets.kt)
    internal var lifecycleStarted = false
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
        wallView = WallSurface(this).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
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
            @Suppress("DEPRECATION") run { insL = ins.systemWindowInsetLeft; insT = ins.systemWindowInsetTop; insR = ins.systemWindowInsetRight; imeB = ins.systemWindowInsetBottom }
            insB = if (android.os.Build.VERSION.SDK_INT >= 30) ins.getInsets(android.view.WindowInsets.Type.systemBars()).bottom
                else @Suppress("DEPRECATION") minOf(ins.stableInsetBottom, imeB)
            chrome.setPadding(insL, insT, insR, insB)
            floatBox.setPadding(insL, insT, insR, insB)
            drawerCache?.setPadding(insL, insT, insR, imeB)
            today?.pad(insT, imeB)
            pickerIme()
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

    override fun onDestroy() { alive--; if (NotifDots.onChange === dotRx) NotifDots.onChange = null; super.onDestroy() }
    private val dotRx: (Set<String>) -> Unit = { changed -> Dots.refresh(window.decorView, changed) }

    /** The drawer's "recently used" packages, most recent first ([Recent]); read off the main thread on each return home. */
    internal var recent: List<String> = emptyList()

    override fun onResume() {
        super.onResume()
        Thread {
            val r = Recent.load(applicationContext)
            runOnUiThread { if (r != recent) { recent = r; drawer?.refill() } }
        }.start()
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
        NotifDots.onChange = dotRx
        NotifDots.setOn(this, (model ?: HomeFile.load(this))?.style?.badge != "off")
        model = null          // Tuỳ chỉnh may have changed home.json (HomeFile's cache)
        apps = null           // package changes while stopped are not broadcast to us
        render(false)         // rebuilds only when the key changed: coming back from an app is free
    }

    override fun onPause() {
        exitEdit()
        Guard.listeners.remove(listener)
        timeRx?.let { runCatching { unregisterReceiver(it) } }; timeRx = null
        popup?.dismiss()
        super.onPause()
    }

    override fun onStart() { super.onStart(); lifecycleStarted = true; awStart() }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) { super.onActivityResult(requestCode, resultCode, data); awResult(requestCode, resultCode, data) }

    override fun onStop() {
        lifecycleStarted = false; awStop()
        pkgRx?.let { runCatching { unregisterReceiver(it) } }; pkgRx = null
        super.onStop()
    }

    /** HOME pressed while we are in front: close what is open (one thing at a time), else back to page 1. */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        popup?.dismiss()
        when {
            overlay != null -> closeOverlay()
            editing -> exitEdit()
            drawer != null -> closeDrawer()
            else -> pager.snapTo(1)
        }
    }

    @Deprecated("still the way back works on API 26-32")
    override fun onBackPressed() {
        when {
            overlay != null -> closeOverlay()
            editing -> exitEdit()   // multi-select: Back leaves it (as Home does), picks dropped
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
            if (ai.name == HomeEntry::class.java.name) continue   // "KidGuard Home" would open this very screen
            if (ai.packageName !in out) {
                // loadLabel is a binder call per app (Samsung routes it through its EDM service): ~15 ms on every return home.
                // The apk path changes on each update, the locale is in the key: a stale label cannot survive either.
                val k = ai.applicationInfo?.sourceDir + "|" + ai.name + "|" + resources.configuration.locales[0]
                out[ai.packageName] = AppE(ai.packageName, ai.name, labelCache.getOrPut(k) { ri.loadLabel(pm).toString() })
            }
        }
        return out.also { apps = it }
    }
    private val labelCache = HashMap<String, String>()

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
        awSync(m)
        preset = HomeStyle.preset(m.style.preset)
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        dark = m.style.theme == "dark" || (m.style.theme == "auto" && night)
        val accent = m.style.accent.ifEmpty { if (dark) preset.accentDark else preset.accentLight }
        val np = HomeStyle.palette(accent, dark)
        val faceChanged = face != m.style.face
        face = m.style.face
        if (np != pal || today == null || faceChanged) {
            // the launcher's own accent and light / dark for its modals and sheets ("Xin giờ" & co)
            pal = np; u = Ui(this, pal); kit = HomeKit(this, pal, m.style.font.toFloat())
            // the kept drawer has the old colours baked in: drop it unless it is on screen
            drawerCache?.takeIf { drawer == null }?.let { root.removeView(it); drawerCache = null }
            acts = KidActions(this, u) { key = ""; render(true) }
            today = TodayPage(this, kit, u, acts, { model }, { update(it) }, { openMain() }).also { it.onRefresh = { key = ""; render(true) }; it.pad(insT, imeB) }
        } else if (kit.font != m.style.font.toFloat()) kit = HomeKit(this, pal, m.style.font.toFloat())
        val block = QuickLogic.phoneBlock(dv.want)
        lap("palette")
        val banner = if (!Policy.isOwner(this) && !s.everPaired) null   // standalone use before setup: nothing to nag about
            else LauncherLogic.banner(Policy.isOwner(this), Guard.running != null, Usage.granted(this), locationOn(), Acc.isOn(this))
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
        ensureWall(); lap("wall")
        buildBanner(banner)
        buildTopBar()
        buildPages(m, all, show, dv, r, s, block); lap("pages")
        buildDock(m, all, show, dv, r); lap("dock")
        hint.setImageDrawable(Ic.get(this, R.drawable.li_chevron_up, Color.WHITE, dp(18)))
        inkViews += hint
        // the "Hôm nay" page (25-35 ms) is off screen on almost every render: rebuilt once the main thread is idle
        if (pager.page == 0) today?.fill(r, dv.minute, dv.dow, dv.now) else {
            val gen = ++todayGen
            android.os.Looper.myQueue().addIdleHandler { if (gen == todayGen && !isFinishing) today?.fill(r, dv.minute, dv.dow, dv.now); false }
        }
        lap("today")
        lap("${IconCache.stats()}"); lap.done("rebuild")
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
        val k = (if (photo) "photo:" + java.io.File(filesDir, Wallpaper.PHOTO).lastModified() else id) + ":${st.dim}:${st.blur}@${w}x$h"
        if (k == wallKey) return
        wallKey = k; inkCache.clear()
        val old = wall
        // blur + dim (Tuỳ chỉnh) on any wallpaper: a calmer background makes the grid easier to read
        wall = Wallpaper.blur((if (photo) Wallpaper.photo(this, w, h) else null) ?: Wallpaper.draw(id, w, h), st.blur).also { Wallpaper.dim(it, st.dim) }
        wallView.show(wall)
        // the opaque wallpaper covers the window: the theme's black background was one more full-screen fill per frame
        window.setBackgroundDrawable(null)
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
        typeface = tf(bold)
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
        // + 16 dp: the first row sat right under the status bar icons (One UI / Pixel leave a gap there too)
        val top = insT + dp(16) + bannerBox.height + (if (topBar.visibility == View.VISIBLE) topBar.height else 0)
        val bot = insB + bottom.height
        for (i in 1 until pager.childCount) {
            val c = pager.getChildAt(i)
            if (c.paddingTop != top || c.paddingBottom != bot) c.setPadding(0, top, 0, bot)
        }
    }

    internal fun buildPages(m: HomeModel, all: Map<String, AppE>, show: Map<String, LauncherLogic.Show>, dv: View0, r: Rules, s: Store, block: String?) {
        inkViews.clear(); itemViews.clear(); clocks.clear(); tickers.clear()
        val keep = pager.page
        pager.removeAllViews()
        today?.view?.let { (it.parent as? ViewGroup)?.removeView(it); pager.addView(it) }
        val hasNext = HomeLayout.hasWidget(m, "next")
        for ((pi, p) in m.pages.withIndex()) {
            val cell = CellLayout(this, m.cols, m.rows)
            for (it in p) {
                val v: View = when (it.kind) {
                    "widget" -> { val w0 = android.os.SystemClock.uptimeMillis(); when (it.type) {
                        "clock" -> clockWidget(r, dv, it.w, it.h, hasNext)
                        "time" -> timeWidget(r, dv, block, s, it.w)
                        "quick" -> quickWidget(s, it.w)
                        "next" -> nextWidget(r, dv)
                        "counts" -> countsWidget(r, m)
                        "analog" -> analogWidget(dv, it.w, it.h)
                        "month" -> monthWidget(dv, it.w, it.h)
                        "battery" -> batteryWidget(it.w)
                        "todo" -> todoWidget(m, it.w, it.h)
                        "note" -> noteWidget(m, it.w, it.h)
                        "usage" -> usageWidget(dv, all, it.w, it.h)
                        "when" -> whenWidget(r, dv, block, it.w, it.h)
                        "msg" -> msgWidget(s, it.w, it.h)
                        "earn" -> earnWidget(r, s, it.w, it.h)
                        "class" -> classWidget(m, dv, it.w, it.h)
                        HomeLayout.APP -> appWidget(it, show)
                        else -> null
                    }?.also { v -> armWidget(v, it); (android.os.SystemClock.uptimeMillis() - w0).let { ms -> if (ms > 4) android.util.Log.i("KG.perf", "widget ${it.type} ${ms}ms") } } }
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
                cell.addView(v, CellLP(it.x, it.y, it.w, it.h, it.id))
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
        val on = drag != null || editing
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
    internal var todayGen = 0
    /** Live parts of widgets (analog hands, battery): run on the minute tick with the fresh View0, no rebuild. */
    internal val tickers = ArrayList<(View0) -> Unit>()
    internal var armed: View? = null
    internal var downX = 0f; internal var downY = 0f
    internal var flip: Runnable? = null
    internal var hoverX = 0f; internal var hoverY = 0f   // last drag hover point (edge flip repeats while the finger rests)
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
        val rail = LinearLayout(ctx).apply { orientation = VERTICAL; gravity = Gravity.CENTER }   // letters packed in the middle (POCO / One UI), not spread over the height
        val bubble = TextView(ctx)
        val scroll = ScrollView(ctx)
        private var lastKey = ""
        // init{} below calls refill(): its state must be declared above it
        var firstHit: View? = null
        private var fillGen = 0
        private var built = 0
        private var rowsLeft: () -> Unit = {}
        private var railKey = ""
        private var off = 0   // list children above the A–Z rows (the "recently used" header, row, divider)
        val rowOf = LinkedHashMap<String, Int>()   // rail letter -> index of the grid row holding its first app
        /** Every built cell with its rail letter: the scrubbed letter's apps stay lit, the rest dim (POCO / MIUI drawer). */
        private val cells = ArrayList<Pair<View, String>>()
        private var scrubbed: String? = null   // the letter under the finger (null once it lifts)
        private var lit: String? = null        // the highlighted letter: stays after the finger lifts, until the list is scrolled by hand
        private val alphaTo = HashMap<View, Float>()   // each cell's target alpha (its current one lags behind while animating)
        private var downOnRail = false
        private val hideBubble = Runnable { bubble.animate().alpha(0f).setDuration(150).withEndAction { bubble.visibility = View.GONE; bubble.alpha = 1f }.start() }
        private var scrollAnim: android.animation.ObjectAnimator? = null
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
                    val onRail = e.x >= width - paddingRight - dp(RAIL_DP)
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
            inner.addView(rail, LayoutParams(dp(RAIL_DP), -1))
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
                // the letter under the finger; above / below the packed block = the first / last one
                var i = 0
                while (i < rail.childCount - 1 && e.y >= rail.getChildAt(i).bottom) i++
                val letter = rail.getChildAt(i).tag as String
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                        if (letter != scrubbed) { scrubbed = letter; scrubTo(letter); v.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK) }
                        bubble.removeCallbacks(hideBubble); bubble.animate().cancel(); bubble.alpha = 1f
                        bubble.text = if (letter == "#") "…" else letter
                        bubble.visibility = View.VISIBLE
                        bubble.translationY = (e.y - dp(30)).coerceIn(0f, (v.height - dp(60)).toFloat())
                        if (e.actionMasked == MotionEvent.ACTION_DOWN) v.performClick()
                    }
                    // a tap shows the letter too: the bubble stays ~1 s, the highlight until the list is scrolled by hand
                    else -> { scrubbed = null; bubble.postDelayed(hideBubble, 1000) }
                }
                true
            }
            rail.contentDescription = getString(R.string.hm_d_rail)
            refill()
        }

        /** Rebuilds the grid only when what it shows changed (apps, pauses, the query, columns); cheap to call on every render. */
        fun refill() {
            val s = Store.of(context); val r = s.rules
            val all = launcherApps(); val dv = decision(r, s)
            val q = search.text.toString(); val fq = LauncherLogic.fold(q).trim()
            val shown = all.values.filter { LauncherLogic.show(it.pkg, dv.want, r, packageName) != LauncherLogic.Show.HIDE && LauncherLogic.matchesFolded(it.folded, fq) }
                .sortedBy { it.sortKey }
            val anyPaused = shown.any { dv.want[it.pkg] != null }
            val k = listOf(q, shown.joinToString(",") { it.pkg + "=" + it.label }, shown.map { dv.want[it.pkg] }, model?.cols, model?.onHome(), model?.style?.shape,
                model?.style?.icon, if (q.isBlank()) recent.take(12) else "", if (anyPaused) dv.minute else 0, if (anyPaused) dv.leftMs / 60_000 else 0, if (anyPaused) r.groups.map { it.hours } else 0).joinToString("|")
            if (k == lastKey) return
            lastKey = k
            unlight(); list.removeAllViews(); cells.clear(); alphaTo.clear(); firstHit = null; fillGen++
            if (shown.isEmpty()) {
                rail.removeAllViews(); railKey = ""
                list.addView(text(getString(R.string.hm_no_result, q), 15f, pal.sub).apply { gravity = Gravity.CENTER; setPadding(dp(16), dp(40), dp(16), dp(16)) }, LayoutParams(-1, -2))
                return
            }
            val cols = (model?.cols ?: 4).coerceIn(3, 5)
            val onHome = model?.onHome() ?: emptySet()
            val rows = shown.chunked(cols)
            // "Dùng gần đây": one row on top, only without a query (POCO / Pixel); the A–Z rows come after it
            val rec = if (q.isBlank()) recent.mapNotNull { p -> shown.firstOrNull { it.pkg == p } }.take(cols) else emptyList()
            off = 0
            // rail letters come from the data, so they exist before their rows are built
            rowOf.clear()
            if (q.isBlank()) for ((i, a) in shown.withIndex()) LauncherLogic.letter(a.label).let { l -> if (l !in rowOf) rowOf[l] = i / cols }
            // ~20 TextViews: rebuilt only when the letters change, not on every key typed
            val letters = rowOf.keys.joinToString("")
            if (letters != railKey) {
                railKey = letters; rail.removeAllViews()
                // small and light like POCO's: the grid keeps its room. Names starting with a digit or a sign sit under "…".
                for (l in rowOf.keys) rail.addView(text(if (l == "#") "…" else l, 10f, pal.sub).apply {
                    tag = l; gravity = Gravity.CENTER; setPadding(0, dp(2), 0, dp(2)); importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LayoutParams(-1, -2))
            }
            // ~1.5 ms per icon: 50+ apps cost 80 ms in one frame on a Galaxy A51 (each key typed, each open after a change).
            // Build what fills the screen now, the rest a few rows per frame; a new refill drops the old queue.
            val gen = ++fillGen
            fun addRow(i: Int) {
                val row = LinearLayout(context).apply { orientation = HORIZONTAL; setPadding(dp(8), dp(6), dp(8), dp(6)); clipChildren = false; clipToPadding = false }
                for (a in rows[i]) {
                    val v = appIcon(a, dv.want[a.pkg] != null, dv, r, Mode.DRAWER)
                    if (firstHit == null) firstHit = v
                    cells += v to LauncherLogic.letter(a.label)
                    row.addView(v, LayoutParams(0, -2, 1f))
                }
                // pad the last row so cells keep their width
                while (row.childCount < cols) row.addView(View(context), LayoutParams(0, 1, 1f))
                list.addView(row, list.childCount - 1, LayoutParams(-1, -2))
            }
            list.addView(View(context), LayoutParams(-1, dp(24)))   // bottom gap, rows go before it
            if (rec.size >= 2) {
                list.addView(text(getString(R.string.hm_recent), 12f, pal.sub).apply { setPadding(dp(16), dp(4), dp(16), 0) }, list.childCount - 1, LayoutParams(-1, -2))
                val row = LinearLayout(context).apply { orientation = HORIZONTAL; setPadding(dp(8), dp(6), dp(8), dp(6)); clipChildren = false; clipToPadding = false }
                for (a in rec) { val v = appIcon(a, dv.want[a.pkg] != null, dv, r, Mode.DRAWER); cells += v to ""; row.addView(v, LayoutParams(0, -2, 1f)) }
                while (row.childCount < cols) row.addView(View(context), LayoutParams(0, 1, 1f))
                list.addView(row, list.childCount - 1, LayoutParams(-1, -2))
                list.addView(View(context).apply { setBackgroundColor(pal.sub and 0x00FFFFFF or 0x33000000) }, list.childCount - 1, LayoutParams(-1, 1).apply { setMargins(dp(16), dp(6), dp(16), dp(8)) })
                off = 3
            }
            // as many rows as the visible list holds: the first row measured at the list's width
            addRow(0)
            val w = (if (scroll.width > 0) scroll.width else resources.displayMetrics.widthPixels - dp(RAIL_DP))
            val rowH = list.getChildAt(off).let { it.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.UNSPECIFIED); it.measuredHeight.coerceAtLeast(dp(48)) }
            val first = minOf(rows.size, (if (scroll.height > 0) scroll.height else resources.displayMetrics.heightPixels) / rowH + 1)
            for (i in 1 until first) addRow(i)
            built = first; rowsLeft = { if (gen == fillGen) { while (built < rows.size) addRow(built++) } }
            fun more() { if (gen != fillGen || built >= rows.size) return; repeat(2) { if (built < rows.size) addRow(built++) }; postOnAnimation { more() } }
            if (built < rows.size) postOnAnimation { more() }
        }

        /** The row view for [letter], building any rows still queued first. */
        fun rowFor(letter: String): View? { val i = rowOf[letter] ?: return null; if (i >= built) rowsLeft(); return list.getChildAt(i + off) }

        /**
         * Glides to [letter]'s first row (180 ms) and lights its apps. The row's top is read after layout: rows built just
         * now (the drawer builds a few per frame) had no position yet and a post{} could run before the layout pass, so
         * the list jumped to the wrong place (often the top).
         */
        private fun scrubTo(letter: String) {
            light(letter)
            val rv = rowFor(letter) ?: return
            val go = {
                val max = (list.height - scroll.height).coerceAtLeast(0)
                scrollAnim?.cancel()
                scrollAnim = android.animation.ObjectAnimator.ofInt(scroll, "scrollY", rv.top.coerceIn(0, max)).setDuration(180).apply {
                    interpolator = android.view.animation.DecelerateInterpolator(1.6f); start()
                }
            }
            if (!list.isLayoutRequested && rv.isLaidOut) go()
            else list.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() { list.viewTreeObserver.removeOnGlobalLayoutListener(this); if (lit == letter) go() }
            })
        }

        /** Back to normal: the list was scrolled by hand, the search changed, or the drawer closed. */
        fun unlight() {
            if (lit == null && bubble.visibility != View.VISIBLE) return
            scrollAnim?.cancel(); bubble.removeCallbacks(hideBubble); bubble.visibility = View.GONE; light(null)
        }

        /** Any touch reaches here first, even once the ScrollView has claimed the gesture: a drag on the list ends the highlight. */
        override fun dispatchTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> downOnRail = e.x >= width - paddingRight - dp(RAIL_DP)
                MotionEvent.ACTION_MOVE -> if (!downOnRail && lit != null && abs(e.rawY - py0) > pslop) unlight()
            }
            return super.dispatchTouchEvent(e)
        }

        /** [letter]'s apps at full strength, the others dimmed; null = all back to normal. The rail marks it too. */
        private fun light(letter: String?) {
            lit = letter
            for (j in 0 until rail.childCount) (rail.getChildAt(j) as TextView).let { t ->
                val on = t.tag == letter
                t.setTextColor(if (on) pal.solid else pal.sub); t.typeface = tf(on)
            }
            for ((v, l) in cells) {
                val a = if (letter == null || l == letter) 1f else 0.25f
                if ((alphaTo[v] ?: 1f) != a) { alphaTo[v] = a; v.animate().alpha(a).setDuration(120).start() }
            }
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
                    val free = !pager.dragging && pager.page > 0 && drag == null && !editing && this@HomeActivity.overlay == null && popup?.isShowing != true
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
        // Launcher3's scroll interpolator (quintic out): the page leaves at the finger's speed. The default (viscous fluid)
        // starts slow, so every swipe hitched right after the finger lifted.
        private val scroller = OverScroller(ctx) { t -> val u = t - 1f; u * u * u * u * u + 1f }
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
            snapTo(target, abs(vx))
        }

        private fun release() { dragging = false; vt?.recycle(); vt = null }

        fun snapTo(p: Int, vx: Float = 0f) {
            val n = (childCount - 1).coerceAtLeast(0)
            val old = page
            page = p.coerceIn(0, n)
            val dist = page * width - scrollX
            // quintic out starts at 5x the mean speed: match the fling, 180-320 ms (Launcher3 also derives it from velocity)
            val ms = if (vx > minFling && dist != 0) (5000f * abs(dist) / vx).toInt().coerceIn(180, 320) else 300
            scroller.startScroll(scrollX, 0, dist, 0, ms)
            postInvalidateOnAnimation()
            if (page != old) {
                onPage()
                announceForAccessibility(if (page == 0) getString(R.string.hm_t_title) else getString(R.string.hm_page, page, n))
            }
        }

        /** No animation (first show, HOME from far away). */
        fun jump(p: Int) { scroller.abortAnimation();  page = p.coerceIn(0, (childCount - 1).coerceAtLeast(0)); scrollTo(page * width, 0); onPage() }

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

        // edit mode: a tap (not a long-press, not a move) picks the item under it; the item never sees the click
        private var tx0 = 0f; private var ty0 = 0f; private var tt0 = 0L
        private val tslop = ViewConfiguration.get(ctx).scaledTouchSlop
        override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
            if (!editing) return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { tx0 = e.x; ty0 = e.y; tt0 = e.eventTime }
                MotionEvent.ACTION_UP -> if (abs(e.x - tx0) < tslop && abs(e.y - ty0) < tslop && e.eventTime - tt0 < ViewConfiguration.getLongPressTimeout() &&
                    drag == null && popup?.isShowing != true) {
                    for (i in childCount - 1 downTo 0) {
                        val c = getChildAt(i)
                        if (e.x >= c.left && e.x < c.right && e.y >= c.top && e.y < c.bottom) {
                            val id = (c.layoutParams as CellLP).id
                            if (id.isNotEmpty()) { togglePick(id); return true }
                        }
                    }
                }
            }
            return false
        }

        private val ringFill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ringLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        private val tick = android.graphics.Path()

        /** Edit mode: a pick circle on each item's top-right (the icon's corner for a 1x1), ticked on the accent when picked (POCO). */
        override fun dispatchDraw(c: Canvas) {
            super.dispatchDraw(c)
            if (!editing) return
            val r = dpf(10f)
            for (i in 0 until childCount) {
                val v = getChildAt(i); val lp = v.layoutParams as CellLP
                if (lp.id.isEmpty()) continue
                // a 1x1: the icon's corner; a widget: the corner of its card (FitCell centres a wrap-height card in the cells)
                val icon = if (v is ViewGroup && v.childCount > 0 && (v !is LinearLayout || (lp.w == 1 && lp.h == 1))) v.getChildAt(0) else null
                val cx = if (icon != null) v.left + icon.right - r * 0.4f else v.right - r * 1.2f
                val cy = if (icon != null) v.top + icon.top + r * 0.4f else v.top + r * 1.2f
                val on = lp.id in picked
                ringFill.color = if (on) pal.solid else 0x55000000
                c.drawCircle(cx, cy, r, ringFill)
                ringLine.color = if (on) pal.onSolid else Color.WHITE; ringLine.strokeWidth = dpf(if (on) 2f else 1.5f)
                if (on) {
                    tick.rewind(); tick.moveTo(cx - r * 0.45f, cy + r * 0.02f); tick.lineTo(cx - r * 0.1f, cy + r * 0.38f); tick.lineTo(cx + r * 0.5f, cy - r * 0.32f)
                    c.drawPath(tick, ringLine)
                } else c.drawCircle(cx, cy, r - ringLine.strokeWidth / 2, ringLine)
            }
        }

        fun cellW() = (width - 2 * side) / cols
        fun cellH() = (height - paddingTop - paddingBottom) / rows

        override fun onMeasure(w: Int, h: Int) {
            val W = MeasureSpec.getSize(w); val H = MeasureSpec.getSize(h)
            val cw = (W - 2 * side) / cols; val ch = ((H - paddingTop - paddingBottom) / rows).coerceAtLeast(1)
            shareLabels(cw)
            for (i in 0 until childCount) {
                val c = getChildAt(i); val lp = c.layoutParams as CellLP
                c.measure(MeasureSpec.makeMeasureSpec((cw * lp.w - gap).coerceAtLeast(0), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec((ch * lp.h - gap).coerceAtLeast(0), MeasureSpec.EXACTLY))
            }
            setMeasuredDimension(W, H)
        }

        /**
         * One UI: a long name may spill into the free space beside it when the neighbours' names are short (or the cell is
         * empty). Each 1x1 icon's label borrows the same amount left and right (it stays centred): the smaller of what
         * the two sides can spare. Decided once per measure; drawing outside the cell costs nothing per frame.
         */
        private fun shareLabels(cw: Int) {
            val inner = cw - gap
            val at = HashMap<Int, IconLabel>()   // y * cols + x -> label of a 1x1 icon
            val busy = HashSet<Int>()
            for (i in 0 until childCount) {
                val c = getChildAt(i); val lp = c.layoutParams as CellLP
                for (yy in lp.y until lp.y + lp.h) for (xx in lp.x until lp.x + lp.w) busy += yy * cols + xx
                if (lp.w != 1 || lp.h != 1) continue
                val box = c as? LinearLayout ?: continue
                (0 until box.childCount).map { box.getChildAt(it) }.firstOrNull { it is IconLabel }?.let { at[lp.y * cols + lp.x] = it as IconLabel }
            }
            fun need(l: IconLabel) = l.paint.measureText(l.text.toString()) + l.paddingLeft + l.paddingRight
            // what a cell can lend to one side: half its free width (all of it is free when empty, nothing for widgets)
            fun spare(x: Int, y: Int): Float = when {
                x < 0 || x >= cols -> (side - gap / 2f - kit.dpf(10f)).coerceAtLeast(0f)   // page margin, never to the screen edge
                (y * cols + x) !in busy -> cw / 2f
                else -> at[y * cols + x]?.let { ((inner - need(it)) / 2f).coerceAtLeast(0f) } ?: 0f
            }
            for ((k, l) in at) {
                val x = k % cols; val y = k / cols
                val over = need(l) - inner
                val e = if (over <= 0f) 0 else minOf(over / 2f, spare(x - 1, y) + gap / 2f, spare(x + 1, y) + gap / 2f).toInt().coerceAtLeast(0)
                val lp = l.layoutParams as? LinearLayout.LayoutParams ?: continue
                lp.leftMargin = -e; lp.rightMargin = -e
            }
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

/**
 * The home wallpaper on its own surface, composited by SurfaceFlinger like the system wallpaper of stock launchers.
 * As an ImageView the 1080x2400 bitmap was drawn again on every frame of a page swipe: 14-15 ms a frame on a Galaxy A51
 * (GPU-bound), 8 ms with it hidden, One UI 5 ms. Drawn once here, when the surface appears or the picture changes.
 */
internal class WallSurface(ctx: Context) : android.view.SurfaceView(ctx), android.view.SurfaceHolder.Callback {
    private var bmp: Bitmap? = null
    private var ready = false
    private val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
    init { holder.addCallback(this) }

    fun show(b: Bitmap?) { bmp = b; paintNow() }

    private fun paintNow() {
        if (!ready) return
        val b = bmp
        val c = runCatching { if (android.os.Build.VERSION.SDK_INT >= 26) holder.lockHardwareCanvas() else holder.lockCanvas() }.getOrNull() ?: return
        try {
            c.drawColor(android.graphics.Color.BLACK)
            if (b != null && !b.isRecycled) c.drawBitmap(b, null, android.graphics.Rect(0, 0, c.width, c.height), paint)
        } finally { runCatching { holder.unlockCanvasAndPost(c) } }
    }

    override fun surfaceCreated(h: android.view.SurfaceHolder) { ready = true; paintNow() }
    override fun surfaceChanged(h: android.view.SurfaceHolder, f: Int, w: Int, hh: Int) = paintNow()
    override fun surfaceDestroyed(h: android.view.SurfaceHolder) { ready = false }
}
