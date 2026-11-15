package dev.kidguard

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsFindTest {
    private val secs = listOf("Màn chính Số cột Cỡ biểu tượng", "Điều hướng Vuốt thay cho 3 nút cử chỉ", "Hình nền Làm mờ")

    @Test fun accentFree() {
        assertEquals(1, SettingsFind.first("dieu huong", secs))
        assertEquals(1, SettingsFind.first("ĐIỀU", secs))
        assertEquals(2, SettingsFind.first("hinh nen", secs))
    }

    @Test fun allWordsInOneSection() {
        assertEquals(1, SettingsFind.first("vuốt 3 nút", secs))
        assertEquals(-1, SettingsFind.first("vuốt hình", secs))
    }

    @Test fun emptyOrNone() {
        assertEquals(-1, SettingsFind.first("  ", secs))
        assertEquals(-1, SettingsFind.first("wifi", secs))
        assertEquals(0, SettingsFind.first("cỡ", secs))
    }
}
