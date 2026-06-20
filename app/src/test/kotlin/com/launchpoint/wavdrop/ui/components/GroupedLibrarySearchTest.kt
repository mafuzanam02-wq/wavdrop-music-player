package com.launchpoint.wavdrop.ui.components

import com.launchpoint.wavdrop.data.model.PlaylistSummary
import com.launchpoint.wavdrop.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupedLibrarySearchTest {

    @Test
    fun `matching playlist appears when supplied`() {
        val results = buildGroupedSearchResults(
            songs = emptyList(),
            playlists = listOf(playlist(1, "Road Trip")),
            query = "road",
        )

        assertEquals(listOf("Road Trip"), results.playlists.map { it.name })
    }

    @Test
    fun `playlist is absent when playlist list is empty`() {
        val results = buildGroupedSearchResults(
            songs = emptyList(),
            query = "road",
        )

        assertTrue(results.playlists.isEmpty())
        assertTrue(results.isEmpty)
    }

    @Test
    fun `blank query does not expose playlist group`() {
        val results = buildGroupedSearchResults(
            songs = listOf(song(1, "Alpha")),
            playlists = listOf(playlist(1, "Alpha Mix")),
            query = "   ",
        )

        assertTrue(results.playlists.isEmpty())
        assertEquals(listOf(1L), results.songs.map { it.id })
    }

    @Test
    fun `playlist results are capped at twenty`() {
        val results = buildGroupedSearchResults(
            songs = emptyList(),
            playlists = (1L..30L).map { id -> playlist(id, "Mix $id") },
            query = "mix",
        )

        assertEquals(20, results.playlists.size)
        assertEquals((1L..20L).toList(), results.playlists.map { it.id })
        assertTrue(results.playlistsCapped)
    }

    @Test
    fun `normalized album matching flows through grouped results`() {
        val results = buildGroupedSearchResults(
            songs = listOf(song(1, "Track", album = "Café Sessions")),
            query = "cafe",
        )

        assertEquals(listOf("Café Sessions"), results.albums.map { it.albumKey })
    }

    @Test
    fun `normalized deep artist matching flows through grouped results`() {
        val results = buildGroupedSearchResults(
            songs = listOf(song(1, "Café City", artist = "Jolé")),
            query = "cafe",
        )

        assertEquals(listOf("Jolé"), results.artists.map { it.artistKey })
    }

    @Test
    fun `playlist only match makes grouped result non empty`() {
        val results = buildGroupedSearchResults(
            songs = emptyList(),
            playlists = listOf(playlist(1, "Focus Mix")),
            query = "focus",
        )

        assertFalse(results.isEmpty)
    }

    private fun playlist(id: Long, name: String) = PlaylistSummary(
        id = id,
        name = name,
        songCount = 3,
        createdAt = 0L,
        updatedAt = 0L,
    )

    private fun song(
        id: Long,
        title: String,
        artist: String = "Artist",
        album: String = "Album",
    ) = Song(
        id = id,
        title = title,
        artist = artist,
        album = album,
        albumId = 0L,
        duration = 200_000L,
        uri = "content://media/$id",
        dateAdded = 0L,
        trackNumber = 0,
        year = 2020,
    )
}
