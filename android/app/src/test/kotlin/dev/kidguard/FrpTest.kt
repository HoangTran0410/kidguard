package dev.kidguard

import org.junit.Assert.assertEquals
import org.junit.Test

class FrpTest {
    @Test fun parseKeepsLongDigitIdsOnly() {
        assertEquals(listOf("112233445566778899001"), Frp.parse("me@x.com, 112233445566778899001 ; 12345"))
        assertEquals(listOf("1234567890", "9876543210"), Frp.parse("1234567890\n9876543210 1234567890"))
        assertEquals(5, Frp.parse((1..9).joinToString(" ") { "10000000000$it" }).size)
        assertEquals(emptyList<String>(), Frp.parse(""))
    }

    @Test fun rulesRoundTrip() {
        val r = Rules.fromJson(org.json.JSONObject("""{"frpIds":["1234567890123456789","bad"]}"""))
        assertEquals(listOf("1234567890123456789"), r.frpIds)
        assertEquals(listOf("1234567890123456789"), Rules.fromJson(r.toJson(true)).frpIds)
        assertEquals(emptyList<String>(), Rules.fromJson(org.json.JSONObject("{}")).frpIds)
    }

    @Test fun stateValue() {
        assertEquals("unsupported", Frp.state(29, true, true, false, false))
        assertEquals("unsupported", Frp.state(34, false, true, false, false))
        assertEquals("off", Frp.state(34, true, false, false, false))
        assertEquals("on", Frp.state(34, true, true, true, false))
        assertEquals("error", Frp.state(34, true, true, false, true))
    }
}
