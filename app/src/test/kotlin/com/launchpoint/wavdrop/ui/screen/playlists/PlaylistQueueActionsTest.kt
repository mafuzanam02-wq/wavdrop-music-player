package com.launchpoint.wavdrop.ui.screen.playlists

import com.launchpoint.wavdrop.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistQueueActionsTest {

    private fun song(id: Long, title: String = "Song $id") = Song(
        id = id,
        title = title,
        artist = "Artist",
        album = "Album",
        albumId = 0L,
        duration = 180_000L,
        uri = "content://media/$id",
        dateAdded = 0L,
        trackNumber = 0,
        year = 2020,
    )

    private fun entry(id: Long, position: Int, title: String = "Song $id") = PlaylistSongItem(
        playlistId = 1L,
        songId = id,
        position = position,
        song = song(id, title),
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

    @Test
    fun `second duplicate resolves to its visible playback index`() {
        val entries = listOf(entry(1, 0), entry(2, 1), entry(1, 2), entry(3, 3))

        assertEquals(2, playlistPlaybackStartIndex(entries, entries[2]))
    }

    @Test
    fun `middle of three duplicates resolves positionally`() {
        val entries = listOf(entry(1, 0), entry(1, 1), entry(1, 2))

        assertEquals(1, playlistPlaybackStartIndex(entries, entries[1]))
    }

    @Test
    fun `filtered duplicate resolves within visible queue rather than persisted position`() {
        val visibleEntries = listOf(entry(1, 0), entry(1, 2), entry(1, 4))

        assertEquals(2, playlistPlaybackStartIndex(visibleEntries, visibleEntries[2]))
    }

    @Test
    fun `single first and last occurrences retain their visible indexes`() {
        val entries = listOf(entry(1, 4), entry(2, 8), entry(3, 12))

        assertEquals(0, playlistPlaybackStartIndex(entries, entries.first()))
        assertEquals(1, playlistPlaybackStartIndex(entries, entries[1]))
        assertEquals(2, playlistPlaybackStartIndex(entries, entries.last()))
    }

    @Test
    fun `selection outside visible queue is rejected`() {
        val visibleEntries = listOf(entry(1, 0), entry(2, 1))

        assertEquals(null, playlistPlaybackStartIndex(visibleEntries, entry(1, 7)))
    }

    @Test
    fun `same song id with different metadata remains position based`() {
        val entries = listOf(
            entry(1, 0, title = "Earlier metadata"),
            entry(1, 1, title = "Selected metadata"),
        )

        assertEquals(1, playlistPlaybackStartIndex(entries, entries[1]))
        assertEquals("Selected metadata", playlistQueueSongs(entries)[1].title)
    }
}
