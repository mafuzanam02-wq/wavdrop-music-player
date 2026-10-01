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

    /** Overlap has begun; gains progress from [startedAtElapsedRealtimeMs]. */
    data class Fading(
        override val key: CrossfadeTransitionKey,
        override val effectiveDurationMs: Long,
        val startedAtElapsedRealtimeMs: Long,
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
    data class BeginFade(val key: CrossfadeTransitionKey, val startedAtElapsedRealtimeMs: Long) : CrossfadeEvent
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
    data class StartSecondary(override val key: CrossfadeTransitionKey) : CrossfadeCommand
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
    if (state !is CrossfadeState.Ready || state.key != event.key || event.startedAtElapsedRealtimeMs < 0L) {
        return CrossfadeReduction(state)
    }
    return CrossfadeReduction(
        CrossfadeState.Fading(state.key, state.effectiveDurationMs, event.startedAtElapsedRealtimeMs),
        listOf(
            CrossfadeCommand.StartSecondary(state.key),
            CrossfadeCommand.ApplyGains(state.key, CrossfadeGainCurve.equalPower(0f)),
        ),
    )
}

private fun reduceTick(state: CrossfadeState, event: CrossfadeEvent.FadeTick): CrossfadeReduction {
    if (state !is CrossfadeState.Fading || state.key != event.key) return CrossfadeReduction(state)
    // startedAt is validated non-negative, so a non-regressing `now` cannot overflow on subtraction.
    if (event.nowElapsedRealtimeMs < state.startedAtElapsedRealtimeMs) {
        return cancel(state, CrossfadeCancelReason.ClockRegression)
    }
    val elapsedMs = event.nowElapsedRealtimeMs - state.startedAtElapsedRealtimeMs
    if (elapsedMs >= state.effectiveDurationMs) {
        return CrossfadeReduction(
            CrossfadeState.HandoffPending(state.key, state.effectiveDurationMs),
            listOf(
                CrossfadeCommand.ApplyGains(state.key, CrossfadeGainCurve.equalPower(1f)),
                CrossfadeCommand.RequestHandoff(state.key),
            ),
        )
    }
    val progress = elapsedMs.toDouble() / state.effectiveDurationMs.toDouble()
    return CrossfadeReduction(
        state,
        listOf(CrossfadeCommand.ApplyGains(state.key, CrossfadeGainCurve.equalPower(progress.toFloat()))),
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
