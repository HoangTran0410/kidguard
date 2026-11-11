package dev.kidguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportScrubTest {
    private fun s(x: String, keep: String? = null) = SupportScrub.text(x, keep)

    @Test fun secretsGo() {
        assertEquals("{\"token\":\"…\",\"pin\":\"…\"}", s("{\"token\":\"abc\",\"pin\":\"1234\"}"))
        assertFalse(s("GET /c/0123456789abcdef0123/state?k=secret").contains("secret"))
        assertFalse(s("X-KG-Token: abcdefgh12345").contains("abcdefgh12345"))
        assertFalse(s("https://api.telegram.org/bot123456789:AAHdqTcvCH1vGWJxfSeofSAs0K5PALDsawQ/getUpdates").contains("AAHdq"))
        assertFalse(s("id 0f4608517f188ea3a971ae6f").contains("0f4608517f188ea3"))
    }

    @Test fun personalGoes() {
        assertEquals("mail email@x", s("mail a.b@gmail.com"))
        assertEquals("mac xx:xx:xx:xx:xx:xx", s("mac aa:bb:cc:dd:ee:ff"))
        assertFalse(s("fix 21.0278512, 105.8341987 acc 12").contains("21.0278"))
        assertFalse(s("{\"ssid\":\"Nha Minh\",\"rssi\":-60}").contains("Nha Minh"))
        assertFalse(s("note Bé Na").contains("Bé"))
        assertEquals("blocked … …", s("blocked Liên Quân"))
    }

    @Test fun ipsPublicGoLanStays() {
        assertEquals("lan 192.168.1.5 ts 100.79.11.22 pub x.x.x.x", s("lan 192.168.1.5 ts 100.79.11.22 pub 113.161.5.9"))
    }

    @Test fun appNamesHashedOwnKept() {
        val out = s("blocked com.zing.zalo by dev.kidguard.Guard at java.lang.Thread.run(Thread.java:1012) android.os.Handler")
        assertFalse(out.contains("zalo")); assertTrue(out.contains("app#"))
        assertTrue(out.contains("dev.kidguard.Guard")); assertTrue(out.contains("java.lang.Thread.run")); assertTrue(out.contains("android.os.Handler"))
        assertTrue(s("blocked com.zing.zalo", keep = "com.zing.zalo").contains("com.zing.zalo"))
        assertTrue(s("relay kidguard.99-hoangtran.workers.dev ok").contains("workers.dev"))
    }

    @Test fun twiceIsSame() {
        val x = "blocked com.zing.zalo at 113.161.5.9 {\"token\":\"x\"} Bé"
        assertEquals(s(x), s(s(x)))
    }
}
