package dev.kidguard

/** Pure part of safe DNS without Device Owner (raw Settings.Global writes, WRITE_SECURE_SETTINGS only). Tested in DnsLogicTest. */
object DnsLogic {
    /**
     * What to write to private_dns_mode / private_dns_specifier, or null when nothing is needed.
     * [want] = effective safe-DNS host ("" = off or paused); [byUs] = we wrote it earlier and still owe a restore;
     * [priorMode]/[priorHost] = what the phone had before us ("" = unknown, then opportunistic).
     */
    fun plan(want: String, curMode: String, curHost: String, byUs: Boolean, priorMode: String, priorHost: String): Pair<String, String>? {
        if (want.isNotEmpty()) return if (curMode == "hostname" && curHost == want) null else "hostname" to want
        if (!byUs) return null
        val m = priorMode.ifEmpty { "opportunistic" }
        val h = if (m == "hostname") priorHost else ""
        return if (curMode == m && (m != "hostname" || curHost == h)) null else m to h
    }
}
