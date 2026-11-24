package dev.kidguard

/**
 * Why the system ended KidGuard's process (Android 11+ `ApplicationExitInfo`), the pure part (unit-tested: ExitLogicTest).
 * Added 2026-11-24 (owner: a ColorOS phone "crashed" on every open after the self-setup adb link, no Java crash on file):
 * an ANR, a native crash or a kill by a permission change never reaches the uncaught-exception handler, so the support
 * report and the census crash table never saw them. [ExitInfo] reads them; this decides what is worth showing / sending.
 */
object ExitLogic {
    /** ApplicationExitInfo.REASON_* (values are API, stable since 30). */
    val NAMES = mapOf(
        0 to "UNKNOWN", 1 to "EXIT_SELF", 2 to "SIGNALED", 3 to "LOW_MEMORY", 4 to "CRASH", 5 to "CRASH_NATIVE", 6 to "ANR",
        7 to "INITIALIZATION_FAILURE", 8 to "PERMISSION_CHANGE", 9 to "EXCESSIVE_RESOURCE_USAGE", 10 to "USER_REQUESTED",
        11 to "USER_STOPPED", 12 to "DEPENDENCY_DIED", 13 to "OTHER", 14 to "FREEZER", 15 to "PACKAGE_STATE_CHANGE", 16 to "PACKAGE_UPDATED",
    )
    fun name(reason: Int) = NAMES[reason] ?: "R$reason"

    /** Sent to the census by itself: faults of ours or kills the user did not ask for. A Java CRASH is sent by [Support.recordCrash]. */
    private val SEND = setOf(5, 6, 7, 8, 9)
    const val SEND_MAX = 3
    const val SEND_AGE_MS = 3 * 86_400_000L

    data class Exit(val t: Long, val reason: Int, val desc: String)

    /** The exits to send now: worth it, newer than [seen] and than [SEND_AGE_MS], oldest first, at most [SEND_MAX]. */
    fun toSend(all: List<Exit>, seen: Long, now: Long): List<Exit> =
        all.filter { it.reason in SEND && it.t > seen && it.t > now - SEND_AGE_MS && it.t <= now + 60_000 }.sortedBy { it.t }.takeLast(SEND_MAX)

    /** The "main" thread's block of an ANR trace (what the UI thread was stuck in), or the start of the trace when absent. */
    fun mainThread(trace: String, max: Int = 6000): String {
        val i = trace.indexOf("\n\"main\" ")
        if (i < 0) return trace.take(max)
        val end = trace.indexOf("\n\n", i + 1).let { if (it < 0) trace.length else it }
        return trace.substring(i + 1, end).take(max)
    }
}
