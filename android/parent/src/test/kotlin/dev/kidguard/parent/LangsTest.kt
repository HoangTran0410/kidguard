package dev.kidguard.parent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The in-app language list: valid codes, tag normalising, and the same languages everywhere they are listed. */
class LangsTest {
    private val codes = Langs.ALL.map { it.first }

    @Test fun valid() {
        assertTrue(Langs.valid("")); assertTrue(Langs.valid("en")); assertTrue(Langs.valid("vi"))
        for (bad in listOf("fr", "EN", "vi-VN", " vi", "en,vi", "<script>", "x".repeat(300))) assertFalse(bad, Langs.valid(bad))
    }

    @Test fun norm() {
        assertEquals("", Langs.norm(null)); assertEquals("", Langs.norm(""))
        assertEquals("vi", Langs.norm("vi")); assertEquals("vi", Langs.norm("vi-VN,en-US"))
        assertEquals("en", Langs.norm("en-GB")); assertEquals("en", Langs.norm("EN_us"))
        assertEquals("", Langs.norm("fr-FR,vi")); assertEquals("", Langs.norm("und"))
    }

    @Test fun listIsClean() {
        assertEquals(codes.distinct(), codes)
        assertTrue(codes.none { it.isEmpty() })
        assertTrue(Langs.ALL.all { it.second.isNotBlank() })
    }

    @Test fun sameAsResources() {
        val cfg = Regex("""android:name="([^"]+)"""").findAll(File("src/main/res/xml/locales_config.xml").readText()).map { it.groupValues[1] }.toSet()
        assertEquals(codes.toSet(), cfg)
        val gradle = File("build.gradle.kts").readText()
        val filters = Regex("""localeFilters \+= listOf\(([^)]*)\)""").find(gradle)!!.groupValues[1].split(',').map { it.trim().trim('"') }.toSet()
        assertEquals(codes.toSet(), filters)
        for (c in codes) if (c != "en") assertTrue(c, File("src/main/res/values-$c/strings.xml").exists())
    }
}
