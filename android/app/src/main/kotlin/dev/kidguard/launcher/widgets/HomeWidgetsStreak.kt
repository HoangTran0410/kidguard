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
    fun badges(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        val best = snap.best
        for (b in StreakLogic.BADGES) {
            val got = b <= best
            addView(TextView(this@streakWidget).apply {
                text = "$b"; textSize = 12f; typeface = android.graphics.Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; includeFontPadding = false
                setTextColor(if (got) pal.onSolid else pal.sub)
                background = if (got) fill(pal.solid, 999f) else GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0); setStroke(dp(2), pal.sub) }
                alpha = if (got) 1f else 0.7f
                contentDescription = getString(if (got) R.string.sk_badge_got else R.string.sk_badge_locked, b)
            }, LinearLayout.LayoutParams(dp(if (wide) 34 else 30), dp(if (wide) 34 else 30)).apply { rightMargin = dp(4) })
        }
    }
    val head = wHead(R.drawable.li_circle_check, getString(R.string.hm_w_streak))
    val big = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
    big.addView(text("${snap.cur}", if (wide) 40f else 34f, pal.accentText, true))
    big.addView(text(getString(R.string.sk_days), 13f, pal.sub).apply { setPadding(dp(5), 0, 0, dp(5)) })
    val celebrated = snap.celebrate
    val status = when {
        celebrated != null -> getString(R.string.sk_new_badge, celebrated)
        complete -> getString(R.string.sk_today_done)
        todos.isEmpty() && chores.isEmpty() -> getString(R.string.sk_empty)
        else -> getString(R.string.sk_today_open)
    }
    val next = StreakLogic.next(snap.cur)
    val sub = getString(R.string.sk_best, snap.best) + (next?.let { " · " + getString(R.string.sk_next, it - snap.cur, it) } ?: "")
    val statusV = text(status, 12f, if (celebrated != null || complete) pal.accentText else pal.sub, celebrated != null).apply { maxLines = 2 }
    if (wide) {
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(head); left.addView(big)
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        right.addView(badges()); right.addView(wSub(sub, 2).apply { setPadding(0, dp(6), 0, 0) }); right.addView(statusV)
        col.addView(left, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(16) }); col.addView(right, LinearLayout.LayoutParams(0, -2, 1f))
    } else {
        col.addView(head); col.addView(big)
        col.addView(wSub(getString(R.string.sk_best, snap.best)))
        col.addView(badges(), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
        col.addView(statusV.apply { setPadding(0, dp(4), 0, 0); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
    }
    val frame = FrameLayout(this)
    frame.addView(col, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL))
    if (celebrated != null) frame.addView(ConfettiView(this, intArrayOf(pal.solid, pal.warn, pal.accentText, pal.danger, pal.tint)) { StreakStore.celebrated(this, celebrated) },
        FrameLayout.LayoutParams(-1, -1))
    card.addView(frame, LinearLayout.LayoutParams(-1, -2))
    card.contentDescription = getString(R.string.hm_w_streak) + ": " + getString(R.string.sk_cd, snap.cur, snap.best)
    wOpen(card)
    return centred(card)
}

internal fun HomeActivity.streakPreview(c: LinearLayout) {
    c.addView(text("7", 22f, pal.accentText, true))
    val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
    for ((i, b) in StreakLogic.BADGES.withIndex()) r.addView(View(this).apply { background = if (i < 2) fill(pal.solid, 999f) else GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0); setStroke(dp(1), pal.sub) } },
        LinearLayout.LayoutParams(dp(9), dp(9)).apply { setMargins(dp(1), dp(3), dp(1), 0) })
    c.addView(r)
}
