package com.launchpoint.wavdrop.data.local.entity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackStatsEntityTest {

    private fun entity(
        songId: Long     = 1L,
        uri: String      = "content://media/1",
        isFavorite: Boolean = false,
        playCount: Int   = 0,
        skipCount: Int   = 0,
    ) = TrackStatsEntity(
        songId               = songId,
        contentUri           = uri,
        isFavorite           = isFavorite,
        playCount            = playCount,
        skipCount            = skipCount,
        lastPlayedAt         = 0L,
        totalListeningTimeMs = 0L,
    )

    @Test
    fun `isFavorite defaults to false`() {
        val e = TrackStatsEntity(songId = 1L, contentUri = "content://media/1")
        assertFalse(e.isFavorite)
    }

    @Test
    fun `isFavorite can be set to true`() {
        val e = entity(isFavorite = true)
        assertTrue(e.isFavorite)
    }

    @Test
    fun `toggling isFavorite does not affect playCount`() {
        val e = entity(playCount = 42, isFavorite = false)
        val toggled = e.copy(isFavorite = true)
        assertEquals(42, toggled.playCount)
    }

    @Test
    fun `toggling isFavorite does not affect skipCount`() {
        val e = entity(skipCount = 7, isFavorite = false)
        val toggled = e.copy(isFavorite = true)
        assertEquals(7, toggled.skipCount)
    }

    @Test
    fun `unfavoriting sets isFavorite back to false`() {
        val e = entity(isFavorite = true)
        val unfavorited = e.copy(isFavorite = false)
        assertFalse(unfavorited.isFavorite)
    }

    @Test
    fun `two entities with same songId and different isFavorite are not equal`() {
        val a = entity(songId = 1L, isFavorite = false)
        val b = entity(songId = 1L, isFavorite = true)
        assertTrue(a != b)
    }

    @Test
    fun `default entity has zero play and skip counts`() {
        val e = TrackStatsEntity(songId = 5L, contentUri = "content://media/5")
        assertEquals(0, e.playCount)
        assertEquals(0, e.skipCount)
    }
}
