package dev.kidguard

import dev.kidguard.ControlLogic.Mode
import dev.kidguard.ControlLogic.Phone
import dev.kidguard.ControlLogic.Tile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlLogicTest {
    private val kid34 = Phone(sdk = 34, owner = true, settingsOk = false, noAirplane = true, writeSys = true, dnd = true, flash = true, hasBt = true)
    private val home34 = Phone(sdk = 34, owner = false, settingsOk = true, noAirplane = false, writeSys = false, dnd = false, flash = true, hasBt = true)

    @Test fun deviceOwnerTogglesWifiAndBluetoothItself() {
        assertEquals(Mode.DIRECT, ControlLogic.mode(Tile.WIFI, kid34))
        assertEquals(Mode.DIRECT, ControlLogic.mode(Tile.BT, kid34))
    }

    @Test fun parentRulesLockWhatOnlySettingsCanChange() {
        assertEquals(Mode.LOCKED, ControlLogic.mode(Tile.AIRPLANE, kid34))
        assertEquals(Mode.LOCKED, ControlLogic.mode(Tile.DATA, kid34))
        assertEquals(Mode.LOCKED, ControlLogic.mode(Tile.AIRPLANE, kid34.copy(noAirplane = false)))   // still no Settings
        assertEquals(Mode.PAGE, ControlLogic.mode(Tile.AIRPLANE, kid34.copy(noAirplane = false, settingsOk = true)))
    }

    @Test fun plainAppOnNewAndroidOpensTheSystemPanel() {
        assertEquals(Mode.PAGE, ControlLogic.mode(Tile.WIFI, home34))
        assertEquals(Mode.PAGE, ControlLogic.mode(Tile.BT, home34))
        assertEquals(Mode.PAGE, ControlLogic.mode(Tile.ROTATE, home34))
        assertEquals(Mode.PAGE, ControlLogic.mode(Tile.DND, home34))
        assertEquals(Mode.DIRECT, ControlLogic.mode(Tile.TORCH, home34))
    }

    @Test fun oldAndroidPlainAppTogglesDirectly() {
        val old = home34.copy(sdk = 27)
        assertEquals(Mode.DIRECT, ControlLogic.mode(Tile.WIFI, old))
        assertEquals(Mode.DIRECT, ControlLogic.mode(Tile.BT, old))
    }

    @Test fun missingHardwareIsHidden() {
        assertEquals(Mode.HIDDEN, ControlLogic.mode(Tile.TORCH, kid34.copy(flash = false)))
        assertEquals(Mode.HIDDEN, ControlLogic.mode(Tile.BT, kid34.copy(hasBt = false)))
    }

    @Test fun brightnessRoundTrips() {
        for (max in listOf(255, 2047, 4095)) for (v in listOf(1, max / 3, max)) {
            val back = ControlLogic.brightValue(ControlLogic.brightPos(v, max), max)
            assertTrue("$v/$max -> $back", kotlin.math.abs(back - v) <= 1 + max / 500)
        }
        assertEquals(1, ControlLogic.brightValue(0f, 255))
        assertEquals(255, ControlLogic.brightValue(1f, 255))
    }

    @Test fun brightnessDetailTilesFollowWhatThePhoneAllows() {
        assertEquals(Mode.DIRECT, ControlLogic.mode(Tile.AUTO_BRIGHT, kid34))
        assertEquals(Mode.PAGE, ControlLogic.mode(Tile.AUTO_BRIGHT, home34))
        // no night light on the phone: no tile; with one, secure settings write it, else its page (or locked for a kid)
        assertEquals(Mode.HIDDEN, ControlLogic.mode(Tile.NIGHT, kid34))
        assertEquals(Mode.DIRECT, ControlLogic.mode(Tile.NIGHT, kid34.copy(night = true, secure = true)))
        assertEquals(Mode.LOCKED, ControlLogic.mode(Tile.NIGHT, kid34.copy(night = true)))
        assertEquals(Mode.PAGE, ControlLogic.mode(Tile.NIGHT, home34.copy(night = true)))
        // dark mode: AOSP locks setNightMode (measured on the API 34 emulator) -> the display page
        assertEquals(Mode.LOCKED, ControlLogic.mode(Tile.DARK, kid34))
        assertEquals(Mode.PAGE, ControlLogic.mode(Tile.DARK, home34))
        assertEquals(Mode.DIRECT, ControlLogic.mode(Tile.DARK, kid34.copy(darkLocked = false)))
        assertEquals(Mode.HIDDEN, ControlLogic.mode(Tile.DARK, home34.copy(sdk = 28)))
        assertEquals(Mode.DIRECT, ControlLogic.mode(Tile.RINGER, home34))
    }
}
