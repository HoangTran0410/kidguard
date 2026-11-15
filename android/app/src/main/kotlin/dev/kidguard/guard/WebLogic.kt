package dev.kidguard

import org.json.JSONArray

/**
 * Website rules pushed to Chromium browsers as managed config (Device Owner only, see Policy.applyBrowsers).
 * Chrome's `URLBlocklist` / `URLAllowlist` are multi-select (String[]), `IncognitoModeAvailability` a choice ("1" = off),
 * read from Chrome 101's app_restrictions.xml on the Oppo (2026-11-15). Pure: unit-tested in WebLogicTest.
 */
object WebLogic {
    const val MAX = 300
    /** "*" in webBlock = every site except webAllow ("only these sites" mode on the page). */
    const val ALL = "*"
    private val HOST = Regex("[a-z0-9]([a-z0-9-]{0,62})(\\.[a-z0-9]([a-z0-9-]{0,62}))*")

    /** "https://M.YouTube.com/watch?v=1" -> "m.youtube.com"; "www.x.com" -> "x.com"; junk -> null. "*" stays "*". */
    fun host(raw: String): String? {
        var s = raw.trim().lowercase()
        if (s == ALL) return ALL
        s = s.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#').substringAfter('@').substringBefore(':').trimEnd('.')
        if (s.startsWith("*.")) s = s.drop(2)
        if (s.startsWith("www.")) s = s.drop(4)
        return s.takeIf { it.length in 3..100 && it.contains('.') && it.matches(HOST) }
    }

    /** Clean list for the rules: valid hosts only, no duplicates, at most [MAX]. */
    fun list(a: JSONArray?): List<String> {
        a ?: return emptyList()
        val out = LinkedHashSet<String>()
        for (i in 0 until a.length()) host(a.optString(i))?.let { out.add(it) }
        return out.take(MAX)
    }

    /** First http(s) host found in shared text ("Look https://x.com/a" -> "x.com"), or null. */
    fun hostInText(text: String): String? =
        Regex("(?i)\\bhttps?://[^\\s<>\"']+").find(text)?.value?.let(::host)?.takeIf { it != ALL }
            ?: text.trim().split(Regex("\\s+")).firstNotNullOfOrNull { w -> host(w)?.takeIf { it != ALL && w.contains('.') && !w.contains('@') } }

    /** Is [host] covered by an entry (same host or a parent domain), Chrome-style? */
    fun covered(host: String, entries: Collection<String>): Boolean =
        entries.any { e -> e == ALL || host == e || host.endsWith(".$e") }

    /** The managed-config values for one browser (empty map = clear everything we set). */
    fun config(dohOff: Boolean, noIncognito: Boolean, block: List<String>, allow: List<String>): Map<String, Any> = buildMap {
        if (dohOff) put("DnsOverHttpsMode", "off")
        if (noIncognito) put("IncognitoModeAvailability", "1")
        if (block.isNotEmpty()) put("URLBlocklist", block.toTypedArray())
        if (allow.isNotEmpty() && block.isNotEmpty()) put("URLAllowlist", allow.toTypedArray())
    }
}
