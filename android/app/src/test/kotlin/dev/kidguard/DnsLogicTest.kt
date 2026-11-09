package dev.kidguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DnsLogicTest {
    private val h = "family.adguard-dns.com"

    @Test fun setsHostWhenDifferent() {
        assertEquals("hostname" to h, DnsLogic.plan(h, "opportunistic", "", false, "", ""))
        assertEquals("hostname" to h, DnsLogic.plan(h, "hostname", "dns.google", false, "", ""))   // drifted to another host
        assertEquals("hostname" to h, DnsLogic.plan(h, "off", "", true, "", ""))
    }

    @Test fun nothingWhenAlreadyRight() = assertNull(DnsLogic.plan(h, "hostname", h, true, "", ""))

    @Test fun offAndNotOursLeavesItAlone() = assertNull(DnsLogic.plan("", "off", "", false, "", ""))

    @Test fun pauseOrOffRestoresPrior() {
        assertEquals("off" to "", DnsLogic.plan("", "hostname", h, true, "off", ""))
        assertEquals("hostname" to "dns.google", DnsLogic.plan("", "hostname", h, true, "hostname", "dns.google"))
        assertEquals("opportunistic" to "", DnsLogic.plan("", "hostname", h, true, "", ""))   // unknown prior
        assertNull(DnsLogic.plan("", "off", "", true, "off", ""))
        assertNull(DnsLogic.plan("", "hostname", "dns.google", true, "hostname", "dns.google"))
    }
}
