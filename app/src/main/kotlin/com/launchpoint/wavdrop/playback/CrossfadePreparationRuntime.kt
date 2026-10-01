package com.launchpoint.wavdrop.playback

import android.util.Log
import androidx.media3.common.MediaItem
import com.launchpoint.wavdrop.data.model.Song

/**
 * Why a still-armed transition no longer belongs to the live runtime state, or null when it does.
 * Pure, occurrence-based (generation + positions); song ids are never consulted.
 */
internal fun crossfadeOwnershipLossReason(
    snapshot: CrossfadeRuntimeSnapshot,
    key: CrossfadeTransitionKey,
): CrossfadeCancelReason? = when {
    !snapshot.controllerConnected -> CrossfadeCancelReason.ControllerDisconnected
    snapshot.isExternalPlayback -> CrossfadeCancelReason.ExternalPlayback
    snapshot.playerQueueNeedsSync -> CrossfadeCancelReason.QueueBecameDirty
    !snapshot.isPlaying -> CrossfadeCancelReason.Pause
    snapshot.queueGeneration != key.queueGeneration -> CrossfadeCancelReason.QueueMutation
    snapshot.currentPlaybackIndex != key.fromPlaybackIndex -> CrossfadeCancelReason.ManualNavigation
    key.fromPlaybackIndex !in snapshot.playbackQueue.indices ||
        key.toPlaybackIndex !in snapshot.playbackQueue.indices -> CrossfadeCancelReason.QueueMutation
    // The repeat semantics must still resolve the key's EXACT automatic target (covers Repeat ONE, and e.g.
    // last -> first under Repeat ALL once repeat becomes OFF); harmless repeat changes stay owned.
    QueueNavigator.automaticNextIndex(
        queueSize = snapshot.playbackQueue.size,
        currentIndex = key.fromPlaybackIndex,
        repeatMode = snapshot.repeatMode,
    ) != key.toPlaybackIndex -> CrossfadeCancelReason.RepeatChanged
    else -> null
}

/**
 * A same-key evaluation is idempotent only when the CF-1 plan values are unchanged too: the key names the
 * occurrence, not the overlap. Only Armed/Ready may ever be retained; Fading/HandoffPending are never
 * plan-equivalent here (this runtime must not be in those phases).
 */
internal fun isRetainablePlan(active: CrossfadeState.Active, plan: CrossfadeTransitionPlan.Eligible): Boolean =
    when (active) {
        is CrossfadeState.Armed ->
            active.effectiveDurationMs == plan.effectiveDurationMs && active.startAtPositionMs == plan.startAtPositionMs
        is CrossfadeState.Ready ->
            active.effectiveDurationMs == plan.effectiveDurationMs && active.startAtPositionMs == plan.startAtPositionMs
        is CrossfadeState.Fading,
        is CrossfadeState.HandoffPending -> false
    }

/**
 * Revalidates an armed plan against the secondary's ACTUAL prepared duration. Policy (fail closed, no
 * re-arming): the already-bound effective overlap must still fit within half of the real next duration;
 * otherwise the preparation is rejected. Unknown/non-positive durations are unusable.
 */
internal fun isPreparedDurationCompatible(effectiveDurationMs: Long, preparedDurationMs: Long): Boolean =
    preparedDurationMs > 0L &&
        effectiveDurationMs >= CrossfadeRules.MIN_ENABLED_DURATION_MS &&
        preparedDurationMs / 2 >= effectiveDurationMs

/**
 * CF-2B3: silent preparation orchestration. Wires snapshot -> CF-1 plan -> occurrence binding -> CF-2A
 * coordinator -> CF-2B2 secondary preparation, and stops at Ready. It never plays the secondary, never
 * changes any gain, never touches the primary player/MediaSession/EQ, and executes only the pre-audible
 * commands (PrepareSecondary, AbandonSecondary).
 *
 * Main-thread confined: every entry point (evaluation, secondary callbacks, close) must run on the
 * authoritative playback/main looper, where [snapshotProvider] may be called. This runtime owns the
 * [CrossfadeSecondaryPlayer] and is its single release owner.
 */
internal class CrossfadePreparationRuntime(
    private val snapshotProvider: () -> CrossfadeRuntimeSnapshot,
    backendFactory: () -> SecondaryPlayerBackend,
    mediaItemFactory: (Song) -> MediaItem = { it.toPlaybackMediaItem() },
) {
    var state: CrossfadeState = CrossfadeState.Idle
        private set

    /** Audible commands that must never reach this slice; recorded (and refused) so tests can observe them. */
    val rejectedAudibleCommands: List<CrossfadeCommand> get() = rejected
    private val rejected = mutableListOf<CrossfadeCommand>()

    private var closed = false

    private val secondary = CrossfadeSecondaryPlayer(
        backendFactory = backendFactory,
        listener = object : CrossfadeSecondaryListener {
            override fun onSecondaryReady(key: CrossfadeTransitionKey, preparedDurationMs: Long) =
                handleSecondaryReady(key, preparedDurationMs)

            override fun onSecondaryFailed(key: CrossfadeTransitionKey) = handleSecondaryFailed(key)
        },
        mediaItemFactory = mediaItemFactory,
    )

    /**
     * Evaluates whether the current occurrence should have a prepared secondary. Captures exactly one
     * snapshot, which is used for ownership checks, planning, binding and selecting the target song.
     * Idempotent for a still-owned transition.
     */
    fun evaluatePreparation(configuredDurationMs: Long, currentDurationMs: Long?) {
        if (closed) return
        val snapshot = snapshotProvider()

        (state as? CrossfadeState.Active)?.let { active ->
            crossfadeOwnershipLossReason(snapshot, active.key)?.let { cancel(it, snapshot) }
        }
        val active = state as? CrossfadeState.Active

        if (!snapshot.controllerConnected) return

        val plan = planCrossfadeFromRuntimeSnapshot(configuredDurationMs, snapshot, currentDurationMs)
        if (plan !is CrossfadeTransitionPlan.Eligible) {
            if (active != null) {
                cancel(cancelReasonFor((plan as CrossfadeTransitionPlan.Unavailable).reason), snapshot)
            }
            return
        }
        val key = bindCrossfadeTransition(snapshot, plan)
        if (key == null) {
            if (active != null) cancel(CrossfadeCancelReason.PlanInvalidated, snapshot)
            return
        }
        if (active != null && active.key == key) {
            if (isRetainablePlan(active, plan)) return // same occurrence AND same plan: idempotent
            // Same occurrence but a different overlap/start: drop the old preparation (abandoned once) and
            // re-arm from this same snapshot.
            cancel(CrossfadeCancelReason.PlanInvalidated, snapshot)
        }

        applyReduction(reduceCrossfade(state, CrossfadeEvent.Arm(key, plan)), snapshot)
    }

    /** Synchronous cancellation from the owner of playback state. Harmless when idle. */
    fun cancel(reason: CrossfadeCancelReason) {
        if (closed) return
        cancel(reason, snapshotProvider())
    }

    /** Idempotent teardown: cancels ownership, then releases the secondary. Nothing resurrects afterwards. */
    fun close() {
        if (closed) return
        val toCancel = state
        closed = true
        state = CrossfadeState.Idle
        if (toCancel is CrossfadeState.Active) {
            // Pre-audible cleanup is covered by release(); no primary operation is ever required here.
            secondary.abandon(toCancel.key)
        }
        secondary.release()
    }

    private fun cancel(reason: CrossfadeCancelReason, snapshot: CrossfadeRuntimeSnapshot) {
        applyReduction(reduceCrossfade(state, CrossfadeEvent.Cancel(reason)), snapshot)
    }

    private fun handleSecondaryReady(key: CrossfadeTransitionKey, preparedDurationMs: Long) {
        if (closed) return
        val armed = state as? CrossfadeState.Armed ?: return
        if (armed.key != key) return // stale / duplicate / after cancellation
        val snapshot = snapshotProvider()
        crossfadeOwnershipLossReason(snapshot, key)?.let {
            cancel(it, snapshot)
            return
        }
        if (!isPreparedDurationCompatible(armed.effectiveDurationMs, preparedDurationMs)) {
            cancel(CrossfadeCancelReason.SecondaryError, snapshot)
            return
        }
        applyReduction(reduceCrossfade(state, CrossfadeEvent.SecondaryReady(key)), snapshot)
    }

    private fun handleSecondaryFailed(key: CrossfadeTransitionKey) {
        if (closed) return
        // The coordinator ignores any key that is not the active one, so a stale failure cannot cancel
        // a newer transition.
        applyReduction(reduceCrossfade(state, CrossfadeEvent.SecondaryFailed(key)), snapshotProvider())
    }

    /** State first (so synchronous callbacks see the new owner), then the pre-audible commands. */
    internal fun applyReduction(reduction: CrossfadeReduction, snapshot: CrossfadeRuntimeSnapshot) {
        state = reduction.state
        for (command in reduction.commands) {
            when (command) {
                is CrossfadeCommand.AbandonSecondary -> secondary.abandon(command.key)
                is CrossfadeCommand.PrepareSecondary -> {
                    val target = snapshot.playbackQueue.getOrNull(command.key.toPlaybackIndex)
                    if (target == null || !secondary.prepare(command.key, target)) {
                        // Nothing to prepare / released: give ownership back rather than stay Armed.
                        if (state == reduction.state) {
                            state = reduceCrossfade(state, CrossfadeEvent.Cancel(CrossfadeCancelReason.PlanInvalidated)).state
                        }
                    }
                }
                is CrossfadeCommand.StartSecondary,
                is CrossfadeCommand.ApplyGains,
                is CrossfadeCommand.RequestHandoff,
                is CrossfadeCommand.RestorePrimaryGain -> {
                    rejected += command
                    Log.w(TAG, "refusing audible crossfade command in silent-preparation runtime: $command")
                    if (state is CrossfadeState.Active) {
                        val active = state as CrossfadeState.Active
                        state = CrossfadeState.Idle
                        secondary.abandon(active.key)
                    }
                    return
                }
            }
        }
    }

    private fun cancelReasonFor(reason: CrossfadeUnavailableReason): CrossfadeCancelReason = when (reason) {
        CrossfadeUnavailableReason.Disabled -> CrossfadeCancelReason.ConfigurationDisabled
        CrossfadeUnavailableReason.ExternalPlayback -> CrossfadeCancelReason.ExternalPlayback
        CrossfadeUnavailableReason.PlayerQueueNeedsSync -> CrossfadeCancelReason.QueueBecameDirty
        CrossfadeUnavailableReason.NotPlaying -> CrossfadeCancelReason.Pause
        CrossfadeUnavailableReason.RepeatOne,
        CrossfadeUnavailableReason.NoNextOccurrence -> CrossfadeCancelReason.RepeatChanged
        else -> CrossfadeCancelReason.PlanInvalidated
    }

    private companion object {
        const val TAG = "WavdropCrossfade"
    }
}
