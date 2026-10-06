package com.launchpoint.wavdrop.data.text

import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WC-10: the reduced-pass [MusicTextNormalizer.normalizeTolerant] must equal the former chained implementation, which is preserved
 * here (test code only) as the semantic reference.
 */
class MusicTextNormalizerEquivalenceTest {

    // ---- legacy reference: the pre-WC-10 production implementation, verbatim ----
    private val whitespace = Regex("\\s+")
    private val combiningMarks = Regex("\\p{Mn}+")
    private val spacedDashSeparator = Regex("\\s+[-\u2010-\u2015]+\\s+")
    private val lowValueSuffix = Regex(
        pattern = "[\\s_\\-]*[\\(\\[]\\s*(?:\\d{2,4}\\s*k|official\\s+audio|official\\s+video|lyrics?|lyric\\s+video|hd)\\s*[\\)\\]]\\s*$",
        option = RegexOption.IGNORE_CASE,
    )

    private fun legacyStrip(value: String): String {
        var current = value
        while (true) {
            val next = current.replace(lowValueSuffix, "")
            if (next == current) return current
            current = next
        }
    }

    private fun String.legacyApostrophes(): String =
        replace("'", "").replace("\u2018", "").replace("\u2019", "").replace("\u02BC", "").replace("\uFF07", "")

    private fun legacyTolerant(value: String?): String {
        val decomposed = Normalizer.normalize(legacyStrip(value.orEmpty()), Normalizer.Form.NFD)
        return decomposed
            .replace(combiningMarks, "")
            .legacyApostrophes()
            .replace('_', ' ')
            .replace(spacedDashSeparator, " ")
            .trim()
            .lowercase(Locale.ROOT)
            .replace(whitespace, " ")
    }

    private fun legacyStrict(value: String?): String = value.orEmpty().trim().lowercase(Locale.ROOT).replace(whitespace, " ")

    private fun describe(s: String?): String =
        if (s == null) "null" else "\"" + s.replace("\n", "\\n") + "\" codepoints=" + s.codePoints().toArray().joinToString(" ") { "U+%04X".format(it) }

    private fun assertSame(input: String?) {
        val expected = legacyTolerant(input)
        val actual = MusicTextNormalizer.normalizeTolerant(input)
        assertEquals("tolerant mismatch for ${describe(input)}", expected, actual)
        assertEquals("search mismatch for ${describe(input)}", expected, MusicTextNormalizer.normalizeSearch(input))
        assertEquals("strict mismatch for ${describe(input)}", legacyStrict(input), MusicTextNormalizer.normalizeStrict(input))
    }

    private val corpus: List<String?> = buildList {
        add(null); add(""); add(" "); add("   "); add("\t\n\r \u000B\u000C"); add("\u00A0"); add("\u2003x\u2003")
        addAll(listOf("Artist - Title", "Artist --- Title", "Artist—Title", "Artist-Title", "Artist —Title", "Artist— Title", "a - - b", " - ", "- a -", "a -", "- a"))
        for (d in listOf('-', '\u2010', '\u2011', '\u2012', '\u2013', '\u2014', '\u2015', '\u2016', '\u2212')) {
            add("A $d B"); add("A $d$d B"); add("A${d}B"); add("A $d" + "B"); add("A${d} B"); add("A  \t$d$d\n  B"); add("$d A $d")
        }
        addAll(listOf("Jolé", "Jole", "Jol\u00E9", "Jole\u0301", "Don't", "Don\u2018t", "Don\u2019t", "Don\u02BCt", "Don\uFF07t", "Dont", "Say \"Hi\"", "\u201Cq\u201D"))
        for (a in listOf("'", "\u2018", "\u2019", "\u02BC", "\uFF07")) { add("a${a}b"); add("a $a- b"); add("$a$a"); add(a) }
        addAll(listOf("Picture_Perfect", "Picture Perfect", "Picture___Perfect", "_a_", "a_-_b", "a _ - _ b"))
        addAll(listOf("Still(256k)", "Still [320k]", "Still_(256k)", "Track (Official Audio) [320k]", "Track [Official Video] (HD)", "x (Lyrics)", "x (lyric)", "x (Lyric Video)", "x (hd)", "x (HD) y", "x (256 K)", "x (5k)", "x (hd)\n", "x (hd) \u2028", "x [hd]\r\n", "(hd)", " (256k)", "a - (hd)", "a-(hd)", "(256k)(256k)", "x (live)", "x (hd", "x hd)"))
        addAll(listOf("轰注", "軽注", "Привет Мир", "한국어 노래", "العربية", "สวัสดี", "हिन्दी", "ΣΑΣ", "ΌΣΟΣ ΣΟΣ Σ", "İstanbul", "I\u0307", "ǅ", "ß", "ﬁ", "Å", "ＡＢＣ", "①②"))
        addAll(listOf("😀 smile", "a\uD83D\uDE00b", "\uD83D", "\uDE00", "a\uD83Db", "\uD83D\u0301\uDE00", "\uD834\uDD67x", "x\uD834\uDD65", "𝒜 - 𝒷", "😀 - 😀"))
        addAll(listOf("Track 01", "1999", "2 Pac", "24K Magic", "100%", "Song #1 (256k)"))
        add("a".repeat(5_000)); add("Long Song Name ".repeat(300) + "(Official Audio)"); add(" - ".repeat(200)); add("x\t\t\t".repeat(300))
        add("already normalized"); add("already normalized text 1 2 3")
    }

    @Test fun `curated corpus equals the legacy reference`() { corpus.forEach(::assertSame) }

    @Test fun `known examples keep their documented results`() {
        assertEquals(MusicTextNormalizer.normalizeTolerant("Jolé"), MusicTextNormalizer.normalizeTolerant("Jole"))
        assertEquals(MusicTextNormalizer.normalizeTolerant("Don’t"), MusicTextNormalizer.normalizeTolerant("Dont"))
        assertEquals(MusicTextNormalizer.normalizeTolerant("Picture_Perfect"), MusicTextNormalizer.normalizeTolerant("Picture Perfect"))
        for (s in listOf("Still(256k)", "Still [320k]", "Still_(256k)")) assertEquals("still", MusicTextNormalizer.normalizeTolerant(s))
        assertEquals("軽注", MusicTextNormalizer.normalizeTolerant("軽注"))
        assertEquals("", MusicTextNormalizer.normalizeTolerant(null))
        assertEquals("", MusicTextNormalizer.normalizeTolerant(""))
        assertEquals("", MusicTextNormalizer.normalizeTolerant(" \t\n "))
        assertEquals("", MusicTextNormalizer.normalizeTolerant("(256k)"))
    }

    @Test fun `spaced dash separator rule is exact`() {
        assertEquals("artist title", MusicTextNormalizer.normalizeTolerant("Artist - Title"))
        assertEquals("artist title", MusicTextNormalizer.normalizeTolerant("Artist --- Title"))
        assertEquals("artist—title", MusicTextNormalizer.normalizeTolerant("Artist—Title"))
        assertEquals("artist-title", MusicTextNormalizer.normalizeTolerant("Artist-Title"))
        assertEquals("artist —title", MusicTextNormalizer.normalizeTolerant("Artist —Title"))
        assertEquals("artist— title", MusicTextNormalizer.normalizeTolerant("Artist— Title"))
        for (d in '\u2010'..'\u2015') assertEquals("a b", MusicTextNormalizer.normalizeTolerant("A $d$d B"))
        assertEquals("a b", MusicTextNormalizer.normalizeTolerant("A '- B")) // apostrophe removal exposes the separator
    }

    @Test fun `every apostrophe variant is removed individually and other quotes stay`() {
        for (a in listOf("'", "\u2018", "\u2019", "\u02BC", "\uFF07")) assertEquals("dont", MusicTextNormalizer.normalizeTolerant("Don${a}t"))
        assertEquals("say \"hi\"", MusicTextNormalizer.normalizeTolerant("Say \"Hi\""))
        assertEquals("\u201Cq\u201D", MusicTextNormalizer.normalizeTolerant("\u201Cq\u201D"))
    }

    @Test fun `regex whitespace predicate matches the old regex for every char`() {
        val re = Regex("\\s")
        for (c in Char.MIN_VALUE..Char.MAX_VALUE) {
            assertEquals("U+%04X".format(c.code), re.matches(c.toString()), MusicTextNormalizer.isRegexWhitespace(c))
        }
    }

    @Test fun `every BMP char alone and between letters matches the legacy reference`() {
        for (code in 0..0xFFFF) {
            val c = code.toChar()
            assertSame("$c")
            assertSame("a${c}b")
            assertSame("a $c b")
            assertSame(" $c ")
        }
    }

    @Test fun `supplementary planes sample matches the legacy reference`() {
        var cp = 0x10000
        while (cp <= 0x10FFFF) {
            val s = String(Character.toChars(cp))
            assertSame("a${s}b"); assertSame("$s $s")
            cp += 37
        }
        for (cp2 in 0x1D165..0x1D18B) assertSame("x" + String(Character.toChars(cp2)) + "y")
    }

    @Test fun `deterministic randomized equivalence over 60000 strings`() {
        val pool = listOf(
            "a", "B", "z", "Z", "é", "É", "e\u0301", "ñ", "ü", "Å", "İ", "Σ", "σ", "ß", "0", "7", " ", "  ", "\t", "\n", "\r", "\u000B", "\u000C",
            "\u00A0", "\u2003", "\u3000", "\u0085", "\u001F", "\u0301", "\u0308", "\u0327", "\u20DD", "\u0903", "'", "\u2018", "\u2019", "\u02BC", "\uFF07",
            "\"", "_", "__", "-", "--", "\u2010", "\u2011", "\u2012", "\u2013", "\u2014", "\u2015", "\u2212", "(", ")", "[", "]", "(256k)", "[320k]", "(hd)",
            "(Official Audio)", "[official video]", "(Lyrics)", "k", "軽", "注", "Привет", "한", "ا", "😀", "\uD83D\uDE00", "\uD834\uDD67", "\uD83D", "\uDE00", ".", "&",
        )
        val rnd = Random(20260610)
        repeat(60_000) { n ->
            val len = rnd.nextInt(0, 14)
            val s = buildString { repeat(len) { append(pool[rnd.nextInt(pool.size)]) } }
            assertSame(s)
            if (n == 0) assertTrue(pool.size > 50)
        }
    }

    @Test fun `concurrent normalization is safe and consistent`() {
        val inputs = corpus.filterNotNull().filter { it.length < 200 }
        val expected = inputs.map(::legacyTolerant)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val tasks = (1..16).map { Callable { inputs.map(MusicTextNormalizer::normalizeTolerant) } }
            pool.invokeAll(tasks).forEach { assertEquals(expected, it.get()) }
        } finally {
            pool.shutdown()
        }
    }

    @Test fun `production source no longer holds the chained whole string passes`() {
        val src = java.io.File("src/main/kotlin/com/launchpoint/wavdrop/data/text/MusicTextNormalizer.kt").readText()
        assertFalse(src.contains("replace(\"\\u2018\", \"\")"))
        assertFalse(src.contains("Regex(\"\\\\p{Mn}+\")"))
        assertFalse(src.contains("spacedDashSeparator"))
    }
}
