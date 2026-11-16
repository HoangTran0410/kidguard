package dev.kidguard

import org.junit.Assert.assertEquals
import org.junit.Test

class ChanTest {
    @Test fun firstUnblockedId() {
        assertEquals("nav", Chan.pick("nav") { false })
        assertEquals("nav.2", Chan.pick("nav") { it == "nav" || it == "nav.1" })
        assertEquals("nav", Chan.pick("nav") { true })   // all blocked: give up on the base id
    }
}
