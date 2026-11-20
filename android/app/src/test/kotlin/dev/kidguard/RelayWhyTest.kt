package dev.kidguard

import org.junit.Assert.assertEquals
import org.junit.Test

class RelayWhyTest {
    @Test fun kinds() {
        assertEquals("dns", RelayWhy.kind("Unable to resolve host \"kg-relay.99-hoangtran.workers.dev\": No address associated with hostname"))
        assertEquals("net", RelayWhy.kind("failed to connect to relay.kidguard.asia/104.21.1.1 (port 443) after 10000ms"))
        assertEquals("net", RelayWhy.kind("timeout"))
        assertEquals("tls", RelayWhy.kind("javax.net.ssl.SSLHandshakeException: Trust anchor for certification path not found."))
        assertEquals("auth", RelayWhy.kind("HTTP 401"))
        assertEquals("", RelayWhy.kind("relay: queue full"))
    }
}
