package dev.kidguard

import org.junit.Assert.assertEquals
import org.junit.Test

class TextTest {
    @Test fun foldDropsVietnameseMarks() {
        assertEquals("dong ho", Text.fold("Đồng Hồ"))
        assertEquals("tin nhan", Text.fold("Tin nhắn"))
    }
    @Test fun foldSpacesCollapses() = assertEquals("an toan cho con", Text.foldSpaces("An  toàn\n cho\tcon"))
    @Test fun normKeepsAccents() = assertEquals("gỡ cài đặt", Text.norm("  Gỡ Cài Đặt "))
}
