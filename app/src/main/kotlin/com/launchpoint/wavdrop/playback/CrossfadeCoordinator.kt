package com.launchpoint.wavdrop.playback

/**
 * Crossfade lifecycle coordinator (CF-2A): a pure, deterministic state machine.
 *
 * Nothing here touches a player, volume, queue, clock or setting. A later runtime engine feeds
 * [CrossfadeEvent]s (with caller-supplied monotonic time) into [reduceCrossfade] and interprets the
 * returned semantic [CrossfadeCommand]s. Crossfade is optional: whenever the lifecycle becomes
 * uncertain it is cancelled back to [CrossfadeState.Idle], which merely abandons the attempt and
 * leaves native playback authoritative. No command ever asks for prepare/seek/play/pause or any
 * queue rebuild.
 *
 * Identity is occurrence-bound: [CrossfadeTransitionKey] is (queueGeneration, from, to) playback
 * positions, never a song id, so duplicate songs are irrelevant and callbacks from an older queue
 * generation can never affect a newer transition.
 */
internal data class CrossfadeTransitionKey(
    val queueGeneration: Long,
    val fromPlaybackIndex: Int,
    val toPlaybackIndex: Int,
)

internal enum class CrossfadeCancelReason {
    Pause,
    Seek,
    ManualNavigation,
    QueueMutation,
    ShuffleChanged,
    RepeatChanged,
    ExternalPlayback,
    QueueBecameDirty,
    ControllerDisconnected,
    PlaybackError,
    SecondaryError,
    HandoffFailed,
    MissedWindow,
    ClockRegression,
    ServiceStopping,
    /** The configured crossfade duration is now OFF. */
    ConfigurationDisabled,
    /** The CF-1 plan no longer holds (e.g. duration or next occurrence changed). */
    PlanInvalidated,
    /** The primary gain path could not safely apply/restore the requested crossfade gain. */
    PrimaryGainError,
}

internal enum class CrossfadeArmRejection {
    NegativeQueueGeneration,
    NegativePlaybackIndex,
    SameOccurrence,
    TargetMismatch,
    DurationBelowMinimum,
    DurationAboveMaximum,
    NegativeStartPosition,
}

internal sealed interface CrossfadeState {
    /** No crossfade owns any resources. */
    data object Idle : CrossfadeState

    sealed interface Active : CrossfadeState {
        val key: CrossfadeTransitionKey
        val effectiveDurationMs: Long
    }

    /** Occurrence-bound; the runtime should prepare the secondary path. */
    data class Armed(
        override val key: CrossfadeTransitionKey,
        override val effectiveDurationMs: Long,
        val startAtPositionMs: Long,
    ) : Active

    /** Secondary path prepared for [key]; still silent. */
    data class Ready(
        override val key: CrossfadeTransitionKey,
        override val effectiveDurationMs: Long,
        val startAtPositionMs: Long,
    ) : Active

    /**
     * Overlap has begun. [beganAtElapsedRealtimeMs] is the monotonic time execution began; [initialElapsedMs]
     * is how much of the planned fade window had already elapsed at that moment (0 for an on-time begin).
     * The two are kept separate so no timestamp is synthesized by subtraction.
     */
    data class Fading(
        override val key: CrossfadeTransitionKey,
        override val effectiveDurationMs: Long,
        val beganAtElapsedRealtimeMs: Long,
        val initialElapsedMs: Long,
    ) : Active

    /** Fade complete; exactly one handoff request has been emitted. */
    data class HandoffPending(
        override val key: CrossfadeTransitionKey,
        override val effectiveDurationMs: Long,
    ) : Active
}

internal sealed interface CrossfadeEvent {
    /** Arm from a CF-1 eligible plan, bound to [key]. */
    data class Arm(
        val key: CrossfadeTransitionKey,
        val plan: CrossfadeTransitionPlan.Eligible,
    ) : CrossfadeEvent

    data class SecondaryReady(val key: CrossfadeTransitionKey) : CrossfadeEvent
    data class SecondaryFailed(val key: CrossfadeTransitionKey) : CrossfadeEvent
    /** [nowElapsedRealtimeMs] is monotonic now; [initialElapsedMs] is the already-elapsed part of the fade window. */
    data class BeginFade(
        val key: CrossfadeTransitionKey,
        val nowElapsedRealtimeMs: Long,
        val initialElapsedMs: Long,
    ) : CrossfadeEvent
    data class FadeTick(val key: CrossfadeTransitionKey, val nowElapsedRealtimeMs: Long) : CrossfadeEvent
    data class HandoffSucceeded(val key: CrossfadeTransitionKey) : CrossfadeEvent
    data class HandoffFailed(val key: CrossfadeTransitionKey) : CrossfadeEvent

    /** Synchronous, key-less cancellation from the owner of playback state. */
    data class Cancel(val reason: CrossfadeCancelReason) : CrossfadeEvent
}

/** Semantic future-engine commands. Values only: nothing is executed in CF-2A. */
internal sealed interface CrossfadeCommand {
    val key: CrossfadeTransitionKey

    data class PrepareSecondary(override val key: CrossfadeTransitionKey) : CrossfadeCommand
    /** Starts the prepared secondary at [initialIncomingGain], the incoming component of the begin gains. */
    data class StartSecondary(
        override val key: CrossfadeTransitionKey,
        val initialIncomingGain: Float,
    ) : CrossfadeCommand
    data class ApplyGains(override val key: CrossfadeTransitionKey, val gains: CrossfadeGains) : CrossfadeCommand
    data class RequestHandoff(override val key: CrossfadeTransitionKey) : CrossfadeCommand
    data class RestorePrimaryGain(override val key: CrossfadeTransitionKey) : CrossfadeCommand
    data class AbandonSecondary(override val key: CrossfadeTransitionKey) : CrossfadeCommand
}

internal data class CrossfadeReduction(
    val state: CrossfadeState,
    val commands: List<CrossfadeCommand> = emptyList(),
    /** Set when this reduction cancelled an active transition (not for supersede or completion). */
    val cancelReason: CrossfadeCancelReason? = null,
    /** Set when an [CrossfadeEvent.Arm] was rejected; the prior state is then unchanged. */
    val armRejection: CrossfadeArmRejection? = null,
)

internal fun reduceCrossfade(state: CrossfadeState, event: CrossfadeEvent): CrossfadeReduction =
    when (event) {
        is CrossfadeEvent.Arm -> reduceArm(state, event)
        is CrossfadeEvent.Cancel -> cancel(state, event.reason)
        is CrossfadeEvent.SecondaryReady -> {
            if (state is CrossfadeState.Armed && state.key == event.key) {
                CrossfadeReduction(CrossfadeState.Ready(state.key, state.effectiveDurationMs, state.startAtPositionMs))
            } else {
                CrossfadeReduction(state)
            }
        }
        is CrossfadeEvent.SecondaryFailed ->
            if (matches(state, event.key)) cancel(state, CrossfadeCancelReason.SecondaryError) else CrossfadeReduction(state)
        is CrossfadeEvent.HandoffFailed ->
            if (state is CrossfadeState.HandoffPending && state.key == event.key) {
                cancel(state, CrossfadeCancelReason.HandoffFailed)
            } else {
                CrossfadeReduction(state)
            }
        is CrossfadeEvent.HandoffSucceeded ->
            if (state is CrossfadeState.HandoffPending && state.key == event.key) {
                CrossfadeReduction(CrossfadeState.Idle)
            } else {
                CrossfadeReduction(state)
            }
        is CrossfadeEvent.BeginFade -> reduceBegin(state, event)
        is CrossfadeEvent.FadeTick -> reduceTick(state, event)
    }

private fun matches(state: CrossfadeState, key: CrossfadeTransitionKey): Boolean =
    state is CrossfadeState.Active && state.key == key

private fun validateArm(key: CrossfadeTransitionKey, plan: CrossfadeTransitionPlan.Eligible): CrossfadeArmRejection? = when {
    key.queueGeneration < 0L -> CrossfadeArmRejection.NegativeQueueGeneration
    key.fromPlaybackIndex < 0 || key.toPlaybackIndex < 0 -> CrossfadeArmRejection.NegativePlaybackIndex
    key.fromPlaybackIndex == key.toPlaybackIndex -> CrossfadeArmRejection.SameOccurrence
    key.toPlaybackIndex != plan.nextPlaybackIndex -> CrossfadeArmRejection.TargetMismatch
    plan.effectiveDurationMs < CrossfadeRules.MIN_ENABLED_DURATION_MS -> CrossfadeArmRejection.DurationBelowMinimum
    plan.effectiveDurationMs > CrossfadeRules.MAX_DURATION_MS -> CrossfadeArmRejection.DurationAboveMaximum
    plan.startAtPositionMs < 0L -> CrossfadeArmRejection.NegativeStartPosition
    else -> null
}

private fun reduceArm(state: CrossfadeState, event: CrossfadeEvent.Arm): CrossfadeReduction {
    val rejection = validateArm(event.key, event.plan)
    if (rejection != null) return CrossfadeReduction(state, armRejection = rejection)
    val armed = CrossfadeState.Armed(event.key, event.plan.effectiveDurationMs, event.plan.startAtPositionMs)
    // Supersede: clean up any active transition first, then prepare the new one.
    return CrossfadeReduction(armed, cleanupCommands(state) + CrossfadeCommand.PrepareSecondary(event.key))
}

private fun reduceBegin(state: CrossfadeState, event: CrossfadeEvent.BeginFade): CrossfadeReduction {
    if (state !is CrossfadeState.Ready || state.key != event.key) return CrossfadeReduction(state)
    if (event.nowElapsedRealtimeMs < 0L || event.initialElapsedMs < 0L) return CrossfadeReduction(state)
    // A fade that already consumed its whole duration must not newly enter Fading.
    if (event.initialElapsedMs >= state.effectiveDurationMs) return CrossfadeReduction(state)
    val progress = initialCrossfadeProgress(event.initialElapsedMs, state.effectiveDurationMs)
        ?: return CrossfadeReduction(state)
    // One gain point: the secondary physically starts at the same incoming gain applied to the transition.
    val gains = CrossfadeGainCurve.equalPower(progress)
    return CrossfadeReduction(
        CrossfadeState.Fading(
            key = state.key,
            effectiveDurationMs = state.effectiveDurationMs,
            beganAtElapsedRealtimeMs = event.nowElapsedRealtimeMs,
            initialElapsedMs = event.initialElapsedMs,
        ),
        listOf(
            CrossfadeCommand.StartSecondary(state.key, gains.incoming),
            CrossfadeCommand.ApplyGains(state.key, gains),
        ),
    )
}

private fun reduceTick(state: CrossfadeState, event: CrossfadeEvent.FadeTick): CrossfadeReduction {
    if (state !is CrossfadeState.Fading || state.key != event.key) return CrossfadeReduction(state)
    // beganAt is validated non-negative, so a non-regressing `now` cannot overflow on subtraction.
    if (event.nowElapsedRealtimeMs < state.beganAtElapsedRealtimeMs) {
        return cancel(state, CrossfadeCancelReason.ClockRegression)
    }
    val elapsedSinceBeginMs = event.nowElapsedRealtimeMs - state.beganAtElapsedRealtimeMs
    // initialElapsed < duration was enforced at begin, so this is positive; comparing against the remaining
    // time (instead of summing) keeps the arithmetic overflow-free.
    val remainingAtBeginMs = state.effectiveDurationMs - state.initialElapsedMs
    if (elapsedSinceBeginMs >= remainingAtBeginMs) {
        return CrossfadeReduction(
            CrossfadeState.HandoffPending(state.key, state.effectiveDurationMs),
            listOf(
                CrossfadeCommand.ApplyGains(state.key, CrossfadeGainCurve.equalPower(1f)),
                CrossfadeCommand.RequestHandoff(state.key),
            ),
        )
    }
    // Here initialElapsed + elapsedSinceBegin < effectiveDuration, so the sum cannot overflow.
    val totalElapsedMs = state.initialElapsedMs + elapsedSinceBeginMs
    val progress = initialCrossfadeProgress(totalElapsedMs, state.effectiveDurationMs)
        ?: return cancel(state, CrossfadeCancelReason.ClockRegression)
    return CrossfadeReduction(
        state,
        listOf(CrossfadeCommand.ApplyGains(state.key, CrossfadeGainCurve.equalPower(progress))),
    )
}

private fun cancel(state: CrossfadeState, reason: CrossfadeCancelReason): CrossfadeReduction {
    if (state !is CrossfadeState.Active) return CrossfadeReduction(state)
    return CrossfadeReduction(CrossfadeState.Idle, cleanupCommands(state), cancelReason = reason)
}

/** Armed/Ready never faded: just abandon. Fading/HandoffPending: restore primary gain, then abandon. */
private fun cleanupCommands(state: CrossfadeState): List<CrossfadeCommand> = when (state) {
    CrossfadeState.Idle -> emptyList()
    is CrossfadeState.Armed, is CrossfadeState.Ready -> listOf(CrossfadeCommand.AbandonSecondary((state as CrossfadeState.Active).key))
    is CrossfadeState.Fading, is CrossfadeState.HandoffPending -> listOf(
        CrossfadeCommand.RestorePrimaryGain((state as CrossfadeState.Active).key),
        CrossfadeCommand.AbandonSecondary(state.key),
    )
}
