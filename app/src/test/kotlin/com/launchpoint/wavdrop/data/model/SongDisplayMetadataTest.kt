package com.launchpoint.wavdrop.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class SongDisplayMetadataTest {

    @Test
    fun `underscores are cleaned when artist metadata is unknown`() {
        val song = song(
            title = "Picture_Perfect",
            artist = "Unknown Artist",
        )

        assertEquals("Picture Perfect", song.displayTitle)
        assertEquals("Unknown Artist", song.displayArtist)
    }

    @Test
    fun `quality suffix is removed from filename fallback`() {
        val song = song(
            title = "Still(256k)",
            artist = "Unknown Artist",
        )

        assertEquals("Still", song.displayTitle)
    }

    @Test
    fun `lyrics and video noise are removed from filename fallback`() {
        val song = song(
            title = "Taylor_Swift_-_Innocent_(Official_Lyrics_Video)",
            artist = "Unknown Artist",
        )

        assertEquals("Innocent", song.displayTitle)
        assertEquals("Taylor Swift", song.displayArtist)
    }

    @Test
    fun `artist title split works with spaced dash filename`() {
        val song = song(
            title = "Taylor Swift - Innocent Lyrics",
            artist = "Unknown Artist",
        )

        assertEquals("Innocent", song.displayTitle)
        assertEquals("Taylor Swift", song.displayArtist)
    }

    @Test
    fun `observed ugly filename fallback produces clean title and artist`() {
        val song = song(
            title = "Taylor_Swift_-_Innocent__Lyrics_(256k)",
            artist = "Unknown Artist",
        )

        assertEquals("Innocent", song.displayTitle)
        assertEquals("Taylor Swift", song.displayArtist)
    }

    @Test
    fun `valid embedded metadata is not overwritten`() {
        val song = song(
            title = "Taylor_Swift_-_Innocent__Lyrics_(256k)",
            artist = "Taylor Swift",
        )

        assertEquals("Taylor_Swift_-_Innocent__Lyrics_(256k)", song.displayTitle)
        assertEquals("Taylor Swift", song.displayArtist)
    }

    @Test
    fun `original stored metadata remains unchanged`() {
        val song = song(
            title = "Taylor_Swift_-_Innocent__Lyrics_(256k)",
            artist = "Unknown Artist",
        )

        assertEquals("Taylor_Swift_-_Innocent__Lyrics_(256k)", song.title)
        assertEquals("Unknown Artist", song.artist)
    }

    @Test
    fun `stable song id is unchanged by display fallback`() {
        val song = song(
            id = 42L,
            title = "Taylor_Swift_-_Innocent__Lyrics_(256k)",
            artist = "Unknown Artist",
        )

        assertEquals(42L, song.id)
        assertEquals("Innocent", song.displayTitle)
    }

    private fun song(
        id: Long = 1L,
        title: String,
        artist: String,
    ): Song = Song(
        id = id,
        title = title,
        artist = artist,
        album = "Unknown Album",
        albumId = 0L,
        duration = 180_000L,
        uri = "content://songs/$id",
        dateAdded = 0L,
        trackNumber = 0,
        year = 0,
    )
}
