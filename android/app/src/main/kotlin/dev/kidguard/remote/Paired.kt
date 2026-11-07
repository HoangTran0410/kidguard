package dev.kidguard

/** Paired parent devices: stable non-secret ids and the list shown in Settings. Pure, Android-free, unit-tested. */
object Paired {
    const val LAST_EVERY_MS = 5 * 60_000L   // `last` moves at most this often, so the state does not change on every request
    const val NAME_MAX = 40

    data class Dev(val name: String, val at: Long, val last: Long)
    data class Row(val id: String, val name: String, val at: Long, val last: Long, val self: Boolean)

    /** First 8 hex chars of the stored sha256(token): identifies a device, reveals nothing usable. */
    fun id(hash: String): String = hash.take(8)

    fun cleanName(s: String?): String = (s ?: "").filter { it >= ' ' && it != '\u007f' }.trim().take(NAME_MAX)

    /** The stored hash for [id], or null when unknown (or, in the freak case of a prefix clash, ambiguous). */
    fun find(hashes: Set<String>, id: String): String? =
        if (id.length != 8 || !id.all { it in '0'..'9' || it in 'a'..'f' }) null else hashes.filter { id(it) == id }.singleOrNull()

    /** Meta after a request with a paired token: new devices get [name] and `at`=`last`=[now]; `last` moves only when due. Same map when nothing changes. */
    fun touch(meta: Map<String, Dev>, hash: String, now: Long, name: String?): Map<String, Dev> {
        val d = meta[hash]
        val n = cleanName(name)
        if (d == null) return meta + (hash to Dev(n, now, now))
        val newName = if (d.name.isBlank() && n.isNotEmpty()) n else d.name
        val newLast = if (now - d.last >= LAST_EVERY_MS) now else d.last
        return if (newName == d.name && newLast == d.last) meta else meta + (hash to d.copy(name = newName, last = newLast))
    }

    /** Rows for state.devices: oldest pairing first; a device with no meta (paired before this) has at = last = 0. */
    fun rows(hashes: Set<String>, meta: Map<String, Dev>, selfHash: String? = null): List<Row> =
        hashes.map { h -> val d = meta[h]; Row(id(h), d?.name ?: "", d?.at ?: 0, d?.last ?: 0, h == selfHash) }
            .sortedWith(compareBy<Row> { it.at }.thenBy { it.id })

    /** Drop meta of tokens that no longer exist. */
    fun prune(meta: Map<String, Dev>, hashes: Set<String>): Map<String, Dev> = meta.filterKeys { it in hashes }
}
