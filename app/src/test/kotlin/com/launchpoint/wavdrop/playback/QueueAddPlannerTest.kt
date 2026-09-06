package com.launchpoint.wavdrop.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifies the Phase 3 queue-preservation invariant for Add-to-Queue / Add-All-to-Queue via the
 * pure [planQueueAdd] decision.
 *
 * The defect: `addToQueue`/`addAllToQueue` used `libraryQueue.isEmpty() || currentPlaybackIndex ==
 * null`, so a transient inability to resolve the current index REPLACED the existing queue. The
 * correct semantics: an existing queue is always preserved by appending to its tail, regardless of
 * whether the current index resolves; only a genuinely empty queue starts a new one.
 *
 * These cases map 1:1 to the required matrix (A–G). Cases C and F (existing queue + UNRESOLVED
 * index) are the crux — they must resolve to append, never to a queue replacement.
 */
class QueueAddPlannerTest {

    // A. addToQueue, empty queue -> start queue with the single song.
    @Test
    fun `addToQueue empty queue starts a new queue`() {
        assertEquals(
            QueueAddPlan.StartNewQueue,
            planQueueAdd(hasExistingQueue = false, isBatchEmpty = false, currentIndexResolvable = false),
        )
    }

    // B. addToQueue, existing queue + resolvable current index -> append, preserve.
    @Test
    fun `addToQueue existing queue with resolvable index appends`() {
        assertEquals(
            QueueAddPlan.AppendPreservingQueue,
            planQueueAdd(hasExistingQueue = true, isBatchEmpty = false, currentIndexResolvable = true),
        )
    }

    // C. addToQueue, existing queue + UNRESOLVED current index -> append, MUST NOT rebuild.
    @Test
    fun `addToQueue existing queue with unresolved index appends and does not rebuild`() {
        assertEquals(
            QueueAddPlan.AppendPreservingQueue,
            planQueueAdd(hasExistingQueue = true, isBatchEmpty = false, currentIndexResolvable = false),
        )
    }

    // D. addAllToQueue, empty queue -> start supplied batch.
    @Test
    fun `addAllToQueue empty queue starts a new queue`() {
        assertEquals(
            QueueAddPlan.StartNewQueue,
            planQueueAdd(hasExistingQueue = false, isBatchEmpty = false, currentIndexResolvable = false),
        )
    }

    // E. addAllToQueue, existing queue + resolvable index -> append the whole batch.
    @Test
    fun `addAllToQueue existing queue with resolvable index appends`() {
        assertEquals(
            QueueAddPlan.AppendPreservingQueue,
            planQueueAdd(hasExistingQueue = true, isBatchEmpty = false, currentIndexResolvable = true),
        )
    }

    // F. addAllToQueue, existing queue + UNRESOLVED index -> append, MUST NOT replace.
    @Test
    fun `addAllToQueue existing queue with unresolved index appends and does not replace`() {
        assertEquals(
            QueueAddPlan.AppendPreservingQueue,
            planQueueAdd(hasExistingQueue = true, isBatchEmpty = false, currentIndexResolvable = false),
        )
    }

    // G. empty batch -> no-op (regardless of queue/index state).
    @Test
    fun `empty batch is a no-op`() {
        assertEquals(
            QueueAddPlan.NoOp,
            planQueueAdd(hasExistingQueue = true, isBatchEmpty = true, currentIndexResolvable = true),
        )
        assertEquals(
            QueueAddPlan.NoOp,
            planQueueAdd(hasExistingQueue = false, isBatchEmpty = true, currentIndexResolvable = false),
        )
    }

    /**
     * The invariant, stated directly: for a non-empty queue, resolvability of the current index
     * must never change the outcome. This is precisely what the old `||` violated.
     */
    @Test
    fun `existing queue outcome is independent of current index resolvability`() {
        val resolvable = planQueueAdd(hasExistingQueue = true, isBatchEmpty = false, currentIndexResolvable = true)
        val unresolved = planQueueAdd(hasExistingQueue = true, isBatchEmpty = false, currentIndexResolvable = false)
        assertEquals(QueueAddPlan.AppendPreservingQueue, resolvable)
        assertEquals(resolvable, unresolved)
    }
}
