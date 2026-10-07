package com.launchpoint.wavdrop.playback

enum class SleepTimerOption(
    val displayName: String,
    val durationMs: Long?,
) {
    OFF("Off", null),
    MINUTES_15("15 minutes", 15 * 60 * 1_000L),
    MINUTES_30("30 minutes", 30 * 60 * 1_000L),
    MINUTES_45("45 minutes", 45 * 60 * 1_000L),
    MINUTES_60("60 minutes", 60 * 60 * 1_000L),
    END_OF_CURRENT_SONG("End of current song", null),
}

/** Where the single authoritative timer currently is. Never inferred from the selected duration. */
enum class SleepTimerPhase {
    /** No timer and no armed boundary. */
    IDLE,

    /** A duration timer is counting down. At expiry it pauses, or arms the finish-current boundary if that modifier is on. */
    COUNTDOWN,

    /**
     * The terminal boundary is ARMED: the countdown (if any) is over and the app is waiting for the bound occurrence to finish.
     * Standalone "End of current song" enters this phase immediately; a duration timer enters it at expiry.
     */
    FINISHING_CURRENT_TRACK,
}

/**
 * The exact queue occurrence that must be the last audible one: the queue generation plus the playback position, never a song
 * id alone, so duplicate songs are safe. [songId] is carried only to re-validate the binding after a queue mutation (it can
 * never select an occurrence).
 */
data class SleepTerminalOccurrence(
    val queueGeneration: Long,
    val playbackIndex: Int,
    val songId: Long,
)

/**
 * The ONE authoritative sleep-timer / terminal-boundary state. In-memory for the process lifetime only: it is never
 * persisted, never part of a backup, and nothing resurrects it after process death.
 */
data class SleepTimerState(
    val option: SleepTimerOption = SleepTimerOption.OFF,
    val startedAtMs: Long? = null,
    /** Countdown deadline. Null once the countdown is over (an armed boundary has no second countdown) and for standalone mode. */
    val endsAtMs: Long? = null,
    val customDurationMs: Long? = null,
    /** The "finish current track" modifier configured for a duration timer (meaningless for standalone and idle states). */
    val finishCurrentTrack: Boolean = false,
    val phase: SleepTimerPhase = SleepTimerPhase.IDLE,
    /** The bound occurrence while [phase] is [SleepTimerPhase.FINISHING_CURRENT_TRACK]; null otherwise. */
    val terminal: SleepTerminalOccurrence? = null,
) {
    val isActive: Boolean get() = phase != SleepTimerPhase.IDLE
    val isCountingDown: Boolean get() = phase == SleepTimerPhase.COUNTDOWN
    val isFinishingCurrentTrack: Boolean get() = phase == SleepTimerPhase.FINISHING_CURRENT_TRACK
}

/** What the controller must DO. The policy decides; the controller only executes. */
internal sealed interface SleepTimerAction {
    object None : SleepTimerAction

    /** Pause playback now. The timer state is already cleared. */
    object PauseNow : SleepTimerAction

    /** The boundary just became armed: end any owned crossfade and make the player stop at the end of the current item. */
    data class ArmBoundary(val terminal: SleepTerminalOccurrence) : SleepTimerAction

    /** The armed occurrence finished and the player stopped on it: record the terminal position. The state is already cleared. */
    object StopAtBoundary : SleepTimerAction

    /** The boundary was cancelled without stopping playback (user intent superseded it). The state is already cleared. */
    object DisarmBoundary : SleepTimerAction
}

internal data class SleepTimerTransition(
    val state: SleepTimerState,
    val action: SleepTimerAction = SleepTimerAction.None,
)

/** How the player moved to a (possibly) different media item, as seen by a Media3 transition callback. */
internal enum class SleepTransitionKind {
    /** Natural advance to the next item (MEDIA_ITEM_TRANSITION_REASON_AUTO). */
    AUTOMATIC,

    /** A repeat-one loop wrap (MEDIA_ITEM_TRANSITION_REASON_REPEAT or the ticker's loop-boundary detector). */
    REPEAT_WRAP,

    /** An explicit navigation / seek to an item (user, notification, external controller). */
    NAVIGATION,

    /** The playlist itself changed (queue repair, mutation): says nothing about user intent. */
    PLAYLIST_CHANGED,
}

/**
 * Pure policy for the sleep timer and its terminal boundary. No player, no clock, no coroutine. Every Media3 callback and every
 * user command is mapped to ONE decision here, so the callbacks do not each invent rules.
 *
 * Invariants: one boundary at a time, bound to one exact occurrence; a boundary is consumed at most once (every transition
 * out of [SleepTimerPhase.FINISHING_CURRENT_TRACK] returns an idle state, so a late duplicate callback finds nothing armed and
 * is harmless); the bound occurrence is never silently moved to another one.
 */
internal object SleepTimerPolicy {

    private val IDLE_STATE = SleepTimerState()

    // ── configuration ───────────────────────────────────────────────────────────────────────────────────────────────────

    /** Starts a duration countdown. Replaces any previous timer or armed boundary. [finishCurrentTrack] binds at EXPIRY. */
    fun startDuration(
        nowMs: Long,
        option: SleepTimerOption,
        customDurationMs: Long?,
        finishCurrentTrack: Boolean,
    ): SleepTimerState {
        val durationMs = customDurationMs ?: option.durationMs ?: return IDLE_STATE
        if (durationMs <= 0L) return IDLE_STATE
        return SleepTimerState(
            option = if (customDurationMs != null) SleepTimerOption.OFF else option,
            startedAtMs = nowMs,
            endsAtMs = nowMs + durationMs,
            customDurationMs = customDurationMs,
            finishCurrentTrack = finishCurrentTrack,
            phase = SleepTimerPhase.COUNTDOWN,
        )
    }

    /**
     * Standalone "End of current song": arms immediately against the exact current occurrence. With no resolvable current
     * occurrence nothing can be bound, so it fails closed to an idle state instead of waiting for "whatever plays next".
     */
    fun startStandalone(nowMs: Long, current: SleepTerminalOccurrence?): SleepTimerTransition {
        if (current == null) return SleepTimerTransition(IDLE_STATE, SleepTimerAction.DisarmBoundary)
        return SleepTimerTransition(
            SleepTimerState(
                option = SleepTimerOption.END_OF_CURRENT_SONG,
                startedAtMs = nowMs,
                phase = SleepTimerPhase.FINISHING_CURRENT_TRACK,
                terminal = current,
            ),
            SleepTimerAction.ArmBoundary(current),
        )
    }

    /** Off: clears everything (an armed boundary is released physically). */
    fun off(state: SleepTimerState): SleepTimerTransition =
        SleepTimerTransition(IDLE_STATE, if (state.isFinishingCurrentTrack) SleepTimerAction.DisarmBoundary else SleepTimerAction.None)

    // ── countdown expiry ────────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * The countdown reached zero. [current] is the exact occurrence playing at this instant (null when it cannot be
     * resolved safely) and [playbackActive] says whether playback is running. Finish OFF, nothing playing or an unresolvable
     * occurrence all pause now; only a running, resolved occurrence with Finish ON arms the boundary.
     */
    fun onCountdownExpired(
        state: SleepTimerState,
        current: SleepTerminalOccurrence?,
        playbackActive: Boolean,
    ): SleepTimerTransition {
        if (state.phase != SleepTimerPhase.COUNTDOWN) return SleepTimerTransition(state) // stale timer job
        if (!state.finishCurrentTrack || current == null || !playbackActive) {
            return SleepTimerTransition(IDLE_STATE, SleepTimerAction.PauseNow)
        }
        return SleepTimerTransition(
            state.copy(
                phase = SleepTimerPhase.FINISHING_CURRENT_TRACK,
                endsAtMs = null,
                terminal = current,
            ),
            SleepTimerAction.ArmBoundary(current),
        )
    }

    // ── natural boundary ────────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * A natural completion of [observed] was reported. Consumes the boundary only when it is armed AND [observed] is exactly the
     * bound occurrence (same generation, same position). A repeated callback finds an idle state; a callback for another
     * occurrence or an older queue generation is ignored (fail closed: the boundary is neither consumed nor moved).
     */
    fun onNaturalBoundary(state: SleepTimerState, observed: SleepTerminalOccurrence?): SleepTimerTransition {
        val terminal = state.terminal
        if (!state.isFinishingCurrentTrack || terminal == null || observed == null) return SleepTimerTransition(state)
        if (observed.queueGeneration != terminal.queueGeneration || observed.playbackIndex != terminal.playbackIndex) {
            return SleepTimerTransition(state)
        }
        return SleepTimerTransition(IDLE_STATE, SleepTimerAction.StopAtBoundary)
    }

    /**
     * The player stopped (play-when-ready false, or STATE_ENDED) while a boundary is armed. [atNaturalEnd] says the stop is the
     * end of the current item rather than an earlier pause. A stop at the natural end of the bound occurrence consumes the
     * boundary; ANY other stop is a user/system pause, which clears it (an expired timer never resurrects). The armed state
     * never survives a stopped player, so it cannot get stuck.
     *
     * Because a player that is armed to pause at the end of each item cannot advance, the first natural end after arming is the
     * bound occurrence by construction; a queue mutation that bumped the generation after arming is therefore accepted when
     * the same song is still the one that ended (the deferred rebind normally already moved the binding).
     */
    fun onPlaybackStopped(
        state: SleepTimerState,
        observed: SleepTerminalOccurrence?,
        atNaturalEnd: Boolean,
    ): SleepTimerTransition {
        val terminal = state.terminal
        if (!state.isFinishingCurrentTrack || terminal == null) return SleepTimerTransition(state)
        if (atNaturalEnd && observed != null) {
            val exact = onNaturalBoundary(state, observed)
            if (exact.action == SleepTimerAction.StopAtBoundary) return exact
            if (observed.queueGeneration != terminal.queueGeneration && observed.songId == terminal.songId) {
                return SleepTimerTransition(IDLE_STATE, SleepTimerAction.StopAtBoundary)
            }
        }
        return SleepTimerTransition(IDLE_STATE, SleepTimerAction.DisarmBoundary)
    }

    /**
     * The player moved past the armed occurrence even though it should not have been able to (the physical pause-at-end was
     * not in effect): stop immediately and clear. Defensive fallback; the primary enforcement is physical.
     */
    fun onBoundaryEscaped(state: SleepTimerState): SleepTimerTransition =
        if (state.isFinishingCurrentTrack) SleepTimerTransition(IDLE_STATE, SleepTimerAction.PauseNow) else SleepTimerTransition(state)

    /**
     * A Media3 transition callback while armed. Automatic advance or a repeat wrap means the boundary escaped; an explicit
     * navigation to any OTHER occurrence is user intent that supersedes the boundary; a navigation that lands on the same
     * occurrence (restart/seek) and a playlist change keep it.
     */
    fun onTransition(
        state: SleepTimerState,
        kind: SleepTransitionKind,
        observed: SleepTerminalOccurrence?,
    ): SleepTimerTransition {
        val terminal = state.terminal
        if (!state.isFinishingCurrentTrack || terminal == null) return SleepTimerTransition(state)
        return when (kind) {
            SleepTransitionKind.AUTOMATIC, SleepTransitionKind.REPEAT_WRAP -> onBoundaryEscaped(state)
            SleepTransitionKind.PLAYLIST_CHANGED -> SleepTimerTransition(state)
            SleepTransitionKind.NAVIGATION ->
                if (observed != null &&
                    observed.queueGeneration == terminal.queueGeneration &&
                    observed.playbackIndex == terminal.playbackIndex
                ) {
                    SleepTimerTransition(state)
                } else {
                    SleepTimerTransition(IDLE_STATE, SleepTimerAction.DisarmBoundary)
                }
        }
    }

    // ── user intent ─────────────────────────────────────────────────────────────────────────────────────────────────────

    /** An explicit Next/Previous: supersedes an armed boundary (a running countdown is unaffected). */
    fun onExplicitNavigation(state: SleepTimerState): SleepTimerTransition = clearArmed(state)

    /** The user chose another song, replaced the queue or started new playback: supersedes an armed boundary. */
    fun onQueueReplaced(state: SleepTimerState): SleepTimerTransition = clearArmed(state)

    /** A seek inside the same occurrence never cancels the boundary. */
    fun onSameOccurrenceSeek(state: SleepTimerState): SleepTimerTransition = SleepTimerTransition(state)

    /** Repeat changed: the boundary outranks repeat, so it is retained untouched. */
    fun onRepeatChanged(state: SleepTimerState): SleepTimerTransition = SleepTimerTransition(state)

    /**
     * The queue identity changed (generation bumped by a structural mutation or a shuffle toggle) while armed. [resolved] is
     * the occurrence currently playing, or null when it cannot be resolved right now.
     *  - same song still playing: the binding is moved to that exact occurrence (the physical item never changed);
     *  - a different song is playing: the bound occurrence is gone (the user removed it or replaced it): cleared;
     *  - unresolved: keep waiting; the natural stop still ends at the current item and a later resolution re-binds.
     */
    fun onQueueIdentityChanged(state: SleepTimerState, resolved: SleepTerminalOccurrence?): SleepTimerTransition {
        val terminal = state.terminal
        if (!state.isFinishingCurrentTrack || terminal == null || resolved == null) return SleepTimerTransition(state)
        if (resolved == terminal) return SleepTimerTransition(state)
        if (resolved.songId != terminal.songId) return SleepTimerTransition(IDLE_STATE, SleepTimerAction.DisarmBoundary)
        return SleepTimerTransition(state.copy(terminal = resolved))
    }

    private fun clearArmed(state: SleepTimerState): SleepTimerTransition =
        if (state.isFinishingCurrentTrack) SleepTimerTransition(IDLE_STATE, SleepTimerAction.DisarmBoundary) else SleepTimerTransition(state)
}
