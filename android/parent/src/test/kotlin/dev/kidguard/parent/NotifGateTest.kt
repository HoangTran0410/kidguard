package dev.kidguard.parent

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Same gate as the kid app's NotifGateTest (INCIDENT 2026-11-20, a notification re-posted every tick re-alerted):
 * every `Notification.Builder(` chain sets `.setOnlyAlertOnce(...)` (not `false`; ongoing: always), or is listed in
 * [ONE_SHOT] with why it cannot repeat. [Notif.post] passes `quiet` (an update of a shown alert is silent).
 */
class NotifGateTest {
    companion object {
        val ONE_SHOT = mapOf(
            "CheckJob.postUnpaired" to "once per kid (Store.unpairedSeen)",
            "CheckJob.checkUpdate" to "once per version (Store.updateNotified)",
            "Notif.postDigest" to "once a day (Digest.due + Store.digestDay)",
            "Notif.test" to "the parent tapped the test button",
        )
    }

    private val cut = Regex("""(Action|RemoteInput)\.Builder\(.*?\)\.build\(\)""", RegexOption.DOT_MATCHES_ALL)
    private val fn = Regex("""fun\s+(\w+)\s*\(""")
    private val once = Regex("""setOnlyAlertOnce\((?!false)""")

    @Test fun notificationsDoNotReAlert() {
        val bad = mutableListOf<String>(); val seen = mutableSetOf<String>(); var n = 0
        File("src/main/kotlin").walk().filter { it.extension == "kt" }.forEach { f ->
            val s = cut.replace(f.readText(), "")
            Regex("""Notification\.Builder\(""").findAll(s).forEach { m ->
                n++
                val end = s.indexOf(".build()", m.range.last).let { if (it < 0) s.length else it }
                val chain = s.substring(m.range.first, end)
                val name = f.nameWithoutExtension + "." + (fn.findAll(s.substring(0, m.range.first)).lastOrNull()?.groupValues?.get(1) ?: "?")
                when {
                    once.containsMatchIn(chain) -> {}
                    "setOngoing(true)" in chain -> bad += "$name: ongoing without setOnlyAlertOnce(true)"
                    name in ONE_SHOT -> seen += name
                    else -> bad += "$name: no setOnlyAlertOnce(true) and not in ONE_SHOT"
                }
            }
        }
        assertTrue("found $n notification builders: scan broken?", n > 5)
        (ONE_SHOT.keys - seen).forEach { bad += "$it: stale ONE_SHOT entry (gone or now sets setOnlyAlertOnce)" }
        assertTrue("notifications that may re-alert (sound / heads-up on every re-post):\n" + bad.joinToString("\n"), bad.isEmpty())
    }
}
