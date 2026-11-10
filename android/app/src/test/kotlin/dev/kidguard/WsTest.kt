package dev.kidguard

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream

class WsTest {
    @Test fun acceptKeyRfcExample() {
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", Ws.accept("dGhlIHNhbXBsZSBub25jZQ=="))
    }

    @Test fun maskedFrameRoundTripAllLengthForms() {
        for (n in listOf(0, 5, 125, 126, 65535, 65536, 200_000)) {
            val p = ByteArray(n) { (it * 7).toByte() }
            val f = Ws.frame(Ws.TEXT, p, byteArrayOf(1, 2, 3, 4))
            val (op, back) = Ws.read(ByteArrayInputStream(f), 300_000)!!
            assertEquals(Ws.TEXT, op); assertArrayEquals("len $n", p, back)
        }
    }

    @Test fun rfcMaskedHello() {
        // RFC 6455 5.7: a masked "Hello"
        val f = Ws.frame(Ws.TEXT, "Hello".toByteArray(), byteArrayOf(0x37, 0xfa.toByte(), 0x21, 0x3d))
        assertArrayEquals(byteArrayOf(0x81.toByte(), 0x85.toByte(), 0x37, 0xfa.toByte(), 0x21, 0x3d, 0x7f, 0x9f.toByte(), 0x4d, 0x51, 0x58), f)
    }

    @Test fun serverUnmaskedFragmentsJoined() {
        // RFC 6455 5.7: "Hel" + "lo" in two unmasked frames
        val s = byteArrayOf(0x01, 0x03, 0x48, 0x65, 0x6c, 0x80.toByte(), 0x02, 0x6c, 0x6f)
        val (op, p) = Ws.read(ByteArrayInputStream(s), 100)!!
        assertEquals(Ws.TEXT, op); assertEquals("Hello", p.toString(Charsets.UTF_8))
    }

    @Test fun pingAndEnd() {
        val s = byteArrayOf(0x89.toByte(), 0x02, 0x68, 0x69)
        val i = ByteArrayInputStream(s)
        val (op, p) = Ws.read(i, 100)!!
        assertEquals(Ws.PING, op); assertEquals("hi", p.toString(Charsets.UTF_8))
        assertNull(Ws.read(i, 100))
    }

    @Test(expected = RuntimeException::class) fun tooBig() {
        Ws.read(ByteArrayInputStream(Ws.frame(Ws.TEXT, ByteArray(200))), 100)
    }
}
