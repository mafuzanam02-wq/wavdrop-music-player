package com.launchpoint.wavdrop.playback

import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.playback.QueueMutation.NonCurrentDeletionPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Non-current library deletion is planned purely from the resolved current occurrence; the planner has no
 * Now Playing input at all. Songs are named by id (A=1, B=2, C=3, D=4); duplicates differ by `tag` (the
 * source position).
 */
class NonCurrentSongDeletionPlannerTest {

    private fun song(id: Long, tag: Long = 0L) = Song(
        id = id, title = "S$id", artist = "Artist", album = "Album",
        albumId = 0L, duration = 200_000L, uri = "content://media/$id/$tag",
        dateAdded = tag, trackNumber = 0, year = 2020,
    )

    private val a = 1L
    private val b = 2L
    private val c = 3L
    private val d = 4L

    private fun library(ids: List<Long>) = ids.mapIndexed { i, id -> song(id, tag = i.toLong()) }

    private fun plan(
        ids: List<Long>,
        current: Int,
        deleted: Long,
        order: List<Int> = ids.indices.toList(),
    ) = QueueMutation.planNonCurrentSongDeletion(library(ids), order, current, deleted)

    private fun NonCurrentDeletionPlan.mut(): NonCurrentDeletionPlan.Mutation {
        assertTrue("expected Mutation but was $this", this is NonCurrentDeletionPlan.Mutation)
        return this as NonCurrentDeletionPlan.Mutation
    }

    private fun NonCurrentDeletionPlan.Mutation.currentSong() = playbackQueue[currentPlaybackIndex]

    @Test fun duplicateDeletedAroundCurrentLeavesCurrentB() {
        val m = plan(listOf(a, b, a), current = 1, deleted = a).mut()
        assertEquals(listOf(b), m.playbackQueue.map { it.id })
        assertEquals(0, m.currentPlaybackIndex)
        assertEquals(1L, m.currentSong().dateAdded) // the original B occurrence
    }

    @Test fun multipleDeletedDuplicatesLeaveCurrentC() {
        val m = plan(listOf(a, b, a, c, a), current = 3, deleted = a).mut()
        assertEquals(listOf(b, c), m.playbackQueue.map { it.id })
        assertEquals(1, m.currentPlaybackIndex)
        assertEquals(3L, m.currentSong().dateAdded)
    }

    @Test fun deletedOccurrenceBeforeCurrent() {
        val m = plan(listOf(a, b, c), current = 2, deleted = a).mut()
        assertEquals(listOf(b, c), m.playbackQueue.map { it.id })
        assertEquals(1, m.currentPlaybackIndex)
        assertEquals(listOf(0), m.removedPlaybackPositions)
    }

    @Test fun deletedOccurrenceAfterCurrent() {
        val m = plan(listOf(a, b, c), current = 0, deleted = c).mut()
        assertEquals(listOf(a, b), m.playbackQueue.map { it.id })
        assertEquals(0, m.currentPlaybackIndex)
        assertEquals(listOf(2), m.removedPlaybackPositions)
    }

    @Test fun deletedOccurrencesBothBeforeAndAfterCurrent() {
        val m = plan(listOf(a, b, c, a), current = 2, deleted = a).mut()
        assertEquals(listOf(b, c), m.playbackQueue.map { it.id })
        assertEquals(1, m.currentPlaybackIndex)
        assertEquals(listOf(3, 0), m.removedPlaybackPositions) // descending
    }

    @Test fun removedPositionsAreDescendingOldPlaybackPositions() {
        val m = plan(listOf(a, a, b, a, a, c), current = 2, deleted = a).mut()
        assertEquals(listOf(4, 3, 1, 0), m.removedPlaybackPositions)
        assertEquals(listOf(b, c), m.playbackQueue.map { it.id })
    }

    @Test fun shuffledPlaybackOrderKeepsSequenceAndCurrentOccurrence() {
        // library [A, B, C, D, A]; playback order D, A(4), B, C, A(0); current B at playback 2.
        val m = plan(listOf(a, b, c, d, a), current = 2, deleted = a, order = listOf(3, 4, 1, 2, 0)).mut()
        assertEquals(listOf(d, b, c), m.playbackQueue.map { it.id })
        assertEquals(1, m.currentPlaybackIndex)
        assertEquals(1L, m.currentSong().dateAdded) // B, source position 1
        assertEquals(listOf(4, 1), m.removedPlaybackPositions)
    }

    @Test fun duplicateSurvivingSongsRetainPositionalIdentity() {
        // library [B, A, B, C, A]; current is the SECOND B (source 2); A deleted.
        val m = plan(listOf(b, a, b, c, a), current = 2, deleted = a).mut()
        assertEquals(listOf(b, b, c), m.playbackQueue.map { it.id })
        assertEquals(listOf(0L, 2L, 3L), m.playbackQueue.map { it.dateAdded })
        assertEquals(1, m.currentPlaybackIndex)
        assertEquals(2L, m.currentSong().dateAdded)
    }

    @Test fun absentDeletedIdIsNoOp() =
        assertEquals(NonCurrentDeletionPlan.NoOp, plan(listOf(a, b, c), current = 1, deleted = d))

    @Test fun resolvedCurrentHavingDeletedIdFailsClosed() =
        assertEquals(NonCurrentDeletionPlan.NoOp, plan(listOf(a, b, a), current = 2, deleted = a))

    @Test fun malformedPlaybackOrderFailsClosed() {
        assertEquals(NonCurrentDeletionPlan.NoOp, plan(listOf(a, b, c), 1, a, order = listOf(0, 1)))
        assertEquals(NonCurrentDeletionPlan.NoOp, plan(listOf(a, b, c), 1, a, order = listOf(0, 0, 1)))
        assertEquals(NonCurrentDeletionPlan.NoOp, plan(listOf(a, b, c), 1, a, order = listOf(0, 1, 9)))
    }

    @Test fun invalidResolvedCurrentIndexFailsClosed() {
        assertEquals(NonCurrentDeletionPlan.NoOp, plan(listOf(a, b), -1, a))
        assertEquals(NonCurrentDeletionPlan.NoOp, plan(listOf(a, b), 2, a))
    }

    @Test fun resultingOrderIsDenseValidAndQueueIsDerived() {
        val m = plan(listOf(a, b, a, c, d, a), current = 3, deleted = a, order = listOf(5, 3, 0, 4, 1, 2)).mut()
        assertEquals(m.libraryQueue.size, m.playbackOrder.size)
        assertEquals(m.libraryQueue.indices.toSet(), m.playbackOrder.toSet())
        assertEquals(m.playbackOrder.map { m.libraryQueue[it] }, m.playbackQueue)
        assertTrue(m.playbackQueue.none { it.id == a })
    }

    @Test fun currentRemainsTheSameSourceOccurrence() {
        val ids = listOf(a, b, a, c, a, c)
        val order = listOf(5, 0, 3, 2, 4, 1)
        for (cur in order.indices) {
            val currentId = ids[order[cur]]
            if (currentId == a) continue
            val before = library(ids)[order[cur]]
            val m = plan(ids, cur, a, order).mut()
            assertEquals(before, m.currentSong())
        }
    }

    @Test fun resultDependsOnlyOnResolvedOccurrenceNotStaleNowPlayingIndex() {
        // queue [A, B, A]: actual current is B (playback 1) while a stale Now Playing index would say 0 (A).
        // The planner takes no Now Playing input, so the stale index cannot make an A survive.
        val staleNowPlayingIndex = 0
        val resolvedIndex = 1
        val m = plan(listOf(a, b, a), current = resolvedIndex, deleted = a).mut()
        assertEquals(listOf(b), m.playbackQueue.map { it.id })
        assertEquals(listOf(2, 0), m.removedPlaybackPositions) // the "stale current" A at 0 IS removed
        assertTrue(staleNowPlayingIndex in m.removedPlaybackPositions)
        // Feeding the stale index as if it were authoritative would instead be a different (fail-closed) plan.
        assertEquals(NonCurrentDeletionPlan.NoOp, plan(listOf(a, b, a), current = staleNowPlayingIndex, deleted = a))
    }
}
