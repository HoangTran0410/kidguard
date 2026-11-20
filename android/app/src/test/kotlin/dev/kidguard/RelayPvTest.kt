package dev.kidguard

import org.junit.Assert.*
import org.junit.Test

/** The `pvo` proof of a PIN-hash change on the relay (audit 2026-11-20 HIGH). */
class RelayPvTest {
    private val h = "a".repeat(64)

    @Test fun proofIsTheHashTheWorkerSaidOkTo() {
        assertEquals(h, RelayPv.proof(RelayPv.stamp("0123456789abcdef", h), "0123456789abcdef"))
    }

    @Test fun noProofForAnotherRelayIdOrNothingStored() {
        assertNull(RelayPv.proof(RelayPv.stamp("0123456789abcdef", h), "fedcba9876543210"))
        assertNull(RelayPv.proof("", "0123456789abcdef"))
        assertNull(RelayPv.proof("0123456789abcdef ", "0123456789abcdef"))   // "no PIN" was ok'd: nothing to prove with
    }
}
