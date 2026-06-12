package com.launchpoint.wavdrop.data.grouping

import com.launchpoint.wavdrop.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumGrouperTest {

    private fun song(
        id: Long,
        album: String,
        artist: String = "Artist",
        albumId: Long = 0L,
        duration: Long = 200_000L,
    ) = Song(
        id = id, title = "Track $id", artist = artist, album = album,
        albumId = albumId, duration = duration, uri = "content://media/$id",
        dateAdded = 0L, trackNumber = 0, year = 2020,
    )

    @Test
    fun `blank album maps to Unknown Album`() {
        assertEquals("Unknown Album", AlbumGrouper.albumKey(song(1, "")))
        assertEquals("Unknown Album", AlbumGrouper.albumKey(song(2, "   ")))
    }

    @Test
    fun `album name is trimmed`() {
        assertEquals("My Album", AlbumGrouper.albumKey(song(1, "  My Album  ")))
    }

    @Test
    fun `group produces one entry per distinct album key`() {
        val songs = listOf(
            song(1, "Album A"),
            song(2, "Album A"),
            song(3, "Album B"),
        )
        assertEquals(2, AlbumGrouper.group(songs).size)
    }

    @Test
    fun `accented and plain album names remain separate display groups`() {
        val songs = listOf(
            song(1, "Jolé"),
            song(2, "Jole"),
        )
        val keys = AlbumGrouper.group(songs).map { it.albumKey }

        assertEquals(2, keys.size)
        assertTrue(keys.contains("Jolé"))
        assertTrue(keys.contains("Jole"))
    }

    @Test
    fun `group results are sorted case-insensitively`() {
        val songs = listOf(
            song(1, "zebra album"),
            song(2, "Apple Album"),
            song(3, "mango album"),
        )
        val keys = AlbumGrouper.group(songs).map { it.albumKey }
        assertEquals(listOf("Apple Album", "mango album", "zebra album"), keys)
    }

    @Test
    fun `primary artist is most frequent artist in album`() {
        val songs = listOf(
            song(1, "Collab", "Artist A"),
            song(2, "Collab", "Artist B"),
            song(3, "Collab", "Artist A"),
        )
        val summary = AlbumGrouper.group(songs).first()
        assertEquals("Artist A", summary.artist)
    }

    @Test
    fun `blank artist falls back to Unknown Artist`() {
        val songs = listOf(song(1, "Album", ""))
        assertEquals("Unknown Artist", AlbumGrouper.group(songs).first().artist)
    }

    @Test
    fun `song count and total duration are summed per album`() {
        val songs = listOf(
            song(1, "Album A", duration = 100_000L),
            song(2, "Album A", duration = 200_000L),
            song(3, "Album B", duration = 50_000L),
        )
        val albumA = AlbumGrouper.group(songs).first { it.albumKey == "Album A" }
        assertEquals(2, albumA.songCount)
        assertEquals(300_000L, albumA.totalDurationMs)
    }

    @Test
    fun `album summary uses first valid album id`() {
        val songs = listOf(
            song(1, "Album A", albumId = 0L),
            song(2, "Album A", albumId = 42L),
            song(3, "Album A", albumId = 99L),
        )

        assertEquals(42L, AlbumGrouper.group(songs).single().albumId)
    }

    @Test
    fun `album summary uses zero when no valid album id exists`() {
        val songs = listOf(
            song(1, "Album A", albumId = 0L),
            song(2, "Album A", albumId = -1L),
        )

        assertEquals(0L, AlbumGrouper.group(songs).single().albumId)
    }
}
