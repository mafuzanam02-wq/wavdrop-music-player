package com.launchpoint.wavdrop.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [LoopBoundaryDetector.isLoopBoundary].
 *
 * These cover all meaningful combinations of (prevPos, curPos, repeatMode) so the
 * pure-function logic can be validated without any Android/MediaController infrastructure.
 */
class LoopBoundaryDetectorTest {

    private val near  = LoopBoundaryDetector.LOOP_NEAR_START_MS
    private val minP  = LoopBoundaryDetector.LOOP_MIN_PREV_POS_MS

    // ── true cases ────────────────────────────────────────────────────────────

    @Test
    fun `near-end to near-start while REPEAT_ONE is a loop boundary`() {
        assertTrue(LoopBoundaryDetector.isLoopBoundary(180_000L, 300L, RepeatMode.ONE))
    }

    @Test
    fun `mid-song to near-start while REPEAT_ONE is a loop boundary`() {
        assertTrue(LoopBoundaryDetector.isLoopBoundary(30_000L, 500L, RepeatMode.ONE))
    }

    @Test
    fun `prevPos exactly at minimum threshold is detected`() {
        assertTrue(LoopBoundaryDetector.isLoopBoundary(minP, 0L, RepeatMode.ONE))
    }

    @Test
    fun `currentPos one below near-start threshold is detected`() {
        assertTrue(LoopBoundaryDetector.isLoopBoundary(30_000L, near - 1, RepeatMode.ONE))
    }

    @Test
    fun `five-second song loops in REPEAT_ONE`() {
        // prevPos=4 500 ms (90 % of 5 s), curPos=300 ms after wrap
        assertTrue(LoopBoundaryDetector.isLoopBoundary(4_500L, 300L, RepeatMode.ONE))
    }

    // ── false cases: wrong mode ───────────────────────────────────────────────

    @Test
    fun `REPEAT_ALL is never a loop boundary`() {
        assertFalse(LoopBoundaryDetector.isLoopBoundary(180_000L, 300L, RepeatMode.ALL))
    }

    @Test
    fun `REPEAT_OFF is never a loop boundary`() {
        assertFalse(LoopBoundaryDetector.isLoopBoundary(180_000L, 300L, RepeatMode.OFF))
    }

    // ── false cases: prevPos too small ────────────────────────────────────────

    @Test
    fun `prevPos one below minimum is not detected`() {
        assertFalse(LoopBoundaryDetector.isLoopBoundary(minP - 1, 300L, RepeatMode.ONE))
    }

    @Test
    fun `uninitialized prevPos (-1) is not detected`() {
        assertFalse(LoopBoundaryDetector.isLoopBoundary(-1L, 300L, RepeatMode.ONE))
    }

    @Test
    fun `zero prevPos is not detected`() {
        assertFalse(LoopBoundaryDetector.isLoopBoundary(0L, 300L, RepeatMode.ONE))
    }

    @Test
    fun `fresh-start prevPos of 1 s is not detected`() {
        assertFalse(LoopBoundaryDetector.isLoopBoundary(1_000L, 300L, RepeatMode.ONE))
    }

    // ── false cases: currentPos not near start ────────────────────────────────

    @Test
    fun `currentPos exactly at near-start boundary is not detected`() {
        assertFalse(LoopBoundaryDetector.isLoopBoundary(30_000L, near, RepeatMode.ONE))
    }

    @Test
    fun `currentPos at 3 s is not near start`() {
        assertFalse(LoopBoundaryDetector.isLoopBoundary(30_000L, 3_000L, RepeatMode.ONE))
    }

    @Test
    fun `user backward seek to 20 s is not a loop`() {
        assertFalse(LoopBoundaryDetector.isLoopBoundary(60_000L, 20_000L, RepeatMode.ONE))
    }

    @Test
    fun `forward playback progress is never a loop`() {
        assertFalse(LoopBoundaryDetector.isLoopBoundary(5_000L, 5_500L, RepeatMode.ONE))
    }

    @Test
    fun `same position reported twice is not a loop`() {
        assertFalse(LoopBoundaryDetector.isLoopBoundary(30_000L, 30_000L, RepeatMode.ONE))
    }
}
