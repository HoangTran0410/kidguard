package dev.kidguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanNameTest {
    @Test fun nameIsStableAndHidesTheId() {
        val n = LanName.of("kid-123")
        assertEquals(n, LanName.of("kid-123"))
        assertEquals(15, n.length)   // "kg-" + 12 hex
        assertFalse(n.contains("123"))
    }

    @Test fun matchesRenamedServices() {
        val n = LanName.of("abc")
        assertTrue(LanName.matches(n, "abc"))
        assertTrue(LanName.matches("$n (2)", "abc"))
        assertFalse(LanName.matches(n, "abd"))
        assertFalse(LanName.matches(n, ""))
    }

    @Test fun proofNeedsTheToken() {
        val h = LanName.tokenHash("tok")
        assertEquals(64, h.length)
        assertEquals(LanName.proof(h, "nonce-1234567890"), LanName.proof(LanName.tokenHash("tok"), "nonce-1234567890"))
        assertFalse(LanName.proof(h, "nonce-1234567890") == LanName.proof(LanName.tokenHash("tok2"), "nonce-1234567890"))
        assertFalse(LanName.proof(h, "nonce-1234567890") == LanName.proof(h, "nonce-0987654321"))
        // the same vector as core.js lanProof (WebCrypto) and Python hmac: the page and both apps agree byte for byte
        assertEquals("e596ba42e034e7b3d8cd523c8d2dfbe74ce9816634a5ea2db0006857657944d0", LanName.proof(LanName.tokenHash("tok"), "abc"))
    }
}
