package com.launchpoint.wavdrop.data.playlists

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistPositionRulesTest {

    private fun entry(songId: Long, position: Int) =
        PlaylistPositionEntry(songId = songId, position = position)

    @Test
    fun `add multiple songs appends in order`() {
        val result = PlaylistPositionRules.append(
            current = listOf(entry(10, 0)),
            songIds = listOf(20, 30, 40),
        )

        assertEquals(
            listOf(entry(10, 0), entry(20, 1), entry(30, 2), entry(40, 3)),
            result,
        )
    }

    @Test
    fun `remove by position preserves duplicate song entries`() {
        val result = PlaylistPositionRules.removeAtPosition(
            current = listOf(entry(10, 0), entry(20, 1), entry(10, 2)),
            position = 0,
        )

        assertEquals(
            listOf(entry(20, 0), entry(10, 1)),
            result,
        )
    }

    @Test
    fun `move up swaps with previous entry`() {
        val result = PlaylistPositionRules.move(
            current = listOf(entry(10, 0), entry(20, 1), entry(30, 2)),
            fromPosition = 2,
            toPosition = 1,
        )

        assertEquals(
            listOf(entry(10, 0), entry(30, 1), entry(20, 2)),
            result,
        )
    }

    @Test
    fun `move down swaps with next entry`() {
        val result = PlaylistPositionRules.move(
            current = listOf(entry(10, 0), entry(20, 1), entry(30, 2)),
            fromPosition = 0,
            toPosition = 1,
        )

        assertEquals(
            listOf(entry(20, 0), entry(10, 1), entry(30, 2)),
            result,
        )
    }

    @Test
    fun `move first up is a safe no-op`() {
        val result = PlaylistPositionRules.move(
            current = listOf(entry(10, 0), entry(20, 1)),
            fromPosition = 0,
            toPosition = -1,
        )

        assertEquals(listOf(entry(10, 0), entry(20, 1)), result)
    }

    @Test
    fun `move last down is a safe no-op`() {
        val result = PlaylistPositionRules.move(
            current = listOf(entry(10, 0), entry(20, 1)),
            fromPosition = 1,
            toPosition = 2,
        )

        assertEquals(listOf(entry(10, 0), entry(20, 1)), result)
    }

    @Test
    fun `positions are reindexed after remove`() {
        val result = PlaylistPositionRules.removeAtPosition(
            current = listOf(entry(10, 0), entry(20, 4), entry(30, 8)),
            position = 4,
        )

        assertEquals(listOf(entry(10, 0), entry(30, 1)), result)
    }

    @Test
    fun `positions are reindexed after move`() {
        val result = PlaylistPositionRules.move(
            current = listOf(entry(10, 2), entry(20, 4), entry(30, 8)),
            fromPosition = 8,
            toPosition = 2,
        )

        assertEquals(listOf(entry(30, 0), entry(10, 1), entry(20, 2)), result)
    }
}
