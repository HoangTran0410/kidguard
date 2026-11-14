package dev.kidguard

/**
 * Factory reset protection ids (A6.8). Android's FactoryResetProtectionPolicy wants the numeric Google account ID
 * (the OpenID `sub` / People API `people/<id>`), not the email. Pure parsing, unit tested.
 */
object Frp {
    const val MAX = 5
    /** Splits [text] on anything that is not a digit; keeps 10..30 digit tokens (real ids are ~21), distinct, at most [MAX]. */
    fun parse(text: String): List<String> =
        text.split(Regex("[^0-9]+")).filter { it.length in 10..30 }.distinct().take(MAX)
    fun ids(a: org.json.JSONArray?): List<String> = parse(a?.let { (0 until it.length()).joinToString(" ") { i -> it.optString(i) } } ?: "")

    /** State value for the page: unsupported (< Android 11 or not Device Owner) | off | on | error (set refused). */
    fun state(sdk: Int, owner: Boolean, want: Boolean, active: Boolean, failed: Boolean): String = when {
        sdk < 30 || !owner -> "unsupported"
        failed -> "error"
        want && active -> "on"
        else -> "off"
    }
}
