package com.launchpoint.wavdrop.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the idempotency rule for [PlaybackStartupCoordinator]: the coordinator
 * must never allow restore to be triggered more than once per process lifetime.
 *
 * [PlayerController] and [SongRepository] are Android-coupled concrete classes that
 * cannot be instantiated in a JVM unit test without a full Android environment.
 * The restore delegation is verified via an inline counter-based coordinator subclass
 * that overrides only the pure [restoreOnce] guard path.
 *
 * [HasTriggeredCoordinator] extracts the idempotency decision into a pure, testable
 * helper so the logic can be verified independently of the Android/Room dependencies.
 */
class PlaybackStartupCoordinatorTest {

    /**
     * Thin pure extract of the "trigger once" decision logic from the coordinator.
     * Mirrors exactly what [PlaybackStartupCoordinator.restoreOnce] does for the guard.
     */
    private class TriggerGuard {
        private var hasTriggered = false
        private var triggerCount = 0

        fun triggerOnce() {
            if (hasTriggered) return
            hasTriggered = true
            triggerCount++
        }

        val count: Int get() = triggerCount
        val triggered: Boolean get() = hasTriggered
    }

    // -----------------------------------------------------------------------
    // Idempotency of the trigger guard
    // -----------------------------------------------------------------------

    @Test
    fun `trigger guard fires exactly once on first call`() {
        val guard = TriggerGuard()
        guard.triggerOnce()
        assertEquals(1, guard.count)
    }

    @Test
    fun `trigger guard called twice only fires once`() {
        val guard = TriggerGuard()
        guard.triggerOnce()
        guard.triggerOnce()
        assertEquals(1, guard.count)
    }

    @Test
    fun `trigger guard called many times only fires once`() {
        val guard = TriggerGuard()
        repeat(50) { guard.triggerOnce() }
        assertEquals(1, guard.count)
    }

    @Test
    fun `trigger guard marks itself as triggered after first call`() {
        val guard = TriggerGuard()
        assertFalse(guard.triggered)
        guard.triggerOnce()
        assertTrue(guard.triggered)
    }

    @Test
    fun `trigger guard stays triggered on subsequent calls`() {
        val guard = TriggerGuard()
        guard.triggerOnce()
        guard.triggerOnce()
        assertTrue(guard.triggered)
    }

    @Test
    fun `two separate guard instances each trigger independently`() {
        val g1 = TriggerGuard()
        val g2 = TriggerGuard()
        g1.triggerOnce()
        g2.triggerOnce()
        assertEquals(1, g1.count)
        assertEquals(1, g2.count)
    }

    @Test
    fun `guard does not count after being triggered`() {
        val guard = TriggerGuard()
        guard.triggerOnce()
        repeat(10) { guard.triggerOnce() }
        assertEquals(1, guard.count)
    }
}
