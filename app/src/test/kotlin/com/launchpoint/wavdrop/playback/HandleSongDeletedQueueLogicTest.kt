package com.launchpoint.wavdrop.playback

import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.playback.QueueMutation.CurrentDeletionPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Current-song deletion is ONE coherent pure plan (no seek-then-remove sequencing). Songs are named
 * by id (A=1, B=2, C=3, D=4); a duplicate is the same id at another position, told apart by `tag`.
 */
class HandleSongDeletedQueueLogicTest {

    private fun song(id: Long, tag: Long = 0L) = Song(
        id = id, title = "S$id", artist = "Artist", album = "Album",
        albumId = 0L, duration = 200_000L, uri = "content://media/$id/$tag",
        dateAdded = tag, trackNumber = 0, year = 2020,
    )

    private val a = 1L
    private val b = 2L
    private val c = 3L
    private val d = 4L

    private fun plan(
        ids: List<Long>,
        current: Int,
        deleted: Long,
        repeat: RepeatMode = RepeatMode.OFF,
        order: List<Int> = ids.indices.toList(),
    ): CurrentDeletionPlan {
        val library = ids.mapIndexed { i, id -> song(id, tag = i.toLong()) }
        return QueueMutation.planCurrentSongDeletion(library, order, current, deleted, repeat)
    }

    private fun CurrentDeletionPlan.cont(): CurrentDeletionPlan.Continue {
        assertTrue("expected Continue but was $this", this is CurrentDeletionPlan.Continue)
        return this as CurrentDeletionPlan.Continue
    }

    /** Ids of the resulting playback queue, plus the id at the new current occurrence. */
    private fun CurrentDeletionPlan.shape(): Pair<List<Long>, Long> {
        val p = cont()
        return p.playbackQueue.map { it.id } to p.currentSong.id
    }

    @Test fun middleCurrentRepeatOff() {
        val p = plan(listOf(a, b, c, d), current = 1, deleted = b)
        assertEquals(listOf(a, c, d) to c, p.shape())
        assertEquals(1, p.cont().currentPlaybackIndex)
    }

    @Test fun firstCurrent() {
        val p = plan(listOf(a, b, c), current = 0, deleted = a)
        assertEquals(listOf(b, c) to b, p.shape())
        assertEquals(0, p.cont().currentPlaybackIndex)
    }

    @Test fun lastCurrentRepeatOffClears() =
        assertEquals(CurrentDeletionPlan.ClearQueue, plan(listOf(b, c, a), 2, a, RepeatMode.OFF))

    @Test fun lastCurrentRepeatOneClears() =
        assertEquals(CurrentDeletionPlan.ClearQueue, plan(listOf(b, c, a), 2, a, RepeatMode.ONE))

    @Test fun lastCurrentRepeatAllWraps() {
        val p = plan(listOf(b, c, a), 2, a, RepeatMode.ALL)
        assertEquals(listOf(b, c) to b, p.shape())
        assertEquals(0, p.cont().currentPlaybackIndex)
    }

    @Test fun repeatOneAdvancesInsteadOfRepeatingDeletedSong() {
        val p = plan(listOf(b, a, c), 1, a, RepeatMode.ONE)
        assertEquals(listOf(b, c) to c, p.shape())
    }

    @Test fun repeatOneDeletionAdvancesWhileAutomaticTransitionRepeats() {
        assertEquals(2, QueueNavigator.nextIndex(3, 1, RepeatMode.ONE))
        assertEquals(1, QueueNavigator.automaticNextIndex(3, 1, RepeatMode.ONE))
    }

    @Test fun currentSongDuplicatedLaterRemovesEveryOccurrence() {
        assertEquals(listOf(b) to b, plan(listOf(a, b, a), 0, a).shape())
    }

    @Test fun currentAndLaterDuplicateAreBothRemoved() {
        val p = plan(listOf(b, a, a, c), 1, a)
        assertEquals(listOf(b, c) to c, p.shape())
        assertEquals(1, p.cont().currentPlaybackIndex)
    }

    @Test fun immediateNextOccurrenceWithDeletedIdIsSkipped() {
        assertEquals(listOf(b) to b, plan(listOf(a, a, b), 0, a).shape())
    }

    @Test fun severalConsecutiveDeletedDuplicatesAreSkipped() {
        assertEquals(listOf(b, c) to c, plan(listOf(b, a, a, a, c), 1, a, RepeatMode.ONE).shape())
    }

    @Test fun allOccurrencesDeletedClears() {
        for (mode in RepeatMode.entries) {
            assertEquals(CurrentDeletionPlan.ClearQueue, plan(listOf(a, a, a), 1, a, mode))
        }
    }

    @Test fun repeatAllWrapSkipsEarlierDeletedDuplicates() {
        assertEquals(listOf(b) to b, plan(listOf(a, b, a), 2, a, RepeatMode.ALL).shape())
    }

    @Test fun repeatOffDoesNotJumpBackIntoHistory() =
        assertEquals(CurrentDeletionPlan.ClearQueue, plan(listOf(b, c, a, a), 2, a, RepeatMode.OFF))

    // ── Shuffle / occurrence preservation ───────────────────────────────────────

    @Test fun shuffledOrderKeepsPlaybackSequenceAndPicksNextInPlaybackOrder() {
        // library [A, B, C, D]; playback order D, B, A, C; current = A at playback 2 -> next is C.
        val p = plan(listOf(a, b, c, d), 2, a, order = listOf(3, 1, 0, 2))
        assertEquals(listOf(d, b, c) to c, p.shape())
    }

    @Test fun shuffledQueueWithDuplicateDeletedSong() {
        // library [A, B, A, C]; playback order 2, 3, 0, 1 => A(src2) current, C, A(src0), B.
        val p = plan(listOf(a, b, a, c), 0, a, order = listOf(2, 3, 0, 1))
        assertEquals(listOf(c, b) to c, p.shape())
    }

    @Test fun duplicateSurvivingSongsKeepTheirOwnOccurrences() {
        // library [B, A, B, C]; current A; surviving B occurrences stay distinct (tags 0 and 2).
        val p = plan(listOf(b, a, b, c), 1, a).cont()
        assertEquals(listOf(b, b, c), p.playbackQueue.map { it.id })
        assertEquals(listOf(0L, 2L, 3L), p.playbackQueue.map { it.dateAdded })
        assertEquals(1, p.currentPlaybackIndex)
        assertEquals(2L, p.currentSong.dateAdded) // the second B occurrence, not the first
    }

    @Test fun resultingOrderIsDenseValidMappingAndQueueIsDerivedFromIt() {
        val p = plan(listOf(a, b, a, c, d, a), 1, c, order = listOf(5, 3, 0, 4, 1, 2)).cont()
        assertEquals(p.libraryQueue.size, p.playbackOrder.size)
        assertEquals(p.libraryQueue.indices.toSet(), p.playbackOrder.toSet())
        assertEquals(p.playbackOrder.map { p.libraryQueue[it] }, p.playbackQueue)
        assertTrue(p.currentPlaybackIndex in p.playbackQueue.indices)
        assertTrue(p.playbackQueue.none { it.id == c })
    }

    // ── Fail closed ─────────────────────────────────────────────────────────────

    @Test fun malformedPlaybackOrderFailsClosed() {
        assertEquals(CurrentDeletionPlan.NoOp, plan(listOf(a, b, c), 0, a, order = listOf(0, 1)))
        assertEquals(CurrentDeletionPlan.NoOp, plan(listOf(a, b, c), 0, a, order = listOf(0, 0, 1)))
        assertEquals(CurrentDeletionPlan.NoOp, plan(listOf(a, b, c), 0, a, order = listOf(0, 1, 5)))
    }

    @Test fun invalidCurrentIndexFailsClosed() {
        assertEquals(CurrentDeletionPlan.NoOp, plan(listOf(a, b), -1, a))
        assertEquals(CurrentDeletionPlan.NoOp, plan(listOf(a, b), 2, a))
    }

    @Test fun currentOccurrenceNotMatchingDeletedIdFailsClosed() =
        assertEquals(CurrentDeletionPlan.NoOp, plan(listOf(a, b, c), 1, a))
}
