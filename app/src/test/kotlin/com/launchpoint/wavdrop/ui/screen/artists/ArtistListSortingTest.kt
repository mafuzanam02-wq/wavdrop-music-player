package com.launchpoint.wavdrop.ui.screen.artists

import com.launchpoint.wavdrop.data.model.ArtistSummary
import com.launchpoint.wavdrop.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtistListSortingTest {

    @Test
    fun `default sort is name ascending`() {
        assertEquals(ArtistSortMode.NAME_ASC, ArtistSortMode.DEFAULT)
        assertEquals(
            listOf("Alpha", "middle", "Zoo"),
            sortArtists(
                listOf(
                    artist("Zoo"),
                    artist("Alpha"),
                    artist("middle"),
                ),
            ).names(),
        )
    }

    @Test
    fun `name ascending uses raw artist key tie break`() {
        val result = sortArtists(
            listOf(artist("alpha"), artist("Alpha"), artist("Beta")),
            ArtistSortMode.NAME_ASC,
        )

        assertEquals(listOf("Alpha", "alpha", "Beta"), result.names())
    }

    @Test
    fun `name descending uses deterministic raw key tie break`() {
        val result = sortArtists(
            listOf(artist("alpha"), artist("Beta"), artist("Alpha")),
            ArtistSortMode.NAME_DESC,
        )

        assertEquals(listOf("Beta", "Alpha", "alpha"), result.names())
    }

    @Test
    fun `most songs sorts descending then by name`() {
        val result = sortArtists(
            listOf(
                artist("Beta", songCount = 5),
                artist("Alpha", songCount = 5),
                artist("Small", songCount = 1),
            ),
            ArtistSortMode.MOST_SONGS,
        )

        assertEquals(listOf("Alpha", "Beta", "Small"), result.names())
    }

    @Test
    fun `most albums sorts descending then by name`() {
        val result = sortArtists(
            listOf(
                artist("Beta", albumCount = 4),
                artist("Alpha", albumCount = 4),
                artist("Small", albumCount = 1),
            ),
            ArtistSortMode.MOST_ALBUMS,
        )

        assertEquals(listOf("Alpha", "Beta", "Small"), result.names())
    }

    @Test
    fun `longest duration sorts descending then by name`() {
        val result = sortArtists(
            listOf(
                artist("Beta", duration = 500),
                artist("Alpha", duration = 500),
                artist("Short", duration = 100),
            ),
            ArtistSortMode.LONGEST_DURATION,
        )

        assertEquals(listOf("Alpha", "Beta", "Short"), result.names())
    }

    @Test
    fun `deep song title search retains selected sort`() {
        val result = prepareArtists(
            artists = listOf(artist("Alpha"), artist("Beta"), artist("Other")),
            songs = listOf(
                song(1, title = "Shared Song", artist = "Alpha"),
                song(2, title = "Shared Song", artist = "Beta"),
                song(3, title = "Different", artist = "Other"),
            ),
            query = "shared",
            sortMode = ArtistSortMode.NAME_DESC,
        )

        assertEquals(listOf("Beta", "Alpha"), result.names())
    }

    @Test
    fun `deep album name search retains selected sort`() {
        val result = prepareArtists(
            artists = listOf(
                artist("Alpha", songCount = 2),
                artist("Beta", songCount = 5),
                artist("Other", songCount = 10),
            ),
            songs = listOf(
                song(1, artist = "Alpha", album = "Shared Album"),
                song(2, artist = "Beta", album = "Shared Album"),
                song(3, artist = "Other", album = "Different"),
            ),
            query = "shared album",
            sortMode = ArtistSortMode.MOST_SONGS,
        )

        assertEquals(listOf("Beta", "Alpha"), result.names())
    }

    @Test
    fun `sorting does not mutate input`() {
        val original = mutableListOf(artist("Beta"), artist("Alpha"))
        val snapshot = original.toList()

        sortArtists(original, ArtistSortMode.NAME_ASC)

        assertEquals(snapshot, original)
    }

    @Test
    fun `artwork uri remains attached after search and sorting`() {
        val result = prepareArtists(
            artists = listOf(
                artist("Alpha", artworkUri = "art://alpha"),
                artist("Beta", artworkUri = "art://beta"),
            ),
            songs = listOf(
                song(1, title = "Match", artist = "Alpha"),
                song(2, title = "Match", artist = "Beta"),
            ),
            query = "match",
            sortMode = ArtistSortMode.NAME_DESC,
        )

        assertEquals(listOf("art://beta", "art://alpha"), result.map { it.artworkUri })
    }

    @Test
    fun `alphabet index is used only by name modes`() {
        assertTrue(ArtistSortMode.NAME_ASC.usesAlphabetIndex)
        assertTrue(ArtistSortMode.NAME_DESC.usesAlphabetIndex)
        assertFalse(ArtistSortMode.MOST_SONGS.usesAlphabetIndex)
        assertFalse(ArtistSortMode.MOST_ALBUMS.usesAlphabetIndex)
        assertFalse(ArtistSortMode.LONGEST_DURATION.usesAlphabetIndex)
    }

    private fun artist(
        name: String,
        songCount: Int = 1,
        albumCount: Int = 1,
        duration: Long = 200,
        artworkUri: String? = null,
    ) = ArtistSummary(
        artistKey = name,
        songCount = songCount,
        albumCount = albumCount,
        totalDurationMs = duration,
        artworkUri = artworkUri,
    )

    private fun song(
        id: Long,
        title: String = "Track $id",
        artist: String,
        album: String = "Album",
    ) = Song(
        id = id,
        title = title,
        artist = artist,
        album = album,
        albumId = 0,
        duration = 200_000,
        uri = "content://media/$id",
        dateAdded = 0,
        trackNumber = 0,
        year = 2020,
    )

    private fun List<ArtistSummary>.names() = map { it.artistKey }
}
