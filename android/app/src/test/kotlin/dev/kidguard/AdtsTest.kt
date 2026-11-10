package dev.kidguard

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdtsTest {
    /** ADTS header, no CRC: AAC LC, 16 kHz (index 8), mono, frame length [len] incl. 7-byte header. */
    private fun hdr(len: Int) = byteArrayOf(0xFF.toByte(), 0xF1.toByte(), ((1 shl 6) or (8 shl 2) or 0).toByte(),
        ((1 shl 6) or (len shr 11)).toByte(), ((len shr 3) and 0xFF).toByte(), (((len and 7) shl 5) or 0x1F).toByte(), 0xFC.toByte())

    @Test fun framesAndConfig() {
        val b = hdr(17) + ByteArray(10) { 1 } + hdr(12) + ByteArray(5) { 2 } + hdr(40) + ByteArray(3)   // last one cut
        val i = Adts.parse(b)!!
        assertEquals(listOf(Adts.Frame(7, 10), Adts.Frame(24, 5)), i.frames)
        assertEquals(16_000, i.rate); assertEquals(1, i.channels); assertEquals(2, i.profile)
        assertArrayEquals(byteArrayOf(0x14, 0x08), Adts.asc(i))   // AAC LC, 16 kHz, mono
        assertEquals(128L, i.durationMs)
    }

    @Test fun garbage() { assertNull(Adts.parse(ByteArray(20))); assertNull(Adts.parse(ByteArray(0))) }
}
