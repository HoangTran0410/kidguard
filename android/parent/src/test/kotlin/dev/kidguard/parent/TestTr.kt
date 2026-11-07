package dev.kidguard.parent

import org.w3c.dom.Element
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * [Tr] for JVM tests, read from the app's real string resources (unit tests run in the module dir).
 * [TestTr.VI] keeps the tests asserting the exact Vietnamese text; [TestTr.EN] is the default (English) file.
 */
class TestTr(dir: String) : Tr {
    val strings = HashMap<String, String>()
    val plurals = HashMap<String, Map<String, String>>()

    init {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/$dir/strings.xml"))
        val s = doc.getElementsByTagName("string")
        for (i in 0 until s.length) (s.item(i) as Element).let { strings[it.getAttribute("name")] = unescape(it.textContent) }
        val p = doc.getElementsByTagName("plurals")
        for (i in 0 until p.length) {
            val e = p.item(i) as Element; val items = e.getElementsByTagName("item")
            plurals[e.getAttribute("name")] = (0 until items.length).associate { j -> (items.item(j) as Element).let { it.getAttribute("quantity") to unescape(it.textContent) } }
        }
    }

    override fun s(k: T, vararg a: Any): String =
        String.format(Locale.ROOT, strings[k.name] ?: error("no string ${k.name}"), *a)

    override fun q(k: T, n: Int, vararg a: Any): String {
        val forms = plurals[k.name] ?: error("no plurals ${k.name}")
        return String.format(Locale.ROOT, (if (n == 1) forms["one"] else null) ?: forms.getValue("other"), *a)
    }

    companion object {
        val VI by lazy { TestTr("values-vi") }
        val EN by lazy { TestTr("values") }
        /** aapt's escapes as used in our files: \" \' \n \\ */
        fun unescape(s: String) = s.replace("\\\"", "\"").replace("\\'", "'").replace("\\n", "\n").replace("\\\\", "\\")
    }
}
