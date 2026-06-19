package com.launchpoint.wavdrop.ui.screen.playlists

import com.launchpoint.wavdrop.data.model.PlaylistSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistListFilteringAndSortingTest {

    @Test
    fun `blank query returns all playlists`() {
        val items = items()

        assertEquals(items, filterPlaylistItems(items, ""))
    }

    @Test
    fun `whitespace query returns all playlists`() {
        val items = items()

        assertEquals(items, filterPlaylistItems(items, "   "))
    }

    @Test
    fun `search is case insensitive`() {
        val result = filterPlaylistItems(items(), "ROAD")

        assertEquals(listOf("Road Trip"), result.names())
    }

    @Test
    fun `search matches partial playlist name`() {
        val result = filterPlaylistItems(items(), "work")

        assertEquals(listOf("Workout"), result.names())
    }

    @Test
    fun `search uses normalized playlist names`() {
        val result = filterPlaylistItems(listOf(item(1, "Café Mix")), "cafe")

        assertEquals(listOf("Café Mix"), result.names())
    }

    @Test
    fun `nonmatching query returns empty`() {
        assertTrue(filterPlaylistItems(items(), "classical").isEmpty())
    }

    @Test
    fun `search preserves selected sort order`() {
        val result = preparePlaylistItems(
            playlists = listOf(
                item(1, "Mix Beta"),
                item(2, "Other"),
                item(3, "Mix Alpha"),
            ),
            query = "mix",
            sortMode = PlaylistSortMode.NAME_DESC,
        )

        assertEquals(listOf("Mix Beta", "Mix Alpha"), result.names())
    }

    @Test
    fun `default sort is name ascending`() {
        assertEquals(PlaylistSortMode.NAME_ASC, PlaylistSortMode.DEFAULT)
        assertEquals(
            listOf("Alpha", "middle", "Zoo"),
            sortPlaylistItems(
                listOf(item(1, "Zoo"), item(2, "Alpha"), item(3, "middle")),
            ).names(),
        )
    }

    @Test
    fun `name descending works with deterministic id tie break`() {
        val result = sortPlaylistItems(
            listOf(item(3, "alpha"), item(2, "Beta"), item(1, "Alpha")),
            PlaylistSortMode.NAME_DESC,
        )

        assertEquals(listOf(2L, 1L, 3L), result.ids())
    }

    @Test
    fun `recently edited uses descending timestamp then name and id`() {
        val result = sortPlaylistItems(
            listOf(
                item(4, "Beta", updatedAt = 20),
                item(3, "Alpha", updatedAt = 20),
                item(1, "Alpha", updatedAt = 20),
                item(2, "Old", updatedAt = 10),
            ),
            PlaylistSortMode.RECENTLY_EDITED,
        )

        assertEquals(listOf(1L, 3L, 4L, 2L), result.ids())
    }

    @Test
    fun `recently created uses descending timestamp then name and id`() {
        val result = sortPlaylistItems(
            listOf(
                item(4, "Beta", createdAt = 20),
                item(3, "Alpha", createdAt = 20),
                item(1, "Alpha", createdAt = 20),
                item(2, "Old", createdAt = 10),
            ),
            PlaylistSortMode.RECENTLY_CREATED,
        )

        assertEquals(listOf(1L, 3L, 4L, 2L), result.ids())
    }

    @Test
    fun `track count uses descending count then name and id`() {
        val result = sortPlaylistItems(
            listOf(
                item(4, "Beta", songCount = 5),
                item(3, "Alpha", songCount = 5),
                item(1, "Alpha", songCount = 5),
                item(2, "Small", songCount = 1),
            ),
            PlaylistSortMode.TRACK_COUNT,
        )

        assertEquals(listOf(1L, 3L, 4L, 2L), result.ids())
    }

    @Test
    fun `sorting does not mutate input`() {
        val original = mutableListOf(item(2, "Beta"), item(1, "Alpha"))
        val snapshot = original.toList()

        sortPlaylistItems(original, PlaylistSortMode.NAME_ASC)

        assertEquals(snapshot, original)
    }

    @Test
    fun `artwork uri lists stay attached after filtering and sorting`() {
        val alpha = item(1, "Alpha", artworkUris = listOf("art://alpha"))
        val beta = item(2, "Beta", artworkUris = listOf("art://beta"))

        val result = preparePlaylistItems(
            playlists = listOf(alpha, beta),
            query = "a",
            sortMode = PlaylistSortMode.NAME_DESC,
        )

        assertEquals(listOf("art://beta"), result[0].artworkUris)
        assertEquals(listOf("art://alpha"), result[1].artworkUris)
        assertEquals(listOf(2L, 1L), result.ids())
    }

    private fun items() = listOf(
        item(1, "Road Trip"),
        item(2, "Workout"),
        item(3, "Quiet Evenings"),
    )

    private fun item(
        id: Long,
        name: String,
        songCount: Int = 0,
        createdAt: Long = 0,
        updatedAt: Long = 0,
        artworkUris: List<String> = emptyList(),
    ) = PlaylistListItem(
        playlist = PlaylistSummary(
            id = id,
            name = name,
            songCount = songCount,
            createdAt = createdAt,
            updatedAt = updatedAt,
        ),
        artworkUris = artworkUris,
    )

    private fun List<PlaylistListItem>.names() = map { it.playlist.name }
    private fun List<PlaylistListItem>.ids() = map { it.playlist.id }
}
