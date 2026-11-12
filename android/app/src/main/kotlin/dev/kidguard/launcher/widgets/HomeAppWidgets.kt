package dev.kidguard

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Process
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import kotlin.math.abs
import kotlin.math.ceil
import dev.kidguard.HomeLayout.Src
import dev.kidguard.HomeActivity.DragSt

// Other apps' home-screen widgets (AppWidgetHost) on the kid launcher. Item: kind "widget", type "app", pkg/cls = provider, awid = host id.
// Everything lives here; hooks: HomeActivity (aw, onStart/onStop, onActivityResult, render sync), armWidget, openPicker.

internal const val AW_HOST_ID = 0x4b47
private const val REQ_BIND = 4701
private const val REQ_CONF = 4702

/** Host view that steals the gesture after a long press (RemoteViews children would otherwise swallow it). */
internal class KgHostView(ctx: Context) : AppWidgetHostView(ctx) {
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var dx = 0f; private var dy = 0f; private var rx = 0f; private var ry = 0f
    private var stolen = false
    // the activity's drag check measures from its own down point, which never saw this DOWN (a RemoteViews child took it)
    private val fire = Runnable { stolen = true; (context as? HomeActivity)?.let { it.downX = rx; it.downY = ry }; performLongClick() }

    // The timer is cancelled here, not in onInterceptTouchEvent: once the pager took a fast page swipe the CANCEL never reached
    // our intercept (seen on the POCO: a quick swipe starting on Chrome's search bar opened the widget menu on the next page).
    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { dx = e.x; dy = e.y; rx = e.rawX; ry = e.rawY; stolen = false; removeCallbacks(fire); postDelayed(fire, ViewConfiguration.getLongPressTimeout().toLong()) }
            MotionEvent.ACTION_MOVE -> if (!stolen && (abs(e.x - dx) > slop || abs(e.y - dy) > slop)) removeCallbacks(fire)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> removeCallbacks(fire)
        }
        return super.dispatchTouchEvent(e)
    }

    override fun onInterceptTouchEvent(e: MotionEvent) = stolen

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!stolen) return super.onTouchEvent(e)
        if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) stolen = false
        return true
    }

    override fun onDetachedFromWindow() { removeCallbacks(fire); super.onDetachedFromWindow() }

    // AppWidgetHostView adds the system's default widget padding (~8 dp a side) in setAppWidget. The grid already insets
    // widgets to the icon edges (CellLayout.box), so that padding pushed other apps' widgets ~8 dp inside the icon columns.
    override fun setPadding(left: Int, top: Int, right: Int, bottom: Int) = super.setPadding(0, 0, 0, 0)

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        val d = resources.displayMetrics.density
        if (w > 0 && h > 0) runCatching { updateAppWidgetSize(null, (w / d).toInt(), (h / d).toInt(), (w / d).toInt(), (h / d).toInt()) }
    }
}

private class KgHost(ctx: Context) : AppWidgetHost(ctx, AW_HOST_ID) {
    override fun onCreateView(context: Context, appWidgetId: Int, appWidget: AppWidgetProviderInfo?): AppWidgetHostView = KgHostView(context)
}

/** State of the feature, one instance in the activity. */
internal class AwState {
    var host: AppWidgetHost? = null
    val views = HashMap<Int, AppWidgetHostView>()
    var seen: Set<Int>? = null            // ids present in the last synced model
    var pendingId = 0
    var dropAt: Triple<Int, Int, Int>? = null   // page, x, y where a picker drag let go; used once the bind / configure finishes
    var listening = false
}

/** The id of a bind in progress, also kept in prefs so [awSync] can drop it after a process death. */
private const val AW_PENDING_MAX_MS = 10 * 60_000L

internal fun HomeActivity.awPend(id: Int) {
    aw.pendingId = id
    getSharedPreferences("launcher", Context.MODE_PRIVATE).edit().putInt("awPending", id).putLong("awPendingAt", System.currentTimeMillis()).apply()
}

internal fun HomeActivity.awHost(): AppWidgetHost = aw.host ?: KgHost(applicationContext).also { aw.host = it }

internal fun HomeActivity.awStart() { if (aw.host == null && aw.seen.isNullOrEmpty()) return; runCatching { awHost().startListening(); aw.listening = true } }
internal fun HomeActivity.awStop() { if (aw.listening) runCatching { aw.host?.stopListening() }; aw.listening = false }

/** Called by render(): ids that were in the model before but not now (unpinned, dropped on remove, reset) are deleted from the host. */
internal fun HomeActivity.awSync(m: HomeModel) {
    val now = HomeLayout.appWidgetIds(m)
    val before = aw.seen
    aw.seen = now
    if (before == null) {
        // an id allocated for a bind that never finished (process died mid-flow) would stay in the host for ever
        // only a stale one: a recreated activity (rotation, low RAM) still gets the bind/config result for a fresh id
        val sp = getSharedPreferences("launcher", Context.MODE_PRIVATE)
        val p = sp.getInt("awPending", 0)
        if (p != 0) {
            if (System.currentTimeMillis() - sp.getLong("awPendingAt", 0) < AW_PENDING_MAX_MS) aw.pendingId = p
            else { if (p !in now) runCatching { awHost().deleteAppWidgetId(p) }; awPend(0) }
        }
        if (now.isNotEmpty() && !aw.listening && lifecycleStarted) awStart(); return }
    for (id in before - now) { runCatching { aw.host?.deleteAppWidgetId(id) }; aw.views.remove(id) }
    if (before.isEmpty() && now.isNotEmpty() && !aw.listening && lifecycleStarted) awStart()
}

private fun HomeActivity.awMgr() = AppWidgetManager.getInstance(this)

/** Cell size in px of the current grid (any laid-out page), or an estimate. */
private fun HomeActivity.awCell(): Pair<Int, Int> {
    for (i in 0 until pager.childCount) (pager.getChildAt(i) as? HomeActivity.CellLayout)?.takeIf { it.width > 0 }?.let { return it.cellW() to it.cellH() }
    val m = model; val dm = resources.displayMetrics
    return (dm.widthPixels - dp(20)) / (m?.cols ?: 4) to (dm.heightPixels * 0.62f).toInt() / (m?.rows ?: 5)
}

private fun cellsFor(px: Int, cell: Int, max: Int) = ceil(px.toDouble() / cell.coerceAtLeast(1)).toInt().coerceIn(1, max)

/** Smallest size in cells the provider accepts. */
// AppWidgetProviderInfo sizes are already px (the system resolves the provider's dp on this screen). Taking them for dp
// (× density) made every widget ~2.75× too big on a 440 dpi phone: Binance "Hot Coins" (220 dp) asked for 4×all rows.
internal fun HomeActivity.awMinCells(info: AppWidgetProviderInfo): Pair<Int, Int> {
    val m = model; val (cw, ch) = awCell()
    val minW = if (info.minResizeWidth in 1 until info.minWidth) info.minResizeWidth else info.minWidth
    val minH = if (info.minResizeHeight in 1 until info.minHeight) info.minResizeHeight else info.minHeight
    return cellsFor(minW, cw, m?.cols ?: 4) to cellsFor(minH, ch, m?.rows ?: 5)
}

/** Default size when added: what minWidth / minHeight ask for. */
private fun HomeActivity.awDefaultCells(info: AppWidgetProviderInfo): Pair<Int, Int> {
    val m = model; val (cw, ch) = awCell()
    return cellsFor(info.minWidth, cw, m?.cols ?: 4) to cellsFor(info.minHeight, ch, m?.rows ?: 5)
}

/** Sizes offered in the long-press menu: the provider's own, within its resize mode. */
internal fun HomeActivity.awSizes(it: HItem): List<Pair<Int, Int>> {
    val info = runCatching { awMgr().getAppWidgetInfo(it.awid) }.getOrNull() ?: return emptyList()
    val cols = model?.cols ?: 4; val rows = model?.rows ?: 5
    val (mw, mh) = awMinCells(info)
    val base = awDefaultCells(info)
    val hz = info.resizeMode and AppWidgetProviderInfo.RESIZE_HORIZONTAL != 0
    val vt = info.resizeMode and AppWidgetProviderInfo.RESIZE_VERTICAL != 0
    val out = LinkedHashSet<Pair<Int, Int>>()
    out += base
    if (hz || vt) {
        out += (if (hz) mw else base.first) to (if (vt) mh else base.second)
        if (hz) out += cols to base.second
        if (vt) out += base.first to minOf(rows, base.second + 1)
        if (hz && vt) { out += cols to minOf(rows, base.second + 1); out += cols to maxOf(mh, rows / 2) }
    }
    return out.filter { (w, h) -> w in mw..cols && h in mh..rows }.take(5)
}

/** The widget for [it]; a locked card when its app is hidden / blocked, a small one when the app is gone. */
internal fun HomeActivity.appWidget(it: HItem, show: Map<String, LauncherLogic.Show>): View? {
    val label = launcherApps()[it.pkg]?.label ?: it.pkg
    val st = show[it.pkg]
    if (st == null || st == LauncherLogic.Show.HIDE || st == LauncherLogic.Show.PAUSED) {
        val card = wCard().apply { gravity = Gravity.CENTER_VERTICAL }
        card.addView(wHead(R.drawable.li_lock, label))
        card.addView(wSub(getString(if (st == LauncherLogic.Show.PAUSED) R.string.hm_aw_paused else R.string.hm_aw_hidden), 2))
        return centred(card)
    }
    val info = runCatching { awMgr().getAppWidgetInfo(it.awid) }.getOrNull()
    if (info == null) {
        val card = wCard()
        card.addView(wEmpty(getString(R.string.hm_aw_gone)))
        return centred(card)
    }
    val v = aw.views.getOrPut(it.awid) { awHost().createView(this, it.awid, info) }
    (v.parent as? ViewGroup)?.removeView(v)
    v.visibility = View.VISIBLE
    return v
}

// ---- picker section ----

/** [all] / [show]: snapshots taken on the main thread (this runs on the picker's loader thread). */
private fun HomeActivity.awProviders(all: Map<String, HomeActivity.AppE>, show: Map<String, LauncherLogic.Show>): List<Pair<HomeActivity.AppE, List<AppWidgetProviderInfo>>> {
    val infos = runCatching { awMgr().getInstalledProvidersForProfile(Process.myUserHandle()) }.getOrNull() ?: awMgr().installedProviders
    return infos.filter { p -> p.provider.packageName != packageName && show[p.provider.packageName].let { s -> s != null && s != LauncherLogic.Show.HIDE } }
        .groupBy { it.provider.packageName }
        .mapNotNull { (pkg, l) -> all[pkg]?.let { it to l } }
        .sortedBy { it.first.sortKey }
}

/**
 * After our own rows: a header and, per app, its icon + label and one row per provider. Reading the previews is slow
 * (~25 ms an app on a Poco F-class phone: decode + label, 75 apps there), so a background thread reads them, shrunk to
 * the thumbnail size, and the rows are added a few apps per frame. On the main thread this jammed every frame for ~2 s
 * while the sheet slid up.
 */
internal fun HomeActivity.awPickerSection(list: LinearLayout, query: String = "", alive: () -> Boolean) {
    val fq = LauncherLogic.fold(query).trim()
    val shp = model?.style?.shape ?: "soft"
    val (cw, ch) = awCell(); val d = resources.displayMetrics.density; val m = model
    class R0(val info: AppWidgetProviderInfo, val pv: android.graphics.Bitmap?, val name: String)
    class G0(val app: HomeActivity.AppE, val icon: android.graphics.Bitmap?, val rows: List<R0>)
    // thumbnails at most 2x density: 150 providers at full 3x size would hold ~70 MB while the sheet is open
    val ds = minOf(d, 2f); val tw = (130 * ds).toInt(); val th = (84 * ds).toInt()
    fun thumb(dr: android.graphics.drawable.Drawable?): android.graphics.Bitmap? {
        dr ?: return null
        val iw = dr.intrinsicWidth.takeIf { it > 0 } ?: tw; val ih = dr.intrinsicHeight.takeIf { it > 0 } ?: th
        val sc = minOf(tw.toFloat() / iw, th.toFloat() / ih, 1f)
        val w = (iw * sc).toInt().coerceAtLeast(1); val h = (ih * sc).toInt().coerceAtLeast(1)
        return runCatching { android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888).also { dr.setBounds(0, 0, w, h); dr.draw(android.graphics.Canvas(it)) } }.getOrNull()
    }
    // POCO's look: one card per app (icon + name), its widgets two to a row as bare previews (no plate behind them),
    // name + size under each; cards spaced apart so one app's widgets never run into the next one's.
    fun group(g: G0) {
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = fill(pickSurf(), 22f); setPadding(dp(14), dp(12), dp(14), dp(10)) }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, dp(8)) }
        head.addView(ImageView(this).apply { setImageBitmap(g.icon) }, LinearLayout.LayoutParams(dp(26), dp(26)))
        head.addView(text(g.app.label, 15f, pal.text, true).apply { setPadding(dp(10), 0, 0, 0); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        card.addView(head)
        var line: LinearLayout? = null
        for ((k, r) in g.rows.withIndex()) {
            val info = r.info; val name = r.name
            val (w, h) = awDefaultCells(info)
            val cell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(4), dp(6), dp(4), dp(6)) }
            cell.addView(ImageView(this).apply { setImageBitmap(r.pv); scaleType = ImageView.ScaleType.FIT_CENTER; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, LinearLayout.LayoutParams(-1, dp(84)))
            cell.addView(text(name, 13f, pal.text, true).apply { gravity = Gravity.CENTER; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(6), 0, 0) }, LinearLayout.LayoutParams(-1, -2))
            cell.addView(wSub("$w×$h").apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, -2))
            kit.tap(cell, 16f) { aw.dropAt = null; awAdd(info) }
            // drag it onto a page like our own widgets (POCO / Pixel): the bind prompt comes after the drop
            arm(cell, {}, { awDrag = info; DragSt(Src.NewAppWidget(info.provider.packageName, info.provider.className, w, h), null, "widget", w, h, null, cell.getChildAt(0)) },
                immediate = true, dragView = cell.getChildAt(0))
            cell.contentDescription = "$name, ${g.app.label}, $w×$h"
            if (k % 2 == 0) line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; weightSum = 2f }.also { card.addView(it, LinearLayout.LayoutParams(-1, -2)) }
            line!!.addView(cell, LinearLayout.LayoutParams(0, -2, 1f))
        }
        list.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
    }
    val q = java.util.concurrent.ConcurrentLinkedQueue<G0>()
    val done = java.util.concurrent.atomic.AtomicBoolean(false)
    val ctx = applicationContext; val all = launcherApps(); val show = lastShow
    Thread({
        // the provider list too (~30 ms): only the views are made on the main thread
        for ((app, infos) in runCatching { awProviders(all, show) }.getOrDefault(emptyList())) {
            if (!alive()) break
            // a search: the app's name matches (all its widgets) or a widget's own name does (only those)
            val named = infos.map { it to (runCatching { it.loadLabel(ctx.packageManager) }.getOrNull()?.takeIf { n -> n.isNotBlank() } ?: app.label) }
            val keep = if (fq.isEmpty() || LauncherLogic.fold(app.label).contains(fq)) named else named.filter { LauncherLogic.fold(it.second).contains(fq) }
            if (keep.isEmpty()) continue
            val icon = IconCache.get(ctx, app.pkg, app.cls, dp(28), shp)
            q += G0(app, icon, keep.map { (info, name) -> R0(info, thumb(runCatching { info.loadPreviewImage(ctx, 0) ?: info.loadIcon(ctx, 0) }.getOrNull()), name) })
        }
        done.set(true)
    }, "kg-aw-picker").apply { priority = Thread.NORM_PRIORITY - 1 }.start()
    var titled = false
    fun drain() {
        if (!alive()) return
        if (!titled && q.isNotEmpty()) { titled = true; list.addView(text(getString(R.string.hm_w_apps_title), 18f, pal.text, true).apply { setPadding(0, dp(20), 0, dp(4)) }) }
        repeat(3) { q.poll()?.let { group(it) } }
        if (!done.get() || q.isNotEmpty()) list.postOnAnimation { drain() }
    }
    list.postOnAnimation { drain() }
}

// ---- add flow: allocate id -> bind (ask when needed) -> configure -> place ----

internal fun HomeActivity.awAdd(info: AppWidgetProviderInfo) {
    // the picker's list was read earlier: the app may have been hidden / blocked since
    lastShow[info.provider.packageName].let { if (it == null || it == LauncherLogic.Show.HIDE) { closeOverlay(); return toast(getString(R.string.hm_aw_hidden)) } }
    closeOverlay()
    // a still-unplaced id from an earlier try (bind prompt dismissed, configure left open) would leak in the host
    aw.pendingId.takeIf { it != 0 && it !in (aw.seen ?: emptySet()) }?.let { old -> runCatching { awHost().deleteAppWidgetId(old) } }
    aw.pendingId = 0
    val id = runCatching { awHost().allocateAppWidgetId() }.getOrNull() ?: return toast(getString(R.string.hm_aw_fail))
    awPend(id)
    val ok = runCatching { awMgr().bindAppWidgetIdIfAllowed(id, info.provider) }.getOrDefault(false)
    if (ok) return awConfigure(id)
    val i = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id).putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
    if (runCatching { startActivityForResult(i, REQ_BIND) }.isFailure) awFail(id)
}

private fun HomeActivity.awConfigure(id: Int) {
    val info = awMgr().getAppWidgetInfo(id) ?: return awFail(id)
    if (info.configure == null) return awPlace(id)
    if (runCatching { awHost().startAppWidgetConfigureActivityForResult(this, id, 0, REQ_CONF, null) }.isFailure) awFail(id)
}

private fun HomeActivity.awFail(id: Int) {
    runCatching { aw.host?.deleteAppWidgetId(id) }
    if (aw.pendingId == id) awPend(0)
    toast(getString(R.string.hm_aw_fail))
}

private fun HomeActivity.awPlace(id: Int) {
    awPend(0)
    val info = awMgr().getAppWidgetInfo(id) ?: return awFail(id)
    val m = model ?: return awFail(id)
    val (w, h) = awDefaultCells(info)
    val at = aw.dropAt; aw.dropAt = null
    val n = if (at != null) HomeLayout.placeAppWidgetAt(m, info.provider.packageName, info.provider.className, id, w, h, at.first, at.second, at.third)
        else HomeLayout.placeAppWidget(m, info.provider.packageName, info.provider.className, id, w, h, (pager.page - 1).coerceIn(0, (m.pages.size - 1).coerceAtLeast(0)))
    if (n == null) { runCatching { aw.host?.deleteAppWidgetId(id) }; noRoom(null); return }
    update(n)
    HomeLayout.find(n, n.pages.flatten().first { it.type == HomeLayout.APP && it.awid == id }.id)?.first?.let { pi -> pager.post { pager.snapTo(pi + 1) } }
    toast(getString(R.string.hm_w_added, runCatching { info.loadLabel(packageManager) }.getOrNull()?.toString() ?: info.provider.packageName))
}

/** Result of the bind prompt / the widget's own configure screen. */
internal fun HomeActivity.awResult(req: Int, res: Int, data: Intent?) {
    if (req != REQ_BIND && req != REQ_CONF) return
    val id = data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 0)?.takeIf { it != 0 } ?: aw.pendingId
    if (id == 0) return
    if (res != Activity.RESULT_OK) { runCatching { aw.host?.deleteAppWidgetId(id) }; awPend(0); aw.dropAt = null; return }
    if (req == REQ_BIND) awConfigure(id) else awPlace(id)
}
