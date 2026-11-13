package dev.kidguard

/**
 * Which feature needs which permission (docs/protocol.md "Features and permissions"). Pure, so it is unit-tested; the
 * Android side that fills [Caps] is [FeatureCaps] (ui/FeaturesSheet.kt).
 */
data class Caps(
    val owner: Boolean = false,      // Device Owner
    val admin: Boolean = false,      // device admin active
    val acc: Boolean = false,        // accessibility service on
    val usage: Boolean = false,      // usage access
    val location: Boolean = false,   // fine + background location
    val notify: Boolean = false,     // notifications
    val battery: Boolean = false,    // battery optimisation off
    val home: Boolean = false,       // KidGuard is the default HOME app
    val adb: Boolean = false,        // self-ADB set up (paired once; it may wait for Wi-Fi: SelfAdb.why "wifi" / "wss")
    val paired: Boolean = false,     // a parent has paired (store.everPaired)
    val wss: Boolean = false,        // WRITE_SECURE_SETTINGS granted (pm grant at setup; survives Developer options being turned off)
    val adbNet: Boolean = false,     // adb listens on the network (port 5555: `adb connect <ip>:5555`, also our own adb on mobile data)
    val vpn: Boolean = false,        // a VPN (Tailscale) is up: the parent page / adb reach the phone over it
) {
    fun has(cap: String): Boolean = when (cap) {
        "owner" -> owner; "admin" -> admin; "acc" -> acc; "usage" -> usage; "location" -> location
        "notify" -> notify; "battery" -> battery; "home" -> home; "adb" -> adb; "paired" -> paired; "wss" -> wss
        "adbNet" -> adbNet; "vpn" -> vpn
        else -> false
    }
    /** The same phone with [caps] gone (what a pause turns off: [Features.PAUSES]). */
    fun without(caps: Collection<String>): Caps = Caps(
        owner = owner && "owner" !in caps, admin = admin && "admin" !in caps, acc = acc && "acc" !in caps, usage = usage && "usage" !in caps,
        location = location && "location" !in caps, notify = notify && "notify" !in caps, battery = battery && "battery" !in caps,
        home = home && "home" !in caps, adb = adb && "adb" !in caps, paired = paired && "paired" !in caps, wss = wss && "wss" !in caps,
        adbNet = adbNet && "adbNet" !in caps, vpn = vpn && "vpn" !in caps)
    fun toMap(): Map<String, Boolean> = Features.CAPS.associateWith { has(it) }
}

data class Feature(val id: String, val ok: Boolean, val missing: List<String>)

object Features {
    val CAPS = listOf("owner", "admin", "acc", "usage", "location", "notify", "battery", "home", "adb", "paired", "wss", "adbNet", "vpn")

    /** id -> alternatives; one alternative with every cap granted makes the feature usable. Order = display order. */
    val TABLE: List<Pair<String, List<List<String>>>> = listOf(
        "launcher" to listOf(listOf("home")),
        // basic: KidGuard is HOME + accessibility closes the picker / bounces another launcher; self-adb is optional (re-sets the default, no flash)
        "launcherLock" to listOf(listOf("owner"), listOf("home", "acc")),
        "debloat" to listOf(listOf("adb"), listOf("owner")),   // adb first: Device Owner needs a reset phone, adb does not
        "limits" to listOf(listOf("owner"), listOf("acc", "usage", "admin")),
        // basic mode only bounces while the device admin is on too (Basic.enforcing)
        "block" to listOf(listOf("owner"), listOf("acc", "admin")),
        "usage" to listOf(listOf("usage")),
        "location" to listOf(listOf("location")),
        "screen" to listOf(listOf("acc")),
        "remote" to listOf(listOf("paired")),
        "noUninstall" to listOf(listOf("owner"), listOf("admin")),
        // owner: DPM; basic: raw Settings writes, which only need WRITE_SECURE_SETTINGS (no live adb, works with Developer options off)
        "dns" to listOf(listOf("owner"), listOf("wss")),
        "lockLocation" to listOf(listOf("owner"), listOf("wss")),
        // 2026-11-13: what the bank-app pauses take away also lives here, so their popup lists it ([lost])
        "adbShell" to listOf(listOf("adb")),        // parent page adb terminal
        "autoGrant" to listOf(listOf("adb")),       // missing permissions granted again by our own adb (AutoGrant)
        "pcAdb" to listOf(listOf("adbNet")),        // a computer runs `adb connect <ip>:5555` (Wi-Fi or Tailscale, also on mobile data)
        "tailscale" to listOf(listOf("vpn")),       // parent page / adb over Tailscale
    )

    /**
     * Single list of what each bank-app pause turns off, as caps: the popup / notification before such an app opens
     * (PauseAsk) lists [lost] from it. A new pause kind or a new feature only needs a row here / in [TABLE]
     * (FeaturesTest checks every PauseAsk.Kind has a row and every feature has its name strings).
     */
    val PAUSES: Map<String, List<String>> = mapOf(
        "acc" to listOf("acc"),                 // AccPause: our accessibility service off
        "dev" to listOf("adb", "adbNet"),       // DevOpts devPause: USB / wireless debugging off (adbd down: the port too, until it is back)
        "tcp" to listOf("adbNet"),              // DevOpts tcpPause: `adb usb`, the port stays closed until Wi-Fi
        "vpn" to listOf("vpn"),                 // VpnPause: Tailscale / always-on VPN off
    )

    /** Features that work on [c] now and stop working while [pause] is on (display order). */
    fun lost(c: Caps, pause: String): List<String> {
        val before = status(c).filter { it.ok }.map { it.id }.toSet()
        val after = status(c.without(PAUSES[pause] ?: emptyList())).filter { it.ok }.map { it.id }.toSet()
        return TABLE.map { it.first }.filter { it in before && it !in after }
    }

    fun status(c: Caps): List<Feature> = TABLE.map { (id, alts) ->
        val miss = alts.map { alt -> alt.filter { !c.has(it) } }
        if (miss.any { it.isEmpty() }) Feature(id, true, emptyList())
        // fewest missing; on a tie the alternative the user already started (most caps granted), then the first
        else Feature(id, false, alts.indices.minByOrNull { miss[it].size * 100 - (alts[it].size - miss[it].size) }?.let { miss[it] } ?: emptyList())
    }
}
