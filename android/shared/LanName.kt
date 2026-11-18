package dev.kidguard

import java.security.MessageDigest

/**
 * The name a kid phone advertises on the Wi-Fi (Android NSD = mDNS / DNS-SD), shared by the child app (advertises it)
 * and the parent app (looks for it when the saved IP stopped answering: DHCP gave the phone a new one).
 * Service type `_kidguard._tcp`, port = the LAN server. The name carries only a 12-hex hash of the kid id, not the id:
 * anyone on the Wi-Fi sees it. The parent still checks `/api/hello` (id must match) before using an address.
 * docs/protocol.md "LAN address heal".
 */
object LanName {
    const val TYPE = "_kidguard._tcp"

    fun of(id: String): String = "kg-" + hash(id)

    /** Android renames on a clash ("kg-abc (2)"): compare the prefix. */
    fun matches(serviceName: String, id: String): Boolean = id.isNotEmpty() && serviceName.startsWith(of(id))

    private fun hash(id: String): String =
        MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).take(6).joinToString("") { "%02x".format(it) }
}
