package dev.kidguard

import android.content.Context
import android.view.DragEvent
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.OverScroller
import dev.kidguard.HomeActivity.DragSt
import dev.kidguard.HomeLayout.Src
import kotlin.math.abs

/**
 * An open folder's apps: pages of [cols]×[rows] (POCO / iOS), swiped sideways. Items move with a short slide when the
 * order changes (a drag inside the folder re-arranges them live).
 */
internal class FolderGrid(ctx: Context, val cols: Int, val rows: Int) : ViewGroup(ctx) {
    val items = ArrayList<Pair<View, String>>()   // icon view, package
    var onScroll: (Float) -> Unit = {}
    val per get() = cols * rows
    val pages get() = maxOf(1, (items.size + per - 1) / per)
    var page = 0; private set
    private var cellW = 0
    var cellH = 0; private set
    private var slide = false
    private var padY = 0
    private val scroller = OverScroller(ctx, DecelerateInterpolator(2f))
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var x0 = 0f; private var y0 = 0f; private var lastX = 0f; private var swiping = false
    private var vt: VelocityTracker? = null

    fun add(v: View, pkg: String) { items += v to pkg; addView(v) }

    override fun onMeasure(w: Int, h: Int) {
        val wd = MeasureSpec.getSize(w); cellW = wd / cols
        var mh = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            c.measure(MeasureSpec.makeMeasureSpec(cellW, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            mh = maxOf(mh, c.measuredHeight)
        }
        // a fixed height (the iOS square card): rows share it, icons centred in their cells
        if (MeasureSpec.getMode(h) == MeasureSpec.EXACTLY) { cellH = MeasureSpec.getSize(h) / rows; padY = ((cellH - mh) / 2).coerceAtLeast(0); setMeasuredDimension(wd, MeasureSpec.getSize(h)); return }
        padY = 0
        cellH = mh + (4 * resources.displayMetrics.density).toInt()
        val r = if (pages > 1) rows else (items.size + cols - 1) / cols
        setMeasuredDimension(wd, r.coerceAtLeast(1) * cellH)
    }

    private fun slotX(i: Int) = (i / per) * width + (i % per % cols) * cellW
    private fun slotY(i: Int) = (i % per / cols) * cellH + padY

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for ((i, p) in items.withIndex()) {
            val v = p.first
            val x = slotX(i); val y = slotY(i)
            val ox = v.left; val oy = v.top; val had = v.width > 0
            v.layout(x, y, x + cellW, y + v.measuredHeight)
            if (slide && had && (ox != x || oy != y) && v.visibility == View.VISIBLE) {
                v.translationX = (ox - x).toFloat(); v.translationY = (oy - y).toFloat()
                v.animate().translationX(0f).translationY(0f).setDuration(180).setInterpolator(DecelerateInterpolator()).start()
            }
        }
        slide = false
        if (changed && scroller.isFinished) scrollTo(page * width, 0)
    }

    /** Index under a point in this view's own coordinates (the shown page), clamped to the items. */
    fun indexAt(x: Float, y: Float): Int {
        if (cellW == 0 || cellH == 0) return 0
        val c = (x / cellW).toInt().coerceIn(0, cols - 1); val r = (y / cellH).toInt().coerceIn(0, rows - 1)
        return (page * per + r * cols + c).coerceIn(0, items.size - 1)
    }

    fun move(v: View, to: Int) {
        val from = items.indexOfFirst { it.first === v }
        if (from < 0 || from == to) return
        val p = items.removeAt(from); items.add(to.coerceIn(0, items.size), p)
        slide = true; requestLayout()
    }

    fun snapTo(p: Int) {
        page = p.coerceIn(0, pages - 1)
        scroller.startScroll(scrollX, 0, page * width - scrollX, 0, 280); postInvalidateOnAnimation()
    }

    override fun computeScroll() { if (scroller.computeScrollOffset()) { scrollTo(scroller.currX, 0); postInvalidateOnAnimation() } }
    override fun onScrollChanged(l: Int, t: Int, ol: Int, ot: Int) { super.onScrollChanged(l, t, ol, ot); if (width > 0) onScroll(l.toFloat() / width) }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        if (pages < 2) return false
        track(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { x0 = e.x; y0 = e.y; lastX = e.x; swiping = false }
            MotionEvent.ACTION_MOVE -> if (abs(e.x - x0) > slop && abs(e.x - x0) > abs(e.y - y0)) { swiping = true; lastX = e.x; parent.requestDisallowInterceptTouchEvent(true) }
        }
        return swiping
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (pages < 2) return false
        track(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> { scrollTo((scrollX + (lastX - e.x)).toInt().coerceIn(-width / 4, (pages - 1) * width + width / 4), 0); lastX = e.x }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                vt?.computeCurrentVelocity(1000); val vx = vt?.xVelocity ?: 0f
                val dx = scrollX - page * width
                snapTo(when { vx < -800 || dx > width / 3 -> page + 1; vx > 800 || dx < -width / 3 -> page - 1; else -> page })
                swiping = false; vt?.recycle(); vt = null
            }
        }
        return true
    }

    private fun track(e: MotionEvent) {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) { vt?.recycle(); vt = VelocityTracker.obtain() }
        vt?.addMovement(e)
    }
}

/** A drag that started in an open folder: it re-arranges the folder until the finger leaves the panel. */
internal class FolderDrag(val grid: FolderGrid, val panel: View, val folder: String, val view: View) { var flip: Runnable? = null }

/** Called by startDrag: true = the drag stays inside the open folder for now (the panel is not closed). */
internal fun HomeActivity.folderDragBegin(v: View, d: DragSt): Boolean {
    val src = d.src as? Src.InFolder ?: return false
    val o = overlay ?: return false
    val grid = o.findViewWithTag<FolderGrid>("folderGrid") ?: return false
    val panel = grid.parent as? View ?: return false
    folderDrag = FolderDrag(grid, (panel.parent as? View)?.takeIf { it !== o } ?: panel, src.folder, v)
    v.visibility = View.INVISIBLE
    return true
}

/**
 * Drag events while [folderDrag] is on. Inside the panel (with a little slack): re-arrange, flip folder pages at the
 * side edges, drop = save the order. Leaving it: the folder closes and the home takes over (returns false).
 */
internal fun HomeActivity.folderDragEvt(e: DragEvent, d: DragSt): Boolean {
    val f = folderDrag ?: return false
    when (e.action) {
        DragEvent.ACTION_DRAG_LOCATION -> {
            val rl = IntArray(2); root.getLocationInWindow(rl)
            val pl = IntArray(2); f.panel.getLocationInWindow(pl)
            val px = e.x + rl[0] - pl[0]; val py = e.y + rl[1] - pl[1]
            val slack = dp(20)
            if (px < -slack || py < -slack || px > f.panel.width + slack || py > f.panel.height + slack) { folderLeave(f); return false }
            val gl = IntArray(2); f.grid.getLocationInWindow(gl)
            val gx = e.x + rl[0] - gl[0]; val gy = e.y + rl[1] - gl[1]
            val edge = f.grid.width * 0.1f
            val dir = when { gx < edge && f.grid.page > 0 -> -1; gx > f.grid.width - edge && f.grid.page < f.grid.pages - 1 -> 1; else -> 0 }
            if (dir == 0) { f.flip?.let { main.removeCallbacks(it) }; f.flip = null; f.grid.move(f.view, f.grid.indexAt(gx, gy)) }
            else if (f.flip == null) f.flip = Runnable { f.flip = null; if (folderDrag === f) f.grid.snapTo(f.grid.page + dir) }.also { main.postDelayed(it, 550) }
            return true
        }
        DragEvent.ACTION_DROP -> {
            val m = model
            if (m != null) HomeLayout.reorderFolder(m, f.folder, f.grid.items.map { it.second })?.let { update(it) }
            return true
        }
        DragEvent.ACTION_DRAG_ENDED -> {
            f.flip?.let { main.removeCallbacks(it) }
            f.view.visibility = View.VISIBLE
            folderDrag = null
            drag = null; key = ""
            main.post { libraryBack(); render(true) }   // the folder icon on the home shows the new first apps
            return true
        }
    }
    return true
}

/** The finger left the panel: close the folder, the drag goes on as a drag out of it onto the home. */
private fun HomeActivity.folderLeave(f: FolderDrag) {
    f.flip?.let { main.removeCallbacks(it) }
    folderDrag = null
    overlayExit = null
    val o = overlay; overlay = null; o?.visibility = View.GONE; main.post { root.removeView(o) }
    homeDragChrome()
}

/**
 * iOS folder: the card grows out of the folder icon (that icon hides meanwhile) while the home blurs and dims; the
 * name fades in late. Closing runs it backwards into the icon. Without the icon (gone / detached): a plain zoom.
 */
internal fun HomeActivity.folderZoom(dim: View, card: android.view.ViewGroup, content: View, title: View, from: View?, dots: View? = null) {
    val src = from?.takeIf { it.isAttachedToWindow && it.width > 0 }
    // 280 / 220 ms like a home page swipe (180-320 ms); 420 / 340 felt slow next to it (11-09).
    // ease-in-out with a soft landing: the card is seen leaving the icon (a sharp ease-out jumped past it in a frame)
    val ease = android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)
    // Launcher3 / iOS morph: a picture of the folder icon rides on the card. Small, the card shows the icon itself
    // (its 2×2 preview, its corners); grown, the real 3×3 apps. Without it the card's own apps, shrunk, landed in a
    // different layout from the icon's preview and the icons jumped in size the frame the card was swapped for it.
    val ghost = src?.let { s ->
        val b = android.graphics.Bitmap.createBitmap(s.width, s.height, android.graphics.Bitmap.Config.ARGB_8888)
        runCatching { s.draw(android.graphics.Canvas(b)) }
        android.widget.ImageView(this).apply { setImageBitmap(b); scaleType = android.widget.ImageView.ScaleType.FIT_XY }
            // over the card's padding too: inset by it, the picture landed smaller than the icon and the icon then
            // popped back to full size the frame the card went (seen on the Poco, App Library groups)
            .also { card.addView(it, android.widget.FrameLayout.LayoutParams(-1, -1).apply { setMargins(-card.paddingLeft, -card.paddingTop, -card.paddingRight, -card.paddingBottom) }) }
    }
    val plate = card.background
    // the name under the icon on the home: fades out as the big one above the card fades in, and back
    val label = (src?.parent as? android.view.ViewGroup)?.let { p -> (0 until p.childCount).map { p.getChildAt(it) }.firstOrNull { it !== src } }
    var k0 = 1f; var x0 = 0f; var y0 = 0f
    fun toIcon(): Boolean {
        val s = src?.takeIf { it.isAttachedToWindow } ?: return false
        val a = IntArray(2); s.getLocationInWindow(a)
        val b = IntArray(2); card.getLocationInWindow(b)
        // card's own position without its current transform
        x0 = a[0] - (b[0] - card.translationX); y0 = a[1] - (b[1] - card.translationY); k0 = s.width.toFloat() / card.width
        return true
    }
    /** f = 0: the card is the icon; f = 1: the open folder. Everything follows one value, so nothing drifts apart. */
    fun at(f: Float) {
        card.translationX = x0 * (1 - f); card.translationY = y0 * (1 - f)
        val k = k0 + (1 - k0) * f; card.scaleX = k; card.scaleY = k
        // icon picture out over the first 40 %, apps in from 15 to 60 %: a crossfade, not a pop
        val g = (1 - f / 0.4f).coerceIn(0f, 1f)
        ghost?.alpha = g; plate?.alpha = (255 * (1 - g)).toInt()
        content.alpha = ((f - 0.15f) / 0.45f).coerceIn(0f, 1f)
        dots?.alpha = ((f - 0.3f) / 0.5f).coerceIn(0f, 1f)   // the page dots come and go with the apps, not at once
        dim.alpha = f
        title.alpha = ((f - 0.15f) / 0.7f).coerceIn(0f, 1f)
        label?.alpha = (1 - f / 0.6f).coerceIn(0f, 1f)
    }
    var run: android.animation.ValueAnimator? = null
    fun go(from: Float, to: Float, ms: Long, end: () -> Unit) {
        run?.cancel()
        // the apps fade as one GPU layer (alpha on a view group re-drew all its icons offscreen every frame: Oppo A5s jank)
        content.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        run = android.animation.ValueAnimator.ofFloat(from, to).apply {
            duration = ms; interpolator = ease
            addUpdateListener { at(it.animatedValue as Float) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                var cancelled = false
                override fun onAnimationCancel(a: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(a: android.animation.Animator) { if (!cancelled) { content.setLayerType(View.LAYER_TYPE_NONE, null); end() } }
            })
            start()
        }
    }
    var f = 0f
    var started = false; var closing = false
    dim.alpha = 0f; title.alpha = 0f; content.alpha = 0f; dots?.alpha = 0f; ghost?.alpha = 1f; plate?.alpha = 0
    card.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
        override fun onPreDraw(): Boolean {
            card.viewTreeObserver.removeOnPreDrawListener(this)
            if (closing) return true     // closed before its first frame: never open (it would cancel the close)
            started = true
            card.pivotX = 0f; card.pivotY = 0f
            // the picture covers the padding too: unclipped, else its rounded corners were cut square
            if (toIcon()) { src?.visibility = View.INVISIBLE; card.clipToPadding = false }
            else { ghost?.visibility = View.GONE; k0 = 0.85f; x0 = card.width * 0.075f; y0 = card.height * 0.075f }
            at(0f)
            go(0f, 1f, 280) { f = 1f; ghost?.visibility = View.GONE; card.clipToPadding = true }
            run?.addUpdateListener { f = it.animatedValue as Float }
            return true
        }
    })
    overlayExit = { done ->
        closing = true
        if (!started) done()   // Back / Home within the first frame: nothing moved yet, nothing hidden
        else {
        val cur = (run?.animatedValue as? Float) ?: f
        ghost?.visibility = View.VISIBLE; card.clipToPadding = false
        if (!toIcon()) { ghost?.visibility = View.GONE; k0 = 0.85f; x0 = card.width * 0.075f; y0 = card.height * 0.075f }
        // the icon shows again the same frame the card (now a picture of it) goes: no gap, no size jump
        go(cur, 0f, 220) { src?.visibility = View.VISIBLE; label?.alpha = 1f; done() }
        }
    }
}
