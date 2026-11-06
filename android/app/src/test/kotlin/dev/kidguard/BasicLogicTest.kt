package dev.kidguard

import dev.kidguard.BasicLogic.Node
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BasicLogicTest {
    private val blocked = mapOf("com.chrome" to "blocked", "dev.kidguard" to "blocked", "com.home" to "daily", "com.android.settings" to "daily", "com.sys" to "bedtime")
    private val prot = setOf("com.home")

    @Test fun bouncesBlockedOnly() {
        assertEquals("blocked", BasicLogic.bounceReason("com.chrome", "dev.kidguard", blocked, prot))
        assertNull(BasicLogic.bounceReason("com.other", "dev.kidguard", blocked, prot))
        assertNull(BasicLogic.bounceReason(null, "dev.kidguard", blocked, prot))
    }

    @Test fun neverBouncesOwnLauncherSystemDialer() {
        assertNull(BasicLogic.bounceReason("dev.kidguard", "dev.kidguard", blocked, prot))      // own package
        assertNull(BasicLogic.bounceReason("com.home", "dev.kidguard", blocked, prot))          // protected (launcher)
        assertNull(BasicLogic.bounceReason("com.android.settings", "dev.kidguard", blocked, prot))
        val b = blocked + mapOf("com.android.systemui" to "daily", "com.android.phone" to "daily", "com.android.emergency" to "daily", "com.google.android.dialer" to "daily")
        for (p in listOf("com.android.systemui", "com.android.phone", "com.android.emergency", "com.google.android.dialer"))
            assertNull(p, BasicLogic.bounceReason(p, "dev.kidguard", b, emptySet()))
    }

    @Test fun reasonTextKnownAndUnknown() {
        assertEquals("đã hết giờ dùng máy hôm nay", BasicLogic.reasonText("daily"))
        assertEquals("x", BasicLogic.reasonText("x"))
    }

    private fun n(vararg t: String) = t.map { Node(it) }

    @Test fun appInfoPageIsBouncedVi() {
        assertTrue(BasicLogic.selfProtect("com.android.settings", n("Thông tin ứng dụng", "KidGuard", "Mở", "Gỡ cài đặt", "Buộc dừng")))
        assertTrue(BasicLogic.selfProtect("com.coloros.safecenter", n("KidGuard", "Buộc dừng")))
    }

    @Test fun appInfoPageIsBouncedEn() {
        assertTrue(BasicLogic.selfProtect("com.android.settings", n("App info", "KidGuard", "Open", "Uninstall", "Force stop")))
        assertTrue(BasicLogic.selfProtect("com.samsung.android.settings", n("KidGuard", "Uninstall")))
        assertTrue(BasicLogic.selfProtect("com.miui.securitycenter", n("kidguard", "FORCE STOP")))
    }

    @Test fun deviceAdminDeactivate() {
        assertTrue(BasicLogic.selfProtect("com.android.settings", n("KidGuard", "Huỷ kích hoạt ứng dụng quản trị thiết bị này")))
        assertTrue(BasicLogic.selfProtect("com.android.settings", n("KidGuard", "Hủy kích hoạt")))
        assertTrue(BasicLogic.selfProtect("com.android.settings", n("KidGuard", "Deactivate this device admin app")))
    }

    @Test fun installerUninstallDialog() {
        assertTrue(BasicLogic.selfProtect("com.google.android.packageinstaller", n("Gỡ cài đặt ứng dụng này?", "KidGuard")))
        assertTrue(BasicLogic.selfProtect("com.android.packageinstaller", n("Do you want to uninstall this app?", "KidGuard", "Uninstall")))
    }

    @Test fun accessibilityDetailPage() {
        assertTrue(BasicLogic.selfProtect("com.android.settings", n("KidGuard", "Dùng KidGuard")))
        assertTrue(BasicLogic.selfProtect("com.android.settings", n("KidGuard", "KidGuard: cho bố mẹ xem màn hình và giúp con thao tác từ xa")))
        assertTrue(BasicLogic.selfProtect("com.android.settings", listOf(Node("KidGuard"), Node("Tắt", clickable = true))))
    }

    @Test fun settingsAppListWithoutControlPasses() {
        assertFalse(BasicLogic.selfProtect("com.android.settings", n("Ứng dụng", "Chrome", "KidGuard", "Maps", "Zalo")))
        assertFalse(BasicLogic.selfProtect("com.android.settings", n("Trợ năng", "KidGuard", "Tắt")))                 // status text, not a button
        assertFalse(BasicLogic.selfProtect("com.android.settings", n("Truy cập dữ liệu sử dụng", "KidGuard", "Không được phép")))
    }

    @Test fun otherAppsPageWithControlsPasses() {
        assertFalse(BasicLogic.selfProtect("com.android.settings", n("Thông tin ứng dụng", "Chrome", "Gỡ cài đặt", "Buộc dừng")))
        assertFalse(BasicLogic.selfProtect("com.android.settings", n("KidGuard", "Cho phép", "Không cho phép")))        // permission dialog
    }

    @Test fun nonSettingsPackagesIgnored() {
        assertFalse(BasicLogic.selfProtect("com.chrome", n("KidGuard", "Gỡ cài đặt")))
        assertFalse(BasicLogic.selfProtect("dev.kidguard", n("KidGuard", "Gỡ cài đặt")))
    }

    @Test fun settingsPackageList() {
        for (p in listOf("com.android.settings", "com.coloros.safecenter", "com.oppo.safe", "com.miui.securitycenter", "com.samsung.android.settings.x",
            "com.android.packageinstaller", "com.google.android.packageinstaller", "com.android.permissioncontroller"))
            assertTrue(p, BasicLogic.isSettingsPkg(p))
        for (p in listOf("com.android.chrome", "dev.kidguard", "com.android.systemui", "com.zing.zalo"))
            assertFalse(p, BasicLogic.isSettingsPkg(p))
    }
}
