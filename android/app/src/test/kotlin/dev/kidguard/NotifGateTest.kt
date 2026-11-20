package dev.kidguard

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Gate for re-alerting notifications (INCIDENT 2026-11-20: the usage-access notice was re-posted every Guard tick with
 * sound, ongoing, so ColorOS popped it every few seconds and a swipe came back at once). Textual, like [ToastsTest]:
 * every `Notification.Builder(` chain (up to its `.build()`, Action / RemoteInput builders cut out first) must
 * - call `.setOnlyAlertOnce(...)` (not `false`) when it is `.setOngoing(true)`: no exceptions, an ongoing one is
 *   always re-posted (updates, foreground service restarts);
 * - otherwise call it too, or be listed in [ONE_SHOT] ("File.function" + why it cannot repeat). A new notification
 *   posted from a tick / receiver / retry loop must post once per state change (remember what was posted) AND set it.
 */
class NotifGateTest {
    companion object {
        /** Posted once per event, never from a loop. Re-alerting on purpose: a second one IS news (the kid must hear it). */
        val ONE_SHOT = mapOf(
            "HomeWidgetsTimer.notifyDone" to "timer alarm: one per finished timer (TimerReceiver checks endAt)",
            "RefuseHint.notify" to "per app + kind every 6 h, throttle persisted in Store.refused (RefuseLogic.lastAt)",
            "Guard.notifyChild" to "parent command reply (answer / sosAck): one per command",
            "Guard.notifyWarn" to "lock heads-up: Store.markWarned, once per key per day",
            "PauseNotice.show" to "the kid opened a bank app: one per open, times out after 90 s",
            "FixPerms.Notification" to "parent's fixPerms command",
            "Monitoring.tellOn" to "parent turned a watch category on (rule change)",
            "Update.notifyChild" to "once per version tag (prefs 'notified')",
            "Files.notifyKid" to "one per received file (own id)",
            "Snap.tell" to "one per photo the parent takes: the kid is told every time on purpose",
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
        assertTrue("found $n notification builders: scan broken?", n > 20)
        (ONE_SHOT.keys - seen).forEach { bad += "$it: stale ONE_SHOT entry (gone or now sets setOnlyAlertOnce)" }
        assertTrue("notifications that may re-alert (sound / heads-up on every re-post):\n" + bad.joinToString("\n"), bad.isEmpty())
    }
}
