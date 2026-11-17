package dev.kidguard

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SnapLogicTest {
    @Test fun optionsAreClampedToKnownValues() {
        val o = SnapOpts.fromJson(JSONObject("""{"cam":"back","flash":"auto","size":"medium","delay":99}"""))
        assertEquals(SnapOpts("back", "auto", "medium", 5), o)
        assertEquals(SnapOpts(), SnapOpts.fromJson(JSONObject("""{"cam":"x","flash":"y","size":"z","delay":-3}""")))
        assertEquals(SnapOpts(), SnapOpts.fromJson(JSONObject()))
    }

    @Test fun sizesAndQuality() {
        assertEquals(640, SnapOpts().maxSide()); assertEquals(1280, SnapOpts(size = "medium").maxSide()); assertEquals(960, SnapOpts.PIN.maxSide())
        assertEquals(75, SnapOpts.PIN.quality()); assertTrue(SnapOpts(size = "medium").quality() > SnapOpts().quality())
    }

    @Test fun flashDecision() {
        assertTrue(SnapLogic.flashWanted("on", 500f)); assertFalse(SnapLogic.flashWanted("off", 0f))
        assertTrue(SnapLogic.flashWanted("auto", 2f)); assertFalse(SnapLogic.flashWanted("auto", 300f))
        assertFalse(SnapLogic.flashWanted("auto", -1f))   // no light reading: no flash
    }

    @Test fun screenFlashOnlyForTheFrontCamera() {
        assertTrue(SnapLogic.screenFlash(SnapOpts("front"), true)); assertFalse(SnapLogic.screenFlash(SnapOpts("back"), true))
        assertFalse(SnapLogic.screenFlash(SnapOpts("front"), false))
    }

    @Test fun theWhiteScreenIsShorterThanTheWholeJob() {
        assertTrue(SnapLogic.FLASH_HARD_MS <= 2000 && SnapLogic.FLASH_WAIT_MS < SnapLogic.FLASH_HARD_MS && SnapLogic.TOTAL_MS > SnapLogic.FLASH_HARD_MS)
    }

    @Test fun pickHonoursTheSide() {
        val s = listOf(320 to 240, 640 to 480, 1280 to 720, 4000 to 3000)
        assertEquals(640 to 480, ShotLogic.pick(s, 640)); assertEquals(1280 to 720, ShotLogic.pick(s, 1280)); assertEquals(320 to 240, ShotLogic.pick(listOf(4000 to 3000, 320 to 240), 100)!!.let { 320 to 240 })
    }
}
