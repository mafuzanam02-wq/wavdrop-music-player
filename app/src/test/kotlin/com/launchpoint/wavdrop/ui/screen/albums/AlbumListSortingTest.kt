package com.launchpoint.wavdrop.ui.screen.albums

import com.launchpoint.wavdrop.data.model.AlbumSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumListSortingTest {

    @Test
    fun `default sort is name ascending`() {
        assertEquals(AlbumSortMode.NAME_ASC, AlbumSortMode.DEFAULT)
        assertEquals(
            listOf("Alpha", "middle", "Zoo"),
            sortAlbums(
                listOf(
                    album(3, "Zoo"),
                    album(1, "Alpha"),
                    album(2, "middle"),
                ),
            ).names(),
        )
    }

    @Test
    fun `name ascending uses artist raw name and album id tie breaks`() {
        val result = sortAlbums(
            listOf(
                album(4, "alpha", artist = "Beta"),
                album(3, "Alpha", artist = "alpha"),
                album(2, "Alpha", artist = "Alpha"),
                album(1, "Alpha", artist = "Alpha"),
            ),
            AlbumSortMode.NAME_ASC,
        )

        assertEquals(listOf(1L, 2L, 3L, 4L), result.ids())
    }

    @Test
    fun `name descending uses deterministic ascending tie breaks`() {
        val result = sortAlbums(
            listOf(
                album(4, "alpha", artist = "Beta"),
                album(3, "Alpha", artist = "alpha"),
                album(2, "Alpha", artist = "Alpha"),
                album(1, "Beta", artist = "Zed"),
            ),
            AlbumSortMode.NAME_DESC,
        )

        assertEquals(listOf(1L, 2L, 3L, 4L), result.ids())
    }

    @Test
    fun `artist ascending sorts by artist then album name and id`() {
        val result = sortAlbums(
            listOf(
                album(4, "Beta", artist = "Zed"),
                album(3, "Beta", artist = "alpha"),
                album(2, "Alpha", artist = "Alpha"),
                album(1, "Alpha", artist = "Alpha"),
            ),
            AlbumSortMode.ARTIST_ASC,
        )

        assertEquals(listOf(1L, 2L, 3L, 4L), result.ids())
    }

    @Test
    fun `most songs sorts descending then by name`() {
        val result = sortAlbums(
            listOf(
                album(3, "Beta", songCount = 5),
                album(2, "Alpha", songCount = 5),
                album(1, "Small", songCount = 1),
            ),
            AlbumSortMode.MOST_SONGS,
        )

        assertEquals(listOf(2L, 3L, 1L), result.ids())
    }

    @Test
    fun `longest duration sorts descending then by name`() {
        val result = sortAlbums(
            listOf(
                album(3, "Beta", duration = 500),
                album(2, "Alpha", duration = 500),
                album(1, "Short", duration = 100),
            ),
            AlbumSortMode.LONGEST_DURATION,
        )

        assertEquals(listOf(2L, 3L, 1L), result.ids())
    }

    @Test
    fun `search results retain selected sort`() {
        val result = prepareAlbums(
            albums = listOf(
                album(1, "Mix Alpha"),
                album(2, "Other"),
                album(3, "Mix Beta"),
            ),
            query = "mix",
            sortMode = AlbumSortMode.NAME_DESC,
        )

        assertEquals(listOf("Mix Beta", "Mix Alpha"), result.names())
    }

    @Test
    fun `blank query returns all albums sorted`() {
        val result = prepareAlbums(
            albums = listOf(album(2, "Beta"), album(1, "Alpha")),
            query = "   ",
            sortMode = AlbumSortMode.NAME_ASC,
        )

        assertEquals(listOf("Alpha", "Beta"), result.names())
    }

    @Test
    fun `sorting does not mutate input`() {
        val original = mutableListOf(album(2, "Beta"), album(1, "Alpha"))
        val snapshot = original.toList()

        sortAlbums(original, AlbumSortMode.NAME_ASC)

        assertEquals(snapshot, original)
    }

    @Test
    fun `alphabet index is used only by name modes`() {
        assertTrue(AlbumSortMode.NAME_ASC.usesAlphabetIndex)
        assertTrue(AlbumSortMode.NAME_DESC.usesAlphabetIndex)
        assertFalse(AlbumSortMode.ARTIST_ASC.usesAlphabetIndex)
        assertFalse(AlbumSortMode.MOST_SONGS.usesAlphabetIndex)
        assertFalse(AlbumSortMode.LONGEST_DURATION.usesAlphabetIndex)
    }

    private fun album(
        id: Long,
        name: String,
        artist: String = "Artist",
        songCount: Int = 1,
        duration: Long = 200,
    ) = AlbumSummary(
        albumId = id,
        albumKey = name,
        artist = artist,
        songCount = songCount,
        totalDurationMs = duration,
    )

    private fun List<AlbumSummary>.names() = map { it.albumKey }
    private fun List<AlbumSummary>.ids() = map { it.albumId }
}
