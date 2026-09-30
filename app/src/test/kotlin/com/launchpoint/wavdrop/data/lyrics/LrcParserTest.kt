package com.launchpoint.wavdrop.data.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LrcParserTest {

    private fun parse(vararg lines: String) = LrcParser.parse(lines.joinToString("\n"))

    private fun times(parsed: ParsedLrc?) = parsed!!.lines.map { it.timeMs }
    private fun texts(parsed: ParsedLrc?) = parsed!!.lines.map { it.text }

    @Test
    fun `simple minutes seconds hundredths`() {
        val parsed = parse("[00:12.50]First line", "[00:16.20]Second line")

        assertEquals(listOf(12_500L, 16_200L), times(parsed))
        assertEquals(listOf("First line", "Second line"), texts(parsed))
    }

    @Test
    fun `timestamp without fraction`() {
        assertEquals(listOf(12_000L), times(parse("[00:12]Hello")))
    }

    @Test
    fun `fractions are decimal tenths hundredths and milliseconds`() {
        val parsed = parse("[00:12.3]a", "[00:13.34]b", "[00:14.345]c", "[00:15.05]d")

        assertEquals(listOf(12_300L, 13_340L, 14_345L, 15_050L), times(parsed))
    }

    @Test
    fun `single digit and multi digit minutes`() {
        val parsed = parse("[1:02.50]a", "[12:34.567]b", "[100:00]c")

        assertEquals(listOf(62_500L, 754_567L, 6_000_000L), times(parsed))
    }

    @Test
    fun `multiple timestamps on one line create separate occurrences`() {
        val parsed = parse("[00:10.00][00:30.00]Chorus", "[00:20.00]Verse")

        assertEquals(listOf(10_000L, 20_000L, 30_000L), times(parsed))
        assertEquals(listOf("Chorus", "Verse", "Chorus"), texts(parsed))
    }

    @Test
    fun `metadata tags are ignored`() {
        val parsed = parse(
            "[ar:Artist]", "[ti:Title]", "[al:Album]", "[by:Author]", "[length:03:30]", "[00:05.00]Lyric",
        )

        assertEquals(listOf("Lyric"), texts(parsed))
        assertEquals("Lyric", parsed!!.plainText)
    }

    @Test
    fun `positive offset is added to every timestamp`() {
        val parsed = parse("[offset:500]", "[00:10.00]a", "[00:20.00]b")

        assertEquals(listOf(10_500L, 20_500L), times(parsed))
    }

    @Test
    fun `negative offset subtracts and clamps at zero`() {
        val parsed = parse("[offset:-250]", "[00:00.10]a", "[00:10.00]b")

        assertEquals(listOf(0L, 9_750L), times(parsed))
    }

    @Test
    fun `last valid offset tag wins and invalid offsets are ignored`() {
        val parsed = parse("[offset:100]", "[00:10.00]a", "[offset:300]", "[offset:oops]", "[00:20.00]b")

        assertEquals(listOf(10_300L, 20_300L), times(parsed))
    }

    @Test
    fun `out of order source timestamps are sorted by time`() {
        val parsed = parse("[00:30.00]third", "[00:10.00]first", "[00:20.00]second")

        assertEquals(listOf("first", "second", "third"), texts(parsed))
    }

    @Test
    fun `equal timestamps keep source order`() {
        val parsed = parse("[00:10.00]zebra", "[00:10.00]apple", "[00:05.00]intro")

        assertEquals(listOf("intro", "zebra", "apple"), texts(parsed))
    }

    @Test
    fun `malformed timestamps are dropped without failing the parse`() {
        val parsed = parse(
            "[00:99.00]bad seconds", "[00:12.3456]bad fraction", "[1:2x]broken tag", "[00:20.00]good",
        )

        assertEquals(listOf(20_000L), times(parsed))
        assertEquals(listOf("good"), texts(parsed))
        // Lines whose only tag is malformed keep their lyric text (untimed) in the plain projection.
        assertEquals("bad seconds\nbad fraction\nbroken tag\ngood", parsed!!.plainText)
    }

    @Test
    fun `empty timed line is preserved as a timeline boundary`() {
        val parsed = parse("[01:10.00]Last words", "[01:14.00]", "[02:00.00]Return")

        assertEquals(listOf(70_000L, 74_000L, 120_000L), times(parsed))
        assertEquals(listOf("Last words", "", "Return"), texts(parsed))
    }

    @Test
    fun `untimed lrc returns no synced parse`() {
        assertNull(LrcParser.parse("Just some lyrics\nOn plain lines"))
    }

    @Test
    fun `metadata only and blank input return null`() {
        assertNull(parse("[ar:Artist]", "[ti:Title]"))
        assertNull(LrcParser.parse(null))
        assertNull(LrcParser.parse("   \n  "))
    }

    @Test
    fun `only empty timed lines is not usable`() {
        assertNull(parse("[00:10.00]", "[00:20.00]"))
    }

    @Test
    fun `plain text does not duplicate multiply timestamped lines`() {
        val parsed = parse("[00:10.00][00:30.00]Chorus", "[00:20.00]Verse")

        assertEquals("Chorus\nVerse", parsed!!.plainText)
    }

    @Test
    fun `plain text has no timestamps or tags and keeps paragraph breaks`() {
        val parsed = parse("[ti:Song]", "[offset:0]", "[00:01.00]One", "[00:02.00]", "[00:03.00]Two")

        assertEquals("One\n\nTwo", parsed!!.plainText)
    }

    @Test
    fun `mixed timed and untimed lines never get fabricated times`() {
        val parsed = parse("Intro words", "[00:10.00]Timed", "Trailing words")

        assertEquals(listOf(10_000L), times(parsed))
        assertEquals(listOf("Timed"), texts(parsed))
        assertEquals("Intro words\nTimed\nTrailing words", parsed!!.plainText)
    }

    @Test
    fun `bracketed section labels stay lyric text`() {
        val parsed = parse("[00:10.00][Chorus] la la")

        assertEquals(listOf("[Chorus] la la"), texts(parsed))
    }

    @Test
    fun `windows newlines and bom are handled`() {
        val parsed = LrcParser.parse("﻿[00:01.00]One\r\n[00:02.00]Two\r\n")

        assertNotNull(parsed)
        assertEquals(listOf("One", "Two"), texts(parsed))
    }
}
