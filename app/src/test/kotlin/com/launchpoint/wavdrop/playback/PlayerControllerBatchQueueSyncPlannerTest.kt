package com.launchpoint.wavdrop.playback

import com.launchpoint.wavdrop.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    private fun plan(
        old: List<Song>,
        new: List<Song>,
        current: Int,
        controllerAvailable: Boolean = true,
        dirty: Boolean = false,
        controllerIndex: Int? = current,
        controllerSongId: Long? = old.getOrNull(current)?.id,
        count: Int? = old.size,
    ) = planBatchQueuePlayerSync(
        oldPlaybackQueue = old,
        newPlaybackQueue = new,
        currentPlaybackIndex = current,
        controllerAvailable = controllerAvailable,
        playerQueueNeedsSync = dirty,
        controllerCurrentIndex = controllerIndex,
        controllerSongId = controllerSongId,
        controllerMediaItemCount = count,
    )

    @Test
    fun `batch insertion inserts only the batch when the current prefix is unchanged`() {
        val plan = plan(listOf(a, b, c, d), listOf(a, b, e, c, d), current = 1)

        assertEquals(BatchQueuePlayerSyncAction.ReplaceFutureSpan, plan.action)
        assertEquals(2, plan.futureStartIndex)
        assertEquals(2, plan.replaceFromIndex)
        assertEquals(2, plan.replaceToIndexExclusive) // from == to: an insertion, the old suffix is untouched
        assertEquals(listOf(e), plan.songs)
    }

    @Test
    fun `batch insertion does nothing to Media3 when playback queue is already equivalent`() {
        val queue = listOf(a, b, c)

        val plan = plan(queue, queue, current = 1)

        assertEquals(BatchQueuePlayerSyncAction.NoOp, plan.action)
        assertEquals(emptyList<Song>(), plan.songs)
    }

    @Test
    fun `missing controller marks queue dirty for reconnect repair`() {
        val plan = plan(listOf(a, b), listOf(a, b, c), current = 0, controllerAvailable = false, controllerIndex = null, controllerSongId = null, count = null)

        assertEquals(BatchQueuePlayerSyncAction.MarkDirty, plan.action)
        assertEquals(QueueDirtyReason.ControllerUnavailable, plan.dirtyReason)
    }

    @Test
    fun `an already dirty queue stays dirty for the reconciler and never becomes a full re-push`() {
        val plan = plan(listOf(a, b), listOf(a, c, b), current = 0, dirty = true)

        assertEquals(BatchQueuePlayerSyncAction.MarkDirty, plan.action)
        assertEquals(QueueDirtyReason.AlreadyDirty, plan.dirtyReason)
    }

    @Test
    fun `mismatched current occurrence is dirty for the reconciler`() {
        val plan = plan(listOf(a, b, c), listOf(a, b, d, c), current = 1, controllerIndex = 2, controllerSongId = c.id)

        assertEquals(BatchQueuePlayerSyncAction.MarkDirty, plan.action)
        assertEquals(QueueDirtyReason.PhysicalCurrentMismatch, plan.dirtyReason)
    }

    @Test
    fun `controller count smaller than old queue is dirty`() {
        val old = listOf(a, b, c)
        val plan = plan(old, listOf(a, b, d, c), current = 1, count = old.size - 1)

        assertEquals(BatchQueuePlayerSyncAction.MarkDirty, plan.action)
        assertEquals(QueueDirtyReason.PhysicalCountMismatch, plan.dirtyReason)
    }

    @Test
    fun `controller count larger than old queue is dirty`() {
        val old = listOf(a, b, c)
        val plan = plan(old, listOf(a, b, d, c), current = 1, count = old.size + 1)

        assertEquals(BatchQueuePlayerSyncAction.MarkDirty, plan.action)
        assertEquals(QueueDirtyReason.PhysicalCountMismatch, plan.dirtyReason)
    }

    @Test
    fun `available controller with unavailable count is dirty`() {
        val plan = plan(listOf(a, b, c), listOf(a, b, d, c), current = 1, count = null)

        assertEquals(BatchQueuePlayerSyncAction.MarkDirty, plan.action)
        assertEquals(QueueDirtyReason.PhysicalCountMismatch, plan.dirtyReason)
    }

    @Test
    fun `changed prefix is dirty`() {
        val plan = plan(listOf(a, b, c), listOf(d, b, c, e), current = 1)

        assertEquals(BatchQueuePlayerSyncAction.MarkDirty, plan.action)
        assertEquals(QueueDirtyReason.PhysicalPrefixChanged, plan.dirtyReason)
    }

    @Test
    fun `span plan preserves duplicate current occurrence`() {
        val old = listOf(a, b, a, c)
        val new = listOf(a, b, a, d, c)

        val plan = plan(old, new, current = 2, controllerSongId = a.id)

        assertEquals(BatchQueuePlayerSyncAction.ReplaceFutureSpan, plan.action)
        assertEquals(3, plan.futureStartIndex)
        assertEquals(3, plan.replaceFromIndex)
        assertEquals(3, plan.replaceToIndexExclusive)
        assertEquals(listOf(d), plan.songs)
    }

    @Test
    fun `artist catalogue case replaces only the changed middle span`() {
        val oldQueue = listOf(a, b, c, d, e)
        val mutation = QueueMutation.insertAllAfterCurrent(
            libraryQueue = oldQueue,
            playbackOrder = oldQueue.indices.toList(),
            currentPlaybackIndex = 1,
            songs = listOf(c, b, d, f),
        )!!

        val plan = plan(oldQueue, mutation.playbackQueue, current = 1, controllerSongId = b.id)

        assertEquals(listOf(a, b, c, d, f, e), mutation.playbackQueue)
        assertEquals(BatchQueuePlayerSyncAction.ReplaceFutureSpan, plan.action)
        assertEquals(2, plan.futureStartIndex)
        assertEquals(4, plan.replaceFromIndex)
        assertEquals(4, plan.replaceToIndexExclusive)
        assertEquals(listOf(f), plan.songs)
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

        val plan = plan(first.playbackQueue, second.playbackQueue, current = 0, controllerSongId = a.id)

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

    // ---- large queues: the cost of a Play All Next follows the batch, never the existing queue ----

    private fun bigQueue(size: Int) = List(size) { song(10_000L + it) }

    @Test
    fun `large queue play all next of 1 10 and 1000 songs replaces only the batch at 1k 5k and 12288`() {
        for (size in listOf(1_000, 5_000, 12_288)) {
            val old = bigQueue(size)
            for (position in listOf(0, size / 2, size - 2)) { // beginning, middle, near end
                for (batchSize in listOf(1, 10, 1_000)) {
                    val batch = List(batchSize) { song(900_000L + it) }
                    val new = old.subList(0, position + 1) + batch + old.subList(position + 1, size)

                    val plan = plan(old, new, current = position)

                    assertEquals("size=$size pos=$position batch=$batchSize", BatchQueuePlayerSyncAction.ReplaceFutureSpan, plan.action)
                    assertEquals(batchSize, plan.songs.size) // O(batch), not O(queue)
                    assertEquals(plan.replaceFromIndex, plan.replaceToIndexExclusive) // pure insertion: nothing replaced
                    assertEquals(position + 1, plan.replaceFromIndex)
                }
            }
        }
    }

    @Test
    fun `a batch larger than one safe replace is repaired in chunks by the reconciler instead`() {
        val old = bigQueue(12_288)
        val batch = List(MAX_SINGLE_SPAN_REPLACE_ITEMS + 1) { song(900_000L + it) }
        val new = old.subList(0, 101) + batch + old.subList(101, old.size)

        val plan = plan(old, new, current = 100)

        assertEquals(BatchQueuePlayerSyncAction.MarkDirty, plan.action)
        assertEquals(QueueDirtyReason.LargeBatchSpan, plan.dirtyReason)
    }

    @Test
    fun `the batch planner never returns a full queue re-push for any dirty reason`() {
        val old = bigQueue(5_000)
        val new = old.subList(0, 11) + song(1) + old.subList(11, old.size)
        val plans = listOf(
            plan(old, new, current = 10, dirty = true),
            plan(old, new, current = 10, controllerAvailable = false, controllerIndex = null, controllerSongId = null, count = null),
            plan(old, new, current = 10, controllerIndex = 11),
            plan(old, new, current = 10, count = old.size - 1),
        )
        assertTrue(plans.all { it.action == BatchQueuePlayerSyncAction.MarkDirty })
    }
}
