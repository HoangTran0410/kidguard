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

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { dx = e.x; dy = e.y; rx = e.rawX; ry = e.rawY; stolen = false; removeCallbacks(fire); postDelayed(fire, ViewConfiguration.getLongPressTimeout().toLong()) }
            MotionEvent.ACTION_MOVE -> if (!stolen && (abs(e.x - dx) > slop || abs(e.y - dy) > slop)) removeCallbacks(fire)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { removeCallbacks(fire); if (!stolen) return false }
        }
        return stolen
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!stolen) return super.onTouchEvent(e)
        if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) stolen = false
        return true
    }

    override fun onDetachedFromWindow() { removeCallbacks(fire); super.onDetachedFromWindow() }

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
    var listening = false
}

internal fun HomeActivity.awHost(): AppWidgetHost = aw.host ?: KgHost(applicationContext).also { aw.host = it }

internal fun HomeActivity.awStart() { if (aw.host == null && aw.seen.isNullOrEmpty()) return; runCatching { awHost().startListening(); aw.listening = true } }
internal fun HomeActivity.awStop() { if (aw.listening) runCatching { aw.host?.stopListening() }; aw.listening = false }

/** Called by render(): ids that were in the model before but not now (unpinned, dropped on remove, reset) are deleted from the host. */
internal fun HomeActivity.awSync(m: HomeModel) {
    val now = HomeLayout.appWidgetIds(m)
    val before = aw.seen
    aw.seen = now
    if (before == null) { if (now.isNotEmpty() && !aw.listening && lifecycleStarted) awStart(); return }
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
internal fun HomeActivity.awMinCells(info: AppWidgetProviderInfo): Pair<Int, Int> {
    val m = model; val (cw, ch) = awCell(); val d = resources.displayMetrics.density
    val minW = if (info.minResizeWidth in 1 until info.minWidth) info.minResizeWidth else info.minWidth
    val minH = if (info.minResizeHeight in 1 until info.minHeight) info.minResizeHeight else info.minHeight
    return cellsFor((minW * d).toInt(), cw, m?.cols ?: 4) to cellsFor((minH * d).toInt(), ch, m?.rows ?: 5)
}

/** Default size when added: what minWidth / minHeight ask for. */
private fun HomeActivity.awDefaultCells(info: AppWidgetProviderInfo): Pair<Int, Int> {
    val m = model; val (cw, ch) = awCell(); val d = resources.displayMetrics.density
    return cellsFor((info.minWidth * d).toInt(), cw, m?.cols ?: 4) to cellsFor((info.minHeight * d).toInt(), ch, m?.rows ?: 5)
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

private fun HomeActivity.awProviders(): List<Pair<HomeActivity.AppE, List<AppWidgetProviderInfo>>> {
    val all = launcherApps()
    val infos = runCatching { awMgr().getInstalledProvidersForProfile(Process.myUserHandle()) }.getOrNull() ?: awMgr().installedProviders
    return infos.filter { p -> p.provider.packageName != packageName && lastShow[p.provider.packageName].let { s -> s != null && s != LauncherLogic.Show.HIDE } }
        .groupBy { it.provider.packageName }
        .mapNotNull { (pkg, l) -> all[pkg]?.let { it to l } }
        .sortedBy { it.first.sortKey }
}

/** After our own rows: a header and, per app, its icon + label and one row per provider. Built a few apps per frame. */
internal fun HomeActivity.awPickerSection(list: LinearLayout, alive: () -> Boolean) {
    val groups = awProviders()
    if (groups.isEmpty()) return
    list.addView(text(getString(R.string.hm_w_apps_title), 18f, pal.text, true).apply { setPadding(0, dp(20), 0, dp(4)) })
    val sq = model?.style?.shape != "square"
    val (cw, ch) = awCell(); val d = resources.displayMetrics.density; val m = model
    fun group(g: Pair<HomeActivity.AppE, List<AppWidgetProviderInfo>>) {
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(12), 0, dp(4)) }
        head.addView(ImageView(this).apply { setImageBitmap(IconCache.get(this@awPickerSection, g.first.pkg, g.first.cls, dp(28), sq)) }, LinearLayout.LayoutParams(dp(28), dp(28)))
        head.addView(text(g.first.label, 15f, pal.text, true).apply { setPadding(dp(10), 0, 0, 0); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        list.addView(head)
        for (info in g.second) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, dp(6)) }
            val pv = runCatching { info.loadPreviewImage(this, 0) ?: info.loadIcon(this, 0) }.getOrNull()
            row.addView(ImageView(this).apply { setImageDrawable(pv); scaleType = ImageView.ScaleType.FIT_CENTER; background = fill(pal.tint, 12f); setPadding(dp(6), dp(6), dp(6), dp(6)); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, LinearLayout.LayoutParams(dp(96), dp(60)))
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
            val name = runCatching { info.loadLabel(packageManager) }.getOrNull()?.takeIf { it.isNotBlank() } ?: g.first.label
            col.addView(text(name, 14f, pal.text, true).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
            val (w, h) = cellsFor((info.minWidth * d).toInt(), cw, m?.cols ?: 4) to cellsFor((info.minHeight * d).toInt(), ch, m?.rows ?: 5)
            col.addView(wSub("$w×$h"))
            row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
            kit.tap(row, 16f) { awAdd(info) }
            row.contentDescription = "$name, ${g.first.label}, $w×$h"
            list.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
    }
    fun more(from: Int) {
        if (from >= groups.size || !alive()) return
        for (i in from until minOf(from + 2, groups.size)) group(groups[i])
        list.post { more(from + 2) }
    }
    more(0)
}

// ---- add flow: allocate id -> bind (ask when needed) -> configure -> place ----

internal fun HomeActivity.awAdd(info: AppWidgetProviderInfo) {
    closeOverlay()
    val id = runCatching { awHost().allocateAppWidgetId() }.getOrNull() ?: return toast(getString(R.string.hm_aw_fail))
    aw.pendingId = id
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
    if (aw.pendingId == id) aw.pendingId = 0
    toast(getString(R.string.hm_aw_fail))
}

private fun HomeActivity.awPlace(id: Int) {
    aw.pendingId = 0
    val info = awMgr().getAppWidgetInfo(id) ?: return awFail(id)
    val m = model ?: return awFail(id)
    val (w, h) = awDefaultCells(info)
    val n = HomeLayout.placeAppWidget(m, info.provider.packageName, info.provider.className, id, w, h, (pager.page - 1).coerceAtLeast(0))
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
    if (res != Activity.RESULT_OK) { runCatching { aw.host?.deleteAppWidgetId(id) }; aw.pendingId = 0; return }
    if (req == REQ_BIND) awConfigure(id) else awPlace(id)
}
