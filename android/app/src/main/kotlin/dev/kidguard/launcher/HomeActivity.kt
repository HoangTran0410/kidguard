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
        @Volatile var alive = 0; const val FOLDER_MS = 350L; const val SWAP_MS = 220L
        /** Created once: Typeface.create looked the family up again for every bold label. */
        val MEDIUM: Typeface by lazy { Typeface.create("sans-serif-medium", Typeface.NORMAL) }
        /** Launcher font (style.face), families every Android ships: no font files in the APK. */
        val FACES = listOf("sys", "light", "medium", "condensed", "serif", "easy")
        @Volatile var face = "sys"
        private val faces = HashMap<String, Typeface>()
        /** The chosen face, regular or emphasised (titles, buttons). The system "bold font" setting still adds on top. */
        fun tf(bold: Boolean): Typeface = faces.getOrPut("$face$bold") {
            when (face) {
                "light" -> Typeface.create(if (bold) "sans-serif" else "sans-serif-light", Typeface.NORMAL)
                "medium" -> if (bold) Typeface.create("sans-serif", Typeface.BOLD) else MEDIUM
                "condensed" -> Typeface.create("sans-serif-condensed", if (bold) Typeface.BOLD else Typeface.NORMAL)
                "serif" -> Typeface.create("serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
                "easy" -> Typeface.create("sans-serif-medium", Typeface.NORMAL)   // open, even strokes; the wide spacing is added on labels / cards (ReadAids)
                else -> if (bold) MEDIUM else Typeface.DEFAULT
            }
        }
    }

    /** [cat] = ApplicationInfo.category (-1 unknown, game flag folded in), [sys] = preinstalled: the app library's groups. */
    internal class AppE(val pkg: String, val cls: String, val label: String, val cat: Int = -1, val sys: Boolean = false, val real: String = label) {
        val sortKey by lazy { LauncherLogic.sortKey(label) }   // once per app list: sortedBy calls its selector per comparison
        val folded by lazy { LauncherLogic.fold(if (real == label) label else "$label $real") }       // drawer search (custom + real label): once per app list, not per key typed
    }

    /** What is being dragged: a grid / dock / folder / picker source, or [pkg] from the drawer. */
    internal class DragSt(val src: Src?, val pkg: String?, val kind: String, val w: Int, val h: Int, val id: String?, val view: View?)

    internal class CellLP(val x: Int, val y: Int, val w: Int, val h: Int, val id: String = "", val widget: Boolean = false) : ViewGroup.LayoutParams(-1, -1)

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
    internal lateinit var dots: PageDots
    internal lateinit var dock: LinearLayout
    internal var today: TodayPage? = null
    internal var drawer: Drawer? = null
    internal var popup: PopupWindow? = null
    internal var overlayExit: ((() -> Unit) -> Unit)? = null   // the open folder's closing animation (then the callback)
    internal var overlay: View? = null            // folder panel or widget picker (views, not dialogs: a drag survives them)
    internal var apps: Map<String, AppE>? = null
    private var appsLook = -1   // AppLook.version the cached [apps] labels were read with
    internal var model: HomeModel? = null
    internal var key = ""
    internal var lastTap = 0L
    internal var lastTapOn: Any? = null
    internal var drag: DragSt? = null
    internal var folderDrag: FolderDrag? = null   // a drag re-arranging the open folder (HomeFolder.kt)
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
    internal var awDrag: android.appwidget.AppWidgetProviderInfo? = null   // the provider of a picker drag of another app's widget
    internal var dockGap: View? = null      // the opening in the dock where a dragged app would go in
    internal var dockGapK = -1
    internal var dockRoom: Boolean? = null  // this drag's app fits in the dock without replacing one (decided once per drag)
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
        dots = PageDots(this).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES }
        // no "swipe up" chevron under the dots (owner: the swipe is enough, Pixel / iOS show none); TalkBack still gets
        // "all apps" as an action on the dots
        dots.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(R.id.kg_all_apps, getString(R.string.hm_all_apps)))
            }
            override fun performAccessibilityAction(host: View, action: Int, args: android.os.Bundle?) =
                if (action == R.id.kg_all_apps) { openDrawer(); true } else super.performAccessibilityAction(host, action, args)
        }
        dock = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        chrome.addView(bannerBox, LinearLayout.LayoutParams(-1, -2))
        chrome.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))
        bottom.addView(dots, LinearLayout.LayoutParams(-2, dp(18)).apply { bottomMargin = dp(2) })
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
                // the bars without the keyboard: the stable inset, except that ColorOS 5 (Oppo A5s, 8.1) reports stable = 0
                // under a 96 px nav bar (the dock sat on the back / home buttons). Then a small inset is the nav bar and a
                // big one (the keyboard) keeps the last bars value.
                else @Suppress("DEPRECATION") ins.stableInsetBottom.let { st -> if (st > 0) minOf(st, imeB) else if (imeB <= dp(72)) imeB else insB }
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

    override fun onDestroy() { alive--; main.removeCallbacks(renderRetry); main.removeCallbacks(pkgLater)
        pkgRx?.let { runCatching { unregisterReceiver(it) } }; pkgRx = null
        if (NotifDots.onChange === dotRx) NotifDots.onChange = null; super.onDestroy() }
    private val dotRx: (Set<String>) -> Unit = { changed -> Dots.refresh(window.decorView, changed) }

    /** The drawer's "recently used" packages, most recent first ([Recent]); read off the main thread on each return home. */
    internal var recent: List<String> = emptyList()
    /** The app library page (last pager child), filled the first time it scrolls into view; detached while dragging / editing. */
    internal var library: LibraryPage? = null
    /** pkg -> first install time, read off the main thread once per app set (the library's "Mới thêm" group). */
    internal var installed: Pair<Set<String>, Map<String, Long>>? = null
    internal fun onLibrary() = library?.let { it.parent === pager && pager.getChildAt(pager.page) === it } == true

    private var recentGen = 0

    override fun onResume() {
        super.onResume()
        val gen = ++recentGen   // a slower older load must not overwrite a newer result
        Thread {
            val r = Recent.load(applicationContext)
            runOnUiThread { if (gen == recentGen && r != recent) { recent = r; drawer?.refill() ?: drawerPrep() } }
        }.start()
        Guard.listeners.add(listener)
        timeRx = object : BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) { render(false) } }
        registerReceiver(timeRx, IntentFilter().apply { addAction(Intent.ACTION_TIME_TICK); addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_TIMEZONE_CHANGED) })
        if (pkgRx == null) {
            // the key covers the app set, labels and pauses: the Guard re-suspending the same apps (every poke) must not rebuild the home
            pkgRx = object : BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) { apps = null; Policy.invalidate(); main.removeCallbacks(pkgLater); main.postDelayed(pkgLater, 400) } }
            registerReceiver(pkgRx, IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED); addAction(Intent.ACTION_PACKAGE_CHANGED)
                addAction(Intent.ACTION_PACKAGES_SUSPENDED); addAction(Intent.ACTION_PACKAGES_UNSUSPENDED); addDataScheme("package")
            })
        }
        Guard.poke(this)
        NotifDots.onChange = dotRx
        NotifDots.setOn(this, (model ?: HomeFile.load(this))?.style?.badge != "off")
        model = null          // Tuỳ chỉnh may have changed home.json (HomeFile's cache)
        // apps stay: pkgRx lives from the first resume to onDestroy, so installs / updates while an app is in front reach
        // it (re-querying every launcher activity on each return home cost an Oppo A5s 6-7 ms)
        dialerAt = 0L; bannerSet = false
        render(false)         // rebuilds only when the key changed: coming back from an app is free
        drawerPrep()
    }

    override fun onPause() {
        exitEdit()
        Guard.listeners.remove(listener)
        timeRx?.let { runCatching { unregisterReceiver(it) } }; timeRx = null
        popup?.dismiss()
        pager.dragging = false   // a gesture cut by an app switch must not keep busy() true (the home would never rebuild)
        super.onPause()
    }

    override fun onStart() { super.onStart(); lifecycleStarted = true; awStart() }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) { super.onActivityResult(requestCode, resultCode, data); awResult(requestCode, resultCode, data) }

    override fun onStop() {
        lifecycleStarted = false; awStop()
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
        // a restored backup / undo (Tuỳ chỉnh) changes the renames behind our back: read the labels again
        if (appsLook != AppLook.version) { apps = null; appsLook = AppLook.version }
        apps?.let { return it }
        val pm = packageManager
        val out = LinkedHashMap<String, AppE>()
        @Suppress("DEPRECATION")
        for (ri in pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)) {
            val ai = ri.activityInfo ?: continue
            // our own "KidGuard Home" icon would open this very screen; the other flavor (dev.kidguard.home) has the same
            // class name but is a separate app: it stays listed
            if (ai.name == HomeEntry::class.java.name && ai.packageName == packageName) continue
            if (ai.packageName !in out) {
                // loadLabel is a binder call per app (Samsung routes it through its EDM service): kept on disk ([Labels])
                val info = ai.applicationInfo
                @Suppress("DEPRECATION")
                val cat = when { info == null -> -1; info.flags and android.content.pm.ApplicationInfo.FLAG_IS_GAME != 0 -> LibraryLogic.GAME; Build.VERSION.SDK_INT >= 26 -> info.category; else -> -1 }
                val real = Labels.of(this, ri, pm)
                out[ai.packageName] = AppE(ai.packageName, ai.name, AppLook.label(ai.packageName, ai.name, real), cat,
                    info != null && info.flags and (android.content.pm.ApplicationInfo.FLAG_SYSTEM or android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0, real)
            }
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
        // the Guard has not ticked yet (cold start): our own run is kept 15 s for the same rules (usage stats alone were
        // 44-72 ms on every render of a POCO until the Guard's first tick)
        own?.takeIf { (rr, unl, v) -> rr === r && unl == s.unlockUntil && now - v.now in 0..15_000 && v.today == today }?.let { (_, _, v) ->
            return View0(v.want, v.leftMs, v.usage, minute, v.dow, now, v.apps, v.prot, today)
        }
        val (all, prot) = Policy.cached(this)
        val use = runCatching { Usage.today(this, now).ms }.getOrDefault(emptyMap())
        val want = enforced(r, decide(r, use, minute, now, today, all.keys, prot, unlockUntil = s.unlockUntil))
        return View0(want, dailyLeftMs(r, use, today, all.keys, prot), use, minute, Rules.isoDow(today), now, all.keys, prot, today)
            .also { own = Triple(r, s.unlockUntil, it) }
    }

    internal fun resolvePkg(i: Intent): String? = runCatching {
        packageManager.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName?.takeIf { it != "android" && !it.contains("resolver") }
    }.getOrNull()

    // resolved once per resume / package change (a binder call on every render otherwise)
    private var dialerPkg: String? = null; private var dialerAt = 0L
    private val todayIcon: (AppE, Int) -> View = { a, size ->
        ImageView(this).apply { IconCache.get(this@HomeActivity, a.pkg, a.cls, dp(size), model?.style?.shape != "square")?.let { setImageBitmap(it) }; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
    }
    internal fun dialer(): String? {
        if (dialerAt == 0L) { dialerPkg = resolvePkg(Intent(Intent.ACTION_DIAL)); dialerAt = 1L }
        return dialerPkg
    }
    // the banner's checks (device owner, usage access, location, ...: binder calls), at most every 20 s and on each resume:
    // the kid fixing one in Settings comes back through onResume; location from the shade shows within 20 s
    private var bannerAt = 0L; private var bannerSet = false
    private var cOwner = false; private var cUsage = false; private var cLoc = false
    private fun bannerNow(s: Store): String? {
        val now = android.os.SystemClock.elapsedRealtime()
        if (Flavor.HOME) {   // no Guard / admin to warn about: only "Home still opens the old launcher"
            if (!bannerSet || now - bannerAt > 20_000) { cOwner = LauncherSafe.homeIsOurs(this); bannerAt = now; bannerSet = true }
            return if (cOwner) null else "home"
        }
        if (!bannerSet || now - bannerAt > 20_000) {
            // basic mode (admin + accessibility, no Device Owner) is a finished setup too: nothing to "fix" there
            cOwner = Policy.isOwner(this) || Basic.enforcing(this); cUsage = Usage.granted(this); cLoc = locationOn()
            bannerAt = now; bannerSet = true
        }
        return if (!cOwner && !s.everPaired) null   // standalone use before setup: nothing to nag about
            else LauncherLogic.banner(cOwner, Guard.running != null, cUsage, cLoc, Acc.isOn(this) || AccPause.active(this))
    }
    // last check-ins ("đã tới" / "đón con"), re-read only when the stored events change
    private var evRaw: String? = null; private var evKey = ""
    private fun checkinKey(s: Store): String {
        val raw = s.raw("events")
        if (raw != evRaw) { evRaw = raw; evKey = "${s.lastCheckin("arrived")},${s.lastCheckin("pickup")}" }
        return evKey
    }
    private var jsonOf: HomeModel? = null; private var jsonStr = ""

    // owner / paired change rarely: read once per resume (dialer cache is reset there too)
    private var managedAt = 0L; private var managed = true
    /** Tailscale installed by KidGuard for the parents' remote access: hidden from the kid (LauncherLogic.show). */
    internal val tsManaged: Boolean get() = !Flavor.HOME && runCatching { Store.of(this).tsWanted }.getOrDefault(false)

    internal fun settingsOk(r: Rules): Boolean {
        if (Flavor.HOME) return true
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - managedAt > 20_000) { managed = Policy.isOwner(this) || Store.of(this).everPaired; managedAt = now }
        return LauncherLogic.settingsOk(r, owner = managed, paired = false)
    }

    internal fun firstModel(r: Rules, visible: List<AppE>): HomeModel {
        val vis = visible.map { it.pkg }.toSet()
        val d = dialer()   // slot 1 is already the phone: an app that is both dialer and SMS (Truecaller) must not come twice
        val dockApps = listOfNotNull(
            runCatching { Telephony.Sms.getDefaultSmsPackage(this) }.getOrNull(),
            resolvePkg(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)),
            resolvePkg(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))),
        ).filter { it in vis && it != d }
        val all = visible.map { it.pkg }.filter { it != d }
        val used = runCatching {   // most used in the last 2 weeks (needs usage access; KidGuard Home may not have it)
            val now = System.currentTimeMillis()
            getSystemService(android.app.usage.UsageStatsManager::class.java).queryAndAggregateUsageStats(now - 14 * 86_400_000L, now)
                .values.filter { it.totalTimeInForeground > 60_000 }.sortedByDescending { it.totalTimeInForeground }.map { it.packageName }
        }.getOrDefault(emptyList())
        return HomeLayout.fresh(r.launcher.style.ifEmpty { "teen" }, HomeLayout.featured(all.filter { it !in dockApps }, used), dockApps, parent = !Flavor.HOME, known = all)
    }

    // ---------------- render ----------------

    /**
     * Lighter effects: no page fade, no live shadows under moving cards. Chosen in Tuỳ chỉnh ("fx": full / lite), or
     * "auto": lite when the system's animations are off (Developer options / Remove animations) or on a weak phone.
     * Read on every render, so a change in the system settings applies on the next return home.
     */
    internal var liteFx = false
    private val weakPhone by lazy {
        val am = getSystemService(android.app.ActivityManager::class.java)
        val mi = android.app.ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        android.os.Build.VERSION.SDK_INT < 28 || am?.isLowRamDevice == true || mi.totalMem in 1 until 3L * 1024 * 1024 * 1024
    }
    internal fun fxNow(st: HStyle?) = if (st?.calm == true) true else when (st?.fx) {
        "full" -> !android.animation.ValueAnimator.areAnimatorsEnabled()
        "lite" -> true
        else -> !android.animation.ValueAnimator.areAnimatorsEnabled() || weakPhone
    }
    internal fun shadow(d: Float) = if (liteFx) 0f else dpf(d)

    private val renderRetry = Runnable { render(false) }
    private val pkgLater = Runnable { apps = null; dialerAt = 0L; render(false) }   // a suspend / install burst: one rebuild
    internal fun busy() = drag != null || overlay != null || popup?.isShowing == true || pager.dragging

    internal fun render(force: Boolean) {
        val t0 = android.os.SystemClock.elapsedRealtime()
        liteFx = fxNow(model?.style)
        ReadAids.applyMotion(this, model?.style)
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
        val lap = Lap()
        val s = Store.of(this); val r = s.rules; lap("rules")
        val all = launcherApps(); lap("apps")
        val dv = decision(r, s); lap("decision")
        val show = focusShow(all.keys.associateWith { LauncherLogic.show(it, dv.want, r, packageName, settingsOk(r), tsManaged) }, dv); lap("show")
        val visible = all.values.filter { show[it.pkg] != LauncherLogic.Show.HIDE }.sortedBy { it.sortKey }; lap("sort")
        var m = model ?: HomeFile.load(this) ?: firstModel(r, visible); lap("model")
        m = HomeLayout.prune(m) { it !in all && !Policy.installed(this, it) }; lap("prune")
        val dial = dialer()
        m = HomeLayout.appendNew(m, visible.map { it.pkg }.filter { it != dial })
        if (m != model) { HomeFile.save(this, m); model = m }
        lap("new")
        awSync(m); lap("aw")
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
        val banner = bannerNow(s)
        lap("banner")
        val files = runCatching { Files.all(this) }.getOrDefault(emptyList()).joinToString(",") { it.id + it.st }
        lap("files")
        // not the minute itself: what it changes (next event, today's schedule states, the date); the clock text is set in place below
        val timeKey = listOf(dv.today, LauncherLogic.nextEvent(r, dv.minute, dv.dow), LauncherLogic.today(r, dv.minute, dv.dow).map { it.state },
            m.timetable.takeIf { it.length() > 0 }?.let { tt -> TimetableLogic.at(TimetableLogic.slots(tt, dv.dow), dv.minute).let { (c, n) -> "${c?.start}-${n?.start}" } },
            TimetableLogic.eveningFirst(dv.minute))
        lap("time")
        val mj = if (m === jsonOf) jsonStr else m.toJson().toString().also { jsonOf = m; jsonStr = it }; lap("json")
        val k = listOf(mj, all.values.joinToString(",") { it.label }, show.toString(), dv.want.toString(), block, banner, timeKey, dv.leftMs / 60_000, s.everPaired,
            s.raw("sos"), checkinKey(s), s.raw("reqs"), r.blockNote, r.launcher, dark, files,
            r.chores, r.schedules, r.groups.map { it.hours }, r.bedtimeOn, r.bedStart, r.bedEnd, r.lockContact, focusKey(this)).joinToString("|")
        lap("key")
        if (!force && k == key) { tickClocks(dv); focusTick(); lap.done("same"); return }
        if (busy()) { main.removeCallbacks(renderRetry); main.postDelayed(renderRetry, 1500); return }   // never rebuild under a finger / an open panel
        key = k
        // everything the page views depend on except the grid pages themselves (and dock / seen, which no page shows)
        pageGlobal = k.replaceFirst(mj, m.copy(pages = emptyList(), dock = emptyList(), seen = emptySet()).toJson().toString()) + "|" + liteFx + "|" + kit.font
        lastView = dv; lastShow = show
        tempPage = false
        ensureWall(); lap("wall")
        buildBanner(banner); focusChip()
        buildTopBar()
        buildPages(m, all, show, dv, r, s, block); lap("pages")
        buildDock(m, all, show, dv, r); lap("dock")
        // the "Hôm nay" page (25-35 ms) is off screen on almost every render: rebuilt once the main thread is idle
        val fillToday = { if (m.style.today) today?.fill(r, dv, block, all, todayIcon) }
        if (pager.page == 0) fillToday() else {
            val gen = ++todayGen
            android.os.Looper.myQueue().addIdleHandler { if (gen == todayGen && !isFinishing) fillToday(); false }
        }
        lap("today")
        lap("${IconCache.stats()}"); lap.done("rebuild")
        // no-op unless what it shows changed; the first one is built while idle, so the first swipe up has it ready under the finger
        drawerCache?.let { d -> main.post { d.refill() } } ?: drawerWarm(all.values)
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

    internal fun card(radius: Int = 22) = GlassCard(pal.card, dpf(radius.toFloat()), dpf(1f), pal.dark)
    internal fun fill(color: Int, radius: Float = 999f) = GradientDrawable().apply { setColor(color); cornerRadius = dpf(radius) }
    internal fun text(t: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t; setTextSize(TypedValue.COMPLEX_UNIT_SP, sp * (model?.style?.font?.toFloat() ?: 1f)); setTextColor(color)
        typeface = tf(bold)
        includeFontPadding = false
    }

    internal fun buildBanner(code: String?) {
        bannerBox.removeAllViews()
        if (code == null) return
        val msg = when (code) { "owner" -> R.string.hm_b_owner; "usage" -> R.string.hm_b_usage; "location" -> R.string.hm_b_location; "acc" -> R.string.hm_b_acc; "home" -> R.string.hm_b_home; else -> R.string.hm_b_guard }
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
            kit.tap(this, 999f, true) { if (code == "home") homeSettings() else openMain() }
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

    /**
     * "auto" rows: as many rows as make the cells ~square on this screen (POCO: 4×7 on a 20:9 phone), fewer when an icon +
     * one label line would not fit (font scale). Measured without the banner, so a banner coming and going never regrids.
     */
    internal fun autoRows() {
        val m = model ?: return
        // always on now (owner: rows are not a choice; an old manual "rows" pick made oblong cells): m.autoRows is ignored
        if (drag != null || editing || pager.height == 0) return
        val availH = pager.height - insT - dp(16) - (if (topBar.visibility == View.VISIBLE) topBar.height else 0) - insB - bottom.height
        val cellW = (pager.width - 2 * dp(10)) / m.cols
        // what a 1x1 app cell needs (appIcon): 4 dp pad + icon + 2 dp frame + 2 dp margin + one real label line + 2 dp pad, + the 6 dp gap.
        // Measured, not estimated: a guess of 12 sp cut the "g" of "Telegram" at 7 rows on the Poco.
        val minH = cellMinH()
        val r = HomeLayout.fitRows(availH, cellW, minH, m.rows)
        if (r != m.rows) { Log.i("KG.perf", "autoRows ${m.rows} -> $r (h=$availH cw=$cellW min=$minH)"); update(HomeLayout.regrid(m, m.cols, r)) }
    }

    private var minHKey = 0f; private var minHVal = 0
    /** What a 1x1 app cell needs (appIcon): 4 dp pad + icon + 2 dp frame + 2 dp margin + one real label line + 2 dp pad.
     *  No row gap on top (CellLayout.box gives icon cells the whole row): with it the POCO's icon + label needed 310 px against
     *  a 289 px column, rows grew past the columns and 2x2 widgets came out 34 px taller than wide. */
    internal fun cellMinH(): Int {
        val m = model
        val key = iconPx() * 1000f + labelPx(false) + (if (m?.style?.labels == false) 0.5f else 0f)
        if (key != minHKey) {
            // measured, not estimated: a guess of 12 sp cut the "g" of "Telegram" at 7 rows on the Poco
            val line = if (m?.style?.labels != false) iconLabel("Ág", false, Color.WHITE, 1).apply { measure(0, 0) }.measuredHeight else 0
            minHVal = iconPx() + dp(4 + 2 + 2 + 2) + line; minHKey = key
        }
        return minHVal
    }

    internal fun announce(t: String) = root.announceForAccessibility(t)

    /**
     * Home pages leave room for the chrome (status bar + banner on top, dots + dock below). Frozen while an
     * item is dragged: the drop bar and the tip float over the grid, so the grid neither shrinks nor jumps.
     */
    internal fun padPages() {
        if (!::pager.isInitialized) return
        pager.post { autoRows() }
        (tip.layoutParams as? FrameLayout.LayoutParams)?.let { lp -> val b = bottom.height + dp(8); if (lp.bottomMargin != b) { lp.bottomMargin = b; tip.layoutParams = lp } }
        if (drag != null) return
        // + 16 dp: the first row sat right under the status bar icons (One UI / Pixel leave a gap there too)
        val top = insT + dp(16) + bannerBox.height + (if (topBar.visibility == View.VISIBLE) topBar.height else 0)
        val bot = insB + bottom.height
        for (i in 1 until pager.childCount) {
            val c = pager.getChildAt(i)
            // the library has no chrome over it (it fades away there): only the system bars
            if (c === library) { library?.pad(insT + dp(12), insB + dp(12)); continue }
            if (c.paddingTop != top || c.paddingBottom != bot) c.setPadding(0, top, 0, bot)
        }
    }

    /** One built grid page and what it registered (wallpaper ink, clocks, tickers, item views), to keep it across a layout edit. */
    internal class PageBuilt(val cell: CellLayout, val ink: List<View>, val clocks: List<Pair<TextView, TextView>>, val tickers: List<(View0) -> Unit>, val items: Map<String, View>)
    private var builtPages = HashMap<String, MutableList<PageBuilt>>()
    private var builtGlobal = ""
    internal var pageGlobal = ""        // the render key without the grid pages (set by render0)
    internal var reusePages = false     // set by layout edits (update, drop): the next build may keep unchanged pages

    internal fun buildPages(m: HomeModel, all: Map<String, AppE>, show: Map<String, LauncherLogic.Show>, dv: View0, r: Rules, s: Store, block: String?) {
        inkViews.clear(); itemViews.clear(); clocks.clear(); tickers.clear()
        val keep = pager.page
        // a layout-only change (drop, minus, group, move): pages whose items and everything else are the same keep their
        // views. Rebuilding every page cost an Oppo A5s 68 ms per drop (5 frames); data refreshes still rebuild all.
        val global = pageGlobal + "|" + (Flavor.widget("next") && HomeLayout.hasWidget(m, "next"))
        val old = if (reusePages && global == builtGlobal) builtPages else HashMap()
        reusePages = false; builtGlobal = global
        val made = HashMap<String, MutableList<PageBuilt>>()
        pager.removeAllViews()
        today?.view?.let { (it.parent as? ViewGroup)?.removeView(it); pager.addView(it); it.visibility = if (m.style.today) View.VISIBLE else View.INVISIBLE }
        pager.minPage = if (m.style.today) 0 else 1
        val hasNext = (Flavor.widget("next") && HomeLayout.hasWidget(m, "next"))
        for ((pi, p) in m.pages.withIndex()) {
            val sig = p.joinToString(";") { it.toJson().toString() }
            val reuse = old[sig]?.removeLastOrNull()
            if (reuse != null) {
                val cell = reuse.cell
                // what a drag / press / swap preview may have left on them: hidden source, offsets, press scale
                for (i in 0 until cell.childCount) cell.getChildAt(i).apply {
                    // back to each view's rest scale (Motion.rest): a widget card shrunk to fit its cells is not 1, and 1 left it
                    // ~8 % bigger than its cells after every drag
                    animate().cancel(); visibility = View.VISIBLE; alpha = 1f; Motion.rest(this).let { s -> scaleX = s; scaleY = s }; translationX = 0f; translationY = 0f
                    (this as? ViewGroup)?.getChildAt(0)?.let { ic -> ic.animate().cancel(); val s = Motion.rest(ic); ic.scaleX = s; ic.scaleY = s }
                }
                cell.hl = null; cell.plateOff()
                inkViews += reuse.ink; clocks += reuse.clocks; tickers += reuse.tickers; itemViews += reuse.items
                cell.page = pi
                pager.addView(cell)
                made.getOrPut(sig) { ArrayList() } += reuse
                continue
            }
            val i0 = inkViews.size; val c0 = clocks.size; val t0 = tickers.size
            val cell = CellLayout(this, m.cols, m.rows)
            val items = HashMap<String, View>()
            for (it in p) {
                val v: View = when (it.kind) {
                    "widget" -> { val w0 = android.os.SystemClock.uptimeMillis(); if (!Flavor.widget(it.type)) null else when (it.type) {
                        "clock" -> clockWidget(r, dv, it.w, it.h, hasNext)
                        "time" -> timeWidget(r, dv, block, s, it.w)
                        "quick" -> quickWidget(s, it.w)
                        "next" -> nextWidget(r, dv)
                        "counts" -> countsWidget(r, m)
                        "analog" -> analogWidget(dv, it.w, it.h)
                        "month" -> monthWidget(dv, it.w, it.h)
                        "battery" -> batteryWidget(it.w)
                        "lock" -> lockWidget(it.w)
                        "todo" -> todoWidget(m, it.w, it.h)
                        "note" -> noteWidget(m, it.w, it.h)
                        "usage" -> usageWidget(dv, all, it.w, it.h)
                        "when" -> whenWidget(r, dv, block, it.w, it.h)
                        "msg" -> msgWidget(s, it.w, it.h)
                        "earn" -> earnWidget(r, s, it.w, it.h)
                        "class" -> classWidget(m, dv, it.w, it.h)
                        "timer" -> timerWidget(it.w, it.h)
                        "streak" -> streakWidget(m, r, s, it.w, it.h)
                        "media" -> mediaWidget(it.w, it.h)
                        HomeLayout.APP -> appWidget(it, show)
                        else -> null
                    }?.also { v -> armWidget(v, it); (android.os.SystemClock.uptimeMillis() - w0).let { ms -> if (ms > 4) android.util.Log.i("KG.perf", "widget ${it.type} ${ms}ms") } } }
                    "app" -> all[it.pkg]?.takeIf { a -> show[a.pkg] != LauncherLogic.Show.HIDE }?.let { a ->
                        appIcon(a, show[a.pkg] == LauncherLogic.Show.PAUSED, dv, r, Mode.HOME, it)
                    }
                    // an app's pinned shortcut: shown, paused and blocked exactly like its app (never a way around a rule)
                    HomeLayout.SHORTCUT -> all[it.pkg]?.takeIf { a -> show[a.pkg] != LauncherLogic.Show.HIDE }?.let { a ->
                        appIcon(AppE(a.pkg, Shortcuts.PREFIX + it.cls, it.name.ifBlank { a.label }), show[a.pkg] == LauncherLogic.Show.PAUSED, dv, r, Mode.HOME, it)
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
                itemViews[it.id] = v; items[it.id] = v
                // a 1x1 widget (lock, battery…) is drawn like an app icon + name: it keeps the whole cell like the icons around it
                cell.addView(v, CellLP(it.x, it.y, it.w, it.h, it.id, it.kind == "widget" && it.w * it.h > 1))
            }
            cell.page = pi
            pager.addView(cell)
            made.getOrPut(sig) { ArrayList() } += PageBuilt(cell, ArrayList(inkViews.subList(i0, inkViews.size)), ArrayList(clocks.subList(c0, clocks.size)),
                ArrayList(tickers.subList(t0, tickers.size)), items)
        }
        builtPages = made
        // the App Library: the last page, built empty, filled when it first scrolls into view (libraryFill)
        library = if (m.style.lib) LibraryPage(this).also { if (drag == null && !editing) pager.addView(it) } else null
        // built while idle, not on the first swipe to it: ~100 ms of icon views on an Oppo A5s was one 113 ms frame mid-swipe
        library?.let { l ->
            // fill the library once the home is idle: waits while the kid drags, swipes or has a folder open
            fun later(ms: Long) { main.postDelayed({ if (library === l) { if (busy()) later(400) else libraryFill() } }, ms) }
            later(1500)
        }
        styleCells()
        padPages()
        pager.page = keep.coerceIn(pager.minPage.coerceAtMost(pager.childCount - 1), pager.childCount - 1).let { if (it == 0 && keep != 0) 1 else it }
        pager.onPage = { buildDots(); bars(); chromeFade() }
        buildDots()
        pager.post { pager.scrollTo(pager.page * pager.width, 0); chromeFade() }
    }

    /** While dragging: the page panel in the wallpaper's ink colour. */
    internal fun styleCells() {
        val on = editing   // dragging: no page panel (iOS); only the drop cell lights up, fading and gliding
        val c = inkCache["grid"]?.text ?: Color.WHITE
        for (i in 1 until pager.childCount) (pager.getChildAt(i) as? CellLayout)?.let { it.grid = on; it.gridColor = c; it.invalidate() }
    }

    /** -1 page: the chrome (dots, dock, banner) slides away with the pages; status bar icons follow the page. */
    internal fun chromeFade() {
        val w = pager.width.takeIf { it > 0 } ?: return
        val f0 = (pager.scrollX.toFloat() / w).coerceIn(0f, 1f)     // 0 on "Hôm nay", 1 on page 1+
        // the App Library (last page) is full screen like "Hôm nay": the chrome leaves to the left as it comes in
        val li = library?.takeIf { it.parent === pager }?.let { pager.indexOfChild(it) } ?: -1
        val g = if (li > 0) ((li * w - pager.scrollX).toFloat() / w).coerceIn(0f, 1f) else 1f
        val f = minOf(f0, g)
        chrome.alpha = f
        chrome.visibility = if (f < 0.02f) View.INVISIBLE else View.VISIBLE
        // mid-fade: one GPU layer, so the alpha does not redraw dock + dots + banner offscreen every frame
        val lt = if (f > 0.02f && f < 0.98f) View.LAYER_TYPE_HARDWARE else View.LAYER_TYPE_NONE
        if (chrome.layerType != lt) chrome.setLayerType(lt, null)
        chrome.translationX = if (g < f0) -(1 - g) * w * 0.3f else (1 - f) * w * 0.3f
        dots.pos = pager.scrollX.toFloat() / w
        pageFade(w)
    }

    /**
     * iOS / LauncherOS page change: the leaving page fades out as it slides, the arriving one fades in. Only alpha on
     * the page views (CellLayout draws its children without an offscreen buffer, see hasOverlappingRendering).
     */
    internal fun pageFade(w: Int) {
        val x = pager.scrollX
        for (i in 0 until pager.childCount) {
            val v = pager.getChildAt(i)
            val d = abs(i * w - x).toFloat() / w
            // edit mode: the pager shrinks and the neighbours peek in at the sides, they stay visible
            val a = if (editing || liteFx) 1f else if (d >= 1f) 0f else (1f - d * 1.15f).coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }   // smoothstep
            if (v.alpha != a) v.alpha = a
            if (a > 0f && v === library) libraryFill()
        }
    }

    internal fun buildDots() {
        val n = pager.childCount - 1
        dots.contentDescription = getString(R.string.hm_page, pager.page.coerceAtLeast(1), n.coerceAtLeast(1))
        val c = inkCache["dots"]?.text ?: Color.WHITE
        dots.set(n, c, if (pager.minPage == 0) Ic.get(this, R.drawable.li_calendar, c, dp(10)) else null)   // no "Hôm nay" glyph when that page is off
        pager.width.takeIf { it > 0 }?.let { dots.pos = pager.scrollX.toFloat() / it }
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
    internal var focusView: MenuFocus? = null     // the dark layer + lifted icon behind a long-press menu
    internal var swapArm: Runnable? = null        // re-arrange preview waits SWAP_MS: passing over an icon toward its middle (folder) never shoves it
    internal var swapWant: String? = null
    internal var swapId: String? = null           // item sliding out of the way (preview of the re-arrange)
    internal var removeBtn: View? = null
    internal var removeHot = false
    internal var removeNear = false               // heading for the remove circle: no cell highlight under it
    internal var dropped = false                  // the drop changed the layout: endDrag leaves the views for the new render
    internal var land: Triple<DragSt, Float, Float>? = null
    internal var barsCache: Pair<String, Pair<Boolean, Boolean>>? = null
    internal var drawerCache: Drawer? = null
    internal var drawerWarming = false
    private var own: Triple<Rules, Long, View0>? = null
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
        private var lastSk = ""
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
        private var cur: String? = null        // the letter of the top row while the list is scrolled by hand: marked on the rail only
        private val alphaTo = HashMap<View, Float>()   // each cell's target alpha (its current one lags behind while animating)
        private var downOnRail = false
        private val hideBubble = Runnable { Motion.fade(bubble, 0f) { bubble.visibility = View.GONE; bubble.alpha = 1f } }
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
                // empty field: the key only closes the keyboard (Pixel); it used to open the first app of the grid (e.g. Agoda)
                setOnEditorActionListener { v, id, _ ->
                    if (id != EditorInfo.IME_ACTION_GO) false
                    else if (v.text.isNullOrBlank()) { (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0); v.clearFocus(); true }
                    else { firstHit?.performClick(); true }
                }
            }
            bar.addView(search, LayoutParams(0, -2, 1f))
            // ⋮ -> "Hidden apps" (HomeHidden.kt): what the drawer leaves out, and why
            bar.addView(ImageView(ctx).apply {
                setImageDrawable(Ic.get(ctx, R.drawable.li_ellipsis_vertical, pal.sub, dp(20))); setPadding(dp(10), dp(10), dp(10), dp(10)); contentDescription = getString(R.string.hm_more)
                kit.tap(this, -1f) { menu(listOf(Row(R.drawable.li_eye_off, getString(R.string.hm_hidden_m), true) { hiddenScreen() }), this) }
            }, LayoutParams(dp(44), dp(44)))
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
            // scrolling by hand: the rail follows, marking the letter of the row at the top (POCO / One UI), no dimming
            scroll.setOnScrollChangeListener { _, _, y, _, _ -> if (lit == null) follow(y) }
            refill()
        }

        /** Rebuilds the grid only when what it shows changed (apps, pauses, the query, columns); cheap to call on every render. */
        fun refill() {
            val s = Store.of(context); val r = s.rules
            val all = launcherApps(); val dv = decision(r, s)
            val q = search.text.toString(); val fq = LauncherLogic.fold(q).trim()
            // study mode hides every non-study app here too (not only on the home pages), with a note + "End early" at the end
            val fa = focusAllowed(dv)
            val ok = all.values.filter { LauncherLogic.show(it.pkg, dv.want, r, packageName, settingsOk(r), tsManaged) != LauncherLogic.Show.HIDE }
            val hidden = if (fa == null) 0 else ok.count { it.pkg !in fa }
            val shown = ok.filter { (fa == null || it.pkg in fa) && LauncherLogic.matchesFolded(it.folded, fq) }.sortedBy { it.sortKey }
            val anyPaused = shown.any { dv.want[it.pkg] != null }
            val sk = listOf(q, shown.joinToString(",") { it.pkg + "=" + it.label }, shown.map { dv.want[it.pkg] }, model?.cols, model?.onHome(), model?.style?.shape,
                model?.style?.icon, if (q.isBlank()) recent.take(12) else "", hidden, FocusStore.session(context)?.end).joinToString("|")
            val k = sk + "|" + listOf(if (anyPaused) dv.minute else 0, if (anyPaused) dv.leftMs / 60_000 else 0, if (anyPaused) r.groups.map { it.hours } else 0).joinToString("|")
            if (k == lastKey) return
            // only the minute moved (a paused app's time left) while the kid is looking at the drawer: rebuilding it under
            // the finger reset the scroll every minute. It catches up the next time the drawer opens.
            if (sk == lastSk && this@HomeActivity.drawer === this && isShown) return
            lastKey = k; lastSk = sk
            unlight(); list.removeAllViews(); cells.clear(); alphaTo.clear(); firstHit = null; fillGen++
            val note = { if (hidden > 0) list.addView(focusDrawerNote(hidden) { lastKey = ""; refill() }, LayoutParams(-1, -2)) }
            if (shown.isEmpty()) {
                rail.removeAllViews(); railKey = ""
                list.addView(text(getString(R.string.hm_no_result, q), 15f, pal.sub).apply { gravity = Gravity.CENTER; setPadding(dp(16), dp(40), dp(16), dp(16)) }, LayoutParams(-1, -2))
                note()
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
            // bottom: the study note (if any) + a gap, in one view: rows go before it
            list.addView(LinearLayout(context).apply { orientation = VERTICAL; if (hidden > 0) addView(focusDrawerNote(hidden) { lastKey = ""; refill() }, LayoutParams(-1, -2)); addView(View(context), LayoutParams(-1, dp(24))) }, LayoutParams(-1, -2))
            // no "recently used" title: the row over the divider reads as such (owner's call)
            if (rec.size >= 2) {
                val row = LinearLayout(context).apply { orientation = HORIZONTAL; setPadding(dp(8), dp(6), dp(8), dp(6)); clipChildren = false; clipToPadding = false }
                for (a in rec) { val v = appIcon(a, dv.want[a.pkg] != null, dv, r, Mode.DRAWER); cells += v to ""; row.addView(v, LayoutParams(0, -2, 1f)) }
                while (row.childCount < cols) row.addView(View(context), LayoutParams(0, 1, 1f))
                list.addView(row, list.childCount - 1, LayoutParams(-1, -2))
                list.addView(View(context).apply { setBackgroundColor(pal.sub and 0x00FFFFFF or 0x33000000) }, list.childCount - 1, LayoutParams(-1, 1).apply { setMargins(dp(16), dp(6), dp(16), dp(8)) })
                off = 2
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
                scrollAnim = android.animation.ObjectAnimator.ofInt(scroll, "scrollY", rv.top.coerceIn(0, max)).setDuration(Motion.MED).apply {
                    interpolator = Motion.STD; start()
                }
            }
            if (!list.isLayoutRequested && rv.isLaidOut) go()
            else list.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() { list.viewTreeObserver.removeOnGlobalLayoutListener(this); if (lit == letter) go() }
            })
        }

        /** The rail letter whose first row is the last one at or above the list's top edge (only rows already built). */
        private fun follow(y: Int) {
            var at: String? = null
            for ((l, i) in rowOf) { if (i >= built) break; val v = list.getChildAt(i + off) ?: break; if (v.top <= y + dp(8)) at = l else break }
            if (at == cur) return
            cur = at
            for (j in 0 until rail.childCount) (rail.getChildAt(j) as TextView).let { t -> val on = t.tag == at; t.setTextColor(if (on) pal.solid else pal.sub); t.typeface = tf(on) }
        }

        /** Back to normal: the list was scrolled by hand, the search changed, or the drawer closed. */
        fun unlight() {
            if (lit == null && bubble.visibility != View.VISIBLE) return
            scrollAnim?.cancel(); bubble.removeCallbacks(hideBubble); bubble.visibility = View.GONE; light(null); cur = null; follow(scroll.scrollY)
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
                if ((alphaTo[v] ?: 1f) != a) { alphaTo[v] = a; Motion.fade(v, a, Motion.FAST) }
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
        // the finger went down on something that scrolls (another app's list widget, a long list in ours): vertical is its, both
        // ways (Pixel / Launcher3). Was: only swipe-down checked it, swipe-up always opened the drawer over the list
        private var onScroll = false
        private var took = false   // a swipe action ran: the rest of this touch is ours, the icon under it gets CANCEL (no tap = no app opened too)
        private var vt: VelocityTracker? = null
        private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
        private val minFling = ViewConfiguration.get(ctx).scaledMinimumFlingVelocity * 4f

        override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
            track(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { x0 = e.x; y0 = e.y; up = false; took = false; onScroll = scrollsAt(this, e.x.toInt(), e.y.toInt()); downOk = !onScroll
                    if (Perf.on()) android.util.Log.v(Perf.TAG, "swipe down at ${e.x.toInt()},${e.y.toInt()} scroll=$onScroll chain=${hitChain(this, e.x.toInt(), e.y.toInt())}") }
                MotionEvent.ACTION_MOVE -> {
                    val dy = e.y - y0; val dx = e.x - x0
                    val free = !pager.dragging && pager.page > 0 && !onLibrary() && drag == null && !editing && this@HomeActivity.overlay == null && popup?.isShowing != true
                    val upA = Gest.action(this@HomeActivity, GestLogic.UP); val dnA = Gest.action(this@HomeActivity, GestLogic.DOWN)   // gestures: HomeGestures.kt
                    val upDrawer = upA == GestLogic.DEFAULT || upA == GestLogic.DRAWER
                    if (free && !onScroll && upDrawer && dy < -slop && abs(dy) > abs(dx) * 1.5f && drawerBegin()) { up = true; from = e.y; drawerAt(0f) }
                    else if (free && !upDrawer && downOk && drawer == null && GestLogic.swipe(dx, dy, slop.toFloat()) == GestLogic.Dir.UP) { downOk = false; took = true; gestRun(GestLogic.UP) }
                    else if (free && downOk && drawer == null && dy > slop * 2 && dy > abs(dx) * 1.5f) {
                        downOk = false; took = true
                        if (dnA != GestLogic.DEFAULT) gestRun(GestLogic.DOWN) else if (x0 > width / 2f) openControl() else expandShade(false)
                    }
                }
            }
            return up || took
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            track(e)
            if (took) { if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) { took = false; vt?.recycle(); vt = null }; return true }
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
    /** Probe only (kgperf): the views under a point, outermost first, with their vertical scroll room (why a swipe went where it did). */
    internal fun hitChain(g: ViewGroup, x: Int, y: Int, path: String = "", out: StringBuilder = StringBuilder()): String {
        for (i in g.childCount - 1 downTo 0) {
            val c = g.getChildAt(i)
            if (c.visibility != View.VISIBLE) continue
            val cx = x + g.scrollX - c.left - c.translationX.toInt(); val cy = y + g.scrollY - c.top - c.translationY.toInt()
            if (cx < 0 || cy < 0 || cx >= c.width || cy >= c.height) continue
            val me = "$path/${c.javaClass.simpleName}" + (if (c.canScrollVertically(1) || c.canScrollVertically(-1)) "[SCROLL ${c.width}x${c.height}]" else "") + (if (c.scaleX != 1f) "[s${c.scaleX}]" else "")
            val n = out.length
            if (c is ViewGroup) hitChain(c, cx, cy, me, out)
            if (out.length == n) out.append(me).append("{").append(cx).append(',').append(cy).append("} ; ")
        }
        return out.toString()
    }

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
    /**
     * Swipe down on the home: the left half opens the notifications, the right half the quick settings (HyperOS / One UI 7
     * split shade). Phones with one shade (Pixel, older ROMs) open it fully expanded on the right: still the toggles.
     */
    internal fun expandShade(settings: Boolean = false) {
        runCatching {
            val sb = getSystemService("statusbar") ?: return
            val c = Class.forName("android.app.StatusBarManager")
            if (settings) runCatching { c.getMethod("expandSettingsPanel").invoke(sb) }.getOrElse { c.getMethod("expandNotificationsPanel").invoke(sb) }
            else c.getMethod("expandNotificationsPanel").invoke(sb)
        }.onFailure { Log.w("KidGuard", "expand shade: $it") }
    }

    /** Horizontal pages (0 = "Hôm nay"); long-press on empty space of a home page opens Tiện ích / Tuỳ chỉnh / Sắp xếp / KidGuard. */
    internal inner class Pager(ctx: Context) : ViewGroup(ctx) {
        var page = 1
        var minPage = 0   // 1 = the "Hôm nay" page is turned off (style.today): it stays child 0 so page numbers don't move
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
            if (!dragging && page > 0 && drag == null && getChildAt(page) is CellLayout) {
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
                MotionEvent.ACTION_DOWN -> { x0 = e.x; y0 = e.y; lastX = e.x; postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong()); Gest.taps(context).down(e.eventTime, e.x, e.y) }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(e.x - x0) > slop || abs(e.y - y0) > slop) removeCallbacks(longPress)
                    if (!dragging && abs(e.x - x0) > slop && abs(e.x - x0) > abs(e.y - y0)) { dragging = true; lastX = e.x; parent.requestDisallowInterceptTouchEvent(true) }
                    if (dragging) {
                        val max = (childCount - 1).coerceAtLeast(0) * width
                        val nx = scrollX + (lastX - e.x).toInt()
                        // rubber band past the first / last page
                        val adj = if (nx < minPage * width || nx > max) scrollX + ((lastX - e.x) / 3).toInt() else nx
                        scrollTo(adj, 0); lastX = e.x
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    removeCallbacks(longPress)
                    // double tap on empty grid space (HomeGestures.kt); a single tap there does nothing
                    val dbl = e.actionMasked == MotionEvent.ACTION_UP && !dragging && getChildAt(page) is CellLayout && Gest.taps(context).up(e.eventTime, e.x, e.y)
                    if (dragging) settle(); release()
                    if (dbl && gestFree() && Gest.action(this@HomeActivity, GestLogic.DBL) != GestLogic.NONE) gestRun(GestLogic.DBL)
                }
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
            page = p.coerceIn(minPage.coerceAtMost(n), n)
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
        fun jump(p: Int) { scroller.abortAnimation();  page = p.coerceIn(minPage.coerceAtMost((childCount - 1).coerceAtLeast(0)), (childCount - 1).coerceAtLeast(0)); scrollTo(page * width, 0); onPage() }

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
        // items never overlap: the page fade applies alpha to each item, no full-page offscreen buffer per frame
        override fun hasOverlappingRendering() = false

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
                            if (id.isNotEmpty()) {
                                // no minus badge any more (owner: not needed): removing = pick + the bar's remove, or the long-press menu
                                togglePick(id)
                                return true
                            }
                        }
                    }
                }
            }
            return false
        }

        private val ringFill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ringLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        private val tick = android.graphics.Path()


        /** Centre of an item's corner badge: the icon's corner for a 1x1, the card's corner for a widget. */
        fun badgeAt(v: View, left: Boolean): Pair<Float, Float> {
            val r = dpf(10f); val lp = v.layoutParams as CellLP
            val icon = if (v is ViewGroup && v.childCount > 0 && (v !is LinearLayout || (lp.w == 1 && lp.h == 1))) v.getChildAt(0) else null
            val cx = if (icon != null) v.left + (if (left) icon.left + r * 0.4f else icon.right - r * 0.4f)
                     else if (left) v.left + r * 1.2f else v.right - r * 1.2f
            val cy = if (icon != null) v.top + icon.top + r * 0.4f else v.top + r * 1.2f
            return cx to cy
        }

        /** Edit mode: a pick circle on each item's top-right (the icon's corner for a 1x1), ticked on the accent when picked (POCO). */
        override fun dispatchDraw(c: Canvas) {
            super.dispatchDraw(c)
            if (!editing) return
            val r = dpf(10f)
            for (i in 0 until childCount) {
                val v = getChildAt(i); val lp = v.layoutParams as CellLP
                if (lp.id.isEmpty() || v.alpha < 1f) continue   // leaving (minus tapped): its badges go with it
                // a 1x1: the icon's corner; a widget: the corner of its card (FitCell centres a wrap-height card in the cells)
                val (cx, cy) = badgeAt(v, false)
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
        fun cellH() = pitchH(width, height)
        /** Top of row 0: the rows are centred when [pitchH] leaves spare height. */
        fun gridTop() = gridTop(height)

        /**
         * Row pitch = column pitch where the screen allows (iOS: the gap between icons is the same across and down, which is
         * what makes a 2x2 widget square and flush with the icons). Never less than an icon + one label line, never more
         * than the height / rows. POCO: 289 x 331 px cells became 289 x ~302 (label line), the rest centred.
         */
        private fun pitchH(W: Int, H: Int): Int {
            val raw = ((H - paddingTop - paddingBottom) / rows).coerceAtLeast(1)
            val cw = (W - 2 * side) / cols
            return if (raw <= cw) raw else maxOf(cw, cellMinH()).coerceAtMost(raw)
        }
        private fun gridTop(H: Int) = paddingTop + ((H - paddingTop - paddingBottom) - rows * pitchH(width, H)).coerceAtLeast(0) / 2

        override fun onMeasure(w: Int, h: Int) {
            val W = MeasureSpec.getSize(w); val H = MeasureSpec.getSize(h)
            val cw = (W - 2 * side) / cols; val ch = pitchH(W, H)
            shareLabels(cw)
            for (i in 0 until childCount) {
                val c = getChildAt(i); val lp = c.layoutParams as CellLP
                val (_, _, ww, wh) = box(cw, ch, lp)
                c.measure(MeasureSpec.makeMeasureSpec(ww.coerceAtLeast(0), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(wh.coerceAtLeast(0), MeasureSpec.EXACTLY))
            }
            setMeasuredDimension(W, H)
        }

        /**
         * An item's box (x, y offsets inside its cells, width, height). Apps / folders: the whole cells minus the gap.
         * Widgets (iOS metrics, 390 pt screen: 26 pt margins, 158 pt small widgets, 22 pt between them = 5.6 % of the width):
         * the outer edges sit on the outer icons' edges, the top on the icon top, and the gap between two widgets is 5.6 %
         * of the width both across and down, whatever the icon size. Was the gap between icon columns (cw - icon): 31 dp
         * (8.2 %) at the default icon size, which read as loose next to iOS.
         */
        private fun box(cw: Int, ch: Int, lp: CellLP): IntArray {
            // icons: the gap across only; down, the label's own padding and the next icon's 4 dp top pad space the rows (iOS)
            if (!lp.widget) return intArrayOf(0, 0, cw * lp.w - gap, ch * lp.h)
            val icon = iconPx().coerceAtMost(minOf(cw, ch) - gap)
            val ix = ((cw - gap - icon) / 2).coerceAtLeast(0)
            val top = dp(5).coerceAtMost((ch - gap - icon).coerceAtLeast(0))        // appIcon: 4 dp box padding + 1 dp frame
            val g = ((cols * cw + 2 * side) * 22f / 390f).coerceAtLeast((gap + top).toFloat())
            val span = cols * cw - (cw - icon)                                      // first icon's left edge to the last icon's right edge
            val pw = (span + g) / cols                                              // widget column pitch: cols columns, cols-1 gaps of g
            val x = ix + Math.round(lp.x * (pw - cw))
            val w = Math.round(lp.w * pw - g)
            // down: the same pitch as across (pw), so a 2x2 is square like iOS; rows taller than that (very big icons) leave the
            // extra below the widget rather than stretch it
            val h = Math.round(lp.h * minOf(ch.toFloat(), pw.toFloat()) - g).coerceAtMost(lp.h * ch - gap - top)
            // and placed on that pitch down too (as x across): gaps between widgets equal both ways (POCO: 69 across, was 85 down)
            val y = top + Math.round(lp.y * (minOf(ch.toFloat(), pw.toFloat()) - ch))
            return intArrayOf(x, y, w, h)
        }

        /**
         * One UI: a long name may spill into the free space beside it when the neighbours' names are short (or the cell is
         * empty). Each 1x1 icon's label borrows from both sides, evenly when it can, else more from the roomier side. Decided once per measure; drawing outside the cell costs nothing per frame.
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
            // +2: margins are whole px; a label 1 px short of its text ellipsizes ("Khoá màn hì…" needed 249, got 248)
            fun need(l: IconLabel) = kotlin.math.ceil(l.paint.measureText(l.text.toString())) + l.paddingLeft + l.paddingRight + 2f
            // what a cell can lend to one side: half its free width (all of it is free when empty, nothing for widgets)
            fun long(x: Int, y: Int) = x in 0 until cols && at[y * cols + x]?.let { need(it) > inner } == true
            // [dir]: the side the cell is on, seen from the borrower (-1 left, +1 right)
            fun spare(x: Int, y: Int, dir: Int): Float = when {
                x < 0 || x >= cols -> (side - gap / 2f - kit.dpf(10f)).coerceAtLeast(0f)   // page margin, never to the screen edge
                // an empty cell: all of it when nobody on its far side wants it too, else half
                (y * cols + x) !in busy -> if (long(x + dir, y)) cw / 2f else cw - kit.dpf(6f)
                else -> at[y * cols + x]?.let { ((inner - need(it)) / 2f).coerceAtLeast(0f) } ?: 0f
            }
            for ((k, l) in at) {
                val x = k % cols; val y = k / cols
                val over = need(l) - inner
                val lp = l.layoutParams as? LinearLayout.LayoutParams ?: continue
                if (over <= 0f) { lp.leftMargin = 0; lp.rightMargin = 0; continue }
                // centred when both sides can lend half; else the roomier side lends the rest (the name sits a few px off
                // centre). Equal-only borrowing gave nothing whenever one side was a long name or the page edge:
                // "Mạng xã …" between "MoMo" and "Thư mục" on the owner's POCO
                val roomL = spare(x - 1, y, -1) + gap / 2f; val roomR = spare(x + 1, y, 1) + gap / 2f
                var el = minOf(over / 2f, roomL); var er = minOf(over / 2f, roomR)
                val rest = over - el - er
                if (rest > 0f) { val addL = minOf(rest, roomL - el); el += addL; er = minOf(roomR, er + rest - addL) }
                lp.leftMargin = -kotlin.math.ceil(el).toInt(); lp.rightMargin = -kotlin.math.ceil(er).toInt()
            }
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val cw = (r - l - 2 * side) / cols; val ch = pitchH(r - l, b - t); val y0 = gridTop(b - t)
            for (i in 0 until childCount) {
                val c = getChildAt(i); val lp = c.layoutParams as CellLP
                val (ix, top) = box(cw, ch, lp)
                val x = side + lp.x * cw + gap / 2 + ix; val y = y0 + lp.y * ch + gap / 2 + top
                c.layout(x, y, x + c.measuredWidth, y + c.measuredHeight)
            }
        }

        // the drop cell glides to the next cell and fades in / out (it popped from cell to cell)
        private val hlR = android.graphics.RectF(); private var hlA = 0f
        private var lastDraw = 0L   // frame-time steps: the same speed at 60 and 120 Hz
        // the "drop to make a folder" plate behind the target icon (iOS), grows in / fades out
        private var plX = 0f; private var plY = 0f; private var plS = 0f; private var plOn = false; private var plA = 0f
        private val plPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        fun plate(cx: Float, cy: Float, size: Float) { plX = cx; plY = cy; plS = size; plOn = true; invalidate() }
        fun plateOff() { if (plOn) { plOn = false; invalidate() } }

        override fun onDraw(c: Canvas) {
            val cw = cellW().toFloat(); val ch = cellH().toFloat()
            var more = false
            val now = drawingTime; val st = if (lastDraw == 0L) 1f else ((now - lastDraw) / 16.7f).coerceIn(0.25f, 3f); lastDraw = now
            if (plOn || plA > 0f) {
                plA = if (plOn) minOf(1f, plA + 0.16f * st) else maxOf(0f, plA - 0.2f * st); more = more || (plOn && plA < 1f) || (!plOn && plA > 0f)
                val e = 1 - (1 - plA) * (1 - plA)                 // ease-out grow
                val half = plS / 2 * (0.75f + 0.25f * e)
                plPaint.color = Color.argb((95 * plA).toInt(), 0, 0, 0)
                c.drawRoundRect(plX - half, plY - half, plX + half, plY + half, half * 0.5f, half * 0.5f, plPaint)
            }
            val tg = hl
            if (tg != null) {
                val (x, y, w, h) = tg
                val l = side + x * cw + gap / 2f; val t = gridTop() + y * ch + gap / 2f; val r = l + w * cw - gap; val b = t + h * ch - gap
                if (hlA == 0f) hlR.set(l, t, r, b)
                else { val k = 1f - Math.pow(0.65, st.toDouble()).toFloat(); hlR.set(hlR.left + (l - hlR.left) * k, hlR.top + (t - hlR.top) * k, hlR.right + (r - hlR.right) * k, hlR.bottom + (b - hlR.bottom) * k) }
                hlA = minOf(1f, hlA + 0.2f * st)
                more = more || hlA < 1f || kotlin.math.abs(hlR.left - l) > 0.5f || kotlin.math.abs(hlR.top - t) > 0.5f || kotlin.math.abs(hlR.right - r) > 0.5f
            } else if (hlA > 0f) { hlA = maxOf(0f, hlA - 0.2f * st); more = true }
            if (hlA > 0f) {
                // the drop cells: the card colour when it fits, the danger colour when it doesn't
                val col = if (hlOk) pal.card else Color.argb(150, Color.red(pal.danger), Color.green(pal.danger), Color.blue(pal.danger))
                hlPaint.color = col; hlPaint.alpha = (Color.alpha(col) * hlA).toInt()
                kit.drawRound(c, hlR.left, hlR.top, hlR.right, hlR.bottom, hlPaint)
            }
            if (more) postInvalidateOnAnimation() else lastDraw = 0L
            if (!grid) return
            // One soft panel around the page (One UI's edit page), no per-cell outlines: those read as clutter. The cell
            // the item will land in lights up while dragging (hl below), like Pixel / One UI.
            panel.color = Color.argb(28, Color.red(gridColor), Color.green(gridColor), Color.blue(gridColor))
            edge.color = Color.argb(70, Color.red(gridColor), Color.green(gridColor), Color.blue(gridColor))
            val l0 = side.toFloat() - gap / 2f; val t0 = gridTop() - gap / 2f
            val r0 = side + cols * cw + gap / 2f; val b0 = gridTop() + rows * ch + gap / 2f
            kit.drawRound(c, l0, t0, r0, b0, panel, kit.dpf(22f)); kit.drawRound(c, l0, t0, r0, b0, edge, kit.dpf(22f))
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
