package dev.kidguard

import android.animation.ObjectAnimator
import android.view.animation.DecelerateInterpolator
import android.view.DragEvent
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import kotlin.math.abs
import kotlin.math.roundToInt
import dev.kidguard.HomeLayout.Src
import dev.kidguard.HomeActivity.DragSt
import dev.kidguard.HomeActivity.CellLayout

// Drag and drop: hover preview, swap, folder dwell, remove target, landing (split out of HomeActivity; extension functions on it).

// ---- drag and drop ----


/**
 * Pixel-style press: long-press shows [onLong] (the menu); moving the finger 8 dp after that closes it and starts a drag.
 * In a folder panel / the picker ([immediate]) the long-press starts the drag at once.
 */
internal fun HomeActivity.arm(v: View, onLong: () -> Unit, src: () -> DragSt, immediate: Boolean = false, dragView: View = v) {
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
        if (immediate) startDrag(dragView, src()) else { armed = v; onLong() }
        true
    }
}

internal fun HomeActivity.startDrag(v: View, d: DragSt) {
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
    bannerBox.visibility = View.INVISIBLE   // keeps its height: the drop bar floats over it
    buildTopBar(); styleCells()
    main.post { padPages() }
}


internal fun HomeActivity.pageView(): CellLayout? = pager.getChildAt(pager.page) as? CellLayout

/** Finger -> (page index, anchor cell for the dragged size, the item under the finger). */
internal fun HomeActivity.cellAt(x: Float, y: Float, d: DragSt): Triple<Int, Pair<Int, Int>, HItem?>? {
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

internal fun HomeActivity.inView(v: View, x: Float, y: Float): Boolean {
    if (!v.isShown) return false
    val loc = IntArray(2); val rl = IntArray(2); v.getLocationInWindow(loc); root.getLocationInWindow(rl)
    val l = loc[0] - rl[0]; val t = loc[1] - rl[1]
    return x >= l && x < l + v.width && y >= t && y < t + v.height
}

internal fun HomeActivity.dockSlot(x: Float, y: Float): Int {
    if (!inView(dock, x, y)) return -1
    for ((i, v) in dockViews.withIndex()) if (inView(v, x, y)) return i
    return -1
}

internal fun HomeActivity.onDrag(e: DragEvent): Boolean {
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

internal fun HomeActivity.clearHover() { clearCell(); magnet(Float.NaN, Float.NaN) }

/** Resets the cell-level hover: folder target, the re-arrange preview, the tip, the cell highlight, dock slots. */
internal fun HomeActivity.clearCell() {
    folderArm?.let { main.removeCallbacks(it) }; folderArm = null; folderReady = false
    hoverId?.let { itemViews[it]?.background = null }
    hoverId = null
    unswap()
    tip.visibility = View.GONE
    pageView()?.let { if (it.hl != null) { it.hl = null; it.invalidate() } }
    for (v in dockViews) v.background = null
}

internal fun HomeActivity.unswap() {
    swapId?.let { itemViews[it]?.animate()?.translationX(0f)?.translationY(0f)?.setDuration(180)?.start() }
    swapId = null
}

/**
 * The remove target follows the finger: within ~170 dp it leans toward it, grows and brightens (the "pull"); inside
 * the 64 dp zone it turns red (one tick). NaN resets it. Returns true inside the zone (= drop removes).
 */
internal fun HomeActivity.magnet(x: Float, y: Float): Boolean {
    val b = removeBtn ?: return false
    if (!b.isShown) return false
    val loc = IntArray(2); val rl = IntArray(2); b.getLocationInWindow(loc); root.getLocationInWindow(rl)
    val cx = loc[0] - rl[0] - b.translationX + b.width / 2f; val cy = loc[1] - rl[1] - b.translationY + b.height / 2f
    val dist = if (x.isNaN()) Float.MAX_VALUE else kotlin.math.hypot(x - cx, y - cy)
    val k = (1f - dist / dpf(170f)).coerceIn(0f, 1f)
    val hot = dist < dpf(64f)
    removeNear = k > 0.35f
    b.translationX = if (k > 0f) (x - cx) * 0.3f * k else 0f
    b.translationY = if (k > 0f) (y - cy) * 0.3f * k else 0f
    val sc = if (hot) 1.3f else 1f + 0.2f * k
    b.scaleX = sc; b.scaleY = sc; b.alpha = if (hot) 1f else 0.5f + 0.4f * k
    if (hot != removeHot) {
        removeHot = hot
        b.background = fill(if (hot) pal.danger else pal.sheet)
        (b as ImageView).setImageDrawable(Ic.get(this, R.drawable.li_x, if (hot) pal.onDanger else pal.text, dp(24)))
        if (hot) b.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }
    return hot
}

/** The finger is near the middle of [it]'s icon (not just passing over its edge). */
internal fun HomeActivity.nearCentre(it: HItem, x: Float, y: Float): Boolean {
    val v = itemViews[it.id] ?: return false
    val loc = IntArray(2); val rl = IntArray(2); v.getLocationInWindow(loc); root.getLocationInWindow(rl)
    // rest position (it may be sliding out of the way right now); the icon sits above the label
    val cx = loc[0] - rl[0] - v.translationX + v.width / 2f; val cy = loc[1] - rl[1] - v.translationY + v.height / 2f - v.height * 0.12f
    return kotlin.math.hypot(x - cx, y - cy) < v.width * 0.3f
}

internal fun HomeActivity.hover(x: Float, y: Float, d: DragSt) {
    val m = model ?: return
    // the remove target
    if (magnet(x, y) || removeNear) { clearCell(); cancelFlip(); return }
    val slot = dockSlot(x, y)
    if (slot >= 0) {
        clearCell(); cancelFlip()
        if (slot > 0 && d.kind == "app") dockViews[slot].background = fill(pal.card, 18f)
        return
    }
    // the screen edges flip pages (past the last page: a new empty one)
    val edge = dp(22)
    if (x < edge || x > root.width - edge) {
        clearCell()
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
    val (pi, a, under) = cellAt(x, y, d) ?: run { clearCell(); return }
    val cl = pageView() ?: return
    // folder: only after the finger rests on the middle of an app / folder icon for HomeActivity.FOLDER_MS; passing over = move
    if (under != null && d.kind == "app" && (under.kind == "app" || under.kind == "folder") && nearCentre(under, x, y)) {
        if (hoverId == under.id) return
        clearCell()
        hoverId = under.id
        val u = under
        folderArm = Runnable {
            folderArm = null
            if (hoverId != u.id) return@Runnable
            folderReady = true
            itemViews[u.id]?.let { v -> v.background = fill(pal.card, 20f); v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }
            val name = if (u.kind == "folder") folderName(u) else launcherApps()[u.pkg]?.label ?: ""
            showTip(getString(if (u.kind == "folder") R.string.hm_f_drop_into else R.string.hm_f_drop_make, name))
        }.also { main.postDelayed(it, HomeActivity.FOLDER_MS) }
        return
    }
    if (hoverId != null) clearCell()
    val t = taken(m, d) ?: return
    val w = minOf(d.w, m.cols); val h = minOf(d.h, m.rows)
    val free = HomeLayout.free(t.m.pages.getOrNull(pi) ?: emptyList(), m.cols, m.rows, a.first, a.second, w, h)
    val sw = if (free) null else HomeLayout.swapSpot(t, pi, a.first, a.second)
    // re-arrange preview: the item in the way slides to where it will go
    if (sw?.first?.id != swapId) {
        unswap()
        if (sw != null) itemViews[sw.first.id]?.let { v ->
            swapId = sw.first.id
            v.animate().translationX(((sw.second - sw.first.x) * cl.cellW()).toFloat()).translationY(((sw.third - sw.first.y) * cl.cellH()).toFloat())
                .setDuration(200).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        }
    }
    val hl = intArrayOf(a.first, a.second, w, h); val ok = free || sw != null
    if (!(cl.hl contentEquals hl) || cl.hlOk != ok) { cl.hl = hl; cl.hlOk = ok; cl.invalidate() }
}

internal fun HomeActivity.showTip(t: String) {
    tip.text = t; tip.setTextColor(pal.bg); tip.background = fill(pal.text); tip.visibility = View.VISIBLE
}

internal fun HomeActivity.cancelFlip() { flip?.let { main.removeCallbacks(it) }; flip = null }

internal fun HomeActivity.taken(m: HomeModel, d: DragSt): HomeLayout.Taken? =
    if (d.src != null) HomeLayout.take(m, d.src) else d.pkg?.let { HomeLayout.detach(m, it) }

internal fun HomeActivity.drop(x: Float, y: Float, d: DragSt): Boolean {
    cancelFlip()
    val m = model ?: return false
    val t = taken(m, d) ?: return false
    val out: HomeModel? = when {
        magnet(x, y) -> if (d.src is Src.NewWidget || (d.pkg != null && d.pkg !in m.onHome())) m else HomeLayout.dropRemove(t)
        dockSlot(x, y) >= 0 -> HomeLayout.dropDock(t, dockSlot(x, y))
        else -> cellAt(x, y, d)?.let { (pi, a, under) ->
            if (folderReady && under != null && under.id == hoverId && d.kind == "app")
                HomeLayout.dropOnto(t, under.id, if (under.kind == "app") autoName(under.pkg, t.item.pkg) else "")
            else HomeLayout.dropMove(t, pi, a.first, a.second)
        }
    }
    // the dragged item lands from where the finger let go: on its new cell, or back home (no room / same place)
    land = if (removeHot) null else Triple(d, x, y)
    if (out == null) return true   // no room there: it just springs home (the red cell already said so); true = our animation, not the system's
    if (out !== m) { HomeFile.save(this, out); model = out; dropped = true }
    return true
}


/**
 * After the post-drop render: the item's real view starts where the finger let go and springs into its cell (the
 * system drag shadow is gone by then, so it reads as the same icon flying home). An app dropped into a folder: the
 * folder pops instead.
 */
internal fun HomeActivity.landNow() {
    val (d, x, y) = land ?: return
    land = null
    val m = model ?: return
    val pkg = d.pkg ?: d.src.let { (it as? Src.InFolder)?.pkg }
    val all = m.pages.flatten()
    var pop = false
    val v: View = d.id?.let { itemViews[it] }
        ?: all.firstOrNull { pkg != null && it.kind == "app" && it.pkg == pkg }?.let { itemViews[it.id] }
        ?: all.firstOrNull { pkg != null && it.kind == "folder" && pkg in it.apps }?.let { pop = true; itemViews[it.id] }
        ?: m.dock.indexOf(pkg ?: "").takeIf { it > 0 }?.let { dockViews.getOrNull(it) }
        ?: return
    v.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
        override fun onPreDraw(): Boolean {
            if (v.viewTreeObserver.isAlive) v.viewTreeObserver.removeOnPreDrawListener(this)
            if (!v.isAttachedToWindow) return true
            if (pop) {
                v.scaleX = 1.18f; v.scaleY = 1.18f
                v.animate().scaleX(1f).scaleY(1f).setInterpolator(android.view.animation.OvershootInterpolator(3f)).setDuration(300).start()
                return true
            }
            val loc = IntArray(2); val rl = IntArray(2); v.getLocationInWindow(loc); root.getLocationInWindow(rl)
            v.translationX = x - (loc[0] - rl[0] + v.width / 2f); v.translationY = y - (loc[1] - rl[1] + v.height / 2f)
            v.scaleX = 1.06f; v.scaleY = 1.06f
            v.animate().translationX(0f).translationY(0f).scaleX(1f).scaleY(1f)
                .setInterpolator(android.view.animation.OvershootInterpolator(1.3f)).setDuration(340).start()
            return true
        }
    })
}

internal fun HomeActivity.noRoom(v: View?) {
    (v ?: pageView())?.let { ObjectAnimator.ofFloat(it, "translationX", 0f, dpf(10f), -dpf(10f), dpf(6f), -dpf(6f), 0f).setDuration(320).start() }
    toast(getString(R.string.hm_e_no_room))
}

/**
 * After a drop that changed the layout the old views stay as they are (dragged one hidden, the displaced one already
 * slid to its new cell) until the posted render swaps in the new layout: restoring them first flashed the old layout
 * for a frame. (The render is posted: rebuilding the pages inside the drag event dispatch is not safe.)
 */
internal fun HomeActivity.endDrag() {
    if (drag == null) return
    if (!dropped && land == null) drag?.view?.visibility = View.VISIBLE   // landing: the new render's view flies in
    drag = null
    cancelFlip()
    if (dropped) swapId = null
    dropped = false
    clearHover()
    bannerBox.visibility = View.VISIBLE
    key = ""
    main.post { render(true); landNow() }
}
