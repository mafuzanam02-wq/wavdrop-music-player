package com.launchpoint.wavdrop.playback

import com.launchpoint.wavdrop.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P5-D classification rules, pure: the same planner serves every queue mutation, so each rule is checked on the id sequences it sees.
 */
class PhysicalWindowMutationPlannerTest {

    private val planner = WindowMutationPlanner()

    private fun song(id: Long) = RealMedia3QueueRig.song(id)
    private fun queue(n: Int) = List(n) { song(it + 1L) }

    /** A verified window of [queue] around [current] (history 20, ahead 79 unless the ends clamp). */
    private fun windowAround(queue: List<Song>, current: Int, repeat: RepeatMode = RepeatMode.OFF): Pair<InstalledWindow, Int> {
        val w = PhysicalWindowPolicy.DEFAULT.initialWindow(queue.size, current, repeat, 5L, 1L)!!
        val ids = LongArray(w.length) { queue[w.physicalToLogical(it)!!].id }
        val physical = w.logicalToPhysical(current)!!
        return InstalledWindow(w, ids, physical, 0L) to physical
    }

    private fun plan(
        base: InstalledWindow, physical: Int, newCurrent: Int?, newQueue: List<Song>,
        continuation: Int? = null, repeat: RepeatMode = RepeatMode.OFF,
    ) = planner.plan(base, physical, newCurrent, continuation, newQueue, repeat, logicalGeneration = 6L, windowGeneration = 2L)

    @Test fun anEditEntirelyOutsideTheWindowIsLogicalOnlyAndOnlyShiftsPositions() {
        val q = queue(25_000)
        val (base, p) = windowAround(q, 12_500)
        // Append far away.
        val appended = q + List(40) { song(100_000L + it) }
        val a = plan(base, p, 12_500, appended)
        assertTrue(a is MutationEffect.LogicalOnly)
        // Remove an earlier entry far behind the window: the current logical index drops by one, the contents do not change.
        val removedEarlier = q.toMutableList().also { it.removeAt(100) }
        val b = plan(base, p, 12_499, removedEarlier) as MutationEffect.LogicalOnly
        assertEquals(12_480 - 1, b.window.start)
        assertEquals(base.installedCount, b.window.length)
        assertEquals(6L, b.window.logicalGeneration)
        assertEquals(2L, b.window.windowGeneration)
        assertEquals(12_499, b.window.physicalToLogical(p))
    }




    private fun MutationEffect.BoundedEdit.removed() = ops.filterIsInstance<WindowOp.Remove>().sumOf { it.toPhysicalExclusive - it.fromPhysical }
    private fun MutationEffect.BoundedEdit.inserted() = ops.filterIsInstance<WindowOp.Insert>().sumOf { it.count }

    @Test fun anInsertionAfterTheCurrentIsOneInsertedEntryAndOneTrimmedTail() {
        val q = queue(25_000)
        val (base, p) = windowAround(q, 500)
        val withNext = q.toMutableList().also { it.add(501, song(90_001L)) }
        val e = plan(base, p, 500, withNext) as MutationEffect.BoundedEdit
        assertEquals("one entry inserted directly after the current item", listOf(WindowOp.Insert(p + 1, 1)), e.ops.filterIsInstance<WindowOp.Insert>())
        assertEquals("and the entry that no longer fits is dropped from the far end", 1, e.removed())
        assertEquals(90_001L, e.ids[p + 1])
        assertEquals(p, e.expectedCurrent)
        assertEquals(base.installedCount, e.window.length)
        // Removals come first, so the playlist never exceeds the larger of the old and new window.
        assertTrue(e.ops.indexOfFirst { it is WindowOp.Insert } > e.ops.indexOfLast { it is WindowOp.Remove })
    }

    @Test fun aHugeBatchAfterTheCurrentStaysBoundedByTheWindow() {
        val q = queue(25_000)
        val (base, p) = windowAround(q, 500)
        val batch = List(5_000) { song(200_000L + it) }
        val withBatch = q.subList(0, 501) + batch + q.subList(501, q.size)
        val e = plan(base, p, 500, withBatch) as MutationEffect.BoundedEdit
        assertTrue("inserted ${e.inserted()} must fit the window, not the batch", e.inserted() <= PhysicalWindowPolicy.W_MAX - 1)
        assertTrue(e.ops.all { it !is WindowOp.Insert || it.count <= PhysicalWindowPolicy.W_MAX - 1 })
        assertEquals(200_000L, e.ids[p + 1])
        assertEquals(base.installedCount, e.window.length)
    }

    @Test fun anEarlierRemovalInsideTheHistorySpanTouchesOnlyHistory() {
        val q = queue(25_000)
        val (base, p) = windowAround(q, 500)
        val removed = q.toMutableList().also { it.removeAt(497) }
        val e = plan(base, p, 499, removed) as MutationEffect.BoundedEdit
        assertEquals(1, e.removed())
        assertEquals(1, e.inserted())
        assertTrue("every operation is before the current item", e.ops.all { (it as? WindowOp.Remove)?.let { r -> r.toPhysicalExclusive <= p } ?: ((it as WindowOp.Insert).atPhysical < e.expectedCurrent) })
        assertEquals("the current occurrence is now one earlier in the logical queue", 499, e.window.physicalToLogical(e.expectedCurrent))
        assertEquals(q[500].id, e.ids[e.expectedCurrent])
    }
    @Test fun aRemovedCurrentOccurrenceIsAReanchorOnTheContinuation() {
        val q = queue(25_000)
        val (base, p) = windowAround(q, 500)
        val removed = q.toMutableList().also { it.removeAt(500) }
        val e = plan(base, p, newCurrent = null, newQueue = removed, continuation = 500)
        assertEquals(MutationEffect.Reanchor(500, "current_occurrence_removed"), e)
    }

    @Test fun noCurrentAndNoContinuationFailsClosed() {
        val q = queue(25_000)
        val (base, p) = windowAround(q, 500)
        assertTrue(plan(base, p, null, q) is MutationEffect.Recover)
        assertTrue(plan(base, p, 25_000, q) is MutationEffect.Recover)
        assertTrue(plan(base, p, 5, emptyList()) is MutationEffect.Recover)
    }

    @Test fun duplicatesAreClassifiedByPositionNotById() {
        // A B A C A repeated; the current is a specific A.
        val q = List(2_000) { song(longArrayOf(1, 2, 1, 3, 1)[it % 5]) }
        val (base, p) = windowAround(q, 1_000) // pattern index 0 -> A
        // Removing the EARLIER A at 996 (same song id as the current) shifts the current logical index by one, nothing else.
        val removed = q.toMutableList().also { it.removeAt(996) }
        val e = plan(base, p, 999, removed) as MutationEffect.BoundedEdit
        assertEquals(999, e.window.physicalToLogical(e.expectedCurrent))
        assertEquals(1L, e.ids[e.expectedCurrent])
    }

    @Test fun aSwapOfTwoEntriesOutsideTheWindowIsLogicalOnly() {
        val q = queue(25_000)
        val (base, p) = windowAround(q, 500)
        val swapped = q.toMutableList().also { val t = it[9_000]; it[9_000] = it[9_001]; it[9_001] = t }
        assertTrue(plan(base, p, 500, swapped) is MutationEffect.LogicalOnly)
    }

    @Test fun aSwapInsideTheAheadSpanIsABoundedEdit() {
        val q = queue(25_000)
        val (base, p) = windowAround(q, 500)
        val swapped = q.toMutableList().also { val t = it[510]; it[510] = it[511]; it[511] = t }
        val e = plan(base, p, 500, swapped) as MutationEffect.BoundedEdit
        assertEquals(1, e.removed())
        assertEquals(1, e.inserted())
    }

    @Test fun repeatAllAppendAtTheEndPutsNewEntriesBeforeTheWrappedOnes() {
        val q = queue(25_000)
        val (base, p) = windowAround(q, 24_980, RepeatMode.ALL) // ring: ..24999, 0, 1, ...
        assertTrue(base.window.wraps)
        val appended = q + List(5) { song(300_000L + it) }
        val e = plan(base, p, 24_980, appended, repeat = RepeatMode.ALL) as MutationEffect.BoundedEdit
        val ids = e.ids.toList()
        val lastOld = ids.indexOf(25_000L)
        assertEquals("the appended entries directly follow the last old entry", 300_000L, ids[lastOld + 1])
        assertTrue("and never appear before the current item", ids.subList(0, p).none { it >= 300_000L })
        assertEquals(24_980, e.window.physicalToLogical(e.expectedCurrent))
    }

    @Test fun theMapAcrossAShuffleKeepsTheCurrentOccurrenceWhileTheRestIsReplacedAroundIt() {
        val q = queue(25_000)
        val (base, p) = windowAround(q, 12_500)
        val shuffled = q.shuffled(java.util.Random(11)).toMutableList()
        // Move the current song to where the (hypothetical) shuffle puts it: index 700.
        shuffled.remove(q[12_500]); shuffled.add(700, q[12_500])
        val e = plan(base, p, 700, shuffled) as MutationEffect.BoundedEdit
        assertEquals(q[12_500].id, e.ids[e.expectedCurrent])
        assertEquals(700, e.window.physicalToLogical(e.expectedCurrent))
        assertTrue(e.ops.all { (it is WindowOp.Insert && it.count <= PhysicalWindowPolicy.W_MAX - 1) || (it is WindowOp.Remove && it.toPhysicalExclusive - it.fromPhysical <= PhysicalWindowPolicy.W_MAX - 1) })
    }

    @Test fun aQueueThatShrinksBelowTheWindowClampsTheWindowToTheQueue() {
        val q = queue(25_000)
        val (base, p) = windowAround(q, 12_500)
        val tiny = q.subList(12_500, 12_503) // clearEarlier + clearUpNext style: only 3 entries survive
        val e = plan(base, p, 0, tiny)
        e as MutationEffect.BoundedEdit
        assertEquals(3, e.window.length)
        assertEquals(0, e.expectedCurrent)
        assertFalse(e.window.wraps)
    }
}
