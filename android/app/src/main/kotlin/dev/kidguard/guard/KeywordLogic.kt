package dev.kidguard

/**
 * Parent's keywords (rule `watch.words`), pure + unit-tested. Matching ignores case and accents (đ = d) and looks for the
 * word as a piece of the text (so "chet" also finds "chết rồi"); a phrase with spaces must appear in that order.
 */
object KeywordLogic {
    const val MAX_WORDS = 30
    const val MAX_LEN = 40

    private fun n(s: String) = RefuseLogic.norm(s).trim()
    fun key(word: String) = n(word)

    /** What is kept of an edited list: trimmed, no empties, no duplicates (accent/case-insensitive), cut to the limits. */
    fun clean(raw: List<String>): List<String> {
        val seen = HashSet<String>(); val out = ArrayList<String>()
        for (w in raw) {
            val t = w.trim().replace(Regex("\\s+"), " ").take(MAX_LEN)
            if (n(t).isNotEmpty() && seen.add(n(t))) out += t
            if (out.size >= MAX_WORDS) break
        }
        return out
    }

    /** The first word of [words] found in any of [texts], or null. */
    fun hit(words: List<String>, texts: List<String>): String? {
        if (words.isEmpty()) return null
        val hay = texts.filter { it.isNotEmpty() }.map { n(it) }
        return words.firstOrNull { w -> val k = n(w); k.isNotEmpty() && hay.any { it.contains(k) } }
    }

    /** A short piece of [text] around the first place [word] is found (for the alert line); falls back to the start. */
    fun snippet(text: String, word: String, max: Int = 120): String {
        val flat = text.replace(Regex("\\s+"), " ").trim()
        if (flat.length <= max) return flat
        val nf = n(flat)
        // positions of the normalized text match the original only when no character was dropped or merged
        val i = if (nf.length == flat.length) nf.indexOf(n(word)).coerceAtLeast(0) else 0
        val from = (i - max / 3).coerceAtLeast(0)
        return (if (from > 0) "…" else "") + flat.substring(from, (from + max).coerceAtMost(flat.length))
    }
}

/** Alert rate limit (pure, unit-tested): a chat that keeps saying the word must not push the parent every second. */
class WordThrottle(private val perKeyMs: Long = 5 * 60_000L, private val perHour: Int = 12) {
    private val last = HashMap<String, Long>()
    private val hour = ArrayDeque<Long>()

    @Synchronized fun allow(key: String, now: Long): Boolean {
        while (hour.isNotEmpty() && now - hour.first() > 3_600_000L) hour.removeFirst()
        if (hour.size >= perHour) return false
        val l = last[key]
        if (l != null && now - l < perKeyMs) return false
        if (last.size > 200) last.clear()
        last[key] = now; hour.addLast(now)
        return true
    }
}
