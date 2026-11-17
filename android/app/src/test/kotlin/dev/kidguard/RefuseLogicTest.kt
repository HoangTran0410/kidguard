package dev.kidguard

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RefuseLogicTest {
    @Test fun vietnameseAccessibilityDialog() =
        assertEquals(setOf("acc"), RefuseLogic.kinds("Thông báo. Ứng dụng phát hiện thiết bị đang bật Trợ năng của ứng dụng khác. Vui lòng tắt để tiếp tục sử dụng."))

    @Test fun accentsAndCaseDoNotMatter() =
        assertEquals(setOf("acc"), RefuseLogic.kinds("VUI LONG TAT TRO NANG"))

    @Test fun debuggingToast() =
        assertEquals(setOf("dev"), RefuseLogic.kinds("USB debugging is enabled. This app cannot run (50052)."))

    @Test fun developerOptionsVietnamese() =
        assertEquals(setOf("dev"), RefuseLogic.kinds("Vui lòng tắt Tùy chọn nhà phát triển để đảm bảo an toàn"))

    @Test fun vpnAndAccessibilityTogether() =
        assertEquals(setOf("acc", "vpn"), RefuseLogic.kinds("For your security, turn off VPN and accessibility services."))

    @Test fun mentionWithoutComplaintIsNotARefusal() {
        assertTrue(RefuseLogic.kinds("Kết nối VPN nhanh, 50 quốc gia").isEmpty())
        assertTrue(RefuseLogic.kinds("Cài đặt trợ năng").isEmpty())
    }

    @Test fun longScreenIsNotADialog() =
        assertTrue(RefuseLogic.kinds("Hãy tắt VPN ".repeat(80)).isEmpty())

    @Test fun adbIsAWholeWord() {
        assertTrue(RefuseLogic.kinds("Không thể tải: badbadge error").isEmpty())
        assertEquals(setOf("dev"), RefuseLogic.kinds("ADB detected, cannot continue"))
    }

    @Test fun missingSkipsWhatTheRulesCover() {
        val r = Rules(accPause = setOf("a.bank"), devPause = emptySet(), tcpPause = setOf("a.bank"), vpnPause = emptySet())
        assertEquals(setOf("vpn"), RefuseLogic.missing(setOf("acc", "dev", "vpn"), "a.bank", r))
    }

    @Test fun addMergesPerAppAndKeepsNewestFirst() {
        var l = JSONArray()
        l = RefuseLogic.add(l, "a", "A", setOf("acc"), 1000)
        l = RefuseLogic.add(l, "b", "B", setOf("vpn"), 2000)
        l = RefuseLogic.add(l, "a", "A", setOf("dev"), 3000)
        assertEquals(2, l.length())
        assertEquals("a", l.getJSONObject(0).getString("pkg"))
        assertEquals("[\"acc\",\"dev\"]", l.getJSONObject(0).getJSONArray("kinds").toString())
    }

    @Test fun liveDropsCoveredAndOld() {
        val now = 10 * 86_400_000L
        var l = JSONArray()
        l = RefuseLogic.add(l, "old", "Old", setOf("acc"), 1000)
        l = RefuseLogic.add(l, "a", "A", setOf("acc", "vpn"), now - 1000)
        val r = Rules(accPause = setOf("a"), vpnPause = emptySet())
        val out = RefuseLogic.live(l, r, now)
        assertEquals(1, out.length())
        assertEquals("[\"vpn\"]", out.getJSONObject(0).getJSONArray("kinds").toString())
    }
}
