package com.mlmvpn.scanner.ui

import com.mlmvpn.scanner.R
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The app's own strings, in Persian and English, for a plain JVM test -- read straight from the
 * strings.xml of res/values and res/values-fa, where every label the home screen's search matches
 * comes from.
 */
object AppStrings {

    /** The app module's src/main, from wherever Gradle runs the test. */
    val main: File = listOf(File("src/main"), File("app/src/main")).firstOrNull { File(it, "java").isDirectory }
        ?: error("cannot find the app's src/main from ${File(".").absolutePath}")

    /** A string resource's name, by its id. */
    val names: Map<Int, String> by lazy { R.string::class.java.fields.associate { it.getInt(null) to it.name } }

    val fa: Map<String, String> by lazy { read(File(main, "res/values-fa/strings.xml")) }
    val en: Map<String, String> by lazy { read(File(main, "res/values/strings.xml")) }

    fun fa(id: Int): String = fa.getValue(names.getValue(id))
    fun en(id: Int): String = en.getValue(names.getValue(id))

    private fun read(file: File): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("string")
        val out = HashMap<String, String>()
        for (i in 0 until nodes.length) {
            val e = nodes.item(i) as org.w3c.dom.Element
            out[e.getAttribute("name")] = unescape(e.textContent.trim().removeSurrounding("\""))
        }
        return out
    }

    /** The backslash escapes Android resources and Kotlin literals share, for what appears in them. */
    fun unescape(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'u' -> {
                        sb.append(s.substring(i + 2, i + 6).toInt(16).toChar())
                        i += 4
                    }
                    else -> sb.append(n)
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }
}
