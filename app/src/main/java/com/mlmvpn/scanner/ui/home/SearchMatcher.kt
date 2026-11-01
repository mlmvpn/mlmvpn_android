package com.mlmvpn.scanner.ui.home

import java.text.Normalizer

/**
 * How the home screen's search decides what a query matches, and in what order.
 *
 * Plain Kotlin with no Android in it, so every rule below is pinned by SearchMatcherTest rather
 * than by eye on a phone.
 *
 * Persian reaches this app from every keyboard there is, and the same word arrives in spellings
 * that look identical on screen: an Arabic ي or ك from an Arabic layout, a zero-width non-joiner or
 * none («آموزش‌ها» / «آموزشها»), a kashida stretching a letter, a vowel mark, Persian, Arabic or
 * Latin digits. [fold] brings all of them to one form, on both sides -- the query and every text it
 * is matched against -- so the spelling someone happens to type never decides whether a tile is
 * found.
 *
 * The order answers what a launcher search is for: a name that starts with what was typed beats one
 * that only contains it, a word starting with it beats a word containing it, a shorter name beats a
 * longer one, and when several words are typed every one of them has to be there.
 */
object SearchMatcher {

    /** Text in its folded form; [origin] maps each character of it back to its index in the original. */
    class Folded(val text: String, val origin: IntArray)

    /**
     * One text a result can be found by, folded once, and what a match in it is worth: 1 for a
     * name, less for the extra words, a folder or a settings path that only says where it lives.
     */
    class Field(text: String, val weight: Float = 1f) {
        val folded: Folded = fold(text)
        internal val words: List<String> = folded.text.split(' ').filter { it.isNotEmpty() }
        /** The text with its spaces gone, for words typed together or apart («وای فای» / «وای‌فای»). */
        internal val compact: String = folded.text.replace(" ", "")
    }

    private const val NONE = 0
    private const val INFIX = 1
    private const val PREFIX = 2
    private const val EXACT = 3

    /** The one form every text is compared in. */
    fun normalize(s: String): String = fold(s).text

    /** The words of a query, folded. */
    fun tokens(query: String): List<String> = normalize(query).split(' ').filter { it.isNotEmpty() }

    fun fold(s: String): Folded {
        val out = StringBuilder(s.length)
        var origin = IntArray(s.length + 4)
        var pendingSpace = false
        fun put(ch: Char, from: Int) {
            if (out.length + 2 > origin.size) origin = origin.copyOf(origin.size * 2 + 4)
            // Separators collapse into one space, and never lead or trail.
            if (pendingSpace && out.isNotEmpty()) {
                origin[out.length] = from
                out.append(' ')
            }
            pendingSpace = false
            origin[out.length] = from
            out.append(ch)
        }
        for (i in s.indices) {
            val mapped = map(s[i]) ?: continue
            if (mapped == " ") pendingSpace = true else for (ch in mapped) put(ch, i)
        }
        return Folded(out.toString(), origin.copyOf(out.length))
    }

    /** One character's folded form: null to drop it, " " for a word break. */
    private fun map(c: Char): String? {
        when (c) {
            // Zero-width joiners and non-joiners, the kashida, bidi controls, a soft hyphen, the
            // standalone hamza: none of them changes which word it is.
            '‌', '‍', 'ـ', '­', '﻿', '؜', 'ء',
            '‎', '‏', '‪', '‫', '‬', '‭', '‮',
            '⁦', '⁧', '⁨', '⁩' -> return null
            'ي', 'ى', 'ئ', 'ی', 'ۍ', 'ې' -> return "ی"
            'ك', 'ڪ' -> return "ک"
            'ة', 'ۀ', 'ە', 'ہ', 'ھ' -> return "ه"
            'أ', 'إ', 'آ', 'ٱ', 'ٲ', 'ٳ' -> return "ا"
            'ؤ' -> return "و"
            '¹' -> return "1"
            '²' -> return "2"
            '³' -> return "3"
        }
        if (c in '۰'..'۹') return ('0' + (c - '۰')).toString()
        if (c in '٠'..'٩') return ('0' + (c - '٠')).toString()
        if (c.isWhitespace()) return " "
        when (Character.getType(c).toByte()) {
            // Vowel marks and the hamza above («هٔ»), and invisible formatting.
            Character.NON_SPACING_MARK, Character.ENCLOSING_MARK,
            Character.COMBINING_SPACING_MARK, Character.FORMAT -> return null
            Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION,
            Character.START_PUNCTUATION, Character.END_PUNCTUATION,
            Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
            Character.OTHER_PUNCTUATION, Character.MATH_SYMBOL,
            Character.MODIFIER_SYMBOL, Character.OTHER_SYMBOL,
            Character.SPACE_SEPARATOR, Character.LINE_SEPARATOR,
            Character.PARAGRAPH_SEPARATOR -> return " "
        }
        if (c.code in 0xC0..0x24F) {
            // A Latin letter with an accent folds to the bare letter: é -> e.
            val bare = Normalizer.normalize(c.toString(), Normalizer.Form.NFD)
                .filter { Character.getType(it).toByte() != Character.NON_SPACING_MARK }
            if (bare.isNotEmpty()) return bare.lowercase()
        }
        return c.lowercaseChar().toString()
    }

    /** How [t] is found in [f]: as one of its words, at the start of one, inside one, or not at all. */
    private fun kind(t: String, f: Field): Int {
        var best = NONE
        for (w in f.words) {
            when {
                w == t -> return EXACT
                w.startsWith(t) -> best = PREFIX
                // One letter is too little to be found in the middle of a word: «ت» is in half the
                // app's names, and only the ones that start with it are what was meant.
                best == NONE && t.length >= 2 && w.contains(t) -> best = INFIX
            }
        }
        if (best < PREFIX && f.compact.startsWith(t)) best = PREFIX
        if (best == NONE && t.length >= 2 && f.compact.contains(t)) best = INFIX
        return best
    }

    private fun fieldScore(tokens: List<String>, joined: String, f: Field): Int {
        var worst = EXACT
        for (t in tokens) {
            val k = kind(t, f)
            if (k == NONE) return 0
            if (k < worst) worst = k
        }
        val base = when {
            f.compact == joined -> 1000
            f.compact.startsWith(joined) -> 900
            else -> {
                val byKind = when (worst) {
                    EXACT -> 700
                    PREFIX -> 600
                    else -> 400
                }
                // A query whose first word starts the name reads as the name.
                val leading = if (f.words.firstOrNull()?.startsWith(tokens[0]) == true) 40 else 0
                byKind + leading
            }
        }
        // The shorter of two names that match equally well is the likelier one.
        val shortness = 30 - minOf(f.folded.text.length, 60) / 2
        return ((base + shortness) * f.weight).toInt().coerceAtLeast(1)
    }

    /** 0 when the words of the query are not all there; otherwise, the higher the better. */
    fun score(tokens: List<String>, fields: List<Field>): Int {
        if (tokens.isEmpty() || fields.isEmpty()) return 0
        val joined = tokens.joinToString("")
        var best = 0
        for (f in fields) best = maxOf(best, fieldScore(tokens, joined, f))
        if (best > 0) return best
        // Every word is there, though no one text holds them all: still a match, and the weakest.
        for (t in tokens) if (fields.none { kind(t, it) != NONE }) return 0
        return 120
    }

    /** The [items] that match [query], best first; equal ones keep the order they came in. */
    fun <T> rank(query: String, items: List<T>, fields: (T) -> List<Field>): List<T> {
        val tokens = tokens(query)
        if (tokens.isEmpty()) return emptyList()
        return items
            .mapIndexedNotNull { i, item ->
                val s = score(tokens, fields(item))
                if (s > 0) Triple(item, s, i) else null
            }
            .sortedWith(compareByDescending<Triple<T, Int, Int>> { it.second }.thenBy { it.third })
            .map { it.first }
    }

    /**
     * The words of [text] that the query found, as ranges of [text] itself, to draw in bold.
     *
     * Whole words rather than the matched letters alone. A weight change in the middle of a
     * Persian word is a font change in the middle of a joined run, and that is exactly where
     * letters can fall apart into their separate forms; a whole word in bold can not.
     */
    fun highlights(text: String, query: String): List<IntRange> {
        val tokens = tokens(query)
        if (tokens.isEmpty() || text.isEmpty()) return emptyList()
        val f = fold(text)
        val found = mutableListOf<IntRange>()
        for (t in tokens) {
            var at = -1
            var i = f.text.indexOf(t)
            while (i >= 0) {
                if (i == 0 || f.text[i - 1] == ' ') { at = i; break }
                i = f.text.indexOf(t, i + 1)
            }
            if (at < 0 && t.length >= 2) at = f.text.indexOf(t)
            if (at < 0) continue
            found += wordAround(text, f.origin[at], f.origin[at + t.length - 1])
        }
        return merge(found)
    }

    /** From [start] back to the beginning of its word, and from [end] on to the end of its own. */
    private fun wordAround(text: String, start: Int, end: Int): IntRange {
        var s = start
        while (s > 0 && map(text[s - 1]) != " ") s--
        var e = end
        while (e < text.length - 1 && map(text[e + 1]) != " ") e++
        return s..e
    }

    private fun merge(ranges: List<IntRange>): List<IntRange> {
        if (ranges.size < 2) return ranges
        val sorted = ranges.sortedBy { it.first }
        val out = mutableListOf(sorted[0])
        for (r in sorted.drop(1)) {
            val last = out.last()
            if (r.first <= last.last + 1) out[out.size - 1] = last.first..maxOf(last.last, r.last)
            else out += r
        }
        return out
    }
}
