package com.launchpoint.wavdrop.playback

import com.launchpoint.wavdrop.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerControllerCurrentIndexResolverTest {

    private fun song(id: Long) = Song(
        id = id,
        title = "Song $id",
        artist = "Artist",
        album = "Album",
        albumId = 0L,
        duration = 180_000L,
        uri = "content://media/$id",
        dateAdded = 0L,
        trackNumber = 0,
        year = 2020,
    )

    private val a = song(1)
    private val b = song(2)
    private val c = song(3)
    private val d = song(4)

    @Test
    fun `synchronized controller index is authoritative for duplicate songs`() {
        val queue = listOf(a, b, a, c, a)

        val result = resolveCurrentPlaybackIndex(
            playbackQueue = queue,
            controllerIndex = 2,
            controllerSongId = a.id,
            stateIndex = 0,
            stateSongId = a.id,
            playerQueueNeedsSync = false,
        )

        assertEquals(2, result)
    }

    @Test
    fun `synchronized controller index resolves third duplicate occurrence`() {
        val queue = listOf(a, b, a, c, a)

        val result = resolveCurrentPlaybackIndex(
            playbackQueue = queue,
            controllerIndex = 4,
            controllerSongId = a.id,
            stateIndex = 0,
            stateSongId = a.id,
            playerQueueNeedsSync = false,
        )

        assertEquals(4, result)
    }

    @Test
    fun `controller absent uses valid state current index`() {
        val queue = listOf(a, b, a, c)

        val result = resolveCurrentPlaybackIndex(
            playbackQueue = queue,
            controllerIndex = null,
            controllerSongId = null,
            stateIndex = 2,
            stateSongId = a.id,
            playerQueueNeedsSync = false,
        )

        assertEquals(2, result)
    }

    @Test
    fun `dirty player queue ignores stale controller index and uses state index`() {
        val queue = listOf(a, b, c)

        val result = resolveCurrentPlaybackIndex(
            playbackQueue = queue,
            controllerIndex = 2,
            controllerSongId = c.id,
            stateIndex = 1,
            stateSongId = b.id,
            playerQueueNeedsSync = true,
        )

        assertEquals(1, result)
    }

    @Test
    fun `unique controller id fallback is allowed when controller index is unusable`() {
        val queue = listOf(a, b, c)

        val result = resolveCurrentPlaybackIndex(
            playbackQueue = queue,
            controllerIndex = 99,
            controllerSongId = c.id,
            stateIndex = null,
            stateSongId = null,
            playerQueueNeedsSync = false,
        )

        assertEquals(2, result)
    }

    @Test
    fun `duplicate id fallback refuses to choose first occurrence`() {
        val queue = listOf(a, b, a, c)

        val result = resolveCurrentPlaybackIndex(
            playbackQueue = queue,
            controllerIndex = 99,
            controllerSongId = a.id,
            stateIndex = null,
            stateSongId = null,
            playerQueueNeedsSync = false,
        )

        assertNull(result)
    }

    @Test
    fun `synchronized controller index mismatch fails safely without id fallback`() {
        val queue = listOf(a, b, c, d)

        val result = resolveCurrentPlaybackIndex(
            playbackQueue = queue,
            controllerIndex = 1,
            controllerSongId = c.id,
            stateIndex = null,
            stateSongId = null,
            playerQueueNeedsSync = false,
        )

        assertNull(result)
    }

    @Test
    fun `state song id fallback only resolves a unique occurrence`() {
        val queue = listOf(a, b, c)

        val result = resolveCurrentPlaybackIndex(
            playbackQueue = queue,
            controllerIndex = null,
            controllerSongId = null,
            stateIndex = null,
            stateSongId = b.id,
            playerQueueNeedsSync = false,
        )

        assertEquals(1, result)
    }
}
