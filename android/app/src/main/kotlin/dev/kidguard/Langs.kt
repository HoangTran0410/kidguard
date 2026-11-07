package dev.kidguard

/**
 * The UI languages the app offers (pure, unit-tested). Code "" = follow the phone's language.
 * New language: one line in [ALL] + res/values-<code>/strings.xml + a <locale> in res/xml/locales_config.xml
 * + <code> in androidResources.localeFilters (build.gradle.kts). LangsTest checks the last two match [ALL].
 */
object Langs {
    /** Code to the language's own name (shown in its own language, so anyone can find theirs). */
    val ALL: List<Pair<String, String>> = listOf(
        "en" to "English",
        "vi" to "Tiếng Việt",
    )

    fun valid(code: String): Boolean = code.isEmpty() || ALL.any { it.first == code }

    /** A locale tag list ("vi-VN,en-US", "en", "") to the code we offer for its first entry, or "" (system). */
    fun norm(tags: String?): String {
        val lang = tags.orEmpty().split(',').firstOrNull()?.trim()?.substringBefore('-')?.substringBefore('_')?.lowercase().orEmpty()
        return if (lang.isNotEmpty() && valid(lang)) lang else ""
    }
}
