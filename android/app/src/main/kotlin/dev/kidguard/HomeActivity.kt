package dev.kidguard

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
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.LocationManager
import android.net.Uri
import android.os.Build
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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * v0.9.9 kid launcher (protocol "v0.9.9: kid launcher"). Turned on by the `launcher` rule (LauncherSafe): pages of a
 * R x C grid, a dock with the phone fixed in slot 1, an A–Z drawer (swipe up). Every render filters through the
 * Guard's decide() result: blocked / new / hidden apps are never shown, paused ones are greyed with a clock badge.
 * Pure parts: LauncherLogic, HomeLayout, HomeStyle (contrast). Text on the wallpaper adapts (HomeStyle.ink).
 */
class HomeActivity : Activity() {
    companion object { @Volatile var alive = 0 }

    private class AppE(val pkg: String, val cls: String, val label: String)

    private val main = Handler(Looper.getMainLooper())
    private lateinit var u: Ui
    private lateinit var pal: Palette
    private lateinit var preset: HomeStyle.Preset
    private lateinit var acts: KidActions
    private var dark = false
    private lateinit var root: FrameLayout
    private lateinit var wallView: ImageView
    private var wall: Bitmap? = null
    private var wallKey = ""
    private lateinit var content: SwipeUp
    private lateinit var bannerBox: FrameLayout
    private lateinit var pager: Pager
    private lateinit var dots: LinearLayout
    private lateinit var hint: ImageView
    private lateinit var dock: LinearLayout
    private var drawer: Drawer? = null
    private var popup: PopupWindow? = null
    private var apps: Map<String, AppE>? = null
    private var model: HomeModel? = null
    private var key = ""
    private var lastTap = 0L
    private val inkViews = ArrayList<View>()      // TextViews / dot rows that sit right on the wallpaper
    private val inkCache = HashMap<String, Ink>()
    private val listener: () -> Unit = { render(false) }
    private var timeRx: BroadcastReceiver? = null
    private var pkgRx: BroadcastReceiver? = null

    private fun dp(v: Int) = u.dp(v)
    private fun dpf(v: Float) = v * resources.displayMetrics.density
    private fun col(s: String) = Color.parseColor(s)

    override fun getResources(): android.content.res.Resources = Lang.res(baseContext) ?: super.getResources()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        LauncherSafe.watch(this)
        alive++
        if (LauncherSafe.takeReset(this)) { HomeFile.reset(this); IconCache.clear() }
        u = Ui(this)
        dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        acts = KidActions(this, u) { key = ""; render(true) }
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = Color.TRANSPARENT; window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 29) { window.isNavigationBarContrastEnforced = false; window.isStatusBarContrastEnforced = false }
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        root = FrameLayout(this)
        wallView = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_XY; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        root.addView(wallView, FrameLayout.LayoutParams(-1, -1))
        content = SwipeUp(this).apply { orientation = LinearLayout.VERTICAL }
        bannerBox = FrameLayout(this)
        pager = Pager(this)
        dots = LinearLayout(this).apply { gravity = Gravity.CENTER; orientation = LinearLayout.HORIZONTAL }
        hint = ImageView(this).apply {
            contentDescription = getString(R.string.hm_all_apps); setPadding(dp(10), dp(2), dp(10), dp(2))
            setOnClickListener { openDrawer() }
        }
        dock = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        content.addView(bannerBox, LinearLayout.LayoutParams(-1, -2))
        content.addView(pager, LinearLayout.LayoutParams(-1, 0, 1f))
        content.addView(dots, LinearLayout.LayoutParams(-1, dp(18)))
        content.addView(hint, LinearLayout.LayoutParams(-2, dp(22)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        content.addView(dock, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(12), dp(4), dp(12), dp(10)) })
        root.addView(content, FrameLayout.LayoutParams(-1, -1))
        root.setOnApplyWindowInsetsListener { _, ins ->
            @Suppress("DEPRECATION") content.setPadding(ins.systemWindowInsetLeft, ins.systemWindowInsetTop, ins.systemWindowInsetRight, ins.systemWindowInsetBottom)
            drawer?.setPadding(0, ins.systemWindowInsetTop, 0, ins.systemWindowInsetBottom)
            ins
        }
        root.addOnLayoutChangeListener { _, l, t, r, btm, ol, ot, or_, ob -> if (r - l != or_ - ol || btm - t != ob - ot) { ensureWall(); main.post { inkPass() } } }
        setContentView(root)
        render(true)
    }

    override fun onDestroy() { alive--; super.onDestroy() }

    override fun onResume() {
        super.onResume()
        Guard.listeners.add(listener)
        timeRx = object : BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) { render(false) } }
        registerReceiver(timeRx, IntentFilter().apply { addAction(Intent.ACTION_TIME_TICK); addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_TIMEZONE_CHANGED) })
        if (pkgRx == null) {
            pkgRx = object : BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) { apps = null; Policy.invalidate(); key = ""; render(true) } }
            registerReceiver(pkgRx, IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED); addAction(Intent.ACTION_PACKAGE_CHANGED)
                addAction(Intent.ACTION_PACKAGES_SUSPENDED); addAction(Intent.ACTION_PACKAGES_UNSUSPENDED); addDataScheme("package")
            })
        }
        Guard.poke(this)
        render(true)
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

    /** HOME pressed while we are in front: close the drawer, else back to page 1. */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        popup?.dismiss()
        if (drawer != null) closeDrawer() else pager.snapTo(0)
    }

    @Deprecated("still the way back works on API 26-32")
    override fun onBackPressed() { if (drawer != null) closeDrawer() }

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
        val accent = m.style.accent.ifEmpty { if (dark) preset.accentDark else preset.accentLight }
        pal = HomeStyle.palette(accent, dark)
        val block = QuickLogic.phoneBlock(dv.want)
        val banner = LauncherLogic.banner(Policy.isOwner(this), Guard.running != null, Usage.granted(this), locationOn(), Acc.isOn(this))
        val k = listOf(m.toJson().toString(), show.toString(), dv.want.toString(), block, banner, dv.minute, dv.leftMs / 60_000, s.everPaired,
            s.sos().length(), s.lastCheckin("arrived"), s.lastCheckin("pickup"), s.requests().length(), r.blockNote, r.launcher, dark).joinToString("|")
        if (!force && k == key) return
        if (pager.dragging || popup?.isShowing == true) { main.postDelayed({ render(false) }, 1500); return }   // never rebuild under a finger
        key = k
        ensureWall()
        buildBanner(banner)
        buildPages(m, all, show, dv, r, s, block)
        buildDock(m, all, show, dv, r)
        hint.setImageDrawable(Ic.get(this, R.drawable.li_chevron_up, Color.WHITE, dp(18)))
        inkViews += hint
        drawer?.refill()
        main.post { inkPass() }
    }

    private fun ensureWall() {
        val w = root.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val h = root.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        val p = if (::preset.isInitialized) preset else HomeStyle.preset(model?.style?.preset ?: "teen")
        val id = model?.style?.wall?.takeIf { it.isNotEmpty() } ?: if (dark) p.wallDark else p.wallLight
        val k = "$id@${w}x$h"
        if (k == wallKey) return
        wallKey = k; inkCache.clear()
        val old = wall
        wall = Wallpaper.draw(id, w, h)
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
            setOnClickListener { openMain() }
        })
        bannerBox.addView(row, FrameLayout.LayoutParams(-1, -2).apply { setMargins(dp(14), dp(6), dp(14), dp(2)) })
    }

    private fun buildPages(m: HomeModel, all: Map<String, AppE>, show: Map<String, LauncherLogic.Show>, dv: View0, r: Rules, s: Store, block: String?) {
        inkViews.clear()
        val keep = pager.page
        pager.removeAllViews()
        for (p in m.pages) {
            val cell = CellLayout(this, m.cols, m.rows)
            for (it in p) {
                val v: View = when (it.kind) {
                    "widget" -> when (it.type) {
                        "clock" -> clockWidget(r, dv, it.w)
                        "time" -> timeWidget(r, dv, block, s, it.w)
                        "quick" -> quickWidget(s, it.w)
                        else -> null
                    }
                    "app" -> all[it.pkg]?.takeIf { a -> show[a.pkg] != LauncherLogic.Show.HIDE }?.let { a -> appIcon(a, show[a.pkg] == LauncherLogic.Show.PAUSED, dv, r, onHome = true, drawer = false) }
                    else -> null   // folders: part 2
                } ?: continue
                cell.addView(v, CellLayout.LP(it.x, it.y, it.w, it.h))
            }
            pager.addView(cell)
        }
        pager.page = keep.coerceIn(0, (m.pages.size - 1).coerceAtLeast(0))
        pager.onPage = { buildDots(m.pages.size) }
        buildDots(m.pages.size)
        pager.post { pager.scrollTo(pager.page * pager.width, 0) }
    }

    private fun buildDots(n: Int) {
        dots.removeAllViews()
        if (n < 2) { dots.visibility = View.INVISIBLE; return }
        dots.visibility = View.VISIBLE
        dots.contentDescription = getString(R.string.hm_page, pager.page + 1, n)
        val ink = inkCache["dots"]
        val c = ink?.text ?: Color.WHITE
        for (i in 0 until n) dots.addView(View(this).apply {
            // active = filled, others = a ring of the same ink: both keep full contrast (no faded dot)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; if (i == pager.page) setColor(c) else setStroke(dp(1).coerceAtLeast(2), c) }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(7), dp(7)).apply { setMargins(dp(4), 0, dp(4), 0) })
        if (dots !in inkViews) inkViews += dots
        ink?.let { applyScrimTo(dots, it) }
    }

    // ---- app icon ----

    private fun iconPx(): Int = dp((preset.iconDp * (0.8f + 0.1f * ((model?.style?.icon ?: 3) - 1))).toInt())

    private fun appIcon(a: AppE, paused: Boolean, dv: View0, r: Rules, onHome: Boolean, drawer: Boolean): View {
        val px = if (drawer) dp(52) else iconPx()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP; setPadding(0, dp(4), 0, 0) }
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
        val showLabel = drawer || (model?.style?.labels ?: true)
        if (showLabel) {
            val lbl = text(a.label, if (preset.id == "be" && !drawer) 14f else 12f, if (drawer) pal.text else Color.WHITE).apply {
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; gravity = Gravity.CENTER; setPadding(dp(4), dp(1), dp(4), dp(1))
            }
            box.addView(lbl, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4); gravity = Gravity.CENTER_HORIZONTAL })
            if (!drawer) inkViews += lbl
        }
        if (drawer) {
            val sub = when {
                paused -> reopenShort(r, reason ?: "", a.pkg, dv)
                else -> LauncherLogic.appLeftMs(r, dv.usage, a.pkg).takeIf { it in 0 until 60 * 60_000L }?.let { getString(R.string.hm_min_left, ((it + 59_999) / 60_000).toInt()) }
            }
            if (sub != null) box.addView(text(sub, 11f, if (paused) pal.warnText else pal.sub).apply { maxLines = 1; gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(1); gravity = Gravity.CENTER_HORIZONTAL })
        }
        box.contentDescription = if (paused) getString(R.string.hm_paused_desc, a.label, BasicLogic.reasonText(reason ?: "", Store.of(this).tr)) else a.label
        box.isClickable = true; box.isLongClickable = true
        box.background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x33ffffff), null, fill(Color.WHITE, 16f))
        box.setOnClickListener { if (debounce()) { if (paused) pausedSheet(a, reason ?: "", dv, r) else launch(a, box) } }
        box.setOnLongClickListener { appMenu(a, box, onHome); true }
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
            .onFailure { android.widget.Toast.makeText(this, R.string.hm_open_fail, android.widget.Toast.LENGTH_SHORT).show() }
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
        if (HomeLogic.canAsk(s.everPaired, dv.leftMs, reason)) body.addView(pill(getString(R.string.main_more), R.drawable.li_timer, true) { sh.dismiss(); main.post { acts.askMore() } },
            LinearLayout.LayoutParams(-1, dp(52)).apply { setMargins(dp(12), dp(16), dp(12), 0) })
        body.addView(pill(getString(R.string.hm_later), null, false) { sh.dismiss() }, LinearLayout.LayoutParams(-1, dp(52)).apply { setMargins(dp(12), dp(8), dp(12), 0) })
        sh.show()
    }

    /** A full-width button in the launcher's accent (solid: white text on the accent; else text on the sheet). */
    private fun pill(t: String, icon: Int?, primary: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = t; gravity = Gravity.CENTER; setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        val fg = if (primary) pal.onSolid else pal.text
        setTextColor(fg)
        background = if (primary) fill(pal.solid, 16f) else GradientDrawable().apply { setColor(pal.sheet); cornerRadius = dpf(16f); setStroke(dp(1), pal.stroke) }
        if (icon != null) Ic.lead(this, icon, fg)
        isClickable = true; setOnClickListener { onClick() }
    }

    // ---- menus ----

    private class Row(val icon: Int, val label: String, val enabled: Boolean, val run: () -> Unit)

    private fun menu(rows: List<Row>, anchor: View?, x: Int = 0, y: Int = 0) {
        popup?.dismiss()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = fill(pal.sheet, 16f); setPadding(0, dp(6), 0, dp(6)); elevation = dpf(8f) }
        val pw = PopupWindow(box, dp(240), ViewGroup.LayoutParams.WRAP_CONTENT, true)
        for (r in rows) box.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12))
            val c = if (r.enabled) pal.text else pal.sub
            addView(ImageView(context).apply { setImageDrawable(Ic.get(context, r.icon, if (r.enabled) pal.onTint else pal.sub, dp(18))); background = fill(if (r.enabled) pal.tint else pal.bg); setPadding(dp(6), dp(6), dp(6), dp(6)) },
                LinearLayout.LayoutParams(dp(30), dp(30)))
            addView(text(r.label, 15f, c).apply { setPadding(dp(12), 0, 0, 0) })
            isEnabled = r.enabled; isClickable = r.enabled
            if (r.enabled) setOnClickListener { pw.dismiss(); r.run() }
            contentDescription = r.label
        }, LinearLayout.LayoutParams(-1, -2))
        pw.elevation = dpf(8f)
        pw.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        pw.setOnDismissListener { if (popup === pw) popup = null }
        popup = pw
        if (anchor != null) pw.showAsDropDown(anchor, 0, -dp(6)) else pw.showAtLocation(root, Gravity.TOP or Gravity.START, x.coerceAtMost(root.width - dp(250)).coerceAtLeast(dp(8)), y)
    }

    private fun appMenu(a: AppE, v: View, onHome: Boolean) {
        val m = model ?: return
        val placed = a.pkg in m.onHome()
        menu(listOfNotNull(
            Row(R.drawable.li_pin, getString(if (placed) R.string.hm_pinned else R.string.hm_pin), !placed) { update(HomeLayout.pin(m, a.pkg)); if (drawer != null) closeDrawer() },
            Row(R.drawable.li_circle_minus, getString(R.string.hm_unpin), true) { update(HomeLayout.remove(m, a.pkg)) }.takeIf { placed || onHome },
        ), v)
    }

    private fun emptyMenu(x: Int, y: Int) = menu(listOf(
        Row(R.drawable.li_shield, getString(R.string.hm_open_kg), true) { openMain() },
        Row(R.drawable.li_settings_2, getString(R.string.hm_customize), true) {
            Modal(this, u).show(R.drawable.li_settings_2, Modal.Tone.INFO, getString(R.string.hm_soon_title), getString(R.string.hm_soon_body), ok = getString(R.string.close))
        },
    ), null, x, y)

    private fun update(m: HomeModel) { HomeFile.save(this, m); model = m; key = ""; render(true) }

    private fun openMain() = runCatching { startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }

    // ---- widgets ----

    private fun clockWidget(r: Rules, dv: View0, w: Int): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL or Gravity.START; setPadding(dp(14), dp(4), dp(8), dp(4)) }
        val big = when (preset.clock) { "big" -> 60f; "thin" -> 46f; else -> 54f } * if (w <= 3 && preset.cols == 5) 0.85f else 1f
        val t = text(HomeLogic.clock(dv.minute), big, Color.WHITE).apply {
            typeface = Typeface.create(if (preset.clock == "thin") "sans-serif-light" else "sans-serif", Typeface.NORMAL); setPadding(dp(6), 0, dp(6), 0)
        }
        box.addView(t, LinearLayout.LayoutParams(-2, -2))
        val date = SimpleDateFormat("EEEE, d MMMM", resources.configuration.locales[0]).format(Date(dv.now)).replaceFirstChar { it.titlecase() }
        val d = text(date, 15f, Color.WHITE, true).apply { setPadding(dp(6), dp(2), dp(6), dp(2)) }
        box.addView(d, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(2) })
        inkViews += t; inkViews += d
        LauncherLogic.nextEvent(r, dv.minute, dv.dow)?.let { (min, name) ->
            val n = text(if (name.isEmpty()) getString(R.string.hm_next_bed, HomeLogic.clock(min)) else getString(R.string.hm_next, name, HomeLogic.clock(min)), 13f, Color.WHITE)
                .apply { setPadding(dp(6), dp(2), dp(6), dp(2)); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
            box.addView(n, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(2) })
            inkViews += n
        }
        box.contentDescription = "${t.text}. ${d.text}"
        return box
    }

    private fun dur(ms: Long) = HomeLogic.hm(ms).let { (h, m) -> if (h == 0) getString(R.string.home_m, m) else if (m == 0) getString(R.string.home_h, h) else getString(R.string.home_hm, h, m) }

    private fun timeWidget(r: Rules, dv: View0, block: String?, s: Store, w: Int): View {
        val small = w <= 2
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(10), dp(16), dp(10)) }
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
                box.addView(text(dur(dv.leftMs), if (small) 20f else 26f, if (low) pal.warnText else pal.accentText, true).apply { setPadding(0, dp(4), 0, dp(6)); maxLines = 1 })
                val limitMs = (baseLimitMin(r, dv.today) + if (r.bonusDay == dv.today) r.bonusMin else 0) * 60_000L
                val used = dv.usage.filterKeys { it in dv.apps && it !in dv.prot && it !in r.allow }.values.sum()
                val bar = FrameLayout(this).apply { background = fill(pal.stroke, 3f) }
                val f = if (limitMs > 0) (used.toFloat() / limitMs).coerceIn(0.02f, 1f) else 0f
                bar.addView(View(this).apply { background = fill(if (low) pal.warn else pal.solid, 3f) }, FrameLayout.LayoutParams(-1, -1))
                bar.addOnLayoutChangeListener { v, l, _, rr, _, _, _, _, _ -> (v as FrameLayout).getChildAt(0).let { c -> val want = ((rr - l) * f).toInt(); if (c.layoutParams.width != want) { c.layoutParams.width = want; c.post { c.requestLayout() } } } }
                box.addView(bar, LinearLayout.LayoutParams(-1, dp(6)))
                if (!small) box.addView(text("${getString(R.string.home_used, dur(used))} · ${getString(R.string.home_limit, dur(limitMs))}", 12f, pal.sub).apply { setPadding(0, dp(6), 0, 0) })
            }
        }
        box.isClickable = canAsk
        if (canAsk) box.setOnClickListener { if (debounce()) acts.askMore() }
        return box
    }

    private fun quickWidget(s: Store, w: Int): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; background = card(); setPadding(dp(6), dp(8), dp(6), dp(8)) }
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
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(2), dp(2), dp(2), dp(2))
                addView(ImageView(context).apply {
                    setImageDrawable(Ic.get(context, icon, fills.second, dp(22))); background = fill(fills.first)
                    val p = dp((circle - 22) / 2); setPadding(p, p, p, p)
                }, LinearLayout.LayoutParams(dp(circle), dp(circle)))
                addView(text(lab.first, 12f, lab.second, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(5), 0, 0); gravity = Gravity.CENTER })
                contentDescription = lab.first
                isClickable = true
                background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x22000000), null, fill(Color.WHITE, 14f))
                setOnClickListener {
                    if (!debounce()) return@setOnClickListener
                    if (!paired) Modal(this@HomeActivity, u).show(R.drawable.li_link, Modal.Tone.INFO, getString(R.string.hm_pair_title), getString(R.string.hm_pair_body),
                        ok = getString(R.string.hm_open_kg), cancel = getString(R.string.cancel)) { openMain(); true }
                    else run()
                }
            }
            row.addView(b, LinearLayout.LayoutParams(0, -2, 1f))
        }
        return row
    }

    // ---- dock ----

    private fun buildDock(m: HomeModel, all: Map<String, AppE>, show: Map<String, LauncherLogic.Show>, dv: View0, r: Rules) {
        dock.removeAllViews()
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
                box.isClickable = true; box.isLongClickable = true
                box.setOnClickListener {
                    if (!debounce()) return@setOnClickListener
                    runCatching { startActivity(Intent(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        .onFailure { android.widget.Toast.makeText(this, R.string.hm_no_dialer, android.widget.Toast.LENGTH_SHORT).show() }
                }
                box.setOnLongClickListener { sosMenu(box); true }
                box
            } else all[slot]?.takeIf { show[it.pkg] != LauncherLogic.Show.HIDE }?.let { a ->
                val paused = show[a.pkg] == LauncherLogic.Show.PAUSED
                appIcon(a, paused, dv, r, onHome = true, drawer = false).also { v ->
                    if (!preset.dockLabels) (v as LinearLayout).getChildAt(1)?.let { (v as LinearLayout).removeView(it); inkViews.remove(it) }
                    else if (m.style.dockBg) (v as LinearLayout).getChildAt(1)?.let { inkViews.remove(it); (it as TextView).setTextColor(pal.text) }
                }
            }
            if (v != null) cell.addView(v, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            dock.addView(cell, LinearLayout.LayoutParams(0, -2, 1f))
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
            if (!v.isShown || v.width == 0) return null
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
        // dots: redraw with their ink, status bar icons follow the ink at the top
        model?.let { buildDots(it.pages.size) }
        val top = HomeStyle.ink(Wallpaper.sample(bmp, Rect(0, 0, w, dp(28))))
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            (if (top.darkText) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR else 0) or
            (if (Build.VERSION.SDK_INT >= 26 && HomeStyle.ink(Wallpaper.sample(bmp, Rect(0, bmp.height - dp(48), w, bmp.height))).darkText) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0)
    }

    private fun applyScrimTo(v: View, ink: Ink) {
        v.background = if (Contrast.a(ink.scrim) == 0) null else fill(ink.scrim, 10f)
    }

    // ---- drawer ----

    private fun openDrawer() {
        if (drawer != null) return
        popup?.dismiss()
        val d = Drawer(this)
        drawer = d
        root.addView(d, FrameLayout.LayoutParams(-1, -1))
        root.requestApplyInsets()
        d.translationY = root.height * 0.15f; d.alpha = 0f
        d.animate().translationY(0f).alpha(1f).setDuration(180).start()
    }

    private fun closeDrawer() {
        val d = drawer ?: return
        drawer = null
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(d.windowToken, 0)
        d.animate().translationY(root.height * 0.1f).alpha(0f).setDuration(150).withEndAction { root.removeView(d) }.start()
    }

    private inner class Drawer(ctx: Context) : LinearLayout(ctx) {
        val search = EditText(ctx)
        val list = LinearLayout(ctx).apply { orientation = VERTICAL }
        val rail = LinearLayout(ctx).apply { orientation = VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        val heads = LinkedHashMap<String, View>()
        val scroll = object : ScrollView(ctx) {
            var y0 = 0f; var pulling = false
            override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
                if (e.actionMasked == MotionEvent.ACTION_DOWN) { y0 = e.rawY; pulling = false }
                if (e.actionMasked == MotionEvent.ACTION_MOVE && scrollY == 0 && e.rawY - y0 > dp(24)) pulling = true
                return pulling || super.onInterceptTouchEvent(e)
            }
            override fun onTouchEvent(e: MotionEvent): Boolean {
                if (pulling) {
                    if (e.actionMasked == MotionEvent.ACTION_UP) { if (e.rawY - y0 > dp(90)) closeDrawer(); pulling = false }
                    if (e.actionMasked == MotionEvent.ACTION_CANCEL) pulling = false
                    return true
                }
                return super.onTouchEvent(e)
            }
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
                setOnClickListener { if (search.text.isNotEmpty()) search.setText("") else closeDrawer() }
            }, LayoutParams(dp(44), dp(44)))
            addView(bar, LayoutParams(-1, -2).apply { setMargins(dp(14), dp(12), dp(14), dp(8)) })
            val body = LinearLayout(ctx).apply { orientation = HORIZONTAL }
            scroll.isVerticalScrollBarEnabled = false
            scroll.addView(list)
            body.addView(scroll, LayoutParams(0, -1, 1f))
            body.addView(rail, LayoutParams(dp(24), -1))
            addView(body, LayoutParams(-1, 0, 1f))
            rail.setOnTouchListener { v, e ->
                if (rail.childCount == 0) return@setOnTouchListener false
                val i = (e.y / v.height * rail.childCount).toInt().coerceIn(0, rail.childCount - 1)
                val letter = (rail.getChildAt(i) as TextView).text.toString()
                heads[letter]?.let { scroll.scrollTo(0, it.top) }
                if (e.actionMasked == MotionEvent.ACTION_DOWN) v.performClick()
                true
            }
            refill()
        }

        var firstHit: View? = null

        fun refill() {
            val s = Store.of(context); val r = s.rules
            val all = launcherApps(); val dv = decision(r, s)
            val q = search.text.toString()
            val shown = all.values.filter { LauncherLogic.show(it.pkg, dv.want, r, packageName) != LauncherLogic.Show.HIDE && LauncherLogic.matches(it.label, q) }
                .sortedBy { LauncherLogic.sortKey(it.label) }
            list.removeAllViews(); rail.removeAllViews(); heads.clear(); firstHit = null
            if (shown.isEmpty()) {
                list.addView(text(getString(R.string.hm_no_result, q), 15f, pal.sub).apply { gravity = Gravity.CENTER; setPadding(dp(16), dp(40), dp(16), dp(16)) }, LayoutParams(-1, -2))
                return
            }
            val cols = 4
            var row: LinearLayout? = null; var n = 0; var cur = ""
            for (a in shown) {
                val l = LauncherLogic.letter(a.label)
                if (l != cur && q.isBlank()) {
                    row?.let { rw -> while (rw.childCount < cols) rw.addView(View(context), LayoutParams(0, 1, 1f)) }
                    cur = l; n = 0
                    val h = text(l, 13f, pal.accentText, true).apply { setPadding(dp(20), dp(14), 0, dp(4)) }
                    list.addView(h, LayoutParams(-1, -2)); heads[l] = h
                    rail.addView(text(l, 11f, pal.sub, true).apply { gravity = Gravity.CENTER }, LayoutParams(-1, 0, 1f))
                }
                if (n % cols == 0) { row = LinearLayout(context).apply { orientation = HORIZONTAL; setPadding(dp(8), dp(4), dp(8), dp(4)) }; list.addView(row, LayoutParams(-1, -2)) }
                val paused = dv.want[a.pkg] != null
                val v = appIcon(a, paused, dv, r, onHome = a.pkg in (model?.onHome() ?: emptySet()), drawer = true)
                if (firstHit == null) firstHit = v
                row!!.addView(v, LayoutParams(0, -2, 1f)); n++
            }
            // pad the last row so cells keep their width
            row?.let { rw -> while (rw.childCount < cols) rw.addView(View(context), LayoutParams(0, 1, 1f)) }
            list.addView(View(context), LayoutParams(-1, dp(24)))
        }
    }

    // ---- custom views ----

    /** Swipe up anywhere on the home screen (48 dp, or a fling) opens the drawer. */
    private inner class SwipeUp(ctx: Context) : LinearLayout(ctx) {
        private var x0 = 0f; private var y0 = 0f; private var up = false
        private var vt: VelocityTracker? = null
        private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
        private val minFling = ViewConfiguration.get(ctx).scaledMinimumFlingVelocity * 4

        override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
            track(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { x0 = e.x; y0 = e.y; up = false }
                MotionEvent.ACTION_MOVE -> { val dy = e.y - y0; val dx = e.x - x0; if (!pager.dragging && dy < -slop && abs(dy) > abs(dx) * 1.5f) up = true }
            }
            return up
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            track(e)
            if (e.actionMasked == MotionEvent.ACTION_UP && up) {
                vt?.computeCurrentVelocity(1000)
                val vy = vt?.yVelocity ?: 0f
                if (y0 - e.y > dp(48) || vy < -minFling) openDrawer()
                up = false
            }
            if (e.actionMasked == MotionEvent.ACTION_CANCEL) up = false
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) { vt?.recycle(); vt = null }
            return true
        }

        private fun track(e: MotionEvent) {
            if (e.actionMasked == MotionEvent.ACTION_DOWN) { vt?.recycle(); vt = VelocityTracker.obtain() }
            vt?.addMovement(e)
        }
    }

    /** Horizontal pages; long-press on empty space opens the KidGuard / Tuỳ chỉnh menu. */
    private inner class Pager(ctx: Context) : ViewGroup(ctx) {
        var page = 0
        var dragging = false
        var onPage: () -> Unit = {}
        private val scroller = OverScroller(ctx)
        private var vt: VelocityTracker? = null
        private var x0 = 0f; private var y0 = 0f; private var lastX = 0f
        private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
        private val minFling = ViewConfiguration.get(ctx).scaledMinimumFlingVelocity * 3
        private var longFired = false
        private val longPress = Runnable { if (!dragging) { longFired = true; performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); val l = IntArray(2); getLocationInWindow(l); emptyMenu((l[0] + x0).toInt(), (l[1] + y0).toInt()) } }

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
                MotionEvent.ACTION_DOWN -> { x0 = e.x; y0 = e.y; lastX = e.x; longFired = false; postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong()) }
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
            val target = when { vx < -minFling -> page + 1; vx > minFling -> page - 1; else -> ((scrollX + width / 2) / width.coerceAtLeast(1)) }
            snapTo(target)
        }

        private fun release() { dragging = false; vt?.recycle(); vt = null }

        fun snapTo(p: Int) {
            val n = (childCount - 1).coerceAtLeast(0)
            val old = page
            page = p.coerceIn(0, n)
            scroller.startScroll(scrollX, 0, page * width - scrollX, 0, 260)
            postInvalidateOnAnimation()
            if (page != old) { onPage(); announceForAccessibility(getString(R.string.hm_page, page + 1, n + 1)) }
        }

        override fun computeScroll() {
            if (scroller.computeScrollOffset()) { scrollTo(scroller.currX, 0); postInvalidateOnAnimation() }
            else if (!dragging && width > 0 && scrollX != page * width && scroller.isFinished) { /* settled */ }
        }

        private fun track(e: MotionEvent) {
            if (e.actionMasked == MotionEvent.ACTION_DOWN) { vt?.recycle(); vt = VelocityTracker.obtain() }
            vt?.addMovement(e)
        }
    }

    /** cols x rows grid; children carry their cell rectangle (LP). Items are centred in their cells. */
    private class CellLayout(ctx: Context, val cols: Int, val rows: Int) : ViewGroup(ctx) {
        class LP(val x: Int, val y: Int, val w: Int, val h: Int) : ViewGroup.LayoutParams(-1, -1)
        private val gap = (6 * ctx.resources.displayMetrics.density).toInt()
        private val side = (10 * ctx.resources.displayMetrics.density).toInt()

        init { clipChildren = false; clipToPadding = false }

        override fun onMeasure(w: Int, h: Int) {
            val W = MeasureSpec.getSize(w); val H = MeasureSpec.getSize(h)
            val cw = (W - 2 * side) / cols; val ch = H / rows
            for (i in 0 until childCount) {
                val c = getChildAt(i); val lp = c.layoutParams as LP
                c.measure(MeasureSpec.makeMeasureSpec(cw * lp.w - gap, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(ch * lp.h - gap, MeasureSpec.EXACTLY))
            }
            setMeasuredDimension(W, H)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val cw = (r - l - 2 * side) / cols; val ch = (b - t) / rows
            for (i in 0 until childCount) {
                val c = getChildAt(i); val lp = c.layoutParams as LP
                val x = side + lp.x * cw + gap / 2; val y = lp.y * ch + gap / 2
                c.layout(x, y, x + c.measuredWidth, y + c.measuredHeight)
            }
        }
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        // typing on a hardware keyboard on the home screen opens search
        if (drawer == null && e.action == KeyEvent.ACTION_DOWN && e.isPrintingKey) { openDrawer(); drawer?.search?.let { it.requestFocus(); it.append(e.unicodeChar.toChar().toString()) }; return true }
        return super.dispatchKeyEvent(e)
    }
}
