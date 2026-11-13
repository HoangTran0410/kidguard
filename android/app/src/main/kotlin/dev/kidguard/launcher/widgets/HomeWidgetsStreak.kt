package dev.kidguard

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.TimeZone

// "Chuỗi ngày" widget: streak of days the kid finished the daily list (all homework ticked + all chores sent), best streak and
// badges (3 / 7 / 14 / 30). Pure maths: StreakLogic.kt. The day map lives in device-protected prefs "kg_widgets2" (phone only).
// A day counts when the list goes from "not all done" to "all done" while the widget renders (a list left ticked from
// yesterday never counts), so the home page must have been built after the last tick: update() / resume do that.

internal object StreakStore {
    private fun prefs(c: Context) = c.createDeviceProtectedStorageContext().getSharedPreferences("kg_widgets2", Context.MODE_PRIVATE)

    class Snap(val cur: Int, val best: Int, val todayDone: Boolean, val celebrate: Int?, val prevAfter: Int)

    fun today(now: Long = System.currentTimeMillis()) = StreakLogic.dayOf(now, TimeZone.getDefault().getOffset(now).toLong())

    /** Records the list state seen now and returns what to show. [complete] = every task of today's list is done. */
    fun observe(c: Context, complete: Boolean, now: Long = System.currentTimeMillis()): Snap {
        val p = prefs(c)
        val day = today(now)
        var days = (p.getString("days", "") ?: "").split(',').mapNotNull { it.toLongOrNull() }.toSet()
        if (StreakLogic.completes(p.getBoolean("all", complete), complete)) days = StreakLogic.mark(days, day)
        val bestEver = maxOf(p.getInt("best", 0), StreakLogic.best(days))
        val cur = StreakLogic.current(days, day)
        p.edit().putString("days", days.sorted().joinToString(",")).putBoolean("all", complete).putInt("best", bestEver).apply()
        val (cel, prev) = StreakLogic.celebrate(cur, p.getInt("prev", 0))
        if (cel == null && prev != p.getInt("prev", 0)) p.edit().putInt("prev", prev).apply()
        return Snap(cur, bestEver, day in days, cel, prev)
    }

    fun days(c: Context): Set<Long> = (prefs(c).getString("days", "") ?: "").split(',').mapNotNull { it.toLongOrNull() }.toSet()

    /** The celebration is shown: remember it, so a later render does not repeat it. */
    fun celebrated(c: Context, badge: Int) { prefs(c).edit().putInt("prev", badge).apply() }
}

/** Short burst of falling paper bits, once; drawn only while it runs (no per-frame work afterwards). */
internal class ConfettiView(c: Context, private val colors: IntArray, private val onStart: () -> Unit) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val n = 26
    private val rnd = java.util.Random(7)
    private val x = FloatArray(n) { rnd.nextFloat() }
    private val vx = FloatArray(n) { (rnd.nextFloat() - 0.5f) * 0.5f }
    private val vy = FloatArray(n) { 0.5f + rnd.nextFloat() * 0.8f }
    private val sz = FloatArray(n) { 0.8f + rnd.nextFloat() }
    private var t = 1f
    private var anim: ValueAnimator? = null

    /** The system's "remove animations" (animator scale 0) turns the burst off; the badge text still shows. */
    private fun reduced() = runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        onStart()
        if (reduced()) return
        anim = ValueAnimator.ofFloat(0f, 1f).apply { duration = 1500; addUpdateListener { t = it.animatedValue as Float; invalidate() }; start() }
    }
    override fun onDetachedFromWindow() { anim?.cancel(); anim = null; super.onDetachedFromWindow() }

    override fun onDraw(cv: Canvas) {
        if (t >= 1f) return
        val d = resources.displayMetrics.density
        for (i in 0 until n) {
            val px = (x[i] + vx[i] * t) * width
            val py = -0.1f * height + vy[i] * t * height * 1.3f
            p.color = colors[i % colors.size]; p.alpha = (255 * (1f - t * t)).toInt()
            cv.save(); cv.rotate(360f * t * (if (i % 2 == 0) 1 else -1), px, py)
            cv.drawRect(px, py, px + 6 * d * sz[i], py + 3 * d * sz[i], p); cv.restore()
        }
    }
}

internal fun HomeActivity.streakWidget(m: HomeModel, r: Rules, s: Store, w: Int, h: Int): View {
    val todos = KidNotes.todos(m.todo)
    val chores = if (Flavor.HOME) emptyList() else r.chores
    val waiting = HashSet<String>()
    if (chores.isNotEmpty()) s.requests().let { rq -> for (i in 0 until rq.length()) rq.optJSONObject(i)?.optString("chore")?.takeIf { it.isNotEmpty() }?.let { waiting += it } }
    val complete = (todos.isNotEmpty() || chores.isNotEmpty()) && todos.all { it.done } && chores.all { it.id in waiting }
    val snap = StreakStore.observe(this, complete)

    val card = wCard()
    val col = LinearLayout(this).apply { orientation = if (w >= 4) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
    val wide = w >= 4
    // never wider than the row: a narrow card (icon-aligned 2x2) shrinks the circles, it never cuts "30"
    val bMax = dp(if (wide) 34 else 30); val bGap = dp(4)
    fun badges(): View = object : LinearLayout(this) {
        override fun onMeasure(wm: Int, hm: Int) {
            val n = childCount
            if (n > 0 && MeasureSpec.getMode(wm) != MeasureSpec.UNSPECIFIED) {
                val sz = minOf(bMax, (MeasureSpec.getSize(wm) - bGap * n) / n).coerceAtLeast(dp(16))
                for (i in 0 until n) getChildAt(i).layoutParams.let { if (it.width != sz) { it.width = sz; it.height = sz } }
            }
            super.onMeasure(wm, hm)
        }
    }.apply {
        orientation = LinearLayout.HORIZONTAL
        val best = snap.best
        for (b in StreakLogic.BADGES) {
            val got = b <= best
            addView(TextView(this@streakWidget).apply {
                text = "$b"; textSize = WType.CAPTION; typeface = android.graphics.Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; includeFontPadding = false
                setTextColor(if (got) pal.onSolid else pal.sub)
                background = if (got) fill(pal.solid, 999f) else GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0); setStroke(dp(2), pal.sub) }
                alpha = if (got) 1f else 0.7f
                contentDescription = getString(if (got) R.string.sk_badge_got else R.string.sk_badge_locked, b)
            }, LinearLayout.LayoutParams(bMax, bMax).apply { rightMargin = bGap })
        }
    }
    val head = wHead(R.drawable.li_circle_check, getString(R.string.hm_w_streak))
    val big = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
    big.addView(text("${snap.cur}", WType.HERO, pal.accentText, true))
    big.addView(text(getString(R.string.sk_days), WType.BODY, pal.sub).apply { setPadding(dp(5), 0, 0, dp(5)) })
    val celebrated = snap.celebrate
    val status = when {
        celebrated != null -> getString(R.string.sk_new_badge, celebrated)
        complete -> getString(R.string.sk_today_done)
        todos.isEmpty() && chores.isEmpty() -> getString(R.string.sk_empty)
        else -> getString(R.string.sk_today_open)
    }
    val next = StreakLogic.next(snap.cur)
    val sub = getString(R.string.sk_best, snap.best)
    val toNext = next?.let { getString(R.string.sk_next, it - snap.cur, it) }
    val statusV = text(status, WType.CAPTION, if (celebrated != null || complete) pal.accentText else pal.sub, celebrated != null).apply { maxLines = 2 }
    if (wide) {
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(head); left.addView(big)
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        right.addView(badges()); right.addView(wFit(sub + (toNext?.let { " · $it" } ?: ""), WType.CAPTION, pal.sub).apply { setPadding(0, dp(6), 0, 0) }); right.addView(statusV)
        col.addView(left, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(16) }); col.addView(right, LinearLayout.LayoutParams(0, -2, 1f))
    } else {
        col.addView(head); col.addView(big)
        col.addView(wSub(getString(R.string.sk_best, snap.best)))
        col.addView(badges(), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
        col.addView(statusV.apply { setPadding(0, dp(4), 0, 0) })
    }
    val frame = FrameLayout(this)
    frame.addView(col, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL))
    if (celebrated != null) frame.addView(ConfettiView(this, intArrayOf(pal.solid, pal.warn, pal.accentText, pal.danger, pal.tint)) { StreakStore.celebrated(this, celebrated) },
        FrameLayout.LayoutParams(-1, -1))
    card.addView(frame, LinearLayout.LayoutParams(-1, -2))
    card.contentDescription = getString(R.string.hm_w_streak) + ": " + getString(R.string.sk_cd, snap.cur, snap.best)
    kit.tap(card, 22f) { if (debounce("streak")) streakSheet(snap, complete, todos.isEmpty() && chores.isEmpty()) }
    return centred(card)
}

/**
 * Tap on the streak widget: the streak itself (was the "Hôm nay" page, which has no streak on it). Current + best, the
 * 4 badges with what is left to the next, the last 4 weeks as dots, how a day counts, and a way to today's list.
 */
internal fun HomeActivity.streakSheet(snap: StreakStore.Snap, complete: Boolean, empty: Boolean) {
    val sh = Sheet(this, u, getString(R.string.hm_w_streak))
    val body = sh.body
    val big = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM; setPadding(dp(12), dp(4), dp(12), 0) }
    big.addView(text("${snap.cur}", WType.HERO, pal.accentText, true))
    big.addView(text(getString(R.string.sk_days), WType.BODY, pal.sub).apply { setPadding(dp(6), 0, 0, dp(8)) })
    body.addView(big)
    val next = StreakLogic.next(snap.cur)
    body.addView(text(getString(R.string.sk_best, snap.best) + (next?.let { "\n" + getString(R.string.sk_next, it - snap.cur, it) } ?: ""), WType.BODY, pal.sub)
        .apply { setPadding(dp(12), 0, dp(12), dp(8)) })
    // badges: a filled circle when reached, a ring when not, the day count under each
    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(4), dp(8), dp(4), dp(8)) }
    for (b in StreakLogic.BADGES) {
        val got = b <= snap.best
        val cell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        cell.addView(TextView(this).apply {
            text = "$b"; textSize = WType.VALUE; typeface = android.graphics.Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; includeFontPadding = false
            setTextColor(if (got) pal.onSolid else pal.sub)
            background = if (got) fill(pal.solid, 999f) else GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0); setStroke(dp(2), pal.sub) }
            contentDescription = getString(if (got) R.string.sk_badge_got else R.string.sk_badge_locked, b)
        }, LinearLayout.LayoutParams(dp(52), dp(52)))
        cell.addView(text(getString(R.string.sk_badge_n, b), WType.CAPTION, if (got) pal.text else pal.sub).apply { gravity = Gravity.CENTER; setPadding(0, dp(4), 0, 0) })
        row.addView(cell, LinearLayout.LayoutParams(0, -2, 1f))
    }
    body.addView(row)
    // last 28 days, oldest first, 7 per row: filled = done, ring = today (still open), faint = missed
    body.addView(text(getString(R.string.sk_last4w).uppercase(), WType.CAPTION, pal.sub, true).apply { letterSpacing = 0.08f; setPadding(dp(12), dp(12), dp(12), dp(6)) })
    val done = StreakStore.days(this); val today = StreakStore.today()
    val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), 0, dp(8), 0) }
    for (w in 0 until 4) {
        val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (d in 0 until 7) {
            val day = today - 27 + w * 7 + d
            val isDone = day in done
            val cell = FrameLayout(this)
            cell.addView(View(this).apply {
                background = when {
                    isDone -> fill(pal.solid, 999f)
                    day == today -> GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0); setStroke(dp(2), pal.accentText) }
                    else -> fill((pal.sub and 0x00ffffff) or 0x33000000, 999f)
                }
            }, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
            line.addView(cell, LinearLayout.LayoutParams(0, dp(34), 1f))
        }
        grid.addView(line)
    }
    body.addView(grid)
    body.addView(text(getString(when { empty -> R.string.sk_empty; complete -> R.string.sk_today_done; else -> R.string.sk_today_open }), WType.BODY, pal.text, true)
        .apply { setPadding(dp(12), dp(16), dp(12), dp(2)) })
    body.addView(text(getString(R.string.sk_how), WType.BODY, pal.sub).apply { setPadding(dp(12), 0, dp(12), dp(12)) })
    body.addView(kit.pill(getString(R.string.sk_open_list), R.drawable.li_list_checks, true) { sh.dismiss(); pager.snapTo(0) })
    sh.show()
}

/** Picker mock of the real widget: "7 ngày" big + the 4 badge circles with their numbers (3 and 7 earned). Every child
 *  wrap-sized and centred: with the default match-width the "7" sat on the left and the dots were cut off. */
internal fun HomeActivity.streakPreview(c: LinearLayout) {
    val big = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
    big.addView(text("7", 24f, pal.accentText, true).apply { includeFontPadding = false })
    big.addView(text(getString(R.string.sk_days), 10f, pal.sub).apply { includeFontPadding = false; setPadding(dp(3), 0, 0, dp(2)) })
    c.addView(big, LinearLayout.LayoutParams(-2, -2))
    val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
    for ((i, b) in StreakLogic.BADGES.withIndex()) {
        val got = i < 2
        r.addView(TextView(this).apply {
            text = "$b"; textSize = 8f; typeface = android.graphics.Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; includeFontPadding = false
            setTextColor(if (got) pal.onSolid else pal.sub)
            background = if (got) fill(pal.solid, 999f) else GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0); setStroke(dp(1), pal.sub) }
        }, LinearLayout.LayoutParams(dp(18), dp(18)).apply { setMargins(dp(2), 0, dp(2), 0) })
    }
    c.addView(r, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
}
