package dev.kidguard

import java.text.Normalizer
import java.util.Locale

/** Text matching helpers, one copy (maintainability review 2026-11-17: written 5x). Pure, unit-tested (TextTest). */
object Text {
    private val MARKS = Regex("\\p{M}+")   // compiled once: fold runs for every app on each home render
    private val SPACES = Regex("\\s+")

    /** Lower case, accents off, đ -> d: "Đồng hồ" matches "dong ho". */
    fun fold(s: String): String = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(MARKS, "").replace('đ', 'd')

    /** [fold] with runs of white space made one space. */
    fun foldSpaces(s: String): String = fold(s).replace(SPACES, " ")

    /** Trimmed, NFC, lower case (accents kept): exact text compares on screen labels. */
    fun norm(s: String): String = Normalizer.normalize(s.trim(), Normalizer.Form.NFC).lowercase(Locale.ROOT)
}
