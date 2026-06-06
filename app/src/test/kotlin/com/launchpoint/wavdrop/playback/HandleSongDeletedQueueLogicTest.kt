package com.launchpoint.wavdrop.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Verifies the queue-navigation logic that drives handleCurrentSongDeleted():
 *   1. QueueNavigator.nextIndex determines whether to advance or stop.
 *   2. The resulting current-index after "advance then remove" is correct.
 *
 * These are pure-function tests; no ExoPlayer or Android infrastructure is needed.
 */
class HandleSongDeletedQueueLogicTest {

    // ── Next-index determination (drives advance-or-stop decision) ────────────

    @Test
    fun `mid-queue delete has a next item with repeat off`() {
        val next = QueueNavigator.nextIndex(queueSize = 5, currentIndex = 2, repeatMode = RepeatMode.OFF)
        assertEquals(3, next)
    }

    @Test
    fun `first-item delete has a next item with repeat off`() {
        val next = QueueNavigator.nextIndex(queueSize = 5, currentIndex = 0, repeatMode = RepeatMode.OFF)
        assertEquals(1, next)
    }

    @Test
    fun `last-item delete returns null with repeat off — queue clears`() {
        val next = QueueNavigator.nextIndex(queueSize = 5, currentIndex = 4, repeatMode = RepeatMode.OFF)
        assertNull(next)
    }

    @Test
    fun `last-item delete wraps to index 0 with repeat all`() {
        val next = QueueNavigator.nextIndex(queueSize = 5, currentIndex = 4, repeatMode = RepeatMode.ALL)
        assertEquals(0, next)
    }

    @Test
    fun `repeat one delete still advances — deleted song is not looped`() {
        // nextIndex (not automaticNextIndex) is used so RepeatMode.ONE does not
        // cause the deleted item to be re-queued as the next item.
        val next = QueueNavigator.nextIndex(queueSize = 5, currentIndex = 2, repeatMode = RepeatMode.ONE)
        assertEquals(3, next)
    }

    @Test
    fun `repeat one delete at last item returns null — queue clears`() {
        val next = QueueNavigator.nextIndex(queueSize = 5, currentIndex = 4, repeatMode = RepeatMode.ONE)
        assertNull(next)
    }

    @Test
    fun `single-song queue always clears regardless of repeat mode`() {
        for (mode in listOf(RepeatMode.OFF, RepeatMode.ONE, RepeatMode.ALL)) {
            assertNull(
                "Expected null for $mode (single-song queue cannot advance after deletion)",
                QueueNavigator.nextIndex(queueSize = 1, currentIndex = 0, repeatMode = mode),
            )
        }
    }

    // ── Post-advance current-index after removeFromQueue ─────────────────────
    //
    // After seekToPlaybackIndex(nextIdx) the controller's currentIndex is nextIdx.
    // removeFromQueue(oldCurrentIdx) then applies:
    //   if (oldCurrentIdx < nextIdx) newIdx = nextIdx - 1
    //   else                         newIdx = nextIdx
    //
    // This matches the existing removeFromQueue index-shift formula.

    @Test
    fun `mid-queue delete index decrements because old slot was before new current`() {
        // playbackQueue: [A, B, C, D, E], delete C at 2, next D at 3.
        // After remove C: [A, B, D, E], current D now sits at 2 (was 3, adjusted -1).
        val newIdx = computePostRemoveCurrentIndex(oldCurrentIdx = 2, nextIdx = 3, newQueueSize = 4)
        assertEquals(2, newIdx)
    }

    @Test
    fun `first-item delete index decrements to 0`() {
        // [A, B, C], delete A at 0, next B at 1.
        // After remove A: [B, C], current B is at 0 (was 1, adjusted -1).
        val newIdx = computePostRemoveCurrentIndex(oldCurrentIdx = 0, nextIdx = 1, newQueueSize = 2)
        assertEquals(0, newIdx)
    }

    @Test
    fun `repeat all wrap old last-item is behind wrapped index so index stays unchanged`() {
        // [A, B, C], delete C at 2, next wraps to A at 0 (Repeat.ALL).
        // oldCurrentIdx (2) >= nextIdx (0) — no decrement: newIdx = 0.
        val newIdx = computePostRemoveCurrentIndex(oldCurrentIdx = 2, nextIdx = 0, newQueueSize = 2)
        assertEquals(0, newIdx)
    }

    @Test
    fun `penultimate item delete with repeat all index decrements`() {
        // [A, B, C, D], delete C at 2, next D at 3.
        // After remove C: [A, B, D], current D at 2 (was 3, adjusted -1).
        val newIdx = computePostRemoveCurrentIndex(oldCurrentIdx = 2, nextIdx = 3, newQueueSize = 3)
        assertEquals(2, newIdx)
    }

    // ── Distinction from automaticNextIndex ───────────────────────────────────

    @Test
    fun `nextIndex and automaticNextIndex differ for repeat one`() {
        // Deletion uses nextIndex so the deleted song is skipped.
        // Normal auto-advance uses automaticNextIndex so Repeat.ONE replays the same slot.
        val forDeletion   = QueueNavigator.nextIndex(queueSize = 3, currentIndex = 1, repeatMode = RepeatMode.ONE)
        val forAutoAdvance = QueueNavigator.automaticNextIndex(queueSize = 3, currentIndex = 1, repeatMode = RepeatMode.ONE)
        assertEquals("deletion must advance", 2, forDeletion)
        assertEquals("auto-advance must stay", 1, forAutoAdvance)
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    /**
     * Mirrors the index-adjustment logic in removeFromQueue:
     *   if (playbackIndex < currentPlaybackIndex) currentPlaybackIndex - 1 else currentPlaybackIndex
     * where playbackIndex == oldCurrentIdx and currentPlaybackIndex == nextIdx (post-seek).
     */
    private fun computePostRemoveCurrentIndex(
        oldCurrentIdx: Int,
        nextIdx: Int,
        newQueueSize: Int,
    ): Int = if (oldCurrentIdx < nextIdx) {
        nextIdx - 1
    } else {
        nextIdx
    }.coerceIn(0, maxOf(0, newQueueSize - 1))
}
