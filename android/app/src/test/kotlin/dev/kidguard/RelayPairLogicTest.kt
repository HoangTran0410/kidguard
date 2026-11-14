package dev.kidguard

import org.junit.Assert.assertEquals
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
        assertTrue(RelayPairLogic.usable("https://kidguard.99-hoangtran.workers.dev"))
        assertTrue(!RelayPairLogic.usable("http://x"))
    }

    @Test fun pollReplies() {
        assertEquals(RelayPairLogic.Poll.Waiting, RelayPairLogic.parsePoll(200, """{"waiting":true,"exp":1}"""))
        assertEquals(RelayPairLogic.Poll.Taken, RelayPairLogic.parsePoll(200, """{"taken":true}"""))
        assertEquals(RelayPairLogic.Poll.Gone, RelayPairLogic.parsePoll(404, """{"error":"no such code"}"""))
        assertEquals(RelayPairLogic.Poll.Gone, RelayPairLogic.parsePoll(403, ""))
        assertEquals(RelayPairLogic.Poll.Waiting, RelayPairLogic.parsePoll(500, ""))   // relay trouble: try again later
        assertEquals(RelayPairLogic.Poll.Waiting, RelayPairLogic.parsePoll(200, "not json"))
        assertEquals(RelayPairLogic.Poll.Req("", "1234"), RelayPairLogic.parsePoll(200, """{"req":{"pin":"","newPin":"1234","name":"Bin"},"exp":1}"""))
    }
}
