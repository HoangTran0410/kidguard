package dev.kidguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RelayHostTest {
    @Test fun sharedRelayHasTwoNames() {
        assertEquals(Relay.ALT_URL, Relay.other(Relay.DEFAULT_URL + "/"))
        assertEquals(Relay.DEFAULT_URL, Relay.other(Relay.ALT_URL))
        assertNull(Relay.other("https://my.own.relay"))
    }

    @Test fun baseSwapsOnlyTheSharedRelay() {
        try {
            Relay.useAlt = false
            assertEquals(Relay.DEFAULT_URL, Relay.base(Relay.DEFAULT_URL + "/"))
            Relay.useAlt = true
            assertEquals(Relay.ALT_URL, Relay.base(Relay.DEFAULT_URL))
            assertEquals("https://my.own.relay", Relay.base("https://my.own.relay/"))
        } finally { Relay.useAlt = false }
    }
}
