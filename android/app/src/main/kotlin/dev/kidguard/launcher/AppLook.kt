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

    fun set(m: Map<String, AppCustom>) { map = m }

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
        "wifi" to R.drawable.li_wifi, "send" to R.drawable.li_send, "play" to R.drawable.li_play)
    val GLYPHS: List<String> get() = GLYPH_RES.keys.toList()
    val COLORS = listOf(0xFFE5484D, 0xFFF76B15, 0xFFF5A524, 0xFF30A46C, 0xFF12A594, 0xFF0091FF, 0xFF3E63DD, 0xFF8E4EC6, 0xFFE93D82, 0xFF4B5563).map { it.toInt() }
    val EMOJIS = listOf("😀", "😎", "🤖", "🦄", "🐶", "🐱", "🚀", "⚽", "🎮", "🎵", "📚", "🎨", "🍎", "🌈", "⭐", "❤️")

    fun hex(c: Int) = String.format("#%06X", c and 0xFFFFFF)
    fun glyphSpec(name: String, color: Int) = "g:$name:${hex(color)}"
    fun emojiSpec(e: String, color: Int) = "e:$e:${hex(color)}"

    fun parseSpec(s: String): IconSpec? {
        val p = s.split(':')
        if (p.size != 3 || p[2].length != 7 || p[2][0] != '#' || p[2].drop(1).any { !it.isDigit() && it.uppercaseChar() !in 'A'..'F' }) return null
        val color = (0xFF000000L or p[2].drop(1).toLong(16)).toInt()
        return when (p[0]) {
            "g" -> if (p[1] in GLYPH_RES) IconSpec(true, p[1], color) else null
            "e" -> if (p[1].isNotEmpty() && p[1].length <= 8 && p[1].none { it.isWhitespace() }) IconSpec(false, p[1], color) else null
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
            GLYPH_RES[spec.value]?.let { id -> ctx.getDrawable(id)?.mutate()?.apply { setTint(Color.WHITE); setBounds(o, o, o + g, o + g); draw(c) } }
        } else {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = px * 0.56f; textAlign = Paint.Align.CENTER }
            val fm = p.fontMetrics
            c.drawText(spec.value, px / 2f, px / 2f - (fm.ascent + fm.descent) / 2f, p)
        }
        return BitmapDrawable(ctx.resources, b)
    }
}
