package com.launchpoint.wavdrop.data.grouping

import com.launchpoint.wavdrop.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtistGrouperTest {

    private fun song(
        id: Long,
        artist: String,
        album: String = "Album",
        duration: Long = 200_000L,
    ) = Song(
        id = id, title = "Track $id", artist = artist, album = album,
        albumId = 0L, duration = duration, uri = "content://media/$id",
        dateAdded = 0L, trackNumber = 0, year = 2020,
    )

    @Test
    fun `blank artist maps to Unknown Artist`() {
        assertEquals("Unknown Artist", ArtistGrouper.artistKey(song(1, "")))
        assertEquals("Unknown Artist", ArtistGrouper.artistKey(song(2, "   ")))
    }

    @Test
    fun `artist name is trimmed`() {
        assertEquals("The Strokes", ArtistGrouper.artistKey(song(1, "  The Strokes  ")))
    }

    @Test
    fun `group produces one entry per distinct artist key`() {
        val songs = listOf(
            song(1, "Artist A"),
            song(2, "Artist A"),
            song(3, "Artist B"),
        )
        assertEquals(2, ArtistGrouper.group(songs).size)
    }

    @Test
    fun `group results are sorted case-insensitively`() {
        val songs = listOf(
            song(1, "zeppelin"),
            song(2, "Adele"),
            song(3, "miles davis"),
        )
        val keys = ArtistGrouper.group(songs).map { it.artistKey }
        assertEquals(listOf("Adele", "miles davis", "zeppelin"), keys)
    }

    @Test
    fun `song count is correct per artist`() {
        val songs = listOf(
            song(1, "Solo"),
            song(2, "Solo"),
            song(3, "Solo"),
            song(4, "Other"),
        )
        val solo = ArtistGrouper.group(songs).first { it.artistKey == "Solo" }
        assertEquals(3, solo.songCount)
    }

    @Test
    fun `album count is distinct per artist`() {
        val songs = listOf(
            song(1, "Artist", "Album A"),
            song(2, "Artist", "Album A"),
            song(3, "Artist", "Album B"),
            song(4, "Artist", "Album C"),
        )
        val summary = ArtistGrouper.group(songs).first()
        assertEquals(3, summary.albumCount)
    }

    @Test
    fun `total duration is summed per artist`() {
        val songs = listOf(
            song(1, "Artist", duration = 100_000L),
            song(2, "Artist", duration = 250_000L),
            song(3, "Other",  duration = 999_000L),
        )
        val artist = ArtistGrouper.group(songs).first { it.artistKey == "Artist" }
        assertEquals(350_000L, artist.totalDurationMs)
    }

    @Test
    fun `grouping is case-sensitive matching AlbumGrouper behavior`() {
        val songs = listOf(
            song(1, "artist a"),
            song(2, "Artist A"),
        )
        assertEquals(2, ArtistGrouper.group(songs).size)
    }

    @Test
    fun `accented and plain artist names remain separate display groups`() {
        val songs = listOf(
            song(1, "Jolé"),
            song(2, "Jole"),
        )
        val keys = ArtistGrouper.group(songs).map { it.artistKey }

        assertEquals(2, keys.size)
        assertTrue(keys.contains("Jolé"))
        assertTrue(keys.contains("Jole"))
    }

    @Test
    fun `blank artist songs share Unknown Artist group`() {
        val songs = listOf(
            song(1, ""),
            song(2, "  "),
            song(3, "Real Artist"),
        )
        val summaries = ArtistGrouper.group(songs)
        val unknown = summaries.firstOrNull { it.artistKey == "Unknown Artist" }
        assertEquals(2, unknown?.songCount)
    }
}
