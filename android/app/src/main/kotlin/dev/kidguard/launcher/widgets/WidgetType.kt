package dev.kidguard

/**
 * One type scale for every KidGuard-drawn home widget and Today card (sp). Use these, never a literal size:
 *  TITLE   caps header of a card ("THÁNG 11", "CHUỖI NGÀY"), accent or sub colour, letterSpacing 0.08
 *  BODY    main text line / label / list row (also bold labels such as quick actions)
 *  CAPTION secondary line, units, hints, badges
 *  VALUE   one primary value in a card (battery %, countdown)
 *  VALUE_L sheet titles / larger primary value
 *  HERO    the one key number of a widget (streak days), never more
 * Canvas widgets (calendar, timetable) size text from their cell; [cap] keeps it inside this scale.
 */
internal object WType {
    const val TITLE = 12f
    const val BODY = 14f
    const val CAPTION = 12f
    const val VALUE = 18f
    const val VALUE_L = 22f
    const val HERO = 28f
    /** Text px for canvas drawing: [px] scaled by the cell, never above [sp] (at the user's font scale). */
    fun cap(px: Float, sp: Float, scaledDensity: Float) = minOf(px, sp * scaledDensity)
}

/** One-line text that shrinks (to 70%) instead of wrapping or cutting; sizes from [WType], honours the launcher font setting. */
internal fun HomeActivity.wFit(t: String, sp: Float, color: Int, bold: Boolean = false) =
    FitText(this, sp * (model?.style?.font?.toFloat() ?: 1f), 0.7f).apply {
        text = t; full = t; setTextColor(color); typeface = HomeActivity.tf(bold)
    }
