package dev.kidguard

/** GitHub release parsing shared by the child app's updater (Update, Ts) and the parent app's (Updater). Pure, tested in both. */
object Release {
    private val SHA = Regex("[0-9a-f]{64}")
    private val SPACES = Regex("\\s+")
    private val TAG = Regex("v?\\d{1,4}(\\.\\d{1,4}){1,3}")

    /** First token of a `.sha256` / `shasum` line, lower case, or null if it isn't 64 hex digits. */
    fun sha256Of(text: String): String? = text.trim().split(SPACES).firstOrNull()?.lowercase()?.takeIf { SHA.matches(it) }

    /** The one checksum list of a release (owner 2026-11-19, like tv-remote): `{"version", "code", "files": {"<apk>": "<sha256>"}}`. */
    const val MANIFEST = "version.json"

    /** The SHA-256 of [file] in a [MANIFEST] text, lower case, or null (no such file / not 64 hex). */
    fun shaIn(manifest: String, file: String): String? =
        Regex("\"" + Regex.escape(file) + "\"\\s*:\\s*\"([0-9a-fA-F]{64})\"").find(manifest)?.groupValues?.get(1)?.lowercase()

    /** "0.9.0" from a `Location: …/releases/tag/v0.9.0` redirect, or null. */
    fun tagFromLocation(loc: String?): String? = loc?.substringAfterLast("/tag/", "")?.takeIf { TAG.matches(it) }?.removePrefix("v")
}
