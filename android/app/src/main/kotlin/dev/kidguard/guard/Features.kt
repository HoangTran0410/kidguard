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
    val adb: Boolean = false,        // self-ADB usable (the phone can run adb against itself)
    val paired: Boolean = false,     // a parent has paired (store.everPaired)
    val wss: Boolean = false,        // WRITE_SECURE_SETTINGS granted (pm grant at setup; survives Developer options being turned off)
) {
    fun has(cap: String): Boolean = when (cap) {
        "owner" -> owner; "admin" -> admin; "acc" -> acc; "usage" -> usage; "location" -> location
        "notify" -> notify; "battery" -> battery; "home" -> home; "adb" -> adb; "paired" -> paired; "wss" -> wss
        else -> false
    }
    fun toMap(): Map<String, Boolean> = Features.CAPS.associateWith { has(it) }
}

data class Feature(val id: String, val ok: Boolean, val missing: List<String>)

object Features {
    val CAPS = listOf("owner", "admin", "acc", "usage", "location", "notify", "battery", "home", "adb", "paired", "wss")

    /** id -> alternatives; one alternative with every cap granted makes the feature usable. Order = display order. */
    val TABLE: List<Pair<String, List<List<String>>>> = listOf(
        "launcher" to listOf(listOf("home")),
        "launcherLock" to listOf(listOf("owner")),
        "debloat" to listOf(listOf("owner"), listOf("adb")),
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
    )

    fun status(c: Caps): List<Feature> = TABLE.map { (id, alts) ->
        val miss = alts.map { alt -> alt.filter { !c.has(it) } }
        if (miss.any { it.isEmpty() }) Feature(id, true, emptyList())
        // fewest missing; on a tie the alternative the user already started (most caps granted), then the first
        else Feature(id, false, alts.indices.minByOrNull { miss[it].size * 100 - (alts[it].size - miss[it].size) }?.let { miss[it] } ?: emptyList())
    }
}
