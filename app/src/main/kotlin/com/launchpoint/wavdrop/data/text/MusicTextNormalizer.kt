package com.launchpoint.wavdrop.data.text

import java.text.Normalizer
import java.util.Locale

/**
 * Builds comparison keys for music metadata. Do not feed these values back into
 * stored song fields or UI display text.
 *
 * All state is immutable or local to a call, so concurrent use is safe. There is deliberately no cache.
 */
object MusicTextNormalizer {

    private val lowValueSuffix = Regex(
        pattern = "[\\s_\\-]*[\\(\\[]\\s*(?:\\d{2,4}\\s*k|official\\s+audio|official\\s+video|lyrics?|lyric\\s+video|hd)\\s*[\\)\\]]\\s*$",
        option = RegexOption.IGNORE_CASE,
    )

    fun normalizeStrict(value: String?): String =
        value
            .orEmpty()
            .trim()
            .lowercase(Locale.ROOT)
            .collapseWhitespace()

    /**
     * Tolerant comparison key (WC-10 reduced-pass form; output is identical to the former chained implementation):
     * 1. strip trailing low-value bracket suffixes (regex, only when a closing bracket exists);
     * 2. Unicode NFD (not NFKD);
     * 3. ONE builder pass that drops `\p{Mn}` marks and the apostrophe variants and turns `_` into a space;
     * 4. ONE in-place pass that applies the "whitespace, dash run, whitespace" separator rule and collapses whitespace;
     * 5. Unicode-aware `trim()` and ONE `lowercase(Locale.ROOT)` (kept as a String operation: lowercasing is context sensitive).
     * Collapsing before lowercasing is equivalent because lowercasing never creates or removes whitespace.
     */
    fun normalizeTolerant(value: String?): String {
        val withoutSuffixes = stripLowValueSuffixes(value.orEmpty())
        if (withoutSuffixes.isEmpty()) return ""
        val decomposed = Normalizer.normalize(withoutSuffixes, Normalizer.Form.NFD)
        val work = StringBuilder(decomposed.length)
        appendWithoutMarksAndApostrophes(decomposed, work)
        val length = foldSpacedDashesAndWhitespace(work)
        var start = 0
        var end = length
        while (start < end && work[start].isWhitespace()) start++
        while (end > start && work[end - 1].isWhitespace()) end--
        if (start == end) return ""
        return work.substring(start, end).lowercase(Locale.ROOT)
    }

    fun normalizeSearch(value: String?): String = normalizeTolerant(value)

    private fun stripLowValueSuffixes(value: String): String {
        // A match must end in ')' or ']', so strings without either cannot match.
        if (value.indexOf(')') < 0 && value.indexOf(']') < 0) return value
        var current = value
        while (true) {
            val next = current.replace(lowValueSuffix, "")
            if (next == current) return current
            current = next
        }
    }

    /** Drops non-spacing marks (`\p{Mn}`) and `' U+2018 U+2019 U+02BC U+FF07`; maps `_` to a space; everything else is copied. */
    private fun appendWithoutMarksAndApostrophes(source: String, out: StringBuilder) {
        var i = 0
        val n = source.length
        while (i < n) {
            val cp = source.codePointAt(i)
            i += Character.charCount(cp)
            when {
                Character.getType(cp) == Character.NON_SPACING_MARK.toInt() -> Unit
                cp == 0x27 || cp == 0x2018 || cp == 0x2019 || cp == 0x02BC || cp == 0xFF07 -> Unit
                cp == '_'.code -> out.append(' ')
                else -> out.appendCodePoint(cp)
            }
        }
    }

    /**
     * In place, left to right (the write index never passes the read index). Each whitespace run becomes one space; when a run is
     * followed by a dash run and then more whitespace, the whole "whitespace dash-run whitespace" span becomes one space (the old
     * `\s+[-‐-―]+\s+` rule, whose matches never overlap). Returns the new length.
     */
    private fun foldSpacedDashesAndWhitespace(sb: StringBuilder): Int {
        val n = sb.length
        var read = 0
        var write = 0
        while (read < n) {
            val c = sb[read]
            if (!isRegexWhitespace(c)) {
                sb[write++] = c
                read++
                continue
            }
            var next = read + 1
            while (next < n && isRegexWhitespace(sb[next])) next++
            var probe = next
            while (probe < n && isSeparatorDash(sb[probe])) probe++
            if (probe > next && probe < n && isRegexWhitespace(sb[probe])) {
                while (probe < n && isRegexWhitespace(sb[probe])) probe++
                next = probe
            }
            sb[write++] = ' '
            read = next
        }
        return write
    }

    /** Exactly what `Regex("\s")` matches without UNICODE_CHARACTER_CLASS: space, \t, \n, \u000B, \f, \r. */
    internal fun isRegexWhitespace(c: Char): Boolean = c == ' ' || (c in '\t'..'\r')

    private fun isSeparatorDash(c: Char): Boolean = c == '-' || (c in '‐'..'―')

    /** Same result as replacing `\s+` with a space; returns the input itself when nothing needs changing. */
    private fun String.collapseWhitespace(): String {
        val n = length
        var firstChange = -1
        var i = 0
        while (i < n) {
            if (isRegexWhitespace(this[i]) && (this[i] != ' ' || (i + 1 < n && isRegexWhitespace(this[i + 1])))) {
                firstChange = i
                break
            }
            i++
        }
        if (firstChange < 0) return this
        val out = StringBuilder(n)
        out.append(this, 0, firstChange)
        i = firstChange
        while (i < n) {
            if (isRegexWhitespace(this[i])) {
                while (i < n && isRegexWhitespace(this[i])) i++
                out.append(' ')
            } else {
                out.append(this[i++])
            }
        }
        return out.toString()
    }
}
