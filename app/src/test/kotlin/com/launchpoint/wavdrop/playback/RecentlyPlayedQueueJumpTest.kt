package com.launchpoint.wavdrop.playback

import com.launchpoint.wavdrop.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests the pure queue-lookup logic used by [PlayerController.jumpToSongById].
 *
 * PlayerController itself requires a live MediaController (Android component) and cannot be
 * instantiated in a JVM unit test. The decision function [recentlyPlayedQueueIndex] is extracted
 * as a package-internal pure function so the core "is this song in the queue?" logic can be
 * exercised without any Android dependencies.
 */
class RecentlyPlayedQueueJumpTest {

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

    // ── recentlyPlayedQueueIndex ──────────────────────────────────────────────

    @Test
    fun `returns -1 for empty queue`() {
        assertEquals(-1, recentlyPlayedQueueIndex(emptyList(), songId = 1L))
    }

    @Test
    fun `returns -1 when song is not in queue`() {
        val queue = listOf(song(1), song(2), song(3))
        assertEquals(-1, recentlyPlayedQueueIndex(queue, songId = 99L))
    }

    @Test
    fun `returns correct index for unique song`() {
        val queue = listOf(song(10), song(20), song(30))
        assertEquals(1, recentlyPlayedQueueIndex(queue, songId = 20L))
    }

    @Test
    fun `returns first occurrence when duplicates exist`() {
        // indexOfFirst semantics — first match wins for v1
        val queue = listOf(song(1), song(2), song(1), song(3))
        assertEquals(0, recentlyPlayedQueueIndex(queue, songId = 1L))
    }

    @Test
    fun `returns 0 for the first item in queue`() {
        val queue = listOf(song(5), song(6), song(7))
        assertEquals(0, recentlyPlayedQueueIndex(queue, songId = 5L))
    }

    @Test
    fun `returns last index for the last item in queue`() {
        val queue = listOf(song(5), song(6), song(7))
        assertEquals(2, recentlyPlayedQueueIndex(queue, songId = 7L))
    }

    // ── Recently Played tap decision (inline) ─────────────────────────────────
    // These tests model the ViewModel decision logic:
    //   if (recentlyPlayedQueueIndex(...) >= 0) → jump (do not call playFromQueue)
    //   else → fallback to playFromQueue

    @Test
    fun `song in queue resolves to non-negative index — caller should jump`() {
        val queue = listOf(song(1), song(2), song(3))
        val index = recentlyPlayedQueueIndex(queue, songId = 2L)
        assert(index >= 0) { "Expected jump path, got index=$index" }
    }

    @Test
    fun `song not in queue resolves to -1 — caller should fall back`() {
        val queue = listOf(song(1), song(2), song(3))
        val index = recentlyPlayedQueueIndex(queue, songId = 99L)
        assertEquals(-1, index)
    }
}
