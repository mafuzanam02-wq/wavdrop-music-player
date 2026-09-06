package com.launchpoint.wavdrop.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the retryable startup-restore invariant enforced by [StartupRestoreGate] /
 * [StartupRestoreDecision].
 *
 * The old contract ("trigger exactly once per process no matter what") protected a defect: a
 * transient startup failure permanently stranded playback until the process restarted. The correct
 * contract is:
 *  - duplicate concurrent restores are suppressed,
 *  - successful / terminal outcomes are idempotent no-ops afterwards,
 *  - transient failures remain eligible for a later attempt.
 *
 * [PlayerController] and [SongRepository] are Android-coupled and cannot be instantiated in a pure
 * JVM test, so the decision logic is extracted into these pure helpers and verified directly.
 */
class PlaybackStartupCoordinatorTest {

    // -----------------------------------------------------------------------
    // Gate: begin / suppression / retry eligibility
    // -----------------------------------------------------------------------

    @Test
    fun `first request is allowed`() {
        val gate = StartupRestoreGate()
        assertTrue(gate.beginAttempt())
        assertEquals(StartupRestoreState.InFlight, gate.currentState())
    }

    @Test
    fun `second request while in flight is suppressed`() {
        val gate = StartupRestoreGate()
        assertTrue(gate.beginAttempt())
        assertFalse(gate.beginAttempt())
        assertFalse(gate.beginAttempt())
        assertEquals(StartupRestoreState.InFlight, gate.currentState())
    }

    @Test
    fun `Hydrated marks terminal`() {
        assertTerminalAfter(PlayerHydrationResult.Hydrated)
    }

    @Test
    fun `AlreadyHydrated marks terminal`() {
        assertTerminalAfter(PlayerHydrationResult.AlreadyHydrated)
    }

    @Test
    fun `NoSavedSession marks terminal`() {
        assertTerminalAfter(PlayerHydrationResult.NoSavedSession)
    }

    @Test
    fun `FilteredBySettings marks terminal`() {
        assertTerminalAfter(PlayerHydrationResult.FilteredBySettings)
    }

    @Test
    fun `NoResolvableSong marks terminal`() {
        // A saved song that is no longer in the library resolves identically on every retry.
        assertTerminalAfter(PlayerHydrationResult.NoResolvableSong)
    }

    @Test
    fun `SkippedActiveQueue marks terminal`() {
        // Active playback already exists; retrying must not fight it.
        assertTerminalAfter(PlayerHydrationResult.SkippedActiveQueue)
    }

    @Test
    fun `ControllerUnavailable resets eligibility for retry`() {
        assertRetryEligibleAfter(PlayerHydrationResult.ControllerUnavailable)
    }

    @Test
    fun `MediaSetupFailed resets eligibility for retry`() {
        assertRetryEligibleAfter(PlayerHydrationResult.MediaSetupFailed)
    }

    @Test
    fun `after transient failure next request is allowed`() {
        val gate = StartupRestoreGate()
        assertTrue(gate.beginAttempt())
        gate.completeAttempt(PlayerHydrationResult.ControllerUnavailable)
        assertEquals(StartupRestoreState.NotStarted, gate.currentState())
        assertTrue("transient failure must remain retry-eligible", gate.beginAttempt())
    }

    @Test
    fun `thrown failure before a result remains retry-eligible`() {
        val gate = StartupRestoreGate()
        assertTrue(gate.beginAttempt())
        gate.completeWithTransientFailure()
        assertEquals(StartupRestoreState.NotStarted, gate.currentState())
        assertTrue(gate.beginAttempt())
    }

    @Test
    fun `after terminal result repeated requests remain no-op`() {
        val gate = StartupRestoreGate()
        assertTrue(gate.beginAttempt())
        gate.completeAttempt(PlayerHydrationResult.Hydrated)
        assertEquals(StartupRestoreState.Terminal, gate.currentState())
        repeat(10) { assertFalse(gate.beginAttempt()) }
        assertEquals(StartupRestoreState.Terminal, gate.currentState())
    }

    @Test
    fun `every hydration result is classified exactly once`() {
        // Guards against a new PlayerHydrationResult being added without a coordination policy.
        PlayerHydrationResult.entries.forEach { result ->
            val terminal = StartupRestoreDecision.isTerminal(result)
            val expectedState = if (terminal) {
                StartupRestoreState.Terminal
            } else {
                StartupRestoreState.NotStarted
            }
            assertEquals(expectedState, StartupRestoreDecision.nextState(result))
        }
    }

    private fun assertTerminalAfter(result: PlayerHydrationResult) {
        val gate = StartupRestoreGate()
        assertTrue(gate.beginAttempt())
        gate.completeAttempt(result)
        assertEquals(StartupRestoreState.Terminal, gate.currentState())
        assertFalse(gate.beginAttempt())
        assertTrue(StartupRestoreDecision.isTerminal(result))
    }

    private fun assertRetryEligibleAfter(result: PlayerHydrationResult) {
        val gate = StartupRestoreGate()
        assertTrue(gate.beginAttempt())
        gate.completeAttempt(result)
        assertEquals(StartupRestoreState.NotStarted, gate.currentState())
        assertFalse(StartupRestoreDecision.isTerminal(result))
    }
}
