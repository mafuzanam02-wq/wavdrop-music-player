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

/** Where the primary playback position stands relative to a Ready transition's planned fade window. */
internal sealed interface FadeWindowDecision {
    data object Waiting : FadeWindowDecision

    /** The window was entered [latenessMs] (>= 0) past the planned start; preserved, never discarded. */
    data class Due(val latenessMs: Long) : FadeWindowDecision

    data object Missed : FadeWindowDecision
    data object Invalid : FadeWindowDecision
}

/** How late (past the planned start) the fade window may still be entered. Covers a ~500 ms ticker. */
internal const val MAX_FADE_START_LATENESS_MS = 1_500L

/**
 * Pure track-position decision (not wall-clock; no elapsed-realtime here). The window is entered when the
 * position reaches or crosses [startAtPositionMs] - never by equality, since observations are coarse.
 * Policy for lateness: Due only while `position - start <= min(MAX_FADE_START_LATENESS_MS, effective / 2)`;
 * anything later (a large seek forward, a stalled/delayed observer) is Missed and must fail closed rather
 * than start an overlap whose intended timing has passed. Negative/unusable inputs are Invalid.
 */
internal fun decideFadeWindow(
    startAtPositionMs: Long,
    effectiveDurationMs: Long,
    positionMs: Long,
): FadeWindowDecision {
    if (positionMs < 0L || startAtPositionMs < 0L || effectiveDurationMs <= 0L) return FadeWindowDecision.Invalid
    if (positionMs < startAtPositionMs) return FadeWindowDecision.Waiting
    val lateness = positionMs - startAtPositionMs // both non-negative and position >= start: cannot overflow
    return if (isAcceptableFadeLateness(lateness, effectiveDurationMs)) FadeWindowDecision.Due(lateness) else FadeWindowDecision.Missed
}

/** The CF-2C1 accepted window: `0 <= lateness < effective` and `lateness <= min(MAX, effective / 2)`. */
internal fun isAcceptableFadeLateness(latenessMs: Long, effectiveDurationMs: Long): Boolean =
    effectiveDurationMs > 0L &&
        latenessMs >= 0L &&
        latenessMs < effectiveDurationMs &&
        latenessMs <= minOf(MAX_FADE_START_LATENESS_MS, effectiveDurationMs / 2)

/** Result of observing the primary position for a Ready transition. No audible action ever results. */
internal sealed interface FadeWindowObservation {
    /** Not Ready, closed, or the observation belonged to a different transition. */
    data object Inactive : FadeWindowObservation
    data object Waiting : FadeWindowObservation
    /**
     * The fade window was reached for this exact key+plan; reported exactly once. [latenessMs] is how far past
     * the planned start the observation was (0 = on time); a later slice must account for it.
     * The plan facts ([effectiveDurationMs], [startAtPositionMs]) are copied from the Ready state that produced
     * it: the key names the occurrence, not the overlap, so they prove which plan this Due belongs to.
     */
    data class Due(
        val key: CrossfadeTransitionKey,
        val effectiveDurationMs: Long,
        val startAtPositionMs: Long,
        val latenessMs: Long,
    ) : FadeWindowObservation
    data object AlreadyReported : FadeWindowObservation
    data class Cancelled(val reason: CrossfadeCancelReason) : FadeWindowObservation
}

/**
 * CF-2B3: silent preparation orchestration. Wires snapshot -> CF-1 plan -> occurrence binding -> CF-2A
 * coordinator -> CF-2B2 secondary preparation, and stops at Ready. Evaluation/observation never play the
 * secondary, change any gain, or touch the primary player/MediaSession/EQ.
 *
 * CF-2C5: [executeBeginFade] is the ONLY audible entry point. It reduces a validated BeginFade and executes
 * exactly its StartSecondary (via [CrossfadeSecondaryPlayer.start]). The accompanying ApplyGains is
 * ApplyGains's outgoing gain is applied (CF-2C6) through an occurrence-owned [CrossfadePrimaryGainController]
 * over a narrow [PrimaryGainBackend] (this class holds no Player/MediaSession), only AFTER the secondary has
 * started; RestorePrimaryGain commands and [close] restore the primary to 1f. [applyReduction] still refuses
 * every other audible command (notably generic ApplyGains: continuous fades are a later slice). The historical class name is kept deliberately (a rename would obscure this change).
 *
 * Main-thread confined: every entry point (evaluation, secondary callbacks, close) must run on the
 * authoritative playback/main looper, where [snapshotProvider] may be called. This runtime owns the
 * [CrossfadeSecondaryPlayer] and is its single release owner.
 */
internal class CrossfadePreparationRuntime(
    private val snapshotProvider: () -> CrossfadeRuntimeSnapshot,
    backendFactory: () -> SecondaryPlayerBackend,
    mediaItemFactory: (Song) -> MediaItem = { it.toPlaybackMediaItem() },
    primaryGainBackend: PrimaryGainBackend = PrimaryGainBackend.Unavailable,
) {
    private val primaryGain = CrossfadePrimaryGainController(primaryGainBackend)

    var state: CrossfadeState = CrossfadeState.Idle
        private set

    /** Audible commands that must never reach this slice; recorded (and refused) so tests can observe them. */
    val rejectedAudibleCommands: List<CrossfadeCommand> get() = rejected
    private val rejected = mutableListOf<CrossfadeCommand>()

    private var closed = false

    // One-shot timing identity: occurrence key AND plan values, so a replaced plan (same key, different
    // duration/start) is a fresh window. Cleared whenever the state leaves Ready.
    private data class DueMark(val key: CrossfadeTransitionKey, val effectiveDurationMs: Long, val startAtPositionMs: Long)
    private var dueMark: DueMark? = null

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

    /**
     * CF-2C1: consumes a primary track-position observation for [key]'s Ready transition and reports, at most
     * once per exact key+plan, that the fade window was reached. Silent: it never begins a fade, never plays
     * the secondary and never touches gains; the state stays Ready. Ownership is revalidated against a fresh
     * snapshot first; a missed window or an unusable position cancels fail closed.
     */
    fun observePrimaryPosition(key: CrossfadeTransitionKey, positionMs: Long): FadeWindowObservation {
        if (closed) return FadeWindowObservation.Inactive
        val ready = state as? CrossfadeState.Ready ?: return FadeWindowObservation.Inactive
        if (ready.key != key) return FadeWindowObservation.Inactive // observation for a different/stale transition

        val snapshot = snapshotProvider()
        crossfadeOwnershipLossReason(snapshot, key)?.let {
            cancel(it, snapshot)
            return FadeWindowObservation.Cancelled(it)
        }
        val decision = decideFadeWindow(ready.startAtPositionMs, ready.effectiveDurationMs, positionMs)
        return when (decision) {
            FadeWindowDecision.Invalid -> {
                // Timing cannot be established: fail closed; native primary playback stays authoritative.
                cancel(CrossfadeCancelReason.PlanInvalidated, snapshot)
                FadeWindowObservation.Cancelled(CrossfadeCancelReason.PlanInvalidated)
            }
            FadeWindowDecision.Waiting -> FadeWindowObservation.Waiting
            FadeWindowDecision.Missed -> {
                cancel(CrossfadeCancelReason.MissedWindow, snapshot)
                FadeWindowObservation.Cancelled(CrossfadeCancelReason.MissedWindow)
            }
            is FadeWindowDecision.Due -> {
                val mark = DueMark(ready.key, ready.effectiveDurationMs, ready.startAtPositionMs)
                if (dueMark == mark) {
                    FadeWindowObservation.AlreadyReported
                } else {
                    dueMark = mark
                    FadeWindowObservation.Due(ready.key, ready.effectiveDurationMs, ready.startAtPositionMs, decision.latenessMs)
                }
            }
        }
    }

    /**
     * CF-2C4: builds the semantic [CrossfadeEvent.BeginFade] for a [due] observation, or null. Pure event
     * construction: it never reduces the event, never starts the secondary and leaves [state] Ready. Null (with
     * no mutation) for a closed runtime, a negative monotonic time, a malformed/out-of-tolerance Due, or a Due
     * that is not for the exact current Ready key+plan (a stale Due must not disturb a replacement plan). Only
     * lost live ownership cancels (existing reason, fresh snapshot), then returns null. Stateless: repeated calls
     * for a still-valid Due return an equal event; lifecycle idempotency belongs to the later executing slice.
     */
    fun beginFadeEvent(due: FadeWindowObservation.Due, nowElapsedRealtimeMs: Long): CrossfadeEvent.BeginFade? {
        if (closed) return null
        if (nowElapsedRealtimeMs < 0L) return null
        val ready = state as? CrossfadeState.Ready ?: return null
        if (ready.key != due.key ||
            ready.effectiveDurationMs != due.effectiveDurationMs ||
            ready.startAtPositionMs != due.startAtPositionMs
        ) return null
        if (!isAcceptableFadeLateness(due.latenessMs, due.effectiveDurationMs)) return null

        val snapshot = snapshotProvider()
        crossfadeOwnershipLossReason(snapshot, due.key)?.let {
            cancel(it, snapshot)
            return null
        }
        return CrossfadeEvent.BeginFade(due.key, nowElapsedRealtimeMs, due.latenessMs)
    }

    /**
     * CF-2C5: the single safe execution operation for a [due] observation. Runs the CF-2C4 bridge
     * ([beginFadeEvent]: plan, lateness, monotonic time and live-ownership validation), reduces the BeginFade
     * through the coordinator, makes the resulting Fading state visible FIRST, then starts the exact prepared
     * secondary at the coordinator-provided incoming gain. True only when that start succeeded and this
     * Fading transition is still the live owner. Any failure fails closed to Idle (when this call still owns
     * the state); a state changed re-entrantly during the start (error, supersession) is never overwritten.
     */
    fun executeBeginFade(due: FadeWindowObservation.Due, nowElapsedRealtimeMs: Long): Boolean {
        val event = beginFadeEvent(due, nowElapsedRealtimeMs) ?: return false
        val reduction = reduceCrossfade(state, event)
        val fading = reduction.state as? CrossfadeState.Fading ?: return false
        val start = reduction.commands.getOrNull(0) as? CrossfadeCommand.StartSecondary
        val gain = reduction.commands.getOrNull(1) as? CrossfadeCommand.ApplyGains
        if (reduction.commands.size != 2 || start == null || gain == null || start.key != fading.key || gain.key != fading.key) {
            Log.w(TAG, "unexpected BeginFade reduction commands; refusing: ${reduction.commands}")
            return false
        }

        state = fading // state first: a synchronous backend error must observe Fading
        dueMark = null
        val started = secondary.start(start.key, start.initialIncomingGain)
        if (state != fading) return false // re-entrantly cancelled/superseded: the newer state stands
        if (!started) {
            // Nothing is playing and the primary was never touched (no ownership claimed): nothing to restore.
            cancel(CrossfadeCancelReason.SecondaryError, snapshotProvider())
            return false
        }
        // Only now, with the secondary audibly running, lower the primary by the coordinator's outgoing gain.
        if (!primaryGain.apply(gain.key, gain.gains.outgoing)) {
            cancel(CrossfadeCancelReason.PrimaryGainError, snapshotProvider()) // restores (owner claimed) + abandons
            return false
        }
        return true
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
        dueMark = null
        // Restore the primary FIRST (the caller keeps the primary player alive until this returns); needs no snapshot.
        primaryGain.ownerKey?.let { if (!primaryGain.restore(it)) Log.w(TAG, "primary gain restore failed on close") }
        if (toCancel is CrossfadeState.Active) {
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
        if (state !is CrossfadeState.Ready) dueMark = null
        for (command in reduction.commands) {
            when (command) {
                is CrossfadeCommand.AbandonSecondary -> secondary.abandon(command.key)
                // Cleanup order from the coordinator: restore the primary (retains ownership on failure), then abandon.
                is CrossfadeCommand.RestorePrimaryGain -> {
                    if (!primaryGain.restore(command.key)) Log.w(TAG, "primary gain restore failed for $command")
                }
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
                is CrossfadeCommand.RequestHandoff -> {
                    rejected += command
                    Log.w(TAG, "refusing audible crossfade command in silent-preparation runtime: $command")
                    if (state is CrossfadeState.Active) {
                        val active = state as CrossfadeState.Active
                        state = CrossfadeState.Idle
                        // Same order as coordinator cancellation: restore a possibly lowered primary (a no-op
                        // for Armed/Ready, which own no gain; ownership is kept if it fails), then abandon.
                        if (!primaryGain.restore(active.key)) {
                            Log.w(TAG, "primary gain restore failed while refusing $command")
                        }
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
