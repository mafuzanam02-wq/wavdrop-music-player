package com.launchpoint.wavdrop.playback

/**
 * Explicit connection state for the single [androidx.media3.session.MediaController] that
 * [PlayerController] owns.
 *
 * Previously there was no state at all: the controller was built exactly once in `init` and a
 * failed `future.get()` was swallowed, leaving the controller permanently null with no way to
 * rebuild it. Modelling the state lets a failed attempt return to [Disconnected] (retry-eligible)
 * and prevents two concurrent builds.
 */
internal enum class ControllerConnectionState {
    /** No controller and no build in flight; a new connection attempt may start. */
    Disconnected,

    /** A `buildAsync()` is in flight; further requests must wait for it, not start a second build. */
    Connecting,

    /** A live controller is available. */
    Connected,
}

/** Action a caller should take when it needs the controller, given the current state. */
internal enum class ControllerConnectionAction {
    /** Start a fresh `buildAsync()` connection attempt. */
    StartConnection,

    /** A build is already in flight; wait for it rather than starting another. */
    AwaitExisting,

    /** A live controller already exists; use it directly. */
    UseExisting,
}

/**
 * Pure decision for demand-driven controller acquisition. Dependency-free for unit testing.
 *
 * This is deliberately NOT a reconnect daemon: it only decides what a caller that *needs* the
 * controller right now should do. Retry happens because a later demand (hydration,
 * `awaitMediaController`, a playback action) re-enters [onRequest] and, when
 * [ControllerConnectionState.Disconnected], is told to [ControllerConnectionAction.StartConnection]
 * again.
 */
internal object ControllerConnectionDecision {
    fun onRequest(state: ControllerConnectionState): ControllerConnectionAction = when (state) {
        ControllerConnectionState.Disconnected -> ControllerConnectionAction.StartConnection
        ControllerConnectionState.Connecting -> ControllerConnectionAction.AwaitExisting
        ControllerConnectionState.Connected -> ControllerConnectionAction.UseExisting
    }

    /** State after a build succeeds. */
    fun onConnected(): ControllerConnectionState = ControllerConnectionState.Connected

    /** State after a build fails — retry-eligible, not stranded. */
    fun onConnectionFailed(): ControllerConnectionState = ControllerConnectionState.Disconnected

    /** State after a live controller disconnects — retry-eligible on next demand. */
    fun onDisconnected(): ControllerConnectionState = ControllerConnectionState.Disconnected
}

/** What to do with a `buildAsync()` completion, given whether its attempt is still authoritative. */
internal enum class ControllerAttemptOutcome {
    /** The completion belongs to the current attempt and may mutate connection state. */
    Apply,

    /**
     * A newer attempt superseded this one (or [PlayerController.release] invalidated it) while the
     * build was in flight. The completion must NOT touch [mediaController]/state; any controller it
     * produced must be released and discarded.
     */
    DiscardStale,
}

/**
 * Pure attempt-ownership decisions. Each controller build carries the monotonic generation id it
 * was started with; only the completion whose generation still equals the current generation is
 * authoritative. This is the correctness authority for the stale-completion race —
 * `Future.cancel()` is only a best-effort optimisation on top of it. Dependency-free for testing.
 */
internal object ControllerAttemptOwnership {
    fun onCompletion(attemptGeneration: Long, currentGeneration: Long): ControllerAttemptOutcome =
        if (attemptGeneration == currentGeneration) {
            ControllerAttemptOutcome.Apply
        } else {
            ControllerAttemptOutcome.DiscardStale
        }

    /**
     * A disconnect callback may clear connection state only when it originates from the currently
     * installed controller; a disconnect from a stale controller must never clear a newer one.
     */
    fun shouldApplyDisconnect(isCurrentController: Boolean): Boolean = isCurrentController
}
