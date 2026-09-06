package com.launchpoint.wavdrop.playback

/**
 * Lifecycle of a startup playback-session restore attempt.
 *
 * The previous coordinator used a single `hasTriggered` boolean that was set BEFORE the restore
 * result was known, so any transient failure (controller not yet connected, Media3 setup failure,
 * song-repository error) permanently consumed eligibility and stranded playback until the process
 * was restarted. This three-state model instead makes success/terminal outcomes idempotent while
 * keeping transient failures retryable.
 */
internal enum class StartupRestoreState {
    /** No attempt in flight; a new restore may begin. */
    NotStarted,

    /** An attempt is running; concurrent duplicate restores must be suppressed. */
    InFlight,

    /** A terminal/satisfied outcome was reached; further restores are no-ops. */
    Terminal,
}

/**
 * Pure classification of a completed [PlayerHydrationResult] for startup-restore coordination.
 * Kept dependency-free so it can be unit-tested without Android/Room/Media3.
 */
internal object StartupRestoreDecision {
    /**
     * Terminal outcomes are either successful or a permanent "nothing to restore" answer that
     * would return the exact same result on every retry, so retrying them wastes work and — for
     * [PlayerHydrationResult.SkippedActiveQueue] — would risk fighting active playback. Everything
     * NOT listed here is transient and remains eligible for a later attempt.
     */
    fun isTerminal(result: PlayerHydrationResult): Boolean = when (result) {
        PlayerHydrationResult.Hydrated,
        PlayerHydrationResult.AlreadyHydrated,
        PlayerHydrationResult.NoSavedSession,
        PlayerHydrationResult.FilteredBySettings,
        PlayerHydrationResult.NoResolvableSong,
        PlayerHydrationResult.SkippedActiveQueue -> true

        // Transient: the controller was not connected yet, or Media3 setup failed and the previous
        // logical state was rolled back. A later request should be allowed to try again.
        PlayerHydrationResult.ControllerUnavailable,
        PlayerHydrationResult.MediaSetupFailed -> false
    }

    /** State to adopt once an attempt completes with [result]. */
    fun nextState(result: PlayerHydrationResult): StartupRestoreState =
        if (isTerminal(result)) StartupRestoreState.Terminal else StartupRestoreState.NotStarted
}

/**
 * Small explicit state machine guarding startup restore. Thread-safe so it can be driven from an
 * Activity thread ([beginAttempt]) while a background coroutine reports the outcome
 * ([completeAttempt]).
 *
 * Contract:
 * - first [beginAttempt] returns true (caller owns the attempt);
 * - any [beginAttempt] while [StartupRestoreState.InFlight] or [StartupRestoreState.Terminal]
 *   returns false (no duplicate concurrent restore, no post-terminal restore);
 * - [completeAttempt] with a terminal result locks the gate; with a transient result it re-opens
 *   eligibility so the next [beginAttempt] succeeds.
 */
internal class StartupRestoreGate {
    private val lock = Any()
    private var state = StartupRestoreState.NotStarted

    /** Returns true only if the caller acquired the exclusive right to run a restore attempt. */
    fun beginAttempt(): Boolean = synchronized(lock) {
        if (state != StartupRestoreState.NotStarted) return false
        state = StartupRestoreState.InFlight
        true
    }

    /** Records a completed attempt's [result], moving to terminal or back to retry-eligible. */
    fun completeAttempt(result: PlayerHydrationResult) = synchronized(lock) {
        state = StartupRestoreDecision.nextState(result)
    }

    /**
     * Records that an attempt failed before producing a [PlayerHydrationResult] (e.g. the song
     * repository threw). Treated as transient — the gate re-opens for a later attempt.
     */
    fun completeWithTransientFailure() = synchronized(lock) {
        state = StartupRestoreState.NotStarted
    }

    fun currentState(): StartupRestoreState = synchronized(lock) { state }
}
