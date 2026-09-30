package com.launchpoint.wavdrop.playback

import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.playback.QueueMutation.BulkClearPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueBulkClearTest {

    /** [tag] distinguishes occurrences of the same song id, which are otherwise equal. */
    private fun song(id: Long, tag: Long = 0L) = Song(
        id = id, title = "Song $id", artist = "Artist", album = "Album",
        albumId = 0L, duration = 180_000L, uri = "content://media/$id",
        dateAdded = tag, trackNumber = 0, year = 2020,
    )

    private val a = song(1)
    private val b = song(2)
    private val c = song(3)
    private val d = song(4)
    private val e = song(5)

    private fun identity(queue: List<Song>) = queue.indices.toList()

    private fun BulkClearPlan.mutation(): BulkClearPlan.Mutation {
        assertTrue("expected Mutation but was $this", this is BulkClearPlan.Mutation)
        return this as BulkClearPlan.Mutation
    }

    private fun BulkClearPlan.Mutation.assertInvariant() {
        assertEquals(playbackOrder.map { libraryQueue[it] }, playbackQueue)
    }

    @Test
    fun `clear earlier drops history and makes current index zero`() {
        val queue = listOf(a, b, c, d, e)
        val m = QueueMutation.clearEarlier(queue, identity(queue), currentPlaybackIndex = 2).mutation()

        assertEquals(listOf(c, d, e), m.playbackQueue)
        assertEquals(0, m.currentPlaybackIndex)
        assertEquals(2, m.removedCount)
        assertEquals(0, m.mediaRemoveFrom)
        assertEquals(2, m.mediaRemoveToExclusive)
        m.assertInvariant()
    }

    @Test
    fun `clear up next keeps history and current index`() {
        val queue = listOf(a, b, c, d, e)
        val m = QueueMutation.clearUpNext(queue, identity(queue), currentPlaybackIndex = 2).mutation()

        assertEquals(listOf(a, b, c), m.playbackQueue)
        assertEquals(2, m.currentPlaybackIndex)
        assertEquals(2, m.removedCount)
        assertEquals(3, m.mediaRemoveFrom)
        assertEquals(5, m.mediaRemoveToExclusive)
        m.assertInvariant()
    }

    @Test
    fun `clear earlier is a no-op when nothing precedes current`() {
        val queue = listOf(c, d, e)
        assertSame(BulkClearPlan.NoOp, QueueMutation.clearEarlier(queue, identity(queue), 0))
    }

    @Test
    fun `clear up next is a no-op when current is last`() {
        val queue = listOf(a, b, c)
        assertSame(BulkClearPlan.NoOp, QueueMutation.clearUpNext(queue, identity(queue), 2))
    }

    @Test
    fun `out of range or inconsistent input is a no-op`() {
        val queue = listOf(a, b, c)
        assertSame(BulkClearPlan.NoOp, QueueMutation.clearEarlier(queue, identity(queue), 7))
        assertSame(BulkClearPlan.NoOp, QueueMutation.clearUpNext(queue, identity(queue), -1))
        assertSame(BulkClearPlan.NoOp, QueueMutation.clearEarlier(queue, listOf(0, 1), 1))
        assertSame(BulkClearPlan.NoOp, QueueMutation.clearEarlier(queue, listOf(0, 0, 1), 2))
        assertSame(BulkClearPlan.NoOp, QueueMutation.clearEarlier(emptyList(), emptyList(), 0))
    }

    @Test
    fun `duplicate occurrences are cut by index not by song id`() {
        // A1 B A2 [C] A3 D — three distinct occurrences of song A.
        val a1 = song(1, tag = 1)
        val a2 = song(1, tag = 2)
        val a3 = song(1, tag = 3)
        val queue = listOf(a1, b, a2, c, a3, d)

        val earlier = QueueMutation.clearEarlier(queue, identity(queue), 3).mutation()
        assertEquals(listOf(c, a3, d), earlier.playbackQueue)
        assertEquals(0, earlier.currentPlaybackIndex)
        earlier.assertInvariant()

        val upNext = QueueMutation.clearUpNext(queue, identity(queue), 3).mutation()
        assertEquals(listOf(a1, b, a2, c), upNext.playbackQueue)
        assertEquals(3, upNext.currentPlaybackIndex)
        upNext.assertInvariant()
    }

    @Test
    fun `duplicate of the current song elsewhere is never substituted for current`() {
        val c1 = song(3, tag = 1)
        val c2 = song(3, tag = 2)
        val queue = listOf(c1, a, c2, b)

        val m = QueueMutation.clearEarlier(queue, identity(queue), 2).mutation()
        assertEquals(listOf(c2, b), m.playbackQueue)
        assertSame(c2, m.playbackQueue[m.currentPlaybackIndex])
    }

    @Test
    fun `shuffled playback order is cleared in playback order not library order`() {
        val library = listOf(a, b, c, d, e)
        // Playback sequence: D A [E] C B (current = E at playback index 2, library index 4).
        val order = listOf(3, 0, 4, 2, 1)
        assertEquals(listOf(d, a, e, c, b), order.map { library[it] })

        val earlier = QueueMutation.clearEarlier(library, order, 2).mutation()
        assertEquals(listOf(e, c, b), earlier.playbackQueue)
        assertEquals(0, earlier.currentPlaybackIndex)
        assertSame(e, earlier.playbackQueue[0])
        // Surviving source entries keep their original relative source order (B, C, E).
        assertEquals(listOf(b, c, e), earlier.libraryQueue)
        assertEquals(listOf(2, 1, 0), earlier.playbackOrder)
        earlier.assertInvariant()

        val upNext = QueueMutation.clearUpNext(library, order, 2).mutation()
        assertEquals(listOf(d, a, e), upNext.playbackQueue)
        assertEquals(2, upNext.currentPlaybackIndex)
        assertEquals(listOf(a, d, e), upNext.libraryQueue)
        assertEquals(listOf(1, 0, 2), upNext.playbackOrder)
        upNext.assertInvariant()
    }

    @Test
    fun `clearing leaves a valid permutation of the new library queue`() {
        val library = listOf(a, b, c, d, e)
        val order = listOf(1, 4, 0, 3, 2)
        listOf(
            QueueMutation.clearEarlier(library, order, 3).mutation(),
            QueueMutation.clearUpNext(library, order, 1).mutation(),
        ).forEach { m ->
            assertEquals(m.libraryQueue.indices.toList(), m.playbackOrder.sorted())
            m.assertInvariant()
        }
    }
}
