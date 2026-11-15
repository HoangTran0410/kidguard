package dev.kidguard

import java.text.Normalizer

/**
 * "Tìm cài đặt" box at the top of Tuỳ chỉnh (owner 2026-11-15: "càng nhiều feature, tìm mò mãi ko ra chỗ").
 * Pure: accent-free, every query word must appear in a section's text (title + rows + hidden keywords).
 */
object SettingsFind {
    private val marks = Regex("\\p{Mn}+")
    private val junk = Regex("[^\\p{L}\\p{N}\\s]")

    fun fold(s: CharSequence): String =
        junk.replace(marks.replace(Normalizer.normalize(s.toString().lowercase(), Normalizer.Form.NFD), "").replace('đ', 'd'), " ")

    /** Index of the first section whose text holds every word of [q], -1 = none (or an empty query). */
    fun first(q: String, sections: List<String>): Int {
        val w = fold(q).split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (w.isEmpty()) return -1
        return sections.indexOfFirst { t -> val f = fold(t); w.all { f.contains(it) } }
    }
}
