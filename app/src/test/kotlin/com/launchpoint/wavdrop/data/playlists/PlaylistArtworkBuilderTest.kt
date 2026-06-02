package com.launchpoint.wavdrop.data.playlists

import com.launchpoint.wavdrop.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistArtworkBuilderTest {

    private fun song(id: Long, albumId: Long) = Song(
        id = id,
        title = "Song $id",
        artist = "Artist",
        album = "Album",
        albumId = albumId,
        duration = 180_000L,
        uri = "content://media/$id",
        dateAdded = 0L,
        trackNumber = 0,
        year = 2020,
    )

    @Test
    fun `empty playlist returns empty list`() {
        assertEquals(emptyList<String>(), PlaylistArtworkBuilder.buildArtworkUris(emptyList()))
    }

    @Test
    fun `songs without artwork are ignored`() {
        val result = PlaylistArtworkBuilder.buildArtworkUris(
            listOf(song(1, 0L), song(2, -3L)),
        )

        assertEquals(emptyList<String>(), result)
    }

    @Test
    fun `duplicate album art is collapsed`() {
        val result = PlaylistArtworkBuilder.buildArtworkUris(
            listOf(song(1, 42L), song(2, 42L), song(3, 7L)),
        )

        assertEquals(
            listOf(
                "content://media/external/audio/albumart/42",
                "content://media/external/audio/albumart/7",
            ),
            result,
        )
    }

    @Test
    fun `max four artwork uris are returned`() {
        val result = PlaylistArtworkBuilder.buildArtworkUris(
            listOf(song(1, 1L), song(2, 2L), song(3, 3L), song(4, 4L), song(5, 5L)),
        )

        assertEquals(
            listOf(
                "content://media/external/audio/albumart/1",
                "content://media/external/audio/albumart/2",
                "content://media/external/audio/albumart/3",
                "content://media/external/audio/albumart/4",
            ),
            result,
        )
    }

    @Test
    fun `playlist order is preserved`() {
        val result = PlaylistArtworkBuilder.buildArtworkUris(
            listOf(song(1, 9L), song(2, 4L), song(3, 7L)),
        )

        assertEquals(
            listOf(
                "content://media/external/audio/albumart/9",
                "content://media/external/audio/albumart/4",
                "content://media/external/audio/albumart/7",
            ),
            result,
        )
    }
}
