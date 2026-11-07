@file:Suppress("EnumEntryName")

package dev.kidguard.parent

/**
 * Notification text built by [Logic] (pure, unit-tested). Logic asks a [Tr] for a key; the Android side ([ResTr])
 * answers from string resources in the app's language, tests from the same XML (src/test TestTr).
 * Each entry's name IS the resource name in res/values(-xx)/strings.xml (a `<plurals>` when used through [Tr.q]).
 * Text the child phone sends (state.events[].text) is shown as it is: it comes in the child phone's language.
 */
enum class T { ev_request /* plurals */, ev_sos, ev_lost, ev_acc_off }

/** Looks up user-visible text. [s]: a plain string with format args; [q]: a plural picked by [n] (args as given). */
interface Tr {
    fun s(k: T, vararg a: Any): String
    fun q(k: T, n: Int, vararg a: Any): String
}
