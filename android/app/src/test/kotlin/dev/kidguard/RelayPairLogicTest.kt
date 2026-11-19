package dev.kidguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayPairLogicTest {
    @Test fun inviteMustBe16Hex() {
        assertEquals("0123456789abcdef", RelayPairLogic.invite(" 0123456789abcdef "))
        assertNull(RelayPairLogic.invite(null))
        assertNull(RelayPairLogic.invite("0123456789ABCDEF"))
        assertNull(RelayPairLogic.invite("0123456789abcde"))
        assertNull(RelayPairLogic.invite("../../etc/passwd0"))
    }

    @Test fun onlyHttpsRelay() {
        assertTrue(RelayPairLogic.usable("https://kg-relay.99-hoangtran.workers.dev"))
        assertTrue(!RelayPairLogic.usable("http://x"))
    }

    @Test fun pollReplies() {
        assertEquals(RelayPairLogic.Poll.Waiting, RelayPairLogic.parsePoll(200, """{"waiting":true,"exp":1}"""))
        assertEquals(RelayPairLogic.Poll.Taken, RelayPairLogic.parsePoll(200, """{"taken":true}"""))
        assertEquals(RelayPairLogic.Poll.Gone, RelayPairLogic.parsePoll(404, """{"error":"no such code"}"""))
        assertEquals(RelayPairLogic.Poll.Gone, RelayPairLogic.parsePoll(403, ""))
        assertEquals(RelayPairLogic.Poll.Waiting, RelayPairLogic.parsePoll(500, ""))   // relay trouble: try again later
        assertEquals(RelayPairLogic.Poll.Waiting, RelayPairLogic.parsePoll(200, "not json"))
        assertEquals(RelayPairLogic.Poll.Req("", "1234", "Bin"), RelayPairLogic.parsePoll(200, """{"req":{"pin":"","newPin":"1234","name":"Bin"},"exp":1}"""))
    }

    @Test fun offersPairedOnlyOnDefaultRelay() {
        val d = "https://r.example"; val id = "0123456789abcdef"; val tok = "ab".repeat(24)
        assertTrue(RelayPairLogic.offers(false, false, "", "", "", d))            // no parent yet
        assertFalse(RelayPairLogic.offers(false, true, "", "", "", d))            // Home flavor
        assertTrue(RelayPairLogic.offers(true, false, "$d/", id, tok, d))        // paired, default relay
        assertFalse(RelayPairLogic.offers(true, false, "", id, tok, d))          // relay turned off
        assertFalse(RelayPairLogic.offers(true, false, "https://own.example", id, tok, d))   // own relay
        assertFalse(RelayPairLogic.offers(true, false, d, "", tok, d))
        assertFalse(RelayPairLogic.offers(true, false, d, id, "short", d))
    }

    @Test fun pollReqCarriesName() {
        val p = RelayPairLogic.parsePoll(200, """{"req":{"pin":"1234","newPin":"","name":"Mẹ"}}""")
        assertEquals(RelayPairLogic.Poll.Req("1234", "", "Mẹ"), p)
    }
}
