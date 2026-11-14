package dev.kidguard

import android.app.Activity
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

/**
 * Reading aids of the kid launcher (Tuỳ chỉnh > "Dễ đọc"): the "easy" font's letter spacing, solid label backing,
 * "Đọc to" (TextToSpeech, Vietnamese voice when the engine has one, else its default) and "Giảm chuyển động".
 * The decisions are pure (unit-tested); the Android parts are small and wrapped in runCatching (a phone without a
 * TTS engine just stays silent).
 */
object ReadAids {
    /** Reduced motion: the style switch, or the system's animator scale is 0 (Developer options / Remove animations). */
    fun reduce(calm: Boolean, systemScale: Float) = calm || systemScale <= 0f

    /** TextToSpeech.isLanguageAvailable: LANG_AVAILABLE (0), COUNTRY_AVAILABLE (1), COUNTRY_VAR_AVAILABLE (2) mean a voice exists. */
    fun voiceOk(availability: Int) = availability >= 0

    /** Extra letter spacing (em) for the "easy" face, 0 for the others. */
    fun spacing(face: String) = if (face == "easy") 0.06f else 0f

    /** Label backing: dark enough for white text on any wallpaper (WCAG AA over a light one: 0.8 alpha black). */
    const val BACKING = 0x9E000000.toInt()   // white text on it over a white wallpaper ~6:1 (WCAG AA); 0xCC read as heavy black bars

    fun systemScale(ctx: Context): Float = runCatching { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }.getOrDefault(1f)

    private var scaleSet = -1f
    /** The launcher's own animations off (ValueAnimator's process-wide scale, which also covers View.animate()); back to the system's value when off. */
    fun applyMotion(ctx: Context, st: HStyle?) {
        val sys = systemScale(ctx)
        val want = if (reduce(st?.calm == true, sys)) 0f else sys
        if (want == scaleSet) return
        runCatching { android.animation.ValueAnimator::class.java.getMethod("setDurationScale", Float::class.javaPrimitiveType).invoke(null, want); scaleSet = want }
    }

    /**
     * Solid label backing for grid / dock names: white text on a dark pill that hugs the text (iOS "Increase contrast" /
     * Pixel "High contrast text" style). The label must be wrap_content (see HomeActivity.addLabel): a full-cell-width
     * backing read as black bars.
     */
    fun backLabel(t: TextView) {
        val d = t.resources.displayMetrics.density
        t.setTextColor(0xFFFFFFFF.toInt()); t.setShadowLayer(0f, 0f, 0f, 0)
        t.background = GradientDrawable().apply { setColor(BACKING); cornerRadius = 10 * d }
        t.setPadding((7 * d).toInt(), (1 * d).toInt(), (7 * d).toInt(), (2 * d).toInt())
    }

    // ---------------- Đọc to ----------------

    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val idle = Runnable { runCatching { tts?.shutdown() }; tts = null; ready = false }

    fun say(ctx: Context, text: String) {
        if (text.isBlank()) return
        main.removeCallbacks(idle); main.postDelayed(idle, 90_000)
        if (ready && tts != null) { speakNow(text); return }
        pending = text
        if (tts != null) return
        runCatching {
            lateinit var t: TextToSpeech
            t = TextToSpeech(ctx.applicationContext) { status ->
                if (status != TextToSpeech.SUCCESS) {   // no speech engine on this phone: say so, a silent button looks broken
                    tts = null; ready = false; pending = null
                    main.post { Toasts.show(ctx.applicationContext, ctx.getString(R.string.hm2_read_none), true) }; return@TextToSpeech
                }
                runCatching { if (voiceOk(t.isLanguageAvailable(Locale("vi", "VN")))) t.language = Locale("vi", "VN") }
                ready = true
                pending?.let { speakNow(it) }; pending = null
            }
            tts = t
        }.onFailure { tts = null }
    }

    private fun speakNow(text: String) { runCatching { tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "kg-read") } }

    /** Every visible text under [v], top to bottom (the Today page's cards). */
    fun textOf(v: View): String {
        val out = ArrayList<String>()
        fun walk(x: View) {
            if (x.visibility != View.VISIBLE) return
            if (x is TextView && x.text.isNotBlank() && x.javaClass == TextView::class.java) out += x.text.toString().trim()
            if (x is ViewGroup) for (i in 0 until x.childCount) walk(x.getChildAt(i))
        }
        walk(v)
        return out.joinToString(". ")
    }

    /** Today page: a "Đọc to" chip after the header, reading the cards below it. Only when the option is on. */
    fun todayButton(act: Activity, k: HomeKit, col: LinearLayout, on: Boolean) {
        if (!on || col.childCount == 0) return
        val chip = k.text(act.getString(R.string.hm2_read_page), 14f, k.pal.accentText, true).apply {
            setPadding(dp(act, 12), dp(act, 10), dp(act, 12), dp(act, 10)); gravity = android.view.Gravity.CENTER_VERTICAL
            Ic.lead(this, R.drawable.li_volume_2, k.pal.accentText); compoundDrawablePadding = dp(act, 8)
            contentDescription = act.getString(R.string.hm2_read_page)
        }
        k.tap(chip, 12f) {
            val rest = StringBuilder()
            for (i in 0 until col.childCount) { val c = col.getChildAt(i); if (c !== chip) rest.append(textOf(c)).append(". ") }
            say(act, rest.toString())
        }
        col.addView(chip, 1, LinearLayout.LayoutParams(-2, -2).apply { gravity = android.view.Gravity.END; rightMargin = dp(act, 4) })
    }

    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()
}
