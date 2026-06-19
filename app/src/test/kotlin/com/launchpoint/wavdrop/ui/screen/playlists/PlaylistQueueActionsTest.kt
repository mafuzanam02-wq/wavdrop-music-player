package com.launchpoint.wavdrop.ui.screen.playlists

import com.launchpoint.wavdrop.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistQueueActionsTest {

    private fun song(id: Long) = Song(
        id = id,
        title = "Song $id",
        artist = "Artist",
        album = "Album",
        albumId = 0L,
        duration = 180_000L,
        uri = "content://media/$id",
        dateAdded = 0L,
        trackNumber = 0,
        year = 2020,
    )

    private fun entry(id: Long, position: Int) = PlaylistSongItem(
        playlistId = 1L,
        songId = id,
        position = position,
        song = song(id),
    )

    @Test
    fun `playAllNext uses visible entries in order`() {
        val visibleEntries = listOf(entry(3, 0), entry(1, 1), entry(2, 2))

        assertEquals(listOf(3L, 1L, 2L), playlistQueueSongs(visibleEntries).map { it.id })
    }

    @Test
    fun `addAllToQueue uses visible entries in order`() {
        val visibleEntries = listOf(entry(4, 0), entry(5, 1))

        assertEquals(listOf(4L, 5L), playlistQueueSongs(visibleEntries).map { it.id })
    }

    @Test
    fun `empty playlist produces no queue songs`() {
        assertTrue(playlistQueueSongs(emptyList()).isEmpty())
    }

    @Test
    fun `orphan-only playlist produces no resolved queue songs`() {
        val resolvedVisibleEntries = emptyList<PlaylistSongItem>()

        assertTrue(playlistQueueSongs(resolvedVisibleEntries).isEmpty())
    }

    @Test
    fun `filtered playlist uses only filtered visible entries`() {
        val allEntries = listOf(entry(1, 0), entry(2, 1), entry(3, 2))
        val filteredVisibleEntries = allEntries.filter { it.songId != 2L }

        assertEquals(
            listOf(1L, 3L),
            playlistQueueSongs(filteredVisibleEntries).map { it.id },
        )
    }
}
