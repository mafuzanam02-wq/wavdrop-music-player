package com.launchpoint.wavdrop.data.legacy

import com.launchpoint.wavdrop.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BpstatMatcherTest {

    private val songs = listOf(
        makeSong(1L, "Song Alpha",  "Artist A", "Album A"),
        makeSong(2L, "Song Beta",   "Artist B", "Album B"),
        makeSong(3L, "Song Gamma",  "Artist C", "Album C"),
    )

    // ── Matching correctness ──────────────────────────────────────────────────

    @Test
    fun `matched rows are paired with the correct song`() {
        val importRows = listOf(makeRow("Song Alpha", "Artist A", "Album A", plays = 10, skips = 2))
        val result = match(importRows, songs)

        assertEquals(1, result.matchedCount)
        assertEquals(1, result.matchedRows.size)
        assertEquals(songs[0], result.matchedRows[0].first)
        assertEquals(importRows[0], result.matchedRows[0].second)
    }

    @Test
    fun `multiple rows matched to their respective songs`() {
        val importRows = listOf(
            makeRow("Song Alpha", "Artist A", "Album A", plays = 5, skips = 0),
            makeRow("Song Beta",  "Artist B", "Album B", plays = 3, skips = 1),
        )
        val result = match(importRows, songs)

        assertEquals(2, result.matchedCount)
        assertEquals(2, result.matchedRows.size)
        assertEquals(0, result.unmatchedCount)
    }

    @Test
    fun `unmatched rows are not in matchedRows`() {
        val importRows = listOf(makeRow("Unknown", "Nobody", "Nothing", plays = 1, skips = 0))
        val result = match(importRows, songs)

        assertEquals(0, result.matchedCount)
        assertTrue(result.matchedRows.isEmpty())
        assertEquals(1, result.unmatchedCount)
    }

    @Test
    fun `mixed import produces correct matched and unmatched counts`() {
        val importRows = listOf(
            makeRow("Song Alpha", "Artist A", "Album A", plays = 10, skips = 0),
            makeRow("Unknown",    "Nobody",   "Nowhere", plays = 1,  skips = 0),
        )
        val result = match(importRows, songs)

        assertEquals(1, result.matchedCount)
        assertEquals(1, result.unmatchedCount)
    }

    // ── Case-insensitive / whitespace normalisation ────────────────────────────

    @Test
    fun `matching is case-insensitive`() {
        val importRows = listOf(makeRow("SONG ALPHA", "ARTIST A", "ALBUM A", plays = 5, skips = 0))
        val result = match(importRows, songs)
        assertEquals(1, result.matchedCount)
    }

    @Test
    fun `matching trims leading and trailing whitespace`() {
        val importRows = listOf(makeRow("  Song Alpha  ", "  Artist A  ", "  Album A  ", plays = 5, skips = 0))
        val result = match(importRows, songs)
        assertEquals(1, result.matchedCount)
    }

    @Test
    fun `case-insensitive and whitespace both applied together`() {
        val importRows = listOf(makeRow("  SONG ALPHA  ", "  ARTIST A  ", "  ALBUM A  ", plays = 3, skips = 0))
        val result = match(importRows, songs)
        assertEquals(1, result.matchedCount)
    }

    // ── Unmatched sample cap ──────────────────────────────────────────────────

    @Test
    fun `unmatched sample is capped at 10 rows`() {
        val importRows = (1..15).map { i -> makeRow("Track $i", "Artist $i", "Album $i", 1, 0) }
        val result = match(importRows, emptyList())

        assertEquals(15, result.unmatchedCount)
        assertEquals(10, result.unmatchedSample.size)
    }

    @Test
    fun `all matched sample is empty when nothing unmatched`() {
        val importRows = listOf(makeRow("Song Alpha", "Artist A", "Album A", 5, 0))
        val result = match(importRows, songs)

        assertTrue(result.unmatchedSample.isEmpty())
    }

    // ── Empty inputs ──────────────────────────────────────────────────────────

    @Test
    fun `empty import rows produce zero counts`() {
        val parseResult = BlackPlayerImportResult(emptyList(), emptyList(), 0, 0)
        val result = BpstatMatcher.match(parseResult, songs)

        assertEquals(0, result.matchedCount)
        assertEquals(0, result.unmatchedCount)
        assertTrue(result.matchedRows.isEmpty())
    }

    @Test
    fun `empty song library results in all rows unmatched`() {
        val importRows = listOf(makeRow("Song Alpha", "Artist A", "Album A", 10, 0))
        val result = match(importRows, emptyList())

        assertEquals(0, result.matchedCount)
        assertEquals(1, result.unmatchedCount)
    }

    // ── Merge arithmetic verification ─────────────────────────────────────────

    @Test
    fun `matchedRows carry original play and skip counts for repository to use`() {
        val importRows = listOf(makeRow("Song Alpha", "Artist A", "Album A", plays = 148, skips = 7))
        val result = match(importRows, songs)

        val (_, row) = result.matchedRows[0]
        assertEquals(148, row.playCount)
        assertEquals(7, row.skipCount)
    }

    @Test
    fun `matchedRows carry lastPlayedMs for lastPlayedAt merge`() {
        val importRows = listOf(
            BlackPlayerStatImportRow(
                playCount    = 5,
                skipCount    = 1,
                title        = "Song Alpha",
                artist       = "Artist A",
                album        = "Album A",
                filePath     = "/storage/Music/alpha.mp3",
                dateAddedMs  = 1_000_000_000_000L,
                lastPlayedMs = 1_779_810_940_727L,
            )
        )
        val result = match(importRows, songs)

        val (_, row) = result.matchedRows[0]
        assertEquals(1_779_810_940_727L, row.lastPlayedMs)
    }

    @Test
    fun `tolerant metadata match handles accents apostrophes underscores and suffixes`() {
        val library = listOf(
            makeSong(10L, "Jolé", "Don\u2019t Artist", "Picture Perfect"),
            makeSong(11L, "Still", "Artist", "Album"),
        )
        val importRows = listOf(
            makeRow("Jole", "Dont Artist", "Picture_Perfect", plays = 3, skips = 0),
            makeRow("Still(256k)", "Artist", "Album", plays = 4, skips = 0),
        )

        val result = match(importRows, library)

        assertEquals(2, result.matchedCount)
        assertEquals("Jolé", result.matchedRows[0].first.title)
        assertEquals("Still", result.matchedRows[1].first.title)
    }

    @Test
    fun `tolerant duplicate metadata is ambiguous and unmatched`() {
        val library = listOf(
            makeSong(10L, "Jolé", "Artist", "Album"),
            makeSong(11L, "Jole", "Artist", "Album"),
        )
        val importRows = listOf(makeRow("Jolè", "Artist", "Album", plays = 3, skips = 0))

        val result = match(importRows, library)

        assertEquals(0, result.matchedCount)
        assertEquals(1, result.unmatchedCount)
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun match(
    rows: List<BlackPlayerStatImportRow>,
    songs: List<Song>,
): BpstatMatchResult {
    val totalPlays = rows.sumOf { it.playCount.toLong() }
    val totalSkips = rows.sumOf { it.skipCount.toLong() }
    val parseResult = BlackPlayerImportResult(rows, emptyList(), totalPlays, totalSkips)
    return BpstatMatcher.match(parseResult, songs)
}

private fun makeSong(id: Long, title: String, artist: String, album: String) = Song(
    id          = id,
    title       = title,
    artist      = artist,
    album       = album,
    albumId     = 0L,
    duration    = 60_000L,
    uri         = "content://media/external/audio/media/$id",
    dateAdded   = 0L,
    trackNumber = 0,
    year        = 0,
)

private fun makeRow(
    title: String,
    artist: String,
    album: String,
    plays: Int,
    skips: Int,
) = BlackPlayerStatImportRow(
    playCount    = plays,
    skipCount    = skips,
    title        = title,
    artist       = artist,
    album        = album,
    filePath     = "/storage/emulated/0/Music/test.mp3",
    dateAddedMs  = 0L,
    lastPlayedMs = 1_000L,
)
