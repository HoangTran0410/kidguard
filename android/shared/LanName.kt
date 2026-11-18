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

    /**
     * Proof that an address is the paired kid, not a look-alike on the Wi-Fi (the name and `/api/hello` id are public):
     * the parent sends a random nonce (`/api/hello?n=`), the kid answers `proof` = this for each paired token it keeps
     * (it stores only sha256(token), [tokenHash]); the parent sends its token only to a host whose list holds its own.
     */
    fun proof(tokenHash: String, nonce: String): String {
        val m = javax.crypto.Mac.getInstance("HmacSHA256")
        m.init(javax.crypto.spec.SecretKeySpec(tokenHash.toByteArray(), "HmacSHA256"))
        return m.doFinal(("kg-lan:" + nonce).toByteArray()).joinToString("") { "%02x".format(it) }
    }
    fun tokenHash(token: String): String =
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun hash(id: String): String =
        MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).take(6).joinToString("") { "%02x".format(it) }
}
