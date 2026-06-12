package com.launchpoint.wavdrop.data.text

import java.text.Normalizer
import java.util.Locale

/**
 * Builds comparison keys for music metadata. Do not feed these values back into
 * stored song fields or UI display text.
 */
object MusicTextNormalizer {

    private val whitespace = Regex("\\s+")
    private val combiningMarks = Regex("\\p{Mn}+")
    private val spacedDashSeparator = Regex("\\s+[-\u2010-\u2015]+\\s+")
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

    fun normalizeTolerant(value: String?): String {
        val withoutSuffixes = stripLowValueSuffixes(value.orEmpty())
        val decomposed = Normalizer.normalize(withoutSuffixes, Normalizer.Form.NFD)
        return decomposed
            .replace(combiningMarks, "")
            .replaceApostrophes()
            .replace('_', ' ')
            .replace(spacedDashSeparator, " ")
            .trim()
            .lowercase(Locale.ROOT)
            .collapseWhitespace()
    }

    fun normalizeSearch(value: String?): String = normalizeTolerant(value)

    private fun stripLowValueSuffixes(value: String): String {
        var current = value
        while (true) {
            val next = current.replace(lowValueSuffix, "")
            if (next == current) return current
            current = next
        }
    }

    private fun String.replaceApostrophes(): String =
        replace("'", "")
            .replace("\u2018", "")
            .replace("\u2019", "")
            .replace("\u02BC", "")
            .replace("\uFF07", "")

    private fun String.collapseWhitespace(): String = replace(whitespace, " ")
}
