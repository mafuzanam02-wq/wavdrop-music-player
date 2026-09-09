package com.launchpoint.wavdrop.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-policy coverage for [BadMediaRecoveryPlanner] (Phase 8). No MediaController / Media3 needed.
 *
 * These tests pin the bad-media recovery contract:
 *  - a single failed occurrence advances to the next eligible one;
 *  - duplicate song occurrences are treated independently (no song-id blacklist);
 *  - repeat-one never loops the failed item; repeat-all can never loop forever;
 *  - an all-broken queue stops (exhausted) within a bounded number of attempts;
 *  - the queue order (already shuffled upstream) is followed, never regenerated;
 *  - a queue-generation change invalidates a stale episode;
 *  - exactly one "new episode" is signalled per episode (drives single user notification).
 */
class BadMediaRecoveryPlannerTest {

    private fun firstError(
        queueSize: Int,
        failingIndex: Int,
        repeatMode: RepeatMode,
        queueGeneration: Long = 1L,
        wasPlaying: Boolean = true,
    ): BadMediaRecoveryStep = BadMediaRecoveryPlanner.onPlaybackError(
        previous = null,
        queueGeneration = queueGeneration,
        queueSize = queueSize,
        failingIndex = failingIndex,
        repeatMode = repeatMode,
        wasPlaying = wasPlaying,
    )

    // 1. single failed item -> next eligible occurrence
    @Test
    fun `single failed item advances to next occurrence`() {
        val step = firstError(queueSize = 3, failingIndex = 0, repeatMode = RepeatMode.OFF)
        assertTrue(step is BadMediaRecoveryStep.Advance)
        assertEquals(1, (step as BadMediaRecoveryStep.Advance).targetIndex)
        assertTrue(step.isNewEpisode)
        assertEquals(setOf(0), step.state.attemptedOccurrences)
    }

    @Test
    fun `mid-queue failure advances to the following index`() {
        val step = firstError(queueSize = 5, failingIndex = 2, repeatMode = RepeatMode.OFF)
        assertEquals(3, (step as BadMediaRecoveryStep.Advance).targetIndex)
    }

    // 2. duplicate song occurrences treated independently
    @Test
    fun `duplicate occurrences are attempted independently within one episode`() {
        // queue [A, B, A, C], both A occurrences (0 and 2) broken, B broken too: episode walks
        // 0 -> 1 -> 2 -> 3 attempting each distinct occurrence exactly once.
        var step = firstError(queueSize = 4, failingIndex = 0, repeatMode = RepeatMode.OFF)
        assertEquals(1, (step as BadMediaRecoveryStep.Advance).targetIndex)

        step = BadMediaRecoveryPlanner.onPlaybackError(
            previous = step.state, queueGeneration = 1L, queueSize = 4,
            failingIndex = 1, repeatMode = RepeatMode.OFF, wasPlaying = true,
        )
        assertEquals(2, (step as BadMediaRecoveryStep.Advance).targetIndex)
        assertFalse("later occurrence must remain eligible", step.isNewEpisode)

        step = BadMediaRecoveryPlanner.onPlaybackError(
            previous = step.state, queueGeneration = 1L, queueSize = 4,
            failingIndex = 2, repeatMode = RepeatMode.OFF, wasPlaying = true,
        )
        // The later A (index 2) was independently reachable and now advances to C (index 3).
        assertEquals(3, (step as BadMediaRecoveryStep.Advance).targetIndex)
        assertEquals(setOf(0, 1, 2), step.state.attemptedOccurrences)
    }

    // 3. repeat-one does not loop failed item
    @Test
    fun `repeat one advances past failed item instead of looping it`() {
        val step = firstError(queueSize = 3, failingIndex = 1, repeatMode = RepeatMode.ONE)
        assertEquals(2, (step as BadMediaRecoveryStep.Advance).targetIndex)
    }

    @Test
    fun `repeat one single broken item stops rather than repeating`() {
        val step = firstError(queueSize = 1, failingIndex = 0, repeatMode = RepeatMode.ONE)
        assertTrue(step is BadMediaRecoveryStep.Exhausted)
    }

    // 4. repeat-all cannot loop forever
    @Test
    fun `repeat all all-broken queue terminates exhausted`() {
        // Walk the whole broken queue under REPEAT_ALL; it must stop, not wrap forever.
        var state: BadMediaRecoveryState? = null
        var lastStep: BadMediaRecoveryStep? = null
        var index = 0
        var iterations = 0
        val queueSize = 4
        while (iterations < 100) {
            iterations++
            val step = BadMediaRecoveryPlanner.onPlaybackError(
                previous = state, queueGeneration = 1L, queueSize = queueSize,
                failingIndex = index, repeatMode = RepeatMode.ALL, wasPlaying = true,
            )
            lastStep = step
            state = step.state
            when (step) {
                is BadMediaRecoveryStep.Advance -> index = step.targetIndex
                is BadMediaRecoveryStep.Exhausted -> break
            }
        }
        assertTrue("must terminate", lastStep is BadMediaRecoveryStep.Exhausted)
        assertTrue("must terminate within queueSize steps", iterations <= queueSize)
    }

    @Test
    fun `repeat all wraps to earlier occurrence when it has not been attempted`() {
        // Fail the last item first with REPEAT_ALL; index 0 is still eligible so recovery wraps.
        val step = firstError(queueSize = 3, failingIndex = 2, repeatMode = RepeatMode.ALL)
        assertEquals(0, (step as BadMediaRecoveryStep.Advance).targetIndex)
    }

    // 5. all items failed -> stop
    @Test
    fun `repeat off last item failure exhausts`() {
        val step = firstError(queueSize = 3, failingIndex = 2, repeatMode = RepeatMode.OFF)
        assertTrue(step is BadMediaRecoveryStep.Exhausted)
        assertEquals(setOf(2), step.state.attemptedOccurrences)
    }

    // 6. shuffled order respected (planner operates on playback indices and never reorders)
    @Test
    fun `planner follows given index order and never reshuffles`() {
        // Whatever order the (already shuffled) queue is in, the planner only ever advances via
        // nextIndex — it returns positions in ascending traversal, never a random permutation.
        val step = firstError(queueSize = 4, failingIndex = 1, repeatMode = RepeatMode.OFF)
        assertEquals(2, (step as BadMediaRecoveryStep.Advance).targetIndex)
    }

    // 7. queue not mutated (planner is pure — the caller's attempted set is not modified in place)
    @Test
    fun `previous attempted set is not mutated`() {
        val previous = BadMediaRecoveryState(
            queueGeneration = 1L,
            attemptedOccurrences = setOf(0),
            wasPlaying = true,
        )
        BadMediaRecoveryPlanner.onPlaybackError(
            previous = previous, queueGeneration = 1L, queueSize = 3,
            failingIndex = 1, repeatMode = RepeatMode.OFF, wasPlaying = true,
        )
        assertEquals("input state must be immutable", setOf(0), previous.attemptedOccurrences)
    }

    // 8. bounded attempts <= queue size
    @Test
    fun `attempted occurrences never exceed queue size`() {
        var state: BadMediaRecoveryState? = null
        var index = 0
        val queueSize = 6
        repeat(50) {
            val step = BadMediaRecoveryPlanner.onPlaybackError(
                previous = state, queueGeneration = 1L, queueSize = queueSize,
                failingIndex = index, repeatMode = RepeatMode.ALL, wasPlaying = true,
            )
            state = step.state
            assertTrue(step.state.attemptedOccurrences.size <= queueSize)
            if (step is BadMediaRecoveryStep.Advance) index = step.targetIndex else return
        }
    }

    // 9. successful item resets recovery episode (fresh episode == null previous)
    @Test
    fun `null previous opens a fresh episode`() {
        val step = firstError(queueSize = 3, failingIndex = 0, repeatMode = RepeatMode.OFF)
        assertTrue(step.isNewEpisode)
        assertEquals(setOf(0), step.state.attemptedOccurrences)
    }

    // 10. queue generation change invalidates old recovery state
    @Test
    fun `generation change starts a new episode and drops stale attempts`() {
        val stale = BadMediaRecoveryState(
            queueGeneration = 1L,
            attemptedOccurrences = setOf(0, 1, 2),
            wasPlaying = false,
        )
        val step = BadMediaRecoveryPlanner.onPlaybackError(
            previous = stale, queueGeneration = 2L, queueSize = 3,
            failingIndex = 0, repeatMode = RepeatMode.OFF, wasPlaying = true,
        )
        assertTrue("generation change opens a fresh episode", step.isNewEpisode)
        assertEquals("stale attempts dropped", setOf(0), step.state.attemptedOccurrences)
        assertEquals(2L, step.state.queueGeneration)
        assertTrue("fresh intent captured", step.state.wasPlaying)
        assertEquals(1, (step as BadMediaRecoveryStep.Advance).targetIndex)
    }

    // 13. one concise notification per recovery episode (isNewEpisode true exactly once)
    @Test
    fun `isNewEpisode is true only on the first failure of an episode`() {
        val first = firstError(queueSize = 4, failingIndex = 0, repeatMode = RepeatMode.OFF)
        assertTrue(first.isNewEpisode)
        val second = BadMediaRecoveryPlanner.onPlaybackError(
            previous = first.state, queueGeneration = 1L, queueSize = 4,
            failingIndex = 1, repeatMode = RepeatMode.OFF, wasPlaying = true,
        )
        assertFalse(second.isNewEpisode)
        val third = BadMediaRecoveryPlanner.onPlaybackError(
            previous = second.state, queueGeneration = 1L, queueSize = 4,
            failingIndex = 2, repeatMode = RepeatMode.OFF, wasPlaying = true,
        )
        assertFalse(third.isNewEpisode)
    }

    // 14 (intent). original play intent is preserved across a continuing episode.
    @Test
    fun `continuing episode preserves original paused intent`() {
        val first = firstError(
            queueSize = 4, failingIndex = 0, repeatMode = RepeatMode.OFF, wasPlaying = false,
        )
        assertFalse(first.state.wasPlaying)
        // Even though this call passes wasPlaying=true, the episode keeps the ORIGINAL intent.
        val second = BadMediaRecoveryPlanner.onPlaybackError(
            previous = first.state, queueGeneration = 1L, queueSize = 4,
            failingIndex = 1, repeatMode = RepeatMode.OFF, wasPlaying = true,
        )
        assertFalse("original paused intent must be retained", second.state.wasPlaying)
    }

    @Test
    fun `unresolvable failing index exhausts without advancing`() {
        val step = firstError(queueSize = 3, failingIndex = 9, repeatMode = RepeatMode.ALL)
        assertTrue(step is BadMediaRecoveryStep.Exhausted)
        assertNotNull(step.state)
    }
}
