package com.launchpoint.wavdrop.playback

import com.launchpoint.wavdrop.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QueueMutationTest {

    private fun song(id: Long) = Song(
        id = id, title = "Song $id", artist = "Artist", album = "Album",
        albumId = 0L, duration = 180_000L, uri = "content://media/$id",
        dateAdded = 0L, trackNumber = 0, year = 2020,
    )

    private val a = song(1)
    private val b = song(2)
    private val c = song(3)
    private val d = song(4)
    private val e = song(5)
    private val queue = listOf(a, b, c, d, e)

    // ── remove ──────────────────────────────────────────────────────────────────

    @Test
    fun `remove returns null when removing current song`() {
        assertNull(QueueMutation.remove(queue, playbackIndex = 2, currentPlaybackIndex = 2))
    }

    @Test
    fun `remove returns null for out-of-bounds index`() {
        assertNull(QueueMutation.remove(queue, playbackIndex = 10, currentPlaybackIndex = 1))
    }

    @Test
    fun `remove song after current does not shift currentIndex`() {
        val result = QueueMutation.remove(queue, playbackIndex = 3, currentPlaybackIndex = 1)!!
        assertEquals(listOf(a, b, c, e), result.queue)
        assertEquals(1, result.currentIndex)
    }

    @Test
    fun `remove song before current decrements currentIndex`() {
        val result = QueueMutation.remove(queue, playbackIndex = 0, currentPlaybackIndex = 2)!!
        assertEquals(listOf(b, c, d, e), result.queue)
        assertEquals(1, result.currentIndex)
    }

    @Test
    fun `remove last song in queue`() {
        val result = QueueMutation.remove(queue, playbackIndex = 4, currentPlaybackIndex = 1)!!
        assertEquals(listOf(a, b, c, d), result.queue)
        assertEquals(1, result.currentIndex)
    }

    // ── moveToPlayNext ───────────────────────────────────────────────────────────

    @Test
    fun `moveToPlayNext returns null when index is current song`() {
        assertNull(QueueMutation.moveToPlayNext(queue, playbackIndex = 1, currentPlaybackIndex = 1))
    }

    @Test
    fun `moveToPlayNext returns null when index is before current`() {
        assertNull(QueueMutation.moveToPlayNext(queue, playbackIndex = 0, currentPlaybackIndex = 2))
    }

    @Test
    fun `moveToPlayNext returns null when already immediately next`() {
        assertNull(QueueMutation.moveToPlayNext(queue, playbackIndex = 2, currentPlaybackIndex = 1))
    }

    @Test
    fun `moveToPlayNext moves song two positions ahead to immediately next`() {
        val result = QueueMutation.moveToPlayNext(queue, playbackIndex = 3, currentPlaybackIndex = 1)!!
        assertEquals(listOf(a, b, d, c, e), result)
    }

    @Test
    fun `moveToPlayNext moves last song to immediately next`() {
        val result = QueueMutation.moveToPlayNext(queue, playbackIndex = 4, currentPlaybackIndex = 1)!!
        assertEquals(listOf(a, b, e, c, d), result)
    }

    // ── swapAdjacent ────────────────────────────────────────────────────────────

    @Test
    fun `swapAdjacent returns null when first index is current song`() {
        assertNull(QueueMutation.swapAdjacent(queue, 1, 2, currentPlaybackIndex = 1))
    }

    @Test
    fun `swapAdjacent returns null when second index is current song`() {
        assertNull(QueueMutation.swapAdjacent(queue, 2, 1, currentPlaybackIndex = 1))
    }

    @Test
    fun `swapAdjacent returns null when an index is before current`() {
        assertNull(QueueMutation.swapAdjacent(queue, 0, 3, currentPlaybackIndex = 1))
    }

    @Test
    fun `swapAdjacent swaps two adjacent up-next songs - move up`() {
        val result = QueueMutation.swapAdjacent(queue, playbackIndex = 3, otherIndex = 2, currentPlaybackIndex = 1)!!
        assertEquals(listOf(a, b, d, c, e), result)
    }

    @Test
    fun `swapAdjacent swaps two adjacent up-next songs - move down`() {
        val result = QueueMutation.swapAdjacent(queue, playbackIndex = 2, otherIndex = 3, currentPlaybackIndex = 1)!!
        assertEquals(listOf(a, b, d, c, e), result)
    }

    @Test
    fun `swapAdjacent is symmetric - same result regardless of index order`() {
        val r1 = QueueMutation.swapAdjacent(queue, 2, 3, currentPlaybackIndex = 1)
        val r2 = QueueMutation.swapAdjacent(queue, 3, 2, currentPlaybackIndex = 1)
        assertEquals(r1, r2)
    }

    @Test
    fun `insertAfterCurrent inserts selected song directly after current`() {
        val result = QueueMutation.insertAfterCurrent(queue, song = e, currentPlaybackIndex = 1)!!
        assertEquals(listOf(a, b, e, c, d, e), result)
    }

    @Test
    fun `insertAfterCurrent returns null for stale current index`() {
        assertNull(QueueMutation.insertAfterCurrent(queue, song = e, currentPlaybackIndex = 99))
    }

    @Test
    fun `append adds selected song to queue end`() {
        val result = QueueMutation.append(queue, song = c)
        assertEquals(listOf(a, b, c, d, e, c), result)
    }
}
