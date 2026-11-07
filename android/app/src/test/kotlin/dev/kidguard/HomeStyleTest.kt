package dev.kidguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeStyleTest {
    private fun h(s: String) = Contrast.hex(s)
    private fun ok(msg: String, fg: Int, bg: Int, need: Double) {
        val r = Contrast.ratio(fg, bg)
        assertTrue("$msg: %.2f < $need".format(r), r >= need)
    }

    @Test fun ratioMatchesWcag() {
        assertEquals(21.0, Contrast.ratio(h("#000000"), h("#ffffff")), 0.01)
        assertEquals(1.0, Contrast.ratio(h("#777777"), h("#777777")), 0.001)
        assertEquals(4.48, Contrast.ratio(h("#777777"), h("#ffffff")), 0.02)   // the classic just-fails grey
    }

    @Test fun onPicksWhiteOnSolidAndDarkOnLight() {
        assertEquals(Contrast.WHITE, Contrast.on(h("#7c3aed")))
        assertEquals(Contrast.INK, Contrast.on(h("#ede9fe")))
        assertEquals(Contrast.INK, Contrast.on(h("#fde047")))
    }

    /** Every accent x light / dark x every fill role: the glyph or text on it passes AA (4.5 text, 3 icons). */
    @Test fun everyRolePassesForEveryAccentAndTheme() {
        for (a in HomeStyle.ACCENTS) for (dark in listOf(false, true)) {
            val p = HomeStyle.palette(a.id, dark)
            val tag = "${a.id}/${if (dark) "dark" else "light"}"
            // filled shapes
            ok("$tag onSolid", p.onSolid, p.solid, Contrast.TEXT)        // "Xin thêm giờ" label, Xin giờ glyph
            ok("$tag onTint", p.onTint, p.tint, Contrast.TEXT)           // Tới nơi / Đón con glyphs (and any label)
            ok("$tag onDanger", p.onDanger, p.danger, Contrast.TEXT)     // SOS glyph on red
            ok("$tag onWarn", p.onWarn, p.warn, Contrast.LARGE)          // clock badge glyph
            ok("$tag warnText/warnTint", p.warnText, p.warnTint, Contrast.TEXT)
            // tinted circle and solid shapes stand out from the card (non-text 3:1 is not required for a labelled
            // button, but the glyph inside must pass against its own fill, checked above)
            for (card in HomeStyle.cardWorst(p)) {
                ok("$tag text/card", p.text, card, Contrast.TEXT)
                ok("$tag sub/card", p.sub, card, Contrast.TEXT)
                ok("$tag accentText/card", p.accentText, card, Contrast.TEXT)
                ok("$tag dangerText/card", p.dangerText, card, Contrast.TEXT)   // "SOS" label
                ok("$tag warnText/card", p.warnText, card, Contrast.TEXT)
            }
            // opaque sheets / menus / drawer
            for (bg in listOf(p.sheet, p.bg)) {
                ok("$tag text/sheet", p.text, bg, Contrast.TEXT); ok("$tag sub/sheet", p.sub, bg, Contrast.TEXT)
                ok("$tag accentText/sheet", p.accentText, bg, Contrast.TEXT)
            }
            // the warn badge sits on a greyed icon with a ring of the sheet colour: the ring separates it
            ok("$tag warn/ring", p.warn, p.sheet, Contrast.LARGE)
        }
    }

    @Test fun inkOnEveryBuiltInStyleOfPixelPasses() {
        // flat wallpapers from white to black: always >= 4.5 (with a scrim where no ink reaches it alone)
        for (v in 0..255 step 5) {
            val c = Contrast.argb(255, v, v, v)
            val ink = HomeStyle.ink(IntArray(64) { c })
            val eff = if (Contrast.a(ink.scrim) == 0) c else Contrast.over(ink.scrim, c)
            assertTrue("grey $v: ${ink.ratio}", Contrast.ratio(ink.text, eff) >= Contrast.TEXT)
        }
    }

    @Test fun inkPicksDarkOnLightAndLightOnDark() {
        assertTrue(HomeStyle.ink(IntArray(10) { h("#fef3c7") }).darkText)
        assertTrue(!HomeStyle.ink(IntArray(10) { h("#0b1026") }).darkText)
        assertEquals(0, Contrast.a(HomeStyle.ink(IntArray(10) { h("#ffffff") }).scrim))
    }

    @Test fun mixedRegionGetsAScrimThatHolds() {
        // half white, half black: no ink passes on its own
        val px = IntArray(100) { if (it % 2 == 0) h("#ffffff") else h("#000000") }
        val ink = HomeStyle.ink(px)
        assertTrue(Contrast.a(ink.scrim) > 0)
        for (c in px.distinct()) assertTrue(Contrast.ratio(ink.text, Contrast.over(ink.scrim, c)) >= Contrast.TEXT)
    }

    @Test fun aFewStarsAreIgnored() {
        val px = IntArray(100) { if (it < 2) h("#ffffff") else h("#0b1026") }
        val ink = HomeStyle.ink(px)
        assertTrue(!ink.darkText); assertEquals(0, Contrast.a(ink.scrim))
    }

    @Test fun presetsAndDefaults() {
        assertEquals(3, HomeStyle.preset("be").cols); assertEquals(5, HomeStyle.preset("gon").cols)
        assertEquals("teen", HomeStyle.preset("nope").id)
        assertEquals("blue", HomeStyle.accent("nope").id)
        for (p in HomeStyle.PRESETS) { assertTrue(p.wallLight in HomeStyle.WALLS); assertTrue(p.wallDark in HomeStyle.WALLS) }
        assertEquals(8, HomeStyle.ACCENTS.size)
    }
}
