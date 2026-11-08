package dev.kidguard

import org.junit.Assert.assertEquals
import org.junit.Test

class PinHashTest {
    /** The pre-0.9.8 form: stored hashes must still match. */
    private fun oldSha(s: String) = java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun oldHash(pin: String, salt: String): String {
        if (salt.isEmpty()) return oldSha("kg:$pin")
        var h = oldSha("$salt:$pin"); repeat(9_999) { h = oldSha(h + salt) }; return h
    }

    @Test fun sameAsBefore() {
        for ((pin, salt) in listOf("1234" to "a1b2c3d4e5f60718", "000000" to "ffffffffffffffff", "98765432" to "", "1111" to "0123456789abcdef")) {
            assertEquals(oldHash(pin, salt), Rules.hashPin(pin, salt))
        }
        assertEquals(oldSha("x"), Rules.sha256("x"))
    }

    @Test fun faster() {
        val t0 = System.nanoTime(); oldHash("1234", "a1b2c3d4e5f60718"); val t1 = System.nanoTime(); Rules.hashPin("1234", "a1b2c3d4e5f60718"); val t2 = System.nanoTime()
        println("KG.perf pinHash old=${(t1 - t0) / 1_000_000}ms new=${(t2 - t1) / 1_000_000}ms")
    }
}
