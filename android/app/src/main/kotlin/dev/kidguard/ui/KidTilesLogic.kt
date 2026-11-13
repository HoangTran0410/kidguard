package dev.kidguard

/**
 * Which round tiles the child keeps on KidGuard's main screen (iOS Control Center / Android Quick Settings "edit"
 * pattern): SOS is always first and not in this list, then the picked tiles in order, then the "Edit" tile.
 * Stored on the phone only (prefs "quick", key "tiles"); the parent page does not see or set it.
 */
object KidTilesLogic {
    /** Every tile the child may add, display order of the "Add" list. */
    val ALL = listOf("arrived", "pickup", "lock", "more", "usage", "nlog", "files", "log")
    val DEFAULT = listOf("arrived", "pickup", "lock")
    /** SOS + 6 + Edit = two full rows of 4. */
    const val MAX = 6
    const val PER_ROW = 4

    fun parse(s: String?): List<String> = if (s == null) DEFAULT else s.split(',').filter { it in ALL }.distinct().take(MAX)
    fun join(l: List<String>): String = l.joinToString(",")
    fun add(l: List<String>, id: String): List<String> = if (id in l || id !in ALL || l.size >= MAX) l else l + id
    fun remove(l: List<String>, id: String): List<String> = l - id
    /** Tiles not shown yet, in [ALL] order. */
    fun addable(l: List<String>): List<String> = ALL.filter { it !in l }
    /** Cells per row: "sos", the picked ids, "edit". */
    fun rows(l: List<String>): List<List<String>> = (listOf("sos") + l + "edit").chunked(PER_ROW)
}
