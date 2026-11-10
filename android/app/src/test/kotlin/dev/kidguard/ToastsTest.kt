package dev.kidguard

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Gate for [Toasts.MAX]: a text toast is cut at 2 lines on Android 12+ / MIUI, so every string a toast shows must fit,
 * in every language. Finds the strings by scanning the sources for toast calls (Toasts.show / toast / say / end /
 * whyClosed) on the same line as R.string.x. A placeholder counts as a typical value (%s 12 chars, %d 3).
 * The hidden-app reasons are shown as "<label, max 14>: <reason>", so they get 34.
 */
class ToastsTest {
    private val call = Regex("""Toasts\.show\(|\btoast\(|\bsay\(|\bend\(|\bwhyClosed\(""")
    private val id = Regex("""R\.string\.(\w+)""")

    private fun strings(dir: String): Map<String, String> =
        (File("src/main/res/$dir").listFiles { f -> f.name.endsWith(".xml") } ?: emptyArray()).flatMap { f ->
            Regex("""<string name="(\w+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).findAll(f.readText())
                .map { it.groupValues[1] to it.groupValues[2] }.toList()
        }.toMap()

    private fun len(v: String) = v.replace("\\'", "'").replace("\\\"", "\"")
        .replace(Regex("%\\d\\\$s|%s"), "x".repeat(12)).replace(Regex("%\\d\\\$d|%d"), "xxx").length

    @Test fun toastStringsFitTwoLines() {
        val used = File("src/main/kotlin").walk().filter { it.extension == "kt" }.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, l -> if (call.containsMatchIn(l)) l to "${f.name}:${i + 1}" else null }
        }.flatMap { (l, at) -> id.findAll(l).map { it.groupValues[1] to at } }.toMap()
        assertTrue("found no toast strings: scan broken?", used.size > 30)
        val bad = mutableListOf<String>()
        for (dir in listOf("values", "values-vi")) {
            val s = strings(dir)
            used.forEach { (k, at) -> s[k]?.let { if (len(it) > Toasts.MAX) bad += "$dir/$k (${len(it)}) @$at: $it" } }
            s.filterKeys { Regex("""hm_hid_\w+_l""").matches(it) }.forEach { (k, v) -> if (len(v) > 34) bad += "$dir/$k (${len(v)}): $v" }
        }
        assertTrue("toast text over ${Toasts.MAX} chars (cut at 2 lines; shorten or use a Modal):\n" + bad.joinToString("\n"), bad.isEmpty())
    }
}
