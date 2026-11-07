package dev.kidguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyTest {
    @Test fun pinLock() {
        assertEquals(0, Safety.pinLockMs(4))
        assertEquals(5 * 60_000L, Safety.pinLockMs(5))
        assertEquals(10 * 60_000L, Safety.pinLockMs(6))
        assertEquals(64 * 5 * 60_000L, Safety.pinLockMs(50))   // capped
    }

    @Test fun pairBlock() {
        assertEquals(0, Safety.pairBlockMs(1)); assertEquals(0, Safety.pairBlockMs(2))
        assertEquals(30_000L, Safety.pairBlockMs(3))
        assertEquals(0, Safety.pairBlockMs(4))
        assertEquals(60_000L, Safety.pairBlockMs(6))
        assertEquals(120_000L, Safety.pairBlockMs(9))
    }

    @Test fun hostAllowed() {
        listOf(null, "", "192.168.1.5:8770", "127.0.0.1", "localhost:8770", "[::1]:8770", "[fe80::1%25wlan0]",
            "kid-an:8770", "kid-an.tail1234.ts.net", "100.65.87.104:8770").forEach { assertTrue(it.toString(), Safety.hostOk(it)) }
    }

    @Test fun hostRejected() {
        listOf("evil.com", "evil.com:8770", "192.168.1.5.evil.com", "a.b", "ts.net.evil.com", "x.ts.net:80/../").forEach {
            assertFalse(it, Safety.hostOk(it))
        }
    }

    @Test fun corsOrigin() {
        listOf("http://192.168.1.6:8770", "http://10.0.0.2:8770", "http://172.20.1.1:8770", "http://100.65.87.104:8770",
            "http://kid-an.tail1234.ts.net:8770", "http://kid-an:8770", "http://localhost:9001", "http://[fe80::1]:8770").forEach {
            assertTrue(it, Safety.corsOriginOk(it))
        }
        listOf(null, "", "null", "https://192.168.1.6:8770", "http://8.8.8.8:8770", "http://172.32.0.1:8770", "http://evil.com:8770",
            "http://192.168.1.5.evil.com", "http://[2001:db8::1]:8770", "http://192.168.1.6:8770/x", "http://999.1.1.1").forEach {
            assertFalse(it.toString(), Safety.corsOriginOk(it))
        }
    }
}
