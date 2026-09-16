package com.launchpoint.wavdrop.playback

import com.launchpoint.wavdrop.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerControllerBatchQueueSyncPlannerTest {

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
    private val e = song(5)
    private val f = song(6)

    @Test
    fun `batch insertion replaces only the future suffix when current prefix is unchanged`() {
        val oldQueue = listOf(a, b, c, d)
        val newQueue = listOf(a, b, e, c, d)

        val plan = planBatchQueuePlayerSync(
            oldPlaybackQueue = oldQueue,
            newPlaybackQueue = newQueue,
            currentPlaybackIndex = 1,
            controllerAvailable = true,
            playerQueueNeedsSync = false,
            controllerCurrentIndex = 1,
            controllerSongId = b.id,
            controllerMediaItemCount = oldQueue.size,
        )

        assertEquals(BatchQueuePlayerSyncAction.ReplaceFutureSuffix, plan.action)
        assertEquals(2, plan.futureStartIndex)
        assertEquals(listOf(e, c, d), plan.suffix)
    }

    @Test
    fun `batch insertion does nothing to Media3 when playback queue is already equivalent`() {
        val queue = listOf(a, b, c)

        val plan = planBatchQueuePlayerSync(
            oldPlaybackQueue = queue,
            newPlaybackQueue = queue,
            currentPlaybackIndex = 1,
            controllerAvailable = true,
            playerQueueNeedsSync = false,
            controllerCurrentIndex = 1,
            controllerSongId = b.id,
            controllerMediaItemCount = queue.size,
        )

        assertEquals(BatchQueuePlayerSyncAction.NoOp, plan.action)
        assertEquals(emptyList<Song>(), plan.suffix)
    }

    @Test
    fun `missing controller marks queue dirty for reconnect sync`() {
        val plan = planBatchQueuePlayerSync(
            oldPlaybackQueue = listOf(a, b),
            newPlaybackQueue = listOf(a, b, c),
            currentPlaybackIndex = 0,
            controllerAvailable = false,
            playerQueueNeedsSync = false,
            controllerCurrentIndex = null,
            controllerSongId = null,
            controllerMediaItemCount = null,
        )

        assertEquals(BatchQueuePlayerSyncAction.MarkDirty, plan.action)
    }

    @Test
    fun `dirty player queue falls back to full sync`() {
        val plan = planBatchQueuePlayerSync(
            oldPlaybackQueue = listOf(a, b),
            newPlaybackQueue = listOf(a, c, b),
            currentPlaybackIndex = 0,
            controllerAvailable = true,
            playerQueueNeedsSync = true,
            controllerCurrentIndex = 0,
            controllerSongId = a.id,
            controllerMediaItemCount = 2,
        )

        assertEquals(BatchQueuePlayerSyncAction.FullQueueSync, plan.action)
    }

    @Test
    fun `mismatched current occurrence falls back to full sync`() {
        val plan = planBatchQueuePlayerSync(
            oldPlaybackQueue = listOf(a, b, c),
            newPlaybackQueue = listOf(a, b, d, c),
            currentPlaybackIndex = 1,
            controllerAvailable = true,
            playerQueueNeedsSync = false,
            controllerCurrentIndex = 2,
            controllerSongId = c.id,
            controllerMediaItemCount = 3,
        )

        assertEquals(BatchQueuePlayerSyncAction.FullQueueSync, plan.action)
    }

    @Test
    fun `controller count smaller than old queue falls back to full sync`() {
        val oldQueue = listOf(a, b, c)

        val plan = planBatchQueuePlayerSync(
            oldPlaybackQueue = oldQueue,
            newPlaybackQueue = listOf(a, b, d, c),
            currentPlaybackIndex = 1,
            controllerAvailable = true,
            playerQueueNeedsSync = false,
            controllerCurrentIndex = 1,
            controllerSongId = b.id,
            controllerMediaItemCount = oldQueue.size - 1,
        )

        assertEquals(BatchQueuePlayerSyncAction.FullQueueSync, plan.action)
    }

    @Test
    fun `controller count larger than old queue falls back to full sync`() {
        val oldQueue = listOf(a, b, c)

        val plan = planBatchQueuePlayerSync(
            oldPlaybackQueue = oldQueue,
            newPlaybackQueue = listOf(a, b, d, c),
            currentPlaybackIndex = 1,
            controllerAvailable = true,
            playerQueueNeedsSync = false,
            controllerCurrentIndex = 1,
            controllerSongId = b.id,
            controllerMediaItemCount = oldQueue.size + 1,
        )

        assertEquals(BatchQueuePlayerSyncAction.FullQueueSync, plan.action)
    }

    @Test
    fun `available controller with unavailable count falls back to full sync`() {
        val oldQueue = listOf(a, b, c)

        val plan = planBatchQueuePlayerSync(
            oldPlaybackQueue = oldQueue,
            newPlaybackQueue = listOf(a, b, d, c),
            currentPlaybackIndex = 1,
            controllerAvailable = true,
            playerQueueNeedsSync = false,
            controllerCurrentIndex = 1,
            controllerSongId = b.id,
            controllerMediaItemCount = null,
        )

        assertEquals(BatchQueuePlayerSyncAction.FullQueueSync, plan.action)
    }

    @Test
    fun `suffix plan preserves duplicate current occurrence`() {
        val oldQueue = listOf(a, b, a, c)
        val newQueue = listOf(a, b, a, d, c)

        val plan = planBatchQueuePlayerSync(
            oldPlaybackQueue = oldQueue,
            newPlaybackQueue = newQueue,
            currentPlaybackIndex = 2,
            controllerAvailable = true,
            playerQueueNeedsSync = false,
            controllerCurrentIndex = 2,
            controllerSongId = a.id,
            controllerMediaItemCount = oldQueue.size,
        )

        assertEquals(BatchQueuePlayerSyncAction.ReplaceFutureSuffix, plan.action)
        assertEquals(3, plan.futureStartIndex)
        assertEquals(listOf(d, c), plan.suffix)
    }

    @Test
    fun `artist catalogue case keeps current prefix and replaces only future suffix`() {
        val oldQueue = listOf(a, b, c, d, e)
        val mutation = QueueMutation.insertAllAfterCurrent(
            libraryQueue = oldQueue,
            playbackOrder = oldQueue.indices.toList(),
            currentPlaybackIndex = 1,
            songs = listOf(c, b, d, f),
        )!!

        val plan = planBatchQueuePlayerSync(
            oldPlaybackQueue = oldQueue,
            newPlaybackQueue = mutation.playbackQueue,
            currentPlaybackIndex = 1,
            controllerAvailable = true,
            playerQueueNeedsSync = false,
            controllerCurrentIndex = 1,
            controllerSongId = b.id,
            controllerMediaItemCount = oldQueue.size,
        )

        assertEquals(listOf(a, b, c, d, f, e), mutation.playbackQueue)
        assertEquals(BatchQueuePlayerSyncAction.ReplaceFutureSuffix, plan.action)
        assertEquals(2, plan.futureStartIndex)
        assertEquals(listOf(c, d, f, e), plan.suffix)
    }

    @Test
    fun `repeating the same batch becomes a no-op after queue is already correct`() {
        val oldQueue = listOf(a, b, c, d)
        val first = QueueMutation.insertAllAfterCurrent(
            libraryQueue = oldQueue,
            playbackOrder = oldQueue.indices.toList(),
            currentPlaybackIndex = 0,
            songs = listOf(b, c, d),
        )!!
        val second = QueueMutation.insertAllAfterCurrent(
            libraryQueue = first.libraryQueue,
            playbackOrder = first.playbackOrder,
            currentPlaybackIndex = 0,
            songs = listOf(b, c, d),
        )!!

        val plan = planBatchQueuePlayerSync(
            oldPlaybackQueue = first.playbackQueue,
            newPlaybackQueue = second.playbackQueue,
            currentPlaybackIndex = 0,
            controllerAvailable = true,
            playerQueueNeedsSync = false,
            controllerCurrentIndex = 0,
            controllerSongId = a.id,
            controllerMediaItemCount = first.playbackQueue.size,
        )

        assertEquals(first.playbackQueue, second.playbackQueue)
        assertEquals(BatchQueuePlayerSyncAction.NoOp, plan.action)
    }

    @Test
    fun `single play next inserts after later duplicate current occurrence`() {
        val queue = listOf(a, b, a, c)

        val result = QueueMutation.insertAfterCurrent(
            playbackQueue = queue,
            song = d,
            currentPlaybackIndex = 2,
        )

        assertEquals(listOf(a, b, a, d, c), result)
    }
}
