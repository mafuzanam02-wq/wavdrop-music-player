package com.launchpoint.wavdrop.data.playlists

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistDuplicateRemovalTest {
    private val abac = listOf(
        PlaylistPositionEntry(songId = 1, position = 0),
        PlaylistPositionEntry(songId = 2, position = 1),
        PlaylistPositionEntry(songId = 1, position = 2),
        PlaylistPositionEntry(songId = 3, position = 3),
    )

    @Test
    fun `removing position 2 removes only the second A`() {
        val result = PlaylistPositionRules.removeAtPosition(abac, position = 2)
        assertEquals(listOf(1L, 2L, 3L), result.map { it.songId })
        assertEquals(listOf(0, 1, 2), result.map { it.position })
    }

    @Test
    fun `removing position 0 removes only the first A`() {
        val result = PlaylistPositionRules.removeAtPosition(abac, position = 0)
        assertEquals(listOf(2L, 1L, 3L), result.map { it.songId })
        assertEquals(listOf(0, 1, 2), result.map { it.position })
    }

    @Test
    fun `removing an unknown position changes nothing`() {
        val result = PlaylistPositionRules.removeAtPosition(abac, position = 9)
        assertEquals(listOf(1L, 2L, 1L, 3L), result.map { it.songId })
    }
}
