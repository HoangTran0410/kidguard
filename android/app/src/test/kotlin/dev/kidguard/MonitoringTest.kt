package dev.kidguard

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class MonitoringTest {
    private val keys get() = WatchRule().toJson().keys().asSequence().toList()

    @Test fun everyWatchKeyHasARegistryEntry() {
        assertEquals(keys.sorted(), Monitoring.ALL.map { it.id }.sorted())
        Monitoring.ALL.forEach { assertEquals("watch.${it.id}", it.rule) }
        assertEquals(Monitoring.ALL.size, Monitoring.ALL.map { it.id }.toSet().size)
    }

    @Test fun everyEntryIsTextInBothLanguagesAndWiredToItsResources() {
        for (dir in listOf("values", "values-vi")) {
            val xml = File("src/main/res/$dir").listFiles { f -> f.name.startsWith("strings") }!!.joinToString("\n") { it.readText() }
            for (c in Monitoring.ALL) for (part in listOf("title", "kid", "sees"))
                assertTrue("$dir: watch_${c.id}_$part", Regex("<string name=\"watch_${c.id}_$part\">[^<]{5,}</string>").containsMatchIn(xml))
        }
        for (c in Monitoring.ALL) for ((part, id) in listOf("title" to c.title, "kid" to c.kid, "sees" to c.sees))
            assertEquals("${c.id} $part", R.string::class.java.getField("watch_${c.id}_$part").getInt(null), id)
    }

    @Test fun turnedOnOnlyListsNewSwitches() {
        val off = WatchRule(); val on = WatchRule(notif = true)
        assertEquals(listOf("notif"), Monitoring.turnedOn(off, on).map { it.id })
        assertTrue(Monitoring.turnedOn(on, on).isEmpty())
        assertTrue(Monitoring.turnedOn(on, off).isEmpty())
        assertEquals(listOf("notif"), Monitoring.active(on).map { it.id })
    }

    @Test fun rulesRoundTripKeepsWatch() {
        val r = Rules(watch = WatchRule(notif = true))
        assertTrue(Rules.fromJson(r.toJson()).watch.notif)
        assertFalse(Rules.fromJson(Rules().toJson()).watch.notif)
        assertFalse(Rules.fromJson(org.json.JSONObject()).watch.notif)
    }

    @Test fun aModeCannotSwitchWatchOn() {
        assertFalse("watch" in Mode.OVER_KEYS)
    }

    @Test fun everyKeyIsOffByDefault() { keys.forEach { assertFalse(it, WatchRule().isOn(it)) } }
}
