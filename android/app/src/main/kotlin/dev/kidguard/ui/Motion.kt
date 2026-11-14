package dev.kidguard

import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.Interpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator

/**
 * One place for the kid home's motion: how long things take and how they move. Pick a token, don't type a number.
 *
 * Durations (Material 3 / Pixel launcher ranges, the 11-09 perf review: 180-320 ms reads as "fast but seen"):
 *  - [PRESS] 110: finger down, an icon / card sinks. Must feel instant.
 *  - [FAST]  150: things leaving (fade out, close, slide away), small snaps back.
 *  - [MED]   200: things arriving (menu, sheet, dialog, detail panel, fade in).
 *  - [SLOW]  250: big surfaces (control center, edit-mode zoom, grid reflow, release spring).
 * Tuned moves with a reason in their comment keep their own numbers (folder zoom 280/220, drag drop, drawer fling,
 * the streak flame): they are listed in docs/decisions/launcher.md, change them there.
 *
 * Easings (shared instances; interpolators are stateless):
 *  - [STD]    Material "emphasized decelerate" (0.2, 0, 0, 1): a surface moving to its place.
 *  - [DECEL]  plain ease-out: fades and scale-ins.
 *  - [SPRING] small overshoot: something let go of (icon release, slider after a hold).
 *
 * "Remove animations" in Tuỳ chỉnh (ReadAids) scales every ValueAnimator to 0, so all of this obeys it for free.
 */
internal object Motion {
    const val PRESS = 110L
    const val FAST = 150L
    const val MED = 200L
    const val SLOW = 250L

    val STD: Interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
    val DECEL: Interpolator = DecelerateInterpolator(2f)
    val SPRING: Interpolator = OvershootInterpolator(2.5f)

    /** Sinks [v] to [scale] (over [ms]: a long-press hold uses its own wait) or, scale 1, springs it back. */
    /** The scale [v] rests at: 1, or what its layout set (a widget card shrunk to fit its cells, tag [R.id.kg_rest_scale]).
     *  Press and release work around it: springing back to 1 left those cards ~8 % bigger than their cells. */
    fun rest(v: View): Float = (v.getTag(dev.kidguard.R.id.kg_rest_scale) as? Float) ?: 1f

    fun sink(v: View, scale: Float, ms: Long = PRESS) {
        v.animate().cancel()
        val s = rest(v) * scale
        v.animate().scaleX(s).scaleY(s).setDuration(ms).setInterpolator(DECEL).start()
    }

    fun release(v: View) {
        v.animate().cancel()
        val s = rest(v)
        if (v.scaleX != s || v.scaleY != s) v.animate().scaleX(s).scaleY(s).setDuration(SLOW).setInterpolator(SPRING).start()
    }

    /** Arrives at its resting state (alpha 1, scale 1, no offset) from wherever the caller put it. */
    fun enter(v: View, ms: Long = MED, ease: Interpolator = DECEL, layer: Boolean = false) {
        val a = v.animate().alpha(1f).scaleX(1f).scaleY(1f).translationX(0f).translationY(0f).setDuration(ms).setInterpolator(ease)
        if (layer) a.withLayer()
        a.start()
    }

    /** Fades to [to] (0 = gone), then [end]. */
    fun fade(v: View, to: Float, ms: Long = if (to == 0f) FAST else MED, end: (() -> Unit)? = null) {
        val a = v.animate().alpha(to).setDuration(ms).setInterpolator(DECEL)
        if (end != null) a.withEndAction(end)
        a.start()
    }
}
