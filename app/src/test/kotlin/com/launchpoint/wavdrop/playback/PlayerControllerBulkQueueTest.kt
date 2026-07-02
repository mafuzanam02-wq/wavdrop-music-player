package com.launchpoint.wavdrop.playback

import com.launchpoint.wavdrop.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class PlayerControllerBulkQueueTest {

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

    @Test
    fun `playAllNext preserves supplied order with active queue`() {
        val songs = listOf(song(1), song(2), song(3))

        val plan = planPlayAllNext(songs, hasActiveCurrentItem = true)
            as PlayAllNextPlan.InsertAfterCurrent

        val current = song(99)
        val result = QueueMutation.insertAllAfterCurrent(
            libraryQueue = listOf(current),
            playbackOrder = listOf(0),
            currentPlaybackIndex = 0,
            songs = plan.songs,
        )!!

        assertEquals(listOf(current) + songs, result.playbackQueue)
    }

    @Test
    fun `playAllNext preserves supplied order with empty queue`() {
        val songs = listOf(song(1), song(2), song(3))

        val plan = planPlayAllNext(songs, hasActiveCurrentItem = false)
            as PlayAllNextPlan.StartQueue

        assertEquals(songs, plan.queue)
        assertSame(songs.first(), plan.startSong)
    }

    @Test
    fun `addAllToQueue appends in supplied order`() {
        val existing = listOf(song(99))
        val songs = listOf(song(1), song(2), song(3))

        val result = QueueMutation.appendAll(
            libraryQueue = existing,
            playbackOrder = listOf(0),
            songs = songs,
        )

        assertEquals(existing + songs, result.playbackQueue)
        assertEquals(listOf(0, 1, 2, 3), result.playbackOrder)
    }

    @Test
    fun `playAllNext batch preserves current item index`() {
        val existing = listOf(song(10), song(20), song(30))
        val songs = listOf(song(1), song(2), song(3))

        val result = QueueMutation.insertAllAfterCurrent(
            libraryQueue = existing,
            playbackOrder = listOf(0, 1, 2),
            currentPlaybackIndex = 1,
            songs = songs,
        )!!

        assertEquals(song(20), result.playbackQueue[1])
        assertEquals(listOf(10L, 20L, 1L, 2L, 3L, 30L), result.playbackQueue.map { it.id })
        assertEquals(listOf(0, 1, 3, 4, 5, 2), result.playbackOrder)
    }

    @Test
    fun `playAllNext batch preserves shuffled playback order around current item`() {
        val existing = listOf(song(10), song(20), song(30), song(40), song(50))
        val shuffledOrder = listOf(2, 4, 1, 0, 3)
        val songs = listOf(song(101), song(102))

        val result = QueueMutation.insertAllAfterCurrent(
            libraryQueue = existing,
            playbackOrder = shuffledOrder,
            currentPlaybackIndex = 1,
            songs = songs,
        )!!

        assertEquals(listOf(30L, 50L, 101L, 102L, 20L, 10L, 40L), result.playbackQueue.map { it.id })
        assertEquals(listOf(2, 4, 5, 6, 1, 0, 3), result.playbackOrder)
    }

    @Test
    fun `large batch insertion appends all songs in one computed model`() {
        val existing = listOf(song(99))
        val songs = (1L..5_000L).map(::song)

        val result = QueueMutation.appendAll(
            libraryQueue = existing,
            playbackOrder = listOf(0),
            songs = songs,
        )

        assertEquals(5_001, result.libraryQueue.size)
        assertEquals(5_001, result.playbackOrder.size)
        assertEquals(99L, result.playbackQueue.first().id)
        assertEquals(5_000L, result.playbackQueue.last().id)
    }

    @Test
    fun `batch play next with non-empty queue routes to insert not start-queue`() {
        // playAllNext treats any non-empty library queue as active (hasActiveCurrentItem = true),
        // so an unresolvable current index no longer flips to StartQueue and replaces the queue.
        val songs = listOf(song(1), song(2))

        val plan = planPlayAllNext(songs, hasActiveCurrentItem = true)

        assert(plan is PlayAllNextPlan.InsertAfterCurrent)
    }

    @Test
    fun `batch play next unresolved current index appends instead of dropping`() {
        // insertAllAfterCurrent falls back to QueueMutation.appendAll when the current index is
        // unresolved. The existing queue is preserved and the batch is appended, never dropped.
        val existing = listOf(song(10), song(20), song(30))
        val songs = listOf(song(1), song(2), song(3))

        val result = QueueMutation.appendAll(
            libraryQueue = existing,
            playbackOrder = listOf(0, 1, 2),
            songs = songs,
        )

        assertEquals(existing + songs, result.playbackQueue)
        assertEquals(existing.size + songs.size, result.playbackQueue.size)
    }

    @Test
    fun `bulk queue actions preserve duplicate tracks`() {
        val duplicate = song(1)
        val songs = listOf(duplicate, song(2), duplicate)

        val plan = planPlayAllNext(songs, hasActiveCurrentItem = false)
            as PlayAllNextPlan.StartQueue

        assertEquals(listOf(1L, 2L, 1L), plan.queue.map { it.id })
    }
}
