package com.launchpoint.wavdrop.playback

import com.launchpoint.wavdrop.data.model.Song
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Operation-SHAPE tests for the large-queue physical reconciler on a scripted timeline that records every Media3 mutation
 * (no wall-clock thresholds). The fake follows Media3 semantics: replacing items before the current one shifts the current
 * index, and a mutation range must never contain the current item.
 */
class PhysicalQueueReconcilerTest {

    private fun song(id: Long) = Song(
        id = id, title = "Song $id", artist = "Artist", album = "Album", albumId = 0L, duration = 180_000L,
        uri = "content://media/$id", dateAdded = 0L, trackNumber = 0, year = 2020,
    )

    private fun queueOf(size: Int) = List(size) { song(10_000L + it) }

    /** One recorded Media3 mutation: Replace(from, to, count) where count = items materialized/sent. */
    private data class Op(val from: Int, val to: Int, val count: Int)

    private class FakeTimeline(ids: List<Long>, current: Int) : PhysicalQueueTimeline {
        val ids: MutableList<Long> = ids.toMutableList()
        override var currentIndex: Int = current
        val ops = mutableListOf<Op>()
        override val itemCount: Int get() = ids.size
        override fun songIdAt(index: Int): Long? = ids.getOrNull(index)
        override fun replace(fromIndex: Int, toIndexExclusive: Int, songs: List<Song>) {
            require(fromIndex in 0..ids.size && toIndexExclusive in fromIndex..ids.size) { "bad range $fromIndex..$toIndexExclusive of ${ids.size}" }
            require(!(currentIndex in fromIndex until toIndexExclusive)) { "mutation range contains the current item" }
            ops += Op(fromIndex, toIndexExclusive, songs.size)
            repeat(toIndexExclusive - fromIndex) { ids.removeAt(fromIndex) }
            ids.addAll(fromIndex, songs.map { it.id })
            if (toIndexExclusive <= currentIndex) currentIndex += songs.size - (toIndexExclusive - fromIndex)
        }
        val writtenItems: Int get() = ops.sumOf { it.count }
        val largestOp: Int get() = ops.maxOfOrNull { maxOf(it.count, it.to - it.from) } ?: 0
    }

    private val reconciler = PhysicalQueueReconciler()
    private val chunk = PhysicalQueueReconciler.DEFAULT_CHUNK_SIZE

    private fun FakeTimeline.currentId(): Long = ids[currentIndex]

    /** Runs background steps like the controller loop until Completed (or the safety bound). Returns the step count. */
    private fun runToCompletion(timeline: FakeTimeline, queue: List<Song>, current: Int): Int {
        var cursor: ReconcileCursor? = null
        var steps = 0
        while (steps < 1_000) {
            steps++
            when (val outcome = reconciler.reconcile(timeline, queue, current, budget = chunk, cursor = cursor)) {
                QueueReconcileOutcome.Completed -> return steps
                is QueueReconcileOutcome.InProgress -> cursor = outcome.cursor
                is QueueReconcileOutcome.Unsafe -> throw AssertionError("unsafe: ${outcome.reason}")
            }
        }
        throw AssertionError("did not complete")
    }

    private val sizes = listOf(1_000, 5_000, 12_288)

    @Test
    fun `an aligned queue completes without a single Media3 mutation`() {
        for (size in sizes) {
            val queue = queueOf(size)
            val timeline = FakeTimeline(queue.map { it.id }, current = size / 2)
            val steps = runToCompletion(timeline, queue, size / 2)
            assertTrue("no mutation at size=$size", timeline.ops.isEmpty())
            assertTrue("bounded steps at size=$size: $steps", steps <= size / chunk + 3)
        }
    }

    @Test
    fun `dirty Next after a play next insertion establishes only the target window whatever the queue size`() {
        val shapes = sizes.map { size ->
            val current = size / 2
            val physical = queueOf(size)
            // The logical queue gained one song right after the current one; the physical timeline is stale.
            val logical = physical.subList(0, current + 1) + song(777_777L) + physical.subList(current + 1, size)
            val timeline = FakeTimeline(physical.map { it.id }, current)

            val outcome = reconciler.reconcile(timeline, logical, current, priorityIndices = listOf(current + 1), budget = 0)

            assertTrue(outcome is QueueReconcileOutcome.InProgress)
            outcome as QueueReconcileOutcome.InProgress
            assertTrue("target verified", current + 1 in outcome.verified)
            assertEquals(777_777L, timeline.ids[timeline.currentIndex + 1]) // the new song is physically next
            assertEquals(physical[current].id, timeline.currentId()) // current untouched
            assertTrue("every op is a small window op: ${timeline.ops}", timeline.largestOp <= 8)
            timeline.ops.size to timeline.writtenItems
        }
        // The synchronous cost is identical for 1k, 5k and 12,288: it does not scale with the queue.
        assertEquals(1, shapes.toSet().size)
    }

    @Test
    fun `dirty Previous and queue jump to a window are bounded as well`() {
        val size = 12_288
        val logical = queueOf(size)
        val stale = logical.toMutableList().also { it.removeAt(5_000) } // physical lost one item somewhere before current
        val current = 8_000 // logical; physical current index is 7_999
        val timeline = FakeTimeline(stale.map { it.id }, current - 1)

        val previous = reconciler.reconcile(timeline, logical, current, priorityIndices = listOf(current - 1), budget = 0)
        assertTrue(previous is QueueReconcileOutcome.InProgress)
        assertTrue(timeline.largestOp <= 8)
        assertTrue(timeline.ids[timeline.currentIndex - 1] == logical[current - 1].id)

        val jumpTarget = current + 3
        val jump = reconciler.reconcile(timeline, logical, current, priorityIndices = listOf(jumpTarget), budget = 0)
        assertTrue((jump as QueueReconcileOutcome.InProgress).verified.contains(jumpTarget))
        assertTrue(timeline.largestOp <= 8)
    }

    @Test
    fun `a large shuffle ON is reconciled in bounded chunks around the untouched current item and completes`() {
        for (size in sizes) {
            val physical = queueOf(size)
            val currentPhysical = size / 2
            val rnd = Random(size)
            val currentSong = physical[currentPhysical]
            val others = physical.filterIndexed { i, _ -> i != currentPhysical }.shuffled(rnd)
            val logical = listOf(currentSong) + others // shuffle ON: current first
            val timeline = FakeTimeline(physical.map { it.id }, currentPhysical)

            // Next right after the toggle: synchronous, bounded
            val first = reconciler.reconcile(timeline, logical, 0, priorityIndices = listOf(1), budget = 0)
            assertTrue(first is QueueReconcileOutcome.InProgress)
            assertEquals(logical[1].id, timeline.ids[timeline.currentIndex + 1])
            assertTrue("window op bounded", timeline.largestOp <= 8 || timeline.ops.first().count == 0)

            runToCompletion(timeline, logical, 0)

            assertEquals(logical.map { it.id }, timeline.ids)
            assertEquals(0, timeline.currentIndex)
            assertEquals(currentSong.id, timeline.currentId())
            assertTrue("no op larger than a chunk at size=$size", timeline.ops.all { maxOf(it.count, it.to - it.from) <= maxOf(chunk, currentPhysical) })
            assertTrue("every materialized batch <= chunk", timeline.ops.all { it.count <= chunk })
        }
    }

    @Test
    fun `shuffle OFF with a large missing prefix keeps Next immediate and then fills the prefix in chunks`() {
        val size = 12_288
        val original = queueOf(size)
        val currentLogical = 9_000 // un-shuffled position of the playing song
        val rnd = Random(7)
        val currentSong = original[currentLogical]
        val shuffled = listOf(currentSong) + original.filterIndexed { i, _ -> i != currentLogical }.shuffled(rnd)
        val timeline = FakeTimeline(shuffled.map { it.id }, current = 0)

        val next = reconciler.reconcile(timeline, original, currentLogical, priorityIndices = listOf(currentLogical + 1), budget = 0)
        next as QueueReconcileOutcome.InProgress
        assertTrue(next.physicalOffset < 0) // physical prefix is shorter than the logical one: tracked, not rebuilt
        assertEquals(original[currentLogical + 1].id, timeline.ids[currentLogical + 1 + next.physicalOffset])
        assertTrue("window ops only (a tail removal materializes nothing)", timeline.ops.all { it.count <= 8 })

        runToCompletion(timeline, original, currentLogical)

        assertEquals(original.map { it.id }, timeline.ids)
        assertEquals(currentLogical, timeline.currentIndex)
        assertTrue(timeline.ops.all { it.count <= chunk })
    }

    @Test
    fun `duplicate song ids stay positional and the current occurrence is preserved`() {
        val size = 5_000
        val base = List(size) { song(1L + (it % 7)) } // every id repeats hundreds of times
        val current = 2_500
        val logical = base.toMutableList().also { it.add(current + 1, song(1L + 3)) }
        val timeline = FakeTimeline(base.map { it.id }, current)
        val currentIdentityMarker = timeline.currentIndex

        reconciler.reconcile(timeline, logical, current, priorityIndices = listOf(current + 1), budget = 0)
        assertEquals(currentIdentityMarker, timeline.currentIndex) // no shift: nothing before the current changed
        runToCompletion(timeline, logical, current)

        assertEquals(logical.map { it.id }, timeline.ids)
        assertEquals(current, timeline.currentIndex)
    }

    @Test
    fun `a physical current that does not match the logical one is unsafe and nothing is mutated`() {
        val queue = queueOf(2_000)
        val ids = queue.map { it.id }.toMutableList().also { it[1_000] = -5L }
        val timeline = FakeTimeline(ids, 1_000)

        val outcome = reconciler.reconcile(timeline, queue, 1_000, priorityIndices = listOf(1_001), budget = 0)

        assertEquals(QueueReconcileOutcome.Unsafe("physical_current_mismatch"), outcome)
        assertTrue(timeline.ops.isEmpty())
    }

    @Test
    fun `a missing physical current or an out of range logical current is unsafe`() {
        val queue = queueOf(10)
        assertTrue(reconciler.reconcile(FakeTimeline(queue.map { it.id }, 99), queue, 3) is QueueReconcileOutcome.Unsafe)
        assertTrue(reconciler.reconcile(FakeTimeline(queue.map { it.id }, 3), queue, 40) is QueueReconcileOutcome.Unsafe)
    }

    @Test
    fun `an unbounded jump gap is refused rather than rebuilt synchronously`() {
        val size = 12_288
        val logical = queueOf(size)
        val timeline = FakeTimeline(logical.subList(0, 10).map { it.id }, current = 5) // physical knows only 10 songs

        val outcome = reconciler.reconcile(timeline, logical, 5, priorityIndices = listOf(9_000), budget = 0)

        assertEquals(QueueReconcileOutcome.Unsafe("priority_gap_too_large"), outcome)
    }

    @Test
    fun `a stale cursor from another current occurrence is ignored`() {
        val queue = queueOf(2_000)
        val timeline = FakeTimeline(queue.map { it.id }, 100)
        val stale = ReconcileCursor(current = 7, suffixNext = 1_999, prefixNext = -1)

        val outcome = reconciler.reconcile(timeline, queue, 100, budget = chunk, cursor = stale)

        outcome as QueueReconcileOutcome.InProgress
        assertNotEquals(1_999, outcome.cursor.suffixNext) // restarted from the current occurrence, not resumed
        assertEquals(100, outcome.cursor.current)
    }

    @Test
    fun `repair never moves the current item and a second pass after completion is a no-op`() {
        val size = 3_000
        val logical = queueOf(size)
        val shuffledTail = logical.subList(1, size).shuffled(Random(3))
        val physical = listOf(logical[0]) + shuffledTail
        val timeline = FakeTimeline(physical.map { it.id }, 0)

        runToCompletion(timeline, logical, 0)
        val opsAfter = timeline.ops.size
        runToCompletion(timeline, logical, 0)

        assertEquals(opsAfter, timeline.ops.size)
        assertFalse(timeline.ops.any { it.from <= 0 && it.to > 0 && it.count > 0 && it.from == 0 && it.to == 1 })
        assertEquals(logical.map { it.id }, timeline.ids)
    }

    @Test
    fun `verified landing accepts only verified logical indices with an equal media id`() {
        val queue = queueOf(10)
        val verified = setOf(3, 4, 5)
        assertTrue(isVerifiedPhysicalLanding(queue, controllerIndex = 4, controllerSongId = queue[4].id, physicalOffset = 0, verifiedLogicalIndices = verified))
        assertFalse(isVerifiedPhysicalLanding(queue, 6, queue[6].id, 0, verified)) // not verified
        assertFalse(isVerifiedPhysicalLanding(queue, 4, queue[5].id, 0, verified)) // wrong song
        assertFalse(isVerifiedPhysicalLanding(queue, 4, null, 0, verified))
        // physical = logical + offset
        assertTrue(isVerifiedPhysicalLanding(queue, controllerIndex = 1, controllerSongId = queue[4].id, physicalOffset = -3, verifiedLogicalIndices = verified))
    }

    @Test
    fun `current index resolution trusts the controller only at verified indices while dirty`() {
        val queue = queueOf(20)
        fun resolve(controllerIndex: Int, stateIndex: Int, verified: Set<Int>, offset: Int = 0) = resolveCurrentPlaybackIndex(
            playbackQueue = queue,
            controllerIndex = controllerIndex,
            controllerSongId = queue[controllerIndex - offset].id,
            stateIndex = stateIndex,
            stateSongId = queue[stateIndex].id,
            playerQueueNeedsSync = true,
            verifiedLogicalIndices = verified,
            physicalIndexOffset = offset,
        )
        assertEquals(6, resolve(controllerIndex = 6, stateIndex = 5, verified = setOf(5, 6, 7))) // natural advance followed
        assertEquals(5, resolve(controllerIndex = 6, stateIndex = 5, verified = emptySet())) // untrusted: state wins
        assertEquals(6, resolve(controllerIndex = 3, stateIndex = 5, verified = setOf(5, 6), offset = -3)) // offset mapping
    }
}
