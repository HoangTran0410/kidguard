package dev.kidguard

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebloatTest {
    @Test fun neverHide() {
        for (p in listOf("dev.kidguard", "android", "com.android.systemui", "com.android.settings", "com.google.android.gms",
            "com.android.vending", "com.android.providers.media", "com.android.providers.contacts", "com.android.bluetooth", "com.android.nfc",
            "com.android.keychain", "com.android.permissioncontroller", "com.google.android.dialer", "com.google.android.webview"))
            assertFalse(p, Debloat.canHide(p))
        assertTrue(Debloat.canHide("com.heytap.market"))
        assertTrue(Debloat.canHide("com.facebook.katana"))
    }

    @Test fun dynamicNeverAndBadNames() {
        assertFalse(Debloat.canHide("com.heytap.market", setOf("com.heytap.market")))
        assertFalse(Debloat.canHide("bad pkg"))
        assertFalse(Debloat.canHide(""))
        assertFalse(Debloat.canHide("a".repeat(201) + ".b"))
    }

    @Test fun suggestListIsSafe() {
        assertTrue(Debloat.SUGGEST.size > 100)
        assertTrue(Debloat.SUGGEST.all { Debloat.canHide(it) })
        for (bad in listOf("inputmethod", "launcher", "camera", "gallery3d", "photos", "deskclock", "mms", "messaging", "dialer", "contacts", "keyboard"))
            assertTrue(bad, Debloat.SUGGEST.none { it.contains(bad, true) })
        assertTrue(Debloat.isSuggest("com.miui.msa.global"))
        assertTrue(Debloat.isSuggest("com.netflix.partner.activation"))
    }

    @Test fun diff() {
        val d = Debloat.diff(want = setOf("a.b", "c.d", "com.android.settings"), had = setOf("c.d", "e.f"))
        assertEquals(setOf("a.b"), d.hide)
        assertEquals(setOf("e.f"), d.show)
        // something we hid becomes protected (e.g. now the default launcher): it is shown again
        val d2 = Debloat.diff(setOf("c.d"), setOf("c.d"), extraNever = setOf("c.d"))
        assertEquals(emptySet<String>(), d2.hide); assertEquals(setOf("c.d"), d2.show)
        assertEquals(Debloat.Diff(emptySet(), emptySet()), Debloat.diff(emptySet(), emptySet()))
    }

    @Test fun sortSuggestFirstThenLabel() {
        val rows = listOf(Debloat.Row("p.z", "Zebra", false), Debloat.Row("p.b", "banana", true), Debloat.Row("p.a", "Apple", false), Debloat.Row("p.c", "Cherry", true))
        assertEquals(listOf("p.b", "p.c", "p.a", "p.z"), Debloat.sortRows(rows).map { it.pkg })
    }

    @Test fun releaseUnhides() {
        // ours + still hidden from the rules; a rules entry that is not hidden stays untouched
        assertEquals(setOf("a.a", "b.b"), Debloat.toUnhideOnRelease(setOf("a.a"), setOf("b.b", "c.c"), setOf("b.b")))
    }

    @Test fun clearGuard() {
        assertFalse(Debloat.canClear("dev.kidguard", "dev.kidguard"))
        assertFalse(Debloat.canClear("com.google.android.gms", "dev.kidguard"))
        assertTrue(Debloat.canClear("com.heytap.market", "dev.kidguard"))
    }

    @Test fun rulesHiddenParse() {
        val r = Rules.fromJson(JSONObject("""{"hidden":["x.y","bad pkg","x.y",5],"future":1}"""))
        assertEquals(setOf("x.y"), r.hidden)
        assertEquals(emptySet<String>(), Rules.fromJson(JSONObject("{}")).hidden)
    }

    @Test fun diffLine() {
        val a = Rules(); val b = a.copy(hidden = setOf("com.x"))
        assertEquals("Bố mẹ đổi luật: Ẩn app: com.x", Events.ruleDiff(a, b, TestTr.VI))
        assertEquals("Bố mẹ đổi luật: Hiện lại app: com.x", Events.ruleDiff(b, a, TestTr.VI))
    }

    @Test fun userAppsNeverListedOrSuggested() {
        val tt = "com.zhiliaoapp.musically"
        assertFalse(Debloat.listed(system = false, hasIcon = true, pkg = tt, hiddenByUs = false))
        assertFalse(Debloat.suggested(system = false, pkg = tt))
        assertTrue(Debloat.listed(system = true, hasIcon = false, pkg = tt, hiddenByUs = false))
        assertTrue(Debloat.suggested(system = true, pkg = tt))
        assertTrue(Debloat.listed(system = false, hasIcon = false, pkg = tt, hiddenByUs = true))
        assertFalse(Debloat.isSuggest("com.miui.securityadd"))
        assertFalse(Debloat.isSuggest("com.heytap.usercenter"))
    }
}

class DebloatRefusedTest {
    private val s = emptySet<String>()
    @org.junit.Test fun failureIsFlagged() { assertEquals(setOf("a.b"), Debloat.refusedAfter(s, setOf("a.b"), s, s, setOf("a.b"))) }
    @org.junit.Test fun staysUntilSuccess() {
        val r1 = Debloat.refusedAfter(s, setOf("a.b"), s, s, setOf("a.b"))
        assertEquals(setOf("a.b"), Debloat.refusedAfter(r1, s, s, s, setOf("a.b")))
        assertEquals(s, Debloat.refusedAfter(r1, s, setOf("a.b"), s, setOf("a.b")))
    }
    @org.junit.Test fun clearedOnUnhideOrDrop() {
        assertEquals(s, Debloat.refusedAfter(setOf("a.b"), s, s, setOf("a.b"), setOf("a.b")))
        assertEquals(s, Debloat.refusedAfter(setOf("a.b"), s, s, s, s))
        assertEquals(setOf("c.d"), Debloat.refusedAfter(setOf("a.b"), setOf("c.d"), s, s, setOf("c.d")))
    }

    @Test fun shellCommands() {
        assertEquals("pm disable-user --user 0 com.heytap.market", Debloat.disableCmd("com.heytap.market"))
        assertEquals("pm enable --user 0 com.heytap.market", Debloat.enableCmd("com.heytap.market"))
        assertEquals("pm clear --user 0 com.heytap.market", Debloat.clearCmd("com.heytap.market"))
        for (bad in listOf("a.b; reboot", "x y.z", "", "nodot", "a.b\nc.d")) {
            assertEquals(bad, null, Debloat.disableCmd(bad)); assertEquals(null, Debloat.enableCmd(bad)); assertEquals(null, Debloat.clearCmd(bad))
        }
    }

    @Test fun shellOutput() {
        assertTrue(Debloat.disableOk("Package com.x.y new state: disabled-user"))
        assertFalse(Debloat.disableOk("Exception occurred while executing 'disable-user': java.lang.SecurityException"))
        assertFalse(Debloat.disableOk(""))
        assertTrue(Debloat.enableOk("Package com.x.y new state: enabled"))
        assertTrue(Debloat.enableOk("Package com.x.y new state: default"))
        assertFalse(Debloat.enableOk("Error: java.lang.IllegalArgumentException: Unknown package"))
        assertTrue(Debloat.clearOk("Success\n")); assertFalse(Debloat.clearOk("Failed")); assertFalse(Debloat.clearOk(""))
    }

    @Test fun shellBatchCaps() {
        val hide = (1..50).map { "a.p$it" }.toSet()
        val b = Debloat.shellBatch(Debloat.Diff(hide, emptySet()))
        assertEquals(Debloat.SHELL_BATCH, b.hide.size); assertTrue(b.show.isEmpty())
        val mixed = Debloat.shellBatch(Debloat.Diff(hide, (1..5).map { "b.p$it" }.toSet()))
        assertEquals(5, mixed.show.size); assertEquals(Debloat.SHELL_BATCH - 5, mixed.hide.size)
        assertEquals(Debloat.Diff(setOf("a.b"), setOf("c.d")), Debloat.shellBatch(Debloat.Diff(setOf("a.b"), setOf("c.d"))))
    }

    @Test fun releaseEnable() {
        val ours = setOf("a.b", "c.d", "bad pkg")
        assertEquals(setOf("a.b"), Debloat.toEnableOnRelease(ours) { it == "a.b" || it == "bad pkg" })
        assertEquals(emptySet<String>(), Debloat.toEnableOnRelease(emptySet()) { true })
    }
}
