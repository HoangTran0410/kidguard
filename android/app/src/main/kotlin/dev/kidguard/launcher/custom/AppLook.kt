package dev.kidguard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import org.json.JSONObject

/** One app's overrides: a label ("" = the real one) and an icon spec ("" = the original; see [AppLook.parseSpec]). */
data class AppCustom(val label: String = "", val icon: String = "") {
    val empty get() = label.isBlank() && icon.isEmpty()
}

/** A parsed icon spec: "g:<glyph>:#RRGGBB" (a Lucide glyph on a coloured plate) or "e:<emoji>:#RRGGBB". */
data class IconSpec(val glyph: Boolean, val value: String, val color: Int)

/**
 * Per-app rename + custom icon (kept in HomeModel.custom, keyed by package; "pkg/activity" also works). The parse /
 * serialize / label matching are pure (unit-tested); [set] is called by HomeFile on every load / save so the lookups
 * ([label], [iconKey], [drawable]) always see the saved model. The icon cache key includes [iconKey].
 */
object AppLook {
    const val MAX_LABEL = 40
    const val MAX_ENTRIES = 600
    @Volatile private var map: Map<String, AppCustom> = emptyMap()

    /** Goes up whenever the overrides change: the launcher's cached app list (labels) is rebuilt when it differs. */
    @Volatile var version = 0; private set

    fun set(m: Map<String, AppCustom>) { if (m != map) { map = m; version++ } }

    /** Pinned shortcuts (cls = "sc:...") keep their own label and icon. */
    fun get(pkg: String, cls: String): AppCustom? =
        if (cls.startsWith(Shortcuts.PREFIX)) null else (map["$pkg/$cls"] ?: map[pkg])

    fun label(pkg: String, cls: String, real: String): String = get(pkg, cls)?.label?.takeIf { it.isNotBlank() } ?: real

    /** Part of the icon cache key: "" for the original icon. */
    fun iconKey(pkg: String, cls: String): String = get(pkg, cls)?.icon?.takeIf { it.isNotEmpty() }?.let { "#$it" } ?: ""

    /** Search: [query] matches the custom label or the real one. */
    fun matches(custom: String, real: String, query: String): Boolean = LauncherLogic.matches(custom, query) || LauncherLogic.matches(real, query)

    // ---------------- model ----------------

    fun parse(o: JSONObject?): Map<String, AppCustom> {
        if (o == null) return emptyMap()
        val out = LinkedHashMap<String, AppCustom>()
        for (k in o.keys()) {
            if (out.size >= MAX_ENTRIES) break
            val e = o.optJSONObject(k) ?: continue
            val c = AppCustom(e.optString("l", "").trim().take(MAX_LABEL), e.optString("i", "").takeIf { parseSpec(it) != null } ?: "")
            if (k.isNotEmpty() && !c.empty) out[k] = c
        }
        return out
    }

    fun toJson(m: Map<String, AppCustom>): JSONObject = JSONObject().also { o ->
        for ((k, c) in m) if (!c.empty) o.put(k, JSONObject().apply { if (c.label.isNotBlank()) put("l", c.label); if (c.icon.isNotEmpty()) put("i", c.icon) })
    }

    /** [m] with [pkg] set to [c]; an empty [c] removes it ("Khôi phục"). */
    fun with(m: Map<String, AppCustom>, pkg: String, c: AppCustom): Map<String, AppCustom> =
        LinkedHashMap(m).also { if (c.empty) it.remove(pkg) else it[pkg] = c.copy(label = c.label.trim().take(MAX_LABEL)) }

    // ---------------- icon spec ----------------

    /** Lucide glyphs on offer (static ids: resource shrinking would drop drawables looked up by name). */
    val GLYPH_RES: Map<String, Int> = linkedMapOf(
        "phone" to R.drawable.li_phone, "message_circle" to R.drawable.li_message_circle, "mail" to R.drawable.li_mail,
        "calendar" to R.drawable.li_calendar, "clock" to R.drawable.li_clock, "timer" to R.drawable.li_timer,
        "map_pin" to R.drawable.li_map_pin, "car" to R.drawable.li_car, "plane" to R.drawable.li_plane,
        "globe" to R.drawable.li_globe, "house" to R.drawable.li_house, "folder" to R.drawable.li_folder,
        "package" to R.drawable.li_package, "sun" to R.drawable.li_sun, "moon" to R.drawable.li_moon,
        "bell" to R.drawable.li_bell, "mic" to R.drawable.li_mic, "users" to R.drawable.li_users,
        "thumbs_up" to R.drawable.li_thumbs_up, "key" to R.drawable.li_key, "shield" to R.drawable.li_shield,
        "wifi" to R.drawable.li_wifi, "send" to R.drawable.li_send, "play" to R.drawable.li_play,
        // 2026-11-10: more (owner: "too few"); shown in horizontal pages of 18, built page by page
        "book_open" to R.drawable.li_book_open, "graduation_cap" to R.drawable.li_graduation_cap, "pencil" to R.drawable.li_pencil,
        "calculator" to R.drawable.li_calculator, "music" to R.drawable.li_music, "gamepad_2" to R.drawable.li_gamepad_2,
        "camera" to R.drawable.li_camera, "image" to R.drawable.li_image, "video" to R.drawable.li_video,
        "tv" to R.drawable.li_tv, "headphones" to R.drawable.li_headphones, "heart" to R.drawable.li_heart,
        "star" to R.drawable.li_star, "smile" to R.drawable.li_smile, "gift" to R.drawable.li_gift,
        "shopping_cart" to R.drawable.li_shopping_cart, "wallet" to R.drawable.li_wallet, "banknote" to R.drawable.li_banknote,
        "credit_card" to R.drawable.li_credit_card, "bike" to R.drawable.li_bike, "bus" to R.drawable.li_bus,
        "train_front" to R.drawable.li_train_front, "ship" to R.drawable.li_ship, "rocket" to R.drawable.li_rocket,
        "palette" to R.drawable.li_palette, "brush" to R.drawable.li_brush, "puzzle" to R.drawable.li_puzzle,
        "trophy" to R.drawable.li_trophy, "medal" to R.drawable.li_medal, "dumbbell" to R.drawable.li_dumbbell,
        "apple" to R.drawable.li_apple, "pizza" to R.drawable.li_pizza, "coffee" to R.drawable.li_coffee,
        "utensils" to R.drawable.li_utensils, "cake" to R.drawable.li_cake, "cloud" to R.drawable.li_cloud,
        "umbrella" to R.drawable.li_umbrella, "flower" to R.drawable.li_flower, "leaf" to R.drawable.li_leaf,
        "tree_pine" to R.drawable.li_tree_pine, "dog" to R.drawable.li_dog, "cat" to R.drawable.li_cat,
        "fish" to R.drawable.li_fish, "bird" to R.drawable.li_bird, "rabbit" to R.drawable.li_rabbit,
        "paw_print" to R.drawable.li_paw_print, "baby" to R.drawable.li_baby, "user" to R.drawable.li_user,
        "smartphone" to R.drawable.li_smartphone, "laptop" to R.drawable.li_laptop, "monitor" to R.drawable.li_monitor,
        "book" to R.drawable.li_book, "library" to R.drawable.li_library, "languages" to R.drawable.li_languages,
        "notebook_pen" to R.drawable.li_notebook_pen, "ruler" to R.drawable.li_ruler, "atom" to R.drawable.li_atom,
        "flask_conical" to R.drawable.li_flask_conical, "microscope" to R.drawable.li_microscope, "map" to R.drawable.li_map,
        "compass" to R.drawable.li_compass, "search" to R.drawable.li_search, "settings" to R.drawable.li_settings,
        "lock" to R.drawable.li_lock, "dice_5" to R.drawable.li_dice_5, "film" to R.drawable.li_film,
        "clapperboard" to R.drawable.li_clapperboard, "radio" to R.drawable.li_radio, "newspaper" to R.drawable.li_newspaper,
        "flag" to R.drawable.li_flag, "zap" to R.drawable.li_zap, "flame" to R.drawable.li_flame,
        "snowflake" to R.drawable.li_snowflake, "crown" to R.drawable.li_crown, "ghost" to R.drawable.li_ghost,
        "bot" to R.drawable.li_bot, "sparkles" to R.drawable.li_sparkles, "rainbow" to R.drawable.li_rainbow,
        "shirt" to R.drawable.li_shirt, "store" to R.drawable.li_store, "stethoscope" to R.drawable.li_stethoscope,
        "pill" to R.drawable.li_pill)
    val GLYPHS: List<String> get() = GLYPH_RES.keys.toList()
    val COLORS = listOf(0xFFE5484D, 0xFFF76B15, 0xFFF5A524, 0xFF30A46C, 0xFF12A594, 0xFF0091FF, 0xFF3E63DD, 0xFF8E4EC6, 0xFFE93D82, 0xFF4B5563).map { it.toInt() }
    val EMOJIS = listOf("😀", "😂", "🥰", "😎", "🤩", "🤓", "😇", "🥳", "😴", "🤔", "🤖", "👻", "👽", "💩", "🐶", "🐱", "🐭", "🐰", "🦊", "🐻", "🐼", "🐨", "🐯", "🦁", "🐸", "🐵", "🐔", "🐧", "🦄", "🐝", "🦋", "🐢", "🐙", "🐬", "🐳", "🦖", "🌸", "🌻", "🌈", "⭐", "🌙", "☀️", "⚡", "🔥", "❄️", "🌊", "🍎", "🍌", "🍉", "🍓", "🍕", "🍔", "🍟", "🍦", "🍩", "🍪", "🎂", "🍭", "⚽", "🏀", "🏈", "🎾", "🏓", "🏊", "🚴", "🎮", "🎲", "🧩", "🎨", "🎵", "🎸", "🎹", "🎧", "🎬", "📷", "📺", "📚", "📖", "✏️", "🖍️", "📐", "🔬", "🧪", "🌍", "🚀", "✈️", "🚗", "🚌", "🚲", "🏠", "🏫", "🎁", "🎈", "🏆", "💎", "👑", "💰", "❤️", "💙", "💚", "💛", "💜")

    /** Quick picks in the colour picker (6 x 4): mid tones, so the white glyph on top stays readable. */
    val PRESETS = listOf(0xFFE5484D, 0xFFDC2626, 0xFFF76B15, 0xFFF59E0B, 0xFFF5A524, 0xFFCA8A04,
        0xFF84CC16, 0xFF30A46C, 0xFF16A34A, 0xFF12A594, 0xFF0891B2, 0xFF0091FF,
        0xFF2563EB, 0xFF3E63DD, 0xFF6366F1, 0xFF8E4EC6, 0xFFA855F7, 0xFFE93D82,
        0xFFDB2777, 0xFF92400E, 0xFF78716C, 0xFF4B5563, 0xFF1F2937, 0xFF0F766E).map { it.toInt() }
    /** The glyph colour on plate [c]: white, or near-black on light plates (yellow, lime, custom pastels) so it stays readable. */
    fun ink(c: Int): Int = if (luma(c) > 0.6) 0xFF1F2937.toInt() else Color.WHITE
    private fun luma(c: Int): Double = (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) / 255.0
    fun hex(c: Int) = String.format("#%06X", c and 0xFFFFFF)
    fun glyphSpec(name: String, color: Int) = "g:$name:${hex(color)}"
    /** The first grapheme of [s] when it is an emoji (a symbol / pictograph, not a letter or digit), else null. */
    fun firstEmoji(s: String): String? {
        val t = s.trim(); if (t.isEmpty()) return null
        val g = t.substring(0, emojiEnd(t))
        val cp = g.codePointAt(0)
        val type = Character.getType(cp)
        val emoji = cp >= 0x1F000 || type == Character.OTHER_SYMBOL.toInt() || type == Character.SURROGATE.toInt() || cp in 0x2600..0x27BF
        return if (emoji && g.length <= 16 && ':' !in g) g else null
    }

    /**
     * End of the first emoji cluster: base + variation selector / skin tone / keycap / tags, ZWJ-joined parts, flag pairs.
     * Hand-rolled: BreakIterator splits ZWJ sequences on old ICU (Android 8) and on the JVM before 20.
     */
    private fun emojiEnd(t: String): Int {
        var i = Character.charCount(t.codePointAt(0))
        val first = t.codePointAt(0)
        if (first in 0x1F1E6..0x1F1FF && i < t.length && t.codePointAt(i) in 0x1F1E6..0x1F1FF) return i + 2   // flag
        while (i < t.length) {
            val c = t.codePointAt(i)
            when {
                c == 0xFE0F || c == 0xFE0E || c in 0x1F3FB..0x1F3FF || c == 0x20E3 || c in 0xE0020..0xE007F -> i += Character.charCount(c)
                c == 0x200D && i + 1 < t.length -> { i += 1; i += Character.charCount(t.codePointAt(i)) }
                else -> return i
            }
        }
        return i
    }

    fun emojiSpec(e: String, color: Int) = "e:$e:${hex(color)}"

    fun parseSpec(s: String): IconSpec? {
        val p = s.split(':')
        if (p.size != 3 || p[2].length != 7 || p[2][0] != '#' || p[2].drop(1).any { !it.isDigit() && it.uppercaseChar() !in 'A'..'F' }) return null
        val color = (0xFF000000L or p[2].drop(1).toLong(16)).toInt()
        return when (p[0]) {
            "g" -> if (p[1] in GLYPH_RES) IconSpec(true, p[1], color) else null
            "e" -> if (p[1].isNotEmpty() && p[1].length <= 16 &&   // 16: ZWJ sequences / skin tones from the keyboard (👩🏽‍💻 = 7)
                 p[1].none { it.isWhitespace() }) IconSpec(false, p[1], color) else null
            else -> null
        }
    }

    /** A full-bleed square for the icon cache to mask (circle / squircle), or null for the original icon. */
    fun drawable(ctx: Context, pkg: String, cls: String, px: Int): Drawable? {
        val spec = get(pkg, cls)?.icon?.takeIf { it.isNotEmpty() }?.let { parseSpec(it) } ?: return null
        return draw(ctx, spec, px)
    }

    fun draw(ctx: Context, spec: IconSpec, px: Int): Drawable {
        val b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(spec.color)
        if (spec.glyph) {
            val g = (px * 0.56f).toInt(); val o = (px - g) / 2
            GLYPH_RES[spec.value]?.let { id -> ctx.getDrawable(id)?.mutate()?.apply { setTint(ink(spec.color)); setBounds(o, o, o + g, o + g); draw(c) } }
        } else {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = px * 0.56f; textAlign = Paint.Align.CENTER }
            val fm = p.fontMetrics
            c.drawText(spec.value, px / 2f, px / 2f - (fm.ascent + fm.descent) / 2f, p)
        }
        return BitmapDrawable(ctx.resources, b)
    }
}
