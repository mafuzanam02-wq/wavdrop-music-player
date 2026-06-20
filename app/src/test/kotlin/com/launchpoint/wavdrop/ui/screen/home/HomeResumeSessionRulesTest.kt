package com.launchpoint.wavdrop.ui.screen.home

import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.playback.NowPlayingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeResumeSessionRulesTest {

    private val song = Song(
        id = 1L,
        title = "Song",
        artist = "Artist",
        album = "Album",
        albumId = 1L,
        duration = 240_000L,
        uri = "content://song/1",
        dateAdded = 0L,
        trackNumber = 1,
        year = 2026,
    )

    @Test
    fun `playing session is hidden`() {
        val state = NowPlayingState(
            song = song,
            isPlaying = true,
            queue = listOf(song),
            positionMs = 20_000L,
        )

        assertFalse(shouldShowResumeSessionCard(state))
    }

    @Test
    fun `short paused single-song session is hidden`() {
        val state = NowPlayingState(
            song = song,
            queue = listOf(song),
            positionMs = 5_000L,
        )

        assertFalse(shouldShowResumeSessionCard(state))
    }

    @Test
    fun `paused single-song session past threshold is visible`() {
        val state = NowPlayingState(
            song = song,
            queue = listOf(song),
            positionMs = 15_000L,
        )

        assertTrue(shouldShowResumeSessionCard(state))
    }

    @Test
    fun `paused multi-song session at start is visible`() {
        val state = NowPlayingState(
            song = song,
            queue = listOf(song, song.copy(id = 2L)),
            positionMs = 0L,
        )

        assertTrue(shouldShowResumeSessionCard(state))
    }

    @Test
    fun `session without current song is hidden`() {
        assertFalse(
            shouldShowResumeSessionCard(
                NowPlayingState(queue = listOf(song, song.copy(id = 2L))),
            ),
        )
    }

    @Test
    fun `duration formatting supports minutes and hours`() {
        assertEquals("0:00", formatResumeSessionTime(0L))
        assertEquals("1:42", formatResumeSessionTime(102_000L))
        assertEquals("1:02:33", formatResumeSessionTime(3_753_000L))
    }
}
