package dev.kidguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CmdGateTest {
    @Test fun dangerousNeedPin() {
        for (t in listOf("release", "unpairAll", "unpair", "clearData", "reboot", "tailscaleOff", "openPairing", "totpSet", "tgSet", "tgCode", "tgOff", "pinSet", "pinResetCancel"))
            assertTrue(t, CmdGate.needs(t))
    }

    @Test fun everydayCommandsDoNot() {
        for (t in listOf("lock", "ring", "message", "locate", "answer", "wake", "pinReset", "update", "setting", "sosAck"))
            assertFalse(t, CmdGate.needs(t))
    }

    @Test fun relaySameUrlIsFreeChangeIsNot() {
        assertFalse(CmdGate.needs("relay", "https://r.example.dev/", "https://r.example.dev"))
        assertTrue(CmdGate.needs("relay", "https://other.example.dev", "https://r.example.dev"))
        assertTrue(CmdGate.needs("relay", "", "https://r.example.dev"))           // turning it off
        assertTrue(CmdGate.needs("relay", "https://r.example.dev", ""))           // turning it on
        assertTrue(CmdGate.needs("relay", "", ""))                                // "off" when already off: still gated (no free probe)
    }

    @Test fun newPinShape() {
        assertEquals("1234", CmdGate.newPin("1234"))
        assertEquals("12345678", CmdGate.newPin("12345678"))
        assertNull(CmdGate.newPin("123"))
        assertNull(CmdGate.newPin("123456789"))
        assertNull(CmdGate.newPin("12a4"))
        assertNull(CmdGate.newPin(null))
    }

    @Test fun resetDue() {
        assertFalse(CmdGate.resetDue(0, 5))
        assertFalse(CmdGate.resetDue(10, 9))
        assertTrue(CmdGate.resetDue(10, 10))
    }
}
