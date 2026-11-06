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
}
