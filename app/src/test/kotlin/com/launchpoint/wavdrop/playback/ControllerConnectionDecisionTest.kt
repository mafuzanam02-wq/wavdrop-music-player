package com.launchpoint.wavdrop.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the pure demand-driven controller-acquisition decisions used by [PlayerController].
 *
 * The real [androidx.media3.session.MediaController] connection is Android/Looper-coupled and the
 * project intentionally avoids Robolectric/mocking, so only the state-transition logic is unit
 * tested here. The invariant under test: a failed or lost connection returns to a retry-eligible
 * state, and a request never starts a second concurrent build.
 */
class ControllerConnectionDecisionTest {

    @Test
    fun `disconnected request starts a connection`() {
        assertEquals(
            ControllerConnectionAction.StartConnection,
            ControllerConnectionDecision.onRequest(ControllerConnectionState.Disconnected),
        )
    }

    @Test
    fun `connecting request waits and does not duplicate the build`() {
        assertEquals(
            ControllerConnectionAction.AwaitExisting,
            ControllerConnectionDecision.onRequest(ControllerConnectionState.Connecting),
        )
    }

    @Test
    fun `connected request uses the existing controller`() {
        assertEquals(
            ControllerConnectionAction.UseExisting,
            ControllerConnectionDecision.onRequest(ControllerConnectionState.Connected),
        )
    }

    @Test
    fun `connection success moves to connected`() {
        assertEquals(
            ControllerConnectionState.Connected,
            ControllerConnectionDecision.onConnected(),
        )
    }

    @Test
    fun `connection failure returns to a retry-eligible disconnected state`() {
        val failed = ControllerConnectionDecision.onConnectionFailed()
        assertEquals(ControllerConnectionState.Disconnected, failed)
        // Retry-eligible: a later request must be told to start a fresh connection.
        assertEquals(
            ControllerConnectionAction.StartConnection,
            ControllerConnectionDecision.onRequest(failed),
        )
    }

    @Test
    fun `disconnect returns to a retry-eligible disconnected state`() {
        val disconnected = ControllerConnectionDecision.onDisconnected()
        assertEquals(ControllerConnectionState.Disconnected, disconnected)
        assertEquals(
            ControllerConnectionAction.StartConnection,
            ControllerConnectionDecision.onRequest(disconnected),
        )
    }

    // -----------------------------------------------------------------------
    // Stale-completion / attempt-ownership (generation identity)
    // -----------------------------------------------------------------------

    /**
     * Mirrors exactly how [PlayerController] drives the connection generation: an attempt captures
     * a generation when it starts, release() (or a superseding start) bumps the generation, and a
     * completion is authoritative only while its generation is still current.
     */
    private class AttemptModel {
        var currentGeneration = 0L
            private set
        var state = ControllerConnectionState.Disconnected
            private set

        /** startControllerConnection(): state -> Connecting, ++generation. */
        fun beginAttempt(): Long {
            state = ControllerConnectionState.Connecting
            return ++currentGeneration
        }

        /** release(): invalidate in-flight attempts and return to retry-eligible Disconnected. */
        fun invalidateForRelease() {
            ++currentGeneration
            state = ControllerConnectionState.Disconnected
        }

        fun outcomeFor(attemptGeneration: Long): ControllerAttemptOutcome =
            ControllerAttemptOwnership.onCompletion(attemptGeneration, currentGeneration)
    }

    @Test
    fun `one attempt while connecting remains one in-flight attempt`() {
        val model = AttemptModel()
        model.beginAttempt()
        // A second demand while Connecting must not start a duplicate build.
        assertEquals(
            ControllerConnectionAction.AwaitExisting,
            ControllerConnectionDecision.onRequest(model.state),
        )
    }

    @Test
    fun `release invalidates an in-flight attempt`() {
        val model = AttemptModel()
        val a = model.beginAttempt()
        model.invalidateForRelease()
        assertEquals(ControllerAttemptOutcome.DiscardStale, model.outcomeFor(a))
    }

    @Test
    fun `a new attempt may start after release`() {
        val model = AttemptModel()
        model.beginAttempt()
        model.invalidateForRelease()
        assertEquals(ControllerConnectionState.Disconnected, model.state)
        assertEquals(
            ControllerConnectionAction.StartConnection,
            ControllerConnectionDecision.onRequest(model.state),
        )
    }

    @Test
    fun `late success from the old attempt is rejected`() {
        val model = AttemptModel()
        val a = model.beginAttempt()
        model.invalidateForRelease()
        model.beginAttempt() // attempt B is now current
        // A completes late (success): not authoritative -> discard (its controller is released).
        assertEquals(ControllerAttemptOutcome.DiscardStale, model.outcomeFor(a))
    }

    @Test
    fun `stale successful controller is not installed`() {
        // DiscardStale is the contract by which PlayerController releases the produced controller
        // and leaves mediaController/state untouched, so a stale success is never installed.
        val model = AttemptModel()
        val a = model.beginAttempt()
        model.invalidateForRelease()
        val b = model.beginAttempt()
        assertEquals(ControllerAttemptOutcome.DiscardStale, model.outcomeFor(a))
        // The current attempt, by contrast, would be applied.
        assertEquals(ControllerAttemptOutcome.Apply, model.outcomeFor(b))
    }

    @Test
    fun `late failure from the old attempt cannot reset the new attempt`() {
        val model = AttemptModel()
        val a = model.beginAttempt()
        model.invalidateForRelease() // A superseded (release) while its build was in flight
        val b = model.beginAttempt()  // attempt B is now current and connecting
        // A's late failure completion is stale and must not touch B's state.
        assertEquals(ControllerAttemptOutcome.DiscardStale, model.outcomeFor(a))
        assertEquals(ControllerAttemptOutcome.Apply, model.outcomeFor(b))
    }

    @Test
    fun `current attempt success is accepted`() {
        val model = AttemptModel()
        val a = model.beginAttempt()
        assertEquals(ControllerAttemptOutcome.Apply, model.outcomeFor(a))
    }

    @Test
    fun `current attempt failure returns to retry-eligible disconnected`() {
        val model = AttemptModel()
        val a = model.beginAttempt()
        // Authoritative failure applies and moves to a retry-eligible Disconnected state.
        assertEquals(ControllerAttemptOutcome.Apply, model.outcomeFor(a))
        assertEquals(ControllerConnectionState.Disconnected, ControllerConnectionDecision.onConnectionFailed())
    }

    @Test
    fun `disconnect from non-current controller cannot clear current state`() {
        assertFalse(ControllerAttemptOwnership.shouldApplyDisconnect(isCurrentController = false))
        assertTrue(ControllerAttemptOwnership.shouldApplyDisconnect(isCurrentController = true))
    }
}
