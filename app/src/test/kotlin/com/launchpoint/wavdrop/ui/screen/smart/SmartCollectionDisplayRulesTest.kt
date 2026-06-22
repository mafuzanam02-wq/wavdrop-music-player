package com.launchpoint.wavdrop.ui.screen.smart

import com.launchpoint.wavdrop.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmartCollectionDisplayRulesTest {

    @Test
    fun `footer appears only when total eligible count exceeds visible count`() {
        assertEquals(
            "Showing top 30 of 87 qualifying songs",
            smartCollectionCapFooterText(
                visibleCount = 30,
                totalEligibleCount = 87,
                visibleLimit = 30,
            ),
        )
        assertNull(
            smartCollectionCapFooterText(
                visibleCount = 30,
                totalEligibleCount = 30,
                visibleLimit = 30,
            ),
        )
        assertNull(
            smartCollectionCapFooterText(
                visibleCount = 30,
                totalEligibleCount = 87,
                visibleLimit = null,
            ),
        )
    }

    @Test
    fun `playlist snapshot preserves only visible song ids in visible order`() {
        val visibleSongs = listOf(song(7), song(3), song(11))

        assertEquals(
            listOf(7L, 3L, 11L),
            smartCollectionPlaylistSongIds(visibleSongs),
        )
    }

    private fun song(id: Long) = Song(
        id = id,
        title = "Song $id",
        artist = "Artist",
        album = "Album",
        albumId = 1L,
        duration = 180_000L,
        uri = "content://media/external/audio/media/$id",
        dateAdded = id,
        trackNumber = 1,
        year = 2020,
    )
}
