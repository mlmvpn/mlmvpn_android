package com.mlmvpn.scanner.ui.settings

import com.mlmvpn.scanner.ui.AppStrings
import com.mlmvpn.scanner.ui.home.SearchMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Holds SettingsIndex -- what the home screen's search knows about Settings -- to the settings
 * screens themselves.
 *
 * It reads their source: every SettingsRow, SettingsToggle, SettingsTextRow, SettingsSlider and
 * WorkerSetupRow whose title is a fixed text (a string resource, or a `tr(fa, en)` pair) has to be
 * in the index or in [NOT_SEARCHABLE] with a reason, and every entry of the index has to still be
 * a row. A row added to Settings and not to the index fails here, by file and line.
 */
class SettingsIndexTest {

    private sealed class Title {
        data class Res(val name: String) : Title()
        data class Lit(val fa: String, val en: String) : Title()
    }

    private data class Found(val title: Title, val where: String)

    private companion object {
        val main: File = AppStrings.main

        val sources: List<File> = (
            File(main, "java/com/mlmvpn/scanner/ui/settings").listFiles { f -> f.extension == "kt" }.orEmpty().toList() +
                File(main, "java/com/mlmvpn/scanner/ui/AboutScreen.kt")
            ).sortedBy { it.name }

        val names: Map<Int, String> get() = AppStrings.names
        val fa: Map<String, String> get() = AppStrings.fa
        val en: Map<String, String> get() = AppStrings.en

        /** Rows the search has no business finding, and why. */
        val NOT_SEARCHABLE: Map<Title, String> = mapOf(
            Title.Res("settings_update_available") to "shown only while an update waits; «Software update» is the row to find",
            Title.Res("usage_download") to "a counter on the usage page, not a setting",
            Title.Res("usage_upload") to "a counter on the usage page, not a setting",
            Title.Res("usage_total") to "a counter on the usage page, not a setting",
            Title.Res("update_row_version") to "describes the release on offer",
            Title.Res("update_row_size") to "describes the release on offer",
            Title.Res("update_installed_on") to "a date, not a setting",
            Title.Res("vpn_mtu_global") to "on the VPN screen's own MTU page; the MTU row that leads there is listed",
        )

        val CALLS = listOf("SettingsRow", "SettingsToggle", "SettingsTextRow", "SettingsSlider", "WorkerSetupRow")
    }

    // ---------------------------------------------------------------- reading the screens' source

    private fun scanAll(): List<Found> = sources.flatMap { f ->
        val src = stripComments(f.readText())
        scan(src).map { (title, at) -> Found(title, "${f.name}:${src.substring(0, at).count { it == '\n' } + 1}") }
    }

    private fun scan(src: String): List<Pair<Title, Int>> {
        val out = mutableListOf<Pair<Title, Int>>()
        for (call in CALLS) {
            var i = src.indexOf("$call(")
            while (i >= 0) {
                val prev = if (i > 0) src[i - 1] else ' '
                val definition = src.substring(maxOf(0, i - 4), i) == "fun "
                if (!definition && !prev.isLetterOrDigit() && prev != '_') {
                    val open = i + call.length
                    val args = src.substring(open + 1, matching(src, open))
                    titleOf(args)?.let(::classify)?.let { out += it to i }
                }
                i = src.indexOf("$call(", i + 1)
            }
        }
        return out
    }

    /** The source with its comments blanked out (line breaks kept), so a call in a comment is not one. */
    private fun stripComments(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            when {
                s[i] == '"' -> skipString(s, i).let { out.append(s, i, it); i = it }
                s[i] == '\'' -> skipChar(s, i).let { out.append(s, i, it); i = it }
                s.startsWith("//", i) -> while (i < s.length && s[i] != '\n') { out.append(' '); i++ }
                s.startsWith("/*", i) -> {
                    val end = s.indexOf("*/", i + 2).let { if (it < 0) s.length else it + 2 }
                    for (k in i until end) out.append(if (s[k] == '\n') '\n' else ' ')
                    i = end
                }
                else -> { out.append(s[i]); i++ }
            }
        }
        return out.toString()
    }

    /** Past the end of the string literal at [start], templates and all. */
    private fun skipString(s: String, start: Int): Int {
        if (s.startsWith("\"\"\"", start)) {
            val end = s.indexOf("\"\"\"", start + 3)
            return if (end < 0) s.length else end + 3
        }
        var i = start + 1
        while (i < s.length) {
            when (s[i]) {
                '\\' -> i += 2
                '"' -> return i + 1
                '$' -> i = if (i + 1 < s.length && s[i + 1] == '{') matching(s, i + 1) + 1 else i + 1
                else -> i++
            }
        }
        return s.length
    }

    private fun skipChar(s: String, start: Int): Int {
        var i = start + 1
        i += if (i < s.length && s[i] == '\\') 2 else 1
        while (i < s.length && s[i] != '\'') i++
        return minOf(i + 1, s.length)
    }

    /** The bracket that closes the one at [open]. */
    private fun matching(s: String, open: Int): Int {
        var depth = 0
        var i = open
        while (i < s.length) {
            when (s[i]) {
                '(', '{', '[' -> { depth++; i++ }
                ')', '}', ']' -> { depth--; if (depth == 0) return i; i++ }
                '"' -> i = skipString(s, i)
                '\'' -> i = skipChar(s, i)
                else -> i++
            }
        }
        return s.length - 1
    }

    private fun topLevelArgs(args: String): List<String> {
        val parts = mutableListOf<String>()
        var depth = 0
        var start = 0
        var i = 0
        while (i < args.length) {
            when (args[i]) {
                '(', '{', '[' -> { depth++; i++ }
                ')', '}', ']' -> { depth--; i++ }
                '"' -> i = skipString(args, i)
                '\'' -> i = skipChar(args, i)
                ',' -> { if (depth == 0) { parts += args.substring(start, i); start = i + 1 }; i++ }
                else -> i++
            }
        }
        parts += args.substring(start)
        return parts.map { it.trim() }.filter { it.isNotEmpty() }
    }

    private val named = Regex("^([A-Za-z_][A-Za-z0-9_]*)\\s*=(?!=)\\s*(.*)$", RegexOption.DOT_MATCHES_ALL)

    /** The expression passed as the title, by name or as the first argument. */
    private fun titleOf(args: String): String? {
        val parts = topLevelArgs(args)
        for (p in parts) {
            val m = named.find(p) ?: continue
            if (m.groupValues[1] == "title") return m.groupValues[2].trim()
        }
        val first = parts.firstOrNull() ?: return null
        return if (named.find(first) == null) first else null
    }

    private val quoted = "(\"(?:[^\"\\\\]|\\\\.)*\")"
    private val resource = Regex("^(?:stringResource|S)\\(\\s*R\\.string\\.(\\w+)\\s*\\)$")
    private val trPair = Regex("^(?:com\\.mlmvpn\\.scanner\\.store\\.)?tr\\(\\s*$quoted\\s*,\\s*$quoted\\s*,?\\s*\\)$")
    private val isFaPair = Regex("^if\\s*\\(\\s*isFa\\s*\\)\\s*$quoted\\s*else\\s*$quoted$")

    /** A fixed title; null for one computed at run time (an account's name, a method's label...). */
    private fun classify(expr: String): Title? {
        resource.find(expr)?.let { return Title.Res(it.groupValues[1]) }
        val m = trPair.find(expr) ?: isFaPair.find(expr) ?: return null
        val a = literal(m.groupValues[1]) ?: return null
        val b = literal(m.groupValues[2]) ?: return null
        return Title.Lit(a, b)
    }

    /** A plain string literal's value; null for one with a template in it. */
    private fun literal(q: String): String? {
        val body = q.removeSurrounding("\"")
        if (Regex("(?<!\\\\)\\$").containsMatchIn(body)) return null
        return AppStrings.unescape(body)
    }

    // ---------------------------------------------------------------- the index's side

    private fun SText.asTitle(): Title = when (this) {
        is SText.Res -> Title.Res(names[id] ?: error("no string resource with id $id"))
        is SText.Lit -> Title.Lit(fa, en)
    }

    private fun describe(t: Title) = when (t) {
        is Title.Res -> t.name
        is Title.Lit -> "tr(\"${t.fa}\", \"${t.en}\")"
    }

    private fun both(t: SText): List<String> = when (t) {
        is SText.Res -> names.getValue(t.id).let { listOf(fa.getValue(it), en.getValue(it)) }
        is SText.Lit -> listOf(t.fa, t.en)
    }

    private fun search(query: String): List<String> =
        SearchMatcher.rank(query, SettingsIndex.entries) { it.fields(::both) }.map {
            when (val t = it.title) {
                is SText.Res -> names.getValue(t.id)
                is SText.Lit -> t.en
            }
        }

    // ---------------------------------------------------------------- tests

    @Test
    fun theScanReadsTheSettingsScreens() {
        val found = scanAll()
        assertTrue("only ${found.size} rows found -- has the settings code moved?", found.size >= 40)
        val titles = found.map { it.title }.toSet()
        assertTrue(Title.Res("settings_local_port") in titles)
        assertTrue(Title.Res("settings_icon_size") in titles)
        assertTrue(Title.Res("settings_gemini_exit") in titles)
        assertTrue(Title.Lit("حمایت از ما", "Support us") in titles)
        assertTrue(Title.Lit("مسیریابی هوشمند ایران", "Smart Iran Routing") in titles)
    }

    @Test
    fun everyRowOfSettingsCanBeFoundOrIsLeftOutOnPurpose() {
        val listed = SettingsIndex.entries.map { it.title.asTitle() }.toSet()
        val missing = scanAll().filter { it.title !in listed && it.title !in NOT_SEARCHABLE }
        if (missing.isNotEmpty()) {
            fail(
                "Settings rows the home screen's search cannot find. Add each to SettingsIndex with the " +
                    "page it is on, or to NOT_SEARCHABLE in this test with the reason:\n" +
                    missing.joinToString("\n") { "  ${it.where}  ${describe(it.title)}" }
            )
        }
    }

    @Test
    fun everyEntryIsStillARowOfSettings() {
        val found = scanAll().map { it.title }.toSet()
        val stale = SettingsIndex.entries.map { it.title.asTitle() }.filter { it !in found } +
            SettingsIndex.entries.flatMap { e -> e.fallback.map { it.asTitle() } }.filter { it !in found }
        assertTrue("no longer a row of Settings: ${stale.map(::describe)}", stale.isEmpty())
        val goneExclusions = NOT_SEARCHABLE.keys.filter { it !in found }
        assertTrue("NOT_SEARCHABLE names rows that are gone: ${goneExclusions.map(::describe)}", goneExclusions.isEmpty())
    }

    @Test
    fun entriesPointAtKnownPagesAndEachRowIsListedOnce() {
        SettingsIndex.entries.forEach {
            assertTrue("${describe(it.title.asTitle())} -> ${it.route}", it.route in SettingsIndex.routes)
        }
        val twice = SettingsIndex.entries.groupBy { it.title.asTitle() }.filterValues { it.size > 1 }.keys
        assertTrue("listed twice: ${twice.map(::describe)}", twice.isEmpty())
    }

    @Test
    fun everyTextIsThereInBothLanguages() {
        val texts = SettingsIndex.entries.flatMap { listOfNotNull(it.title, it.subtitle) + it.path + it.fallback } +
            SettingsIndex.root
        for (t in texts) {
            when (t) {
                is SText.Res -> {
                    val n = names.getValue(t.id)
                    assertTrue("no Persian for $n", !fa[n].isNullOrBlank())
                    assertTrue("no English for $n", !en[n].isNullOrBlank())
                }
                is SText.Lit -> assertTrue("empty half in ${describe(Title.Lit(t.fa, t.en))}", t.fa.isNotBlank() && t.en.isNotBlank())
            }
        }
    }

    @Test
    fun theRightRowComesFirstForWhatPeopleType() {
        assertEquals("settings_local_port", search("پورت").first())
        assertEquals("settings_language", search("زبان").first())
        assertEquals("settings_language", search("language").first())
        assertEquals("settings_backend_dns", search("dns").first())
        assertEquals("settings_backend_dns", search("دی ان اس").first())
        assertEquals("settings_wallpaper", search("تصویر زمینه").first())
        assertEquals("settings_wallpaper", search("والپیپر").first())
        assertEquals("settings_wallpaper", search("Wallpaper").first())
        assertEquals(listOf("settings_theme"), search("تم تاریک"))
        assertEquals("vpn_mtu_label", search("mtu").first())
        assertEquals("settings_google_fix", search("gemini").first())
        assertEquals("settings_proxy_mode", search("پروکسی").first())
        // An Arabic kaf, as an Arabic keyboard types it.
        assertEquals("settings_crash_report", search("كرش").first())
        assertEquals("Smart Iran Routing", search("مسیریابی هوشمند").first())
        assertTrue(search("zzzz").isEmpty())
    }
}
