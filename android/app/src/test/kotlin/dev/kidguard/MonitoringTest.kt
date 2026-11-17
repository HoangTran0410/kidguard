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
            // the narrow form (keywords only) of notifications has its own text
            for (part in listOf("title", "kid", "sees"))
                assertTrue("$dir: watch_notif_kw_$part", Regex("<string name=\"watch_notif_kw_$part\">[^<]{5,}</string>").containsMatchIn(xml))
        }
        for (c in Monitoring.ALL) for ((part, id) in listOf("title" to c.title, "kid" to c.kid, "sees" to c.sees))
            assertEquals("${c.id} $part", R.string::class.java.getField("watch_${c.id}_$part").getInt(null), id)
        val n = Monitoring.byId("notif")!!
        assertEquals(R.string.watch_notif_kw_kid, n.kid(WatchRule(NotifWatch(on = true, kwOnly = true))))
        assertEquals(R.string.watch_notif_kid, n.kid(WatchRule(NotifWatch(on = true))))
    }

    @Test fun turnedOnOnlyListsNewSwitchesAndWiderForms() {
        val off = WatchRule(); val kw = WatchRule(NotifWatch(on = true, kwOnly = true)); val all = WatchRule(NotifWatch(on = true))
        assertEquals(listOf("notif"), Monitoring.turnedOn(off, all).map { it.id })
        assertTrue(Monitoring.turnedOn(all, all).isEmpty())
        assertTrue(Monitoring.turnedOn(all, off).isEmpty())
        assertEquals(listOf("notif"), Monitoring.turnedOn(kw, all).map { it.id })   // keywords only -> everything: told again
        assertTrue(Monitoring.turnedOn(all, kw).isEmpty())
        assertEquals(listOf("words"), Monitoring.turnedOn(off, WatchRule(words = listOf("x"))).map { it.id })
        assertEquals(listOf("cam"), Monitoring.turnedOn(off, WatchRule(cam = true)).map { it.id })
        assertEquals(listOf("notif"), Monitoring.active(all).map { it.id })
    }

    @Test fun rulesRoundTripKeepsWatch() {
        val w = WatchRule(NotifWatch(true, true, "only", listOf("a.b", "c.d")), listOf("chết", "Ma tuý"))
        val r = Rules(watch = w)
        assertEquals(w, Rules.fromJson(r.toJson()).watch)
        assertEquals(WatchRule(), Rules.fromJson(Rules().toJson()).watch)
        assertEquals(WatchRule(), Rules.fromJson(org.json.JSONObject()).watch)
    }

    @Test fun badWatchJsonFallsBackToOff() {
        val w = WatchRule.fromJson(org.json.JSONObject("""{"notif":{"on":true,"apps":"weird","list":["", "x", "x"]},"words":["  ", "a", "A"]}"""))
        assertEquals("all", w.notif.apps); assertEquals(listOf("x"), w.notif.list); assertEquals(listOf("a"), w.words)
    }

    @Test fun aModeCannotSwitchWatchOn() {
        assertFalse("watch" in Mode.OVER_KEYS)
    }

    @Test fun everyKeyIsOffByDefault() { keys.forEach { assertFalse(it, WatchRule().isOn(it)) } }

    @Test fun appFilter() {
        assertTrue(NotifWatch(apps = "all").appOk("x"))
        assertTrue(NotifWatch(apps = "only", list = listOf("x")).appOk("x"))
        assertFalse(NotifWatch(apps = "only", list = listOf("x")).appOk("y"))
        assertFalse(NotifWatch(apps = "except", list = listOf("x")).appOk("x"))
        assertTrue(NotifWatch(apps = "except", list = listOf("x")).appOk("y"))
    }
}
