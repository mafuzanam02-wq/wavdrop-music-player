package com.launchpoint.wavdrop.data.legacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BlackPlayerStatParserTest {

    // ── Happy path ────────────────────────────────────────────────────────────

    @Test
    fun `parses single valid row correctly`() {
        val line = "148;2;23;Wilfred;Everything We Need;/storage/emulated/0/Music/example.mp3;1759066940607;1779810940727"
        val result = BlackPlayerStatParser.parse(line)

        assertEquals(1, result.validRows.size)
        assertEquals(0, result.invalidRows.size)

        val row = result.validRows[0]
        assertEquals(148, row.playCount)
        assertEquals(2, row.skipCount)
        assertEquals("23", row.title)
        assertEquals("Wilfred", row.artist)
        assertEquals("Everything We Need", row.album)
        assertEquals("/storage/emulated/0/Music/example.mp3", row.filePath)
        assertEquals(1759066940607L, row.dateAddedMs)
        assertEquals(1779810940727L, row.lastPlayedMs)
    }

    @Test
    fun `parses multiple valid rows and sums totals correctly`() {
        val input = """
            148;2;23;Wilfred;Everything We Need;/storage/emulated/0/Music/a.mp3;1759066940607;1779810940727
            10;1;Song Two;Artist B;Album B;/storage/emulated/0/Music/b.mp3;1000000000000;1000000001000
        """.trimIndent()

        val result = BlackPlayerStatParser.parse(input)

        assertEquals(2, result.validRows.size)
        assertEquals(0, result.invalidRows.size)
        assertEquals(158L, result.totalPlayCount)
        assertEquals(3L, result.totalSkipCount)
    }

    @Test
    fun `zero play count and zero skip count are valid`() {
        val line = "0;0;Title;Artist;Album;/storage/emulated/0/Music/c.mp3;0;0"
        val result = BlackPlayerStatParser.parse(line)

        assertEquals(1, result.validRows.size)
        assertEquals(0, result.validRows[0].playCount)
        assertEquals(0, result.validRows[0].skipCount)
    }

    @Test
    fun `empty string metadata fields are accepted`() {
        // Empty title, artist, album — filePath must still be present (can be empty too)
        // Field order: playCount;skipCount;title;artist;album;filePath;dateAddedMs;lastPlayedMs
        //              5        ;0        ;     ;      ;     ;/storage...
        val line = "5;0;;;;" + "/storage/emulated/0/Music/d.mp3;1000000000000;1000000001000"
        val result = BlackPlayerStatParser.parse(line)

        assertEquals(1, result.validRows.size)
        val row = result.validRows[0]
        assertEquals("", row.title)
        assertEquals("", row.artist)
        assertEquals("", row.album)
        assertEquals("/storage/emulated/0/Music/d.mp3", row.filePath)
    }

    // ── Blank / whitespace lines ───────────────────────────────────────────────

    @Test
    fun `blank lines are silently skipped`() {
        val input = "\n\n148;2;23;Wilfred;Everything We Need;/storage/emulated/0/Music/a.mp3;1759066940607;1779810940727\n\n"
        val result = BlackPlayerStatParser.parse(input)

        assertEquals(1, result.validRows.size)
        assertEquals(0, result.invalidRows.size)
    }

    @Test
    fun `whitespace-only content produces empty result`() {
        val result = BlackPlayerStatParser.parse("   \n\t\n  ")

        assertTrue(result.validRows.isEmpty())
        assertTrue(result.invalidRows.isEmpty())
        assertEquals(0L, result.totalPlayCount)
        assertEquals(0L, result.totalSkipCount)
    }

    @Test
    fun `empty string produces empty result`() {
        val result = BlackPlayerStatParser.parse("")

        assertTrue(result.validRows.isEmpty())
        assertTrue(result.invalidRows.isEmpty())
    }

    // ── Validation failures — field count ────────────────────────────────────

    @Test
    fun `row with too few fields is rejected`() {
        val line = "148;2;Title;Artist;Album;/path/to/file.mp3;1000000000000"  // 7 fields
        val result = BlackPlayerStatParser.parse(line)

        assertTrue(result.validRows.isEmpty())
        assertEquals(1, result.invalidRows.size)
        assertEquals(line, result.invalidRows[0])
    }

    @Test
    fun `row with too many fields is rejected`() {
        val line = "148;2;Title;Artist;Album;/path/to/file.mp3;1000000000000;1000000001000;extra"  // 9 fields
        val result = BlackPlayerStatParser.parse(line)

        assertTrue(result.validRows.isEmpty())
        assertEquals(1, result.invalidRows.size)
    }

    // ── Validation failures — non-numeric fields ──────────────────────────────

    @Test
    fun `non-numeric playCount is rejected`() {
        val line = "abc;2;Title;Artist;Album;/path/to/file.mp3;1000000000000;1000000001000"
        assertTrue(BlackPlayerStatParser.parse(line).validRows.isEmpty())
        assertEquals(1, BlackPlayerStatParser.parse(line).invalidRows.size)
    }

    @Test
    fun `non-numeric skipCount is rejected`() {
        val line = "5;xyz;Title;Artist;Album;/path/to/file.mp3;1000000000000;1000000001000"
        assertTrue(BlackPlayerStatParser.parse(line).validRows.isEmpty())
    }

    @Test
    fun `non-numeric dateAddedMs is rejected`() {
        val line = "5;1;Title;Artist;Album;/path/to/file.mp3;not-a-date;1000000001000"
        assertTrue(BlackPlayerStatParser.parse(line).validRows.isEmpty())
    }

    @Test
    fun `non-numeric lastPlayedMs is rejected`() {
        val line = "5;1;Title;Artist;Album;/path/to/file.mp3;1000000000000;not-a-date"
        assertTrue(BlackPlayerStatParser.parse(line).validRows.isEmpty())
    }

    // ── Validation failures — negative values ─────────────────────────────────

    @Test
    fun `negative playCount is rejected`() {
        val line = "-1;0;Title;Artist;Album;/path/to/file.mp3;1000000000000;1000000001000"
        assertTrue(BlackPlayerStatParser.parse(line).validRows.isEmpty())
    }

    @Test
    fun `negative skipCount is rejected`() {
        val line = "5;-3;Title;Artist;Album;/path/to/file.mp3;1000000000000;1000000001000"
        assertTrue(BlackPlayerStatParser.parse(line).validRows.isEmpty())
    }

    @Test
    fun `negative dateAddedMs is rejected`() {
        val line = "5;0;Title;Artist;Album;/path/to/file.mp3;-1;1000000001000"
        assertTrue(BlackPlayerStatParser.parse(line).validRows.isEmpty())
    }

    @Test
    fun `negative lastPlayedMs is rejected`() {
        val line = "5;0;Title;Artist;Album;/path/to/file.mp3;1000000000000;-1"
        assertTrue(BlackPlayerStatParser.parse(line).validRows.isEmpty())
    }

    // ── Mixed valid and invalid ───────────────────────────────────────────────

    @Test
    fun `mixed content correctly separates valid and invalid rows`() {
        val validLine   = "10;1;Title;Artist;Album;/storage/emulated/0/Music/a.mp3;1000000000000;1000000001000"
        val invalidLine = "bad;row;only;five;fields"
        val result = BlackPlayerStatParser.parse("$validLine\n$invalidLine")

        assertEquals(1, result.validRows.size)
        assertEquals(1, result.invalidRows.size)
        assertEquals(invalidLine, result.invalidRows[0])
        assertEquals(10L, result.totalPlayCount)
        assertEquals(1L, result.totalSkipCount)
    }

    @Test
    fun `invalid rows preserve the original raw line`() {
        val badLine = "not;valid;at;all"
        val result = BlackPlayerStatParser.parse(badLine)

        assertEquals(badLine, result.invalidRows[0])
    }

    @Test
    fun `totals only reflect valid rows`() {
        val input = """
            50;5;A;B;C;/path/a.mp3;1000000000000;1000000001000
            bad line with no semicolons
            30;2;D;E;F;/path/b.mp3;1000000000000;1000000001000
        """.trimIndent()

        val result = BlackPlayerStatParser.parse(input)

        assertEquals(2, result.validRows.size)
        assertEquals(1, result.invalidRows.size)
        assertEquals(80L, result.totalPlayCount)
        assertEquals(7L, result.totalSkipCount)
    }
}
