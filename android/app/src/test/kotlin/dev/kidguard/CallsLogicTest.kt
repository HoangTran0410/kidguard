package dev.kidguard

import org.junit.Assert.*
import org.junit.Test

class CallsLogicTest {
    private val unk = CallRule("unknown", listOf("0912 345 678").mapNotNull { CallsLogic.clean(it) })
    private val hid = CallRule("hidden")

    @Test fun canonVietnamForms() {
        assertEquals("0912345678", CallsLogic.canon("+84 912-345-678"))
        assertEquals("0912345678", CallsLogic.canon("0084912345678"))
        assertEquals("0912345678", CallsLogic.canon("84912345678"))
        assertEquals("0912345678", CallsLogic.canon("0912 345 678"))
        assertEquals("14155550100", CallsLogic.canon("+1 415 555 0100"))
        assertEquals("", CallsLogic.canon(null))
    }
    @Test fun cleanKeepsPlus() {
        assertEquals("+84912345678", CallsLogic.clean(" +84 912 345 678 "))
        assertNull(CallsLogic.clean("12")); assertNull(CallsLogic.clean("abc"))
    }
    @Test fun offNeverBlocks() { assertFalse(CallsLogic.shouldBlock(CallRule(), null, false)) }
    @Test fun emergencyNeverBlocked() {
        assertFalse(CallsLogic.shouldBlock(unk, "113", false)); assertFalse(CallsLogic.shouldBlock(unk, "112", false))
    }
    @Test fun hiddenOnlyWithheld() {
        assertTrue(CallsLogic.shouldBlock(hid, null, false)); assertTrue(CallsLogic.shouldBlock(hid, "", false))
        assertFalse(CallsLogic.shouldBlock(hid, "0987654321", false))
    }
    @Test fun unknownRules() {
        assertTrue(CallsLogic.shouldBlock(unk, null, true))
        assertTrue(CallsLogic.shouldBlock(unk, "0987654321", false))
        assertFalse(CallsLogic.shouldBlock(unk, "0987654321", true))
        assertFalse(CallsLogic.shouldBlock(unk, "+84912345678", false))   // allow list, other prefix
        assertFalse(CallsLogic.shouldBlock(unk, "0987654321", false, contactsKnown = false))
        assertTrue(CallsLogic.shouldBlock(unk, null, false, contactsKnown = false))
    }
    @Test fun ruleJsonRoundTrip() {
        val r = CallRule.fromJson(CallRule("unknown", listOf("+84912345678", "0912345678", "x")).toJson())
        assertEquals(CallRule("unknown", listOf("+84912345678")), r)
        assertEquals(CallRule(), CallRule.fromJson(null))
        assertEquals("off", CallRule.fromJson(org.json.JSONObject("{\"block\":\"all\"}")).block)
        val many = org.json.JSONArray((1..50).map { "0900000%03d".format(it) })
        assertEquals(30, CallRule.fromJson(org.json.JSONObject().put("allow", many)).allow.size)
    }
    @Test fun typeNames() { assertEquals("missed", CallsLogic.typeName(3)); assertEquals("in", CallsLogic.typeName(4)); assertTrue(CallsLogic.wasBlocked(CallsLogic.typeName(6))) }
}
