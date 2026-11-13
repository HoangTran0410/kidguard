package dev.kidguard

/**
 * Which round tiles the child keeps on KidGuard's main screen (iOS Control Center / Android Quick Settings "edit"
 * pattern): SOS is always first and not in this list, then the picked tiles in order, then the "Edit" tile.
 * Stored on the phone only (prefs "quick", key "tiles"); the parent page does not see or set it.
 */
object KidTilesLogic {
    /** Every tile the child may add, display order of the "Add" list. */
    val ALL = listOf("arrived", "pickup", "lock", "more", "usage", "nlog", "files", "log")
    /** Only offered while the parents allow it (rule kidClean). */
    const val CLEAN = "clean"
    fun all(clean: Boolean): List<String> = if (clean) ALL + CLEAN else ALL
    val DEFAULT = listOf("arrived", "pickup", "lock")
    /** SOS + 6 + Edit = two full rows of 4. */
    const val MAX = 6
    const val PER_ROW = 4

    /** The child may clean at most once per this long. */
    const val CLEAN_GAP_MS = 600_000L
    /** Minutes to wait before the next clean (0 = go ahead). */
    fun cleanWaitMin(lastAt: Long, now: Long): Int = if (lastAt <= 0 || now < lastAt || now - lastAt >= CLEAN_GAP_MS) 0 else ((CLEAN_GAP_MS - (now - lastAt) + 59_999L) / 60_000L).toInt()
    /** "1,2 GB" / "340 MB" for the result line. */
    fun mbText(mb: Int): String = if (mb >= 1024) String.format(java.util.Locale.US, "%.1f GB", mb / 1024.0) else "$mb MB"

    fun parse(s: String?, clean: Boolean = false): List<String> = if (s == null) DEFAULT else s.split(',').filter { it in all(clean) }.distinct().take(MAX)
    fun join(l: List<String>): String = l.joinToString(",")
    fun add(l: List<String>, id: String, clean: Boolean = false): List<String> = if (id in l || id !in all(clean) || l.size >= MAX) l else l + id
    fun remove(l: List<String>, id: String): List<String> = l - id
    /** Tiles not shown yet, in [ALL] order. */
    fun addable(l: List<String>, clean: Boolean = false): List<String> = all(clean).filter { it !in l }
    /** Cells per row: "sos", the picked ids, "edit". */
    fun rows(l: List<String>): List<List<String>> = (listOf("sos") + l + "edit").chunked(PER_ROW)
}
