package dev.kidguard

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stuck audit 2026-11-17: a broken home.json reset the kid's layout with no way back; now the last good save comes back. */
class HomeFileTest {
    private val m = HomeLayout.fresh("teen", (1..6).map { "com.app$it" }, listOf("com.sms"))
    private fun dir(): File = Files.createTempDirectory("kghome").toFile()

    @Test fun brokenFileFallsBackToLastGoodSave() {
        val d = dir()
        HomeFile.write(d, m)
        File(d, "home.json").writeText("{\"pages\":")   // torn / corrupt
        val back = HomeFile.read(d)
        assertEquals(m.toJson().toString(), back!!.toJson().toString())
        assertTrue(File(d, "home.json.bad").exists())
        assertEquals(m.toJson().toString(), HomeLayout.parse(File(d, "home.json").readText())!!.toJson().toString())   // put back
    }

    @Test fun nothingGoodLeftIsNull() {
        val d = dir()
        File(d, "home.json").writeText("garbage")
        assertNull(HomeFile.read(d))
    }

    @Test fun crashResetDropsTheBackupToo() {
        val d = dir()
        HomeFile.write(d, m)
        HomeFile.reset(d)
        assertFalse(File(d, "home.json").exists())
        assertFalse(File(d, "home.json.prev").exists())
        assertTrue(File(d, "home.json.bad").exists())   // restoreBad after an update still has it
    }
}
