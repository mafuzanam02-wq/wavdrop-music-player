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
    // CF-2L1: true once the authoritative AUTO transition to the key's target was observed. The primary may then legitimately
    // sit on the target occurrence and be BUFFERING while it repositions (isPlaying false), without losing ownership.
    naturalTransition: Boolean = false,
): CrossfadeCancelReason? = when {
    !snapshot.controllerConnected -> CrossfadeCancelReason.ControllerDisconnected
    snapshot.isExternalPlayback -> CrossfadeCancelReason.ExternalPlayback
    snapshot.playerQueueNeedsSync -> CrossfadeCancelReason.QueueBecameDirty
    !snapshot.isPlaying && !naturalTransition -> CrossfadeCancelReason.Pause
    // CF-2I2: EQ became enabled; an overlap would mix processed and unprocessed audio (defensive fallback to the service seam).
    snapshot.equalizerEnabled -> CrossfadeCancelReason.PlanInvalidated
    snapshot.queueGeneration != key.queueGeneration -> CrossfadeCancelReason.QueueMutation
    snapshot.currentPlaybackIndex != key.fromPlaybackIndex &&
        !(naturalTransition && snapshot.currentPlaybackIndex == key.toPlaybackIndex) -> CrossfadeCancelReason.ManualNavigation
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

/** Fading and HandoffPending own audible gain state; Armed/Ready are still plan-managed and silent. */
private fun CrossfadeState.Active.isAudible(): Boolean =
    this is CrossfadeState.Fading || this is CrossfadeState.HandoffPending

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

/** Result of [CrossfadePreparationRuntime.executeFadeTick]. */
internal sealed interface FadeTickExecutionResult {
    /** Closed, not Fading, a different key, or superseded re-entrantly during the tick: nothing (more) was done. */
    data object Inactive : FadeTickExecutionResult

    /** A gain pair was applied and the fade continues. */
    data object Applied : FadeTickExecutionResult

    /** The terminal gain pair (primary 0, secondary 1) was applied; the runtime now rests in HandoffPending. */
    data object HandoffPending : FadeTickExecutionResult

    /** The tick failed closed: primary restore and secondary abandon were run; the runtime is Idle. */
    data class Cancelled(val reason: CrossfadeCancelReason) : FadeTickExecutionResult
}

/** Why an explicit handoff execution failed closed (the coordinator transition is always HandoffFailed). */
internal sealed interface CrossfadeHandoffFailure {
    data object SecondarySnapshotUnavailable : CrossfadeHandoffFailure
    data class PrimaryReconciliationRejected(val reason: CrossfadePrimaryReconciliationRejection) : CrossfadeHandoffFailure
    data object PrimaryReconciliationException : CrossfadeHandoffFailure
    data object PrimaryGainRestoreFailed : CrossfadeHandoffFailure
    data object SecondaryAbandonFailed : CrossfadeHandoffFailure

    /** CF-2L2: a secondary gain write during the soft ownership transfer (or its abort) failed. */
    data object SecondaryGainWriteFailed : CrossfadeHandoffFailure

    /** CF-2L2: a primary gain write during the soft ownership transfer (or its abort) failed. */
    data object PrimaryGainWriteFailed : CrossfadeHandoffFailure

    /** CF-2L3: the coordinator reduced success but the runtime was not fully settled (best-effort cleanup was attempted); never reported as Succeeded. */
    data object SettlementIncomplete : CrossfadeHandoffFailure
}

/** Result of [CrossfadePreparationRuntime.executeHandoff]. */
internal sealed interface CrossfadeHandoffExecutionResult {
    /** Closed, not HandoffPending, a different key, or superseded re-entrantly: nothing (more) was done. */
    data object Inactive : CrossfadeHandoffExecutionResult

    /** Primary repositioned and audible, secondary abandoned, coordinator closed via HandoffSucceeded (Idle). */
    data object Succeeded : CrossfadeHandoffExecutionResult

    /** Live ownership was lost before the handoff began; cancelled with the exact existing reason. */
    data class Cancelled(val reason: CrossfadeCancelReason) : CrossfadeHandoffExecutionResult

    /** Failed closed through the coordinator's HandoffFailed (restore + abandon, Idle). */
    data class Failed(val failure: CrossfadeHandoffFailure) : CrossfadeHandoffExecutionResult

    /**
     * CF-2L1 natural handoff only: the runtime stays HandoffPending, the secondary stays audible at gain 1 and the primary
     * stays at gain 0 while [waitingFor] has not happened. Nothing was reset, restored or abandoned.
     */
    data class Awaiting(val waitingFor: CrossfadeHandoffWait) : CrossfadeHandoffExecutionResult
}

/**
 * CF-2B3: silent preparation orchestration. Wires snapshot -> CF-1 plan -> occurrence binding -> CF-2A
 * coordinator -> CF-2B2 secondary preparation, and stops at Ready. Evaluation/observation never play the
 * secondary, change any gain, or touch the primary player/MediaSession/EQ.
 *
 * CF-2C5/2C7B: [executeBeginFade] and [executeFadeTick] are the ONLY audible entry points. It reduces a validated BeginFade and executes
 * exactly its StartSecondary (via [CrossfadeSecondaryPlayer.start]). The accompanying ApplyGains is
 * ApplyGains's outgoing gain is applied (CF-2C6) through an occurrence-owned [CrossfadePrimaryGainController]
 * over a narrow [PrimaryGainBackend] (this class holds no Player/MediaSession), only AFTER the secondary has
 * started; RestorePrimaryGain commands and [close] restore the primary to 1f. [applyReduction] still refuses
 * every other audible command (generic ApplyGains and RequestHandoff stay refused; only the two dedicated paths execute their exact validated shapes). The historical class name is kept deliberately (a rename would obscure this change).
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
    private val primaryReconciler: CrossfadePrimaryReconciler = CrossfadePrimaryReconciler.Unavailable,
    private val primaryAudioSessionId: () -> Int = { 0 },
    private val audioSessionObserver: CrossfadeAudioSessionObserver = CrossfadeAudioSessionObserver.NoOp,
    private val naturalHandoff: CrossfadeNaturalHandoffSeams? = null,
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

    // CF-2L1 natural-AUTO handoff bookkeeping. Only meaningful while Fading/HandoffPending (cleared otherwise), one transition at a time.
    private var naturalObservedKey: CrossfadeTransitionKey? = null
    private var handoffSeekLeadMs = 0L
    private var handoffSeekTargetMs: Long? = null

    // CF-2L2: monotonic start of the internal soft ownership transfer (secondary B -> primary B); null while not transferring.
    private var transferStartMs: Long? = null

    // CF-2L3: same-item reconciliation seeks issued for the current transition (diagnostic accounting; cleared with the rest).
    private var naturalReconcileRequests = 0

    // CF-2L4: one bounded monotonic projected position clock per stream (transition-owned, cleared with the rest).
    private val primaryClock = NaturalHandoffPositionClock()
    private val secondaryClock = NaturalHandoffPositionClock()
    private var primaryDecision = PositionSampleDecision.Stale
    private var secondaryDecision = PositionSampleDecision.Stale
    private var primaryExpiredLogged = false
    private var secondaryExpiredLogged = false
    private var primaryNotAdvancingLogged = false
    private var secondaryNotAdvancingLogged = false

    /** CF-2L1: true when production wiring supplied the natural-AUTO handoff seams (a pending handoff then waits on real state). */
    val usesNaturalHandoff: Boolean get() = naturalHandoff != null

    /** CF-2L2: true while the short soft ownership-transfer envelope is running (the driver then evaluates at a finer cadence). */
    val isNaturalTransferInProgress: Boolean get() = transferStartMs != null

    private fun ownershipLoss(snapshot: CrossfadeRuntimeSnapshot, key: CrossfadeTransitionKey): CrossfadeCancelReason? =
        crossfadeOwnershipLossReason(snapshot, key, naturalTransition = naturalObservedKey == key)

    private fun clearNaturalHandoff() {
        naturalObservedKey = null
        handoffSeekLeadMs = 0L
        handoffSeekTargetMs = null
        transferStartMs = null
        naturalReconcileRequests = 0
        primaryClock.invalidate()
        secondaryClock.invalidate()
        primaryDecision = PositionSampleDecision.Stale
        secondaryDecision = PositionSampleDecision.Stale
        primaryExpiredLogged = false
        secondaryExpiredLogged = false
        primaryNotAdvancingLogged = false
        secondaryNotAdvancingLogged = false
    }

    /** CF-2L3: read-only proof of what this runtime still holds (see [isCrossfadeFullySettled]). Never exposes a player. */
    fun settlementSnapshot(): CrossfadeSettlementSnapshot = CrossfadeSettlementSnapshot(
        state = state,
        hasNaturalObservedKey = naturalObservedKey != null,
        hasSeekTarget = handoffSeekTargetMs != null,
        seekLeadMs = handoffSeekLeadMs,
        transferActive = transferStartMs != null,
        primaryGainOwnerKey = primaryGain.ownerKey,
        secondaryOwned = secondary.hasActiveOwnership,
        secondaryStarted = secondary.isStarted,
        closed = closed,
        reconcileRequestCount = naturalReconcileRequests,
        positionClocksActive = primaryClock.hasAnchor || secondaryClock.hasAnchor,
    )

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

        // CF-2C7C: audible transitions are lifecycle-managed, never re-planned. Only live ownership and an
        // explicit OFF can end them; either cancels and returns (no same-call re-arm). Otherwise the exact
        // active state is retained, so enabled duration / current-duration changes wait for the next transition.
        (state as? CrossfadeState.Active)?.takeIf { it.isAudible() }?.let { audible ->
            val lost = ownershipLoss(snapshot, audible.key)
            when {
                lost != null -> cancel(lost, snapshot)
                !CrossfadeRules.isEnabled(configuredDurationMs) -> cancel(CrossfadeCancelReason.ConfigurationDisabled, snapshot)
            }
            return
        }

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

    /**
     * CF-2C7B: executes one caller-supplied [CrossfadeEvent.FadeTick] for [key]. Eligible only while the runtime
     * is open and Fading for that exact key; anything else is [FadeTickExecutionResult.Inactive] with no mutation
     * and no gain write. Live ownership is revalidated against a fresh snapshot BEFORE the coordinator reduces
     * the tick (loss cancels with its reason and applies no gain). The coordinator owns all fade mathematics:
     * its [CrossfadeCommand.ApplyGains] pair is applied verbatim, secondary incoming FIRST, then primary
     * outgoing. The reduced state is made visible before either backend runs, and after each external effect a
     * state changed re-entrantly is never overwritten (the old tick simply stops). A failed gain write cancels
     * fail-closed (restore primary, abandon secondary). The terminal tick leaves [CrossfadeState.HandoffPending]
     * with final gains applied; its RequestHandoff is recognised here but NOT executed (the handoff is a later
     * slice). There is no timer: the caller supplies monotonic time.
     */
    fun executeFadeTick(key: CrossfadeTransitionKey, nowElapsedRealtimeMs: Long): FadeTickExecutionResult {
        if (closed) return FadeTickExecutionResult.Inactive
        val fading = state as? CrossfadeState.Fading ?: return FadeTickExecutionResult.Inactive
        if (fading.key != key) return FadeTickExecutionResult.Inactive

        val snapshot = snapshotProvider()
        ownershipLoss(snapshot, key)?.let {
            cancel(it, snapshot)
            return FadeTickExecutionResult.Cancelled(it)
        }

        val reduction = reduceCrossfade(fading, CrossfadeEvent.FadeTick(key, nowElapsedRealtimeMs))
        val clockReason = reduction.cancelReason
        if (clockReason != null) {
            // Coordinator-owned cleanup (RestorePrimaryGain, AbandonSecondary); no gain is applied.
            applyReduction(reduction, snapshot)
            return FadeTickExecutionResult.Cancelled(clockReason)
        }
        val target = reduction.state
        val gain = reduction.commands.getOrNull(0) as? CrossfadeCommand.ApplyGains
        val terminal = target is CrossfadeState.HandoffPending
        val shapeOk = gain != null && gain.key == key && when (target) {
            is CrossfadeState.Fading -> target == fading && reduction.commands.size == 1
            is CrossfadeState.HandoffPending ->
                target.key == key && reduction.commands.size == 2 &&
                    (reduction.commands[1] as? CrossfadeCommand.RequestHandoff)?.key == key
            else -> false
        }
        if (!shapeOk || gain == null) {
            Log.w(TAG, "unexpected FadeTick reduction; failing closed: ${reduction.commands}")
            cancel(CrossfadeCancelReason.PlanInvalidated, snapshot)
            return FadeTickExecutionResult.Cancelled(CrossfadeCancelReason.PlanInvalidated)
        }

        state = target // state first: reentrant work must observe the new (possibly terminal) state
        if (!secondary.setGain(key, gain.gains.incoming)) {
            if (state != target) return FadeTickExecutionResult.Inactive
            cancel(CrossfadeCancelReason.SecondaryError, snapshotProvider())
            return FadeTickExecutionResult.Cancelled(CrossfadeCancelReason.SecondaryError)
        }
        if (state != target) return FadeTickExecutionResult.Inactive // changed re-entrantly: never apply the old pair
        if (!primaryGain.apply(key, gain.gains.outgoing)) {
            if (state != target) return FadeTickExecutionResult.Inactive
            cancel(CrossfadeCancelReason.PrimaryGainError, snapshotProvider())
            return FadeTickExecutionResult.Cancelled(CrossfadeCancelReason.PrimaryGainError)
        }
        if (state != target) return FadeTickExecutionResult.Inactive
        return if (terminal) FadeTickExecutionResult.HandoffPending else FadeTickExecutionResult.Applied
    }

    /**
     * CF-2D3: explicit, exact-key handoff of a terminal fade. Eligible only while open and in HandoffPending for
     * [key] (else Inactive, no effects). Live ownership is revalidated first (loss cancels with its exact reason
     * and nothing else runs). Then: secondary physical snapshot -> primary reconciliation (snapshot forwarded
     * unchanged) -> primary gain restored to 1f -> secondary abandoned -> coordinator HandoffSucceeded (Idle). The
     * primary is restored BEFORE the secondary is silenced so the only audible source is never cut first. Any
     * failure drives the coordinator's HandoffFailed (restore + abandon, Idle); a primary seek already issued is
     * never rolled back. After every external effect a state changed re-entrantly wins (Inactive). Generic
     * RequestHandoff stays refused; nothing calls this automatically; no stats or persistence happen here.
     */
    fun executeHandoff(key: CrossfadeTransitionKey): CrossfadeHandoffExecutionResult {
        if (closed) return CrossfadeHandoffExecutionResult.Inactive
        val pending = state as? CrossfadeState.HandoffPending ?: return CrossfadeHandoffExecutionResult.Inactive
        if (pending.key != key) return CrossfadeHandoffExecutionResult.Inactive

        val snapshot = snapshotProvider()
        ownershipLoss(snapshot, key)?.let {
            cancel(it, snapshot)
            return CrossfadeHandoffExecutionResult.Cancelled(it)
        }
        if (naturalHandoff != null) return executeNaturalHandoff(pending, snapshot, naturalHandoff)

        val handoff = secondary.handoffSnapshot(key)
        if (state != pending) return CrossfadeHandoffExecutionResult.Inactive
        if (handoff == null) return failHandoff(pending, snapshot, CrossfadeHandoffFailure.SecondarySnapshotUnavailable)

        val reconciliation = try {
            primaryReconciler.reconcile(key, handoff)
        } catch (e: Exception) {
            Log.w(TAG, "primary handoff reconciliation threw", e)
            if (state != pending) return CrossfadeHandoffExecutionResult.Inactive
            return failHandoff(pending, snapshot, CrossfadeHandoffFailure.PrimaryReconciliationException)
        }
        if (state != pending) return CrossfadeHandoffExecutionResult.Inactive
        if (reconciliation is CrossfadePrimaryReconciliationResult.Rejected) {
            return failHandoff(pending, snapshot, CrossfadeHandoffFailure.PrimaryReconciliationRejected(reconciliation.reason))
        }

        return completeHandoff(pending, snapshot)
    }

    /** Primary restored BEFORE the secondary is silenced, then the coordinator success (no cleanup commands). */
    private fun completeHandoff(
        pending: CrossfadeState.HandoffPending,
        snapshot: CrossfadeRuntimeSnapshot,
    ): CrossfadeHandoffExecutionResult {
        val key = pending.key
        val restored = primaryGain.restore(key)
        if (state != pending) return CrossfadeHandoffExecutionResult.Inactive
        if (!restored) return failHandoff(pending, snapshot, CrossfadeHandoffFailure.PrimaryGainRestoreFailed)

        val abandoned = secondary.abandon(key)
        if (state != pending) return CrossfadeHandoffExecutionResult.Inactive
        if (!abandoned) return failHandoff(pending, snapshot, CrossfadeHandoffFailure.SecondaryAbandonFailed)

        // Physical transfer is complete: the coordinator success reduction carries no cleanup commands.
        applyReduction(reduceCrossfade(state, CrossfadeEvent.HandoffSucceeded(key)), snapshot)
        return settleAfterSuccess(key, naturalHandoff)
    }

    /**
     * CF-2L3: once HandoffSucceeded is reduced the old transition is DEAD. All natural-handoff bookkeeping is cleared explicitly
     * here (not only as an incidental side effect of the reduction) and the full settlement invariant is verified BEFORE the
     * caller can schedule anything. If anything survives, a best-effort cleanup runs and the result is NOT Succeeded.
     */
    private fun settleAfterSuccess(key: CrossfadeTransitionKey, seams: CrossfadeNaturalHandoffSeams?): CrossfadeHandoffExecutionResult {
        clearNaturalHandoff()
        var settlement = settlementSnapshot()
        if (!isCrossfadeFullySettled(settlement)) {
            primaryGain.ownerKey?.let { if (!primaryGain.restore(it)) Log.w(TAG, "primary gain restore failed during settlement") }
            secondary.currentKey?.let { secondary.abandon(it) }
            settlement = settlementSnapshot()
            if (seams != null) logTransfer(seams, settledLine(key, settlement))
            return CrossfadeHandoffExecutionResult.Failed(CrossfadeHandoffFailure.SettlementIncomplete)
        }
        if (seams != null) logTransfer(seams, settledLine(key, settlement))
        return CrossfadeHandoffExecutionResult.Succeeded
    }

    private fun settledLine(key: CrossfadeTransitionKey, s: CrossfadeSettlementSnapshot): String =
        "HANDOFF_SETTLED key=$key state=${crossfadeStateName(s.state)} transferActive=${s.transferActive} " +
            "seekTargetPresent=${s.hasSeekTarget} seekLeadMs=${s.seekLeadMs} primaryGainOwned=${s.primaryGainOwnerKey != null} " +
            "secondaryOwned=${s.secondaryOwned} positionClocksActive=${s.positionClocksActive} settled=${isCrossfadeFullySettled(s)}"

    /**
     * CF-2L1: an authoritative primary media-item transition fact. Qualifies only while Fading or HandoffPending, only for
     * Media3's genuine AUTO reason, only onto the exact [CrossfadeTransitionKey.toPlaybackIndex], and only while live
     * ownership (generation, clean queue, controller, bounds) still holds; anything else is inert (a wrong index or a stale
     * fact changes nothing, and real divergence is still cancelled by the ownership rules). Accepted facts are remembered for
     * this one exact key only (a bounded single observation: it may arrive just before the terminal tick) and, when already
     * HandoffPending, immediately re-evaluate the takeover. No stats, gain or secondary effect happens here by itself.
     */
    fun onPrimaryNaturalTransition(observation: CrossfadeNaturalTransitionObservation) {
        if (closed) return
        val active = state as? CrossfadeState.Active ?: return
        if (!active.isAudible()) return
        if (!isNaturalAutoTransition(observation.reason)) return
        val key = active.key
        if (observation.mediaItemIndex != key.toPlaybackIndex) return
        val snapshot = snapshotProvider()
        if (state != active) return
        crossfadeOwnershipLossReason(snapshot, key, naturalTransition = true)?.let {
            cancel(it, snapshot)
            return
        }
        if (key.toPlaybackIndex !in snapshot.playbackQueue.indices) {
            cancel(CrossfadeCancelReason.QueueMutation, snapshot)
            return
        }
        naturalObservedKey = key
        if (active is CrossfadeState.HandoffPending) executeHandoff(key)
    }

    /** CF-2L1: re-evaluates a pending natural handoff after an authoritative primary state change. Inert otherwise. */
    fun advancePendingHandoff() {
        if (closed) return
        val pending = state as? CrossfadeState.HandoffPending ?: return
        executeHandoff(pending.key)
    }

    /**
     * CF-2L1: the natural-AUTO terminal handoff. The terminal fade left primary gain 0 and secondary gain 1; the secondary
     * stays alive and audible until the primary is genuinely usable on the target. Waits (no effects) for: the exact AUTO
     * transition; the primary physically on the target occurrence in READY; and, after a same-item reposition onto a FRESH
     * secondary position, trustworthy evidence that the primary is within [NATURAL_TRANSFER_ENTRY_TOLERANCE_MS]. Only then (CF-2L2)
     * does the short internal soft ownership transfer run (see [beginTransfer]/[continueTransfer]/[completeTransfer]); the
     * secondary is silenced before it is abandoned, and success follows only the completed transfer.
     *
     * CF-2L4: continuity is judged on PROJECTED positions (see [NaturalHandoffPositionClock]), one clock per stream fed from ONE shared
     * monotonic reading per evaluation, because raw `currentPosition` is frozen between sparse updates and comparing raw samples
     * manufactured false divergences. Readiness is read from real player facts, never from a returned command or a fixed delay.
     */
    private fun executeNaturalHandoff(
        pending: CrossfadeState.HandoffPending,
        snapshot: CrossfadeRuntimeSnapshot,
        seams: CrossfadeNaturalHandoffSeams,
    ): CrossfadeHandoffExecutionResult {
        val key = pending.key
        if (naturalObservedKey != key) return CrossfadeHandoffExecutionResult.Awaiting(CrossfadeHandoffWait.NaturalTransition)
        val now = seams.nowElapsedRealtimeMs() // ONE shared monotonic reading for this whole evaluation
        val facts = try {
            seams.primaryFacts()
        } catch (e: Exception) {
            Log.w(TAG, "primary takeover facts failed", e)
            null
        }
        if (facts == null || facts.physicalIndex != key.toPlaybackIndex || !facts.isReady) {
            primaryClock.invalidate() // a primary that is not a valid READY target has no meaningful position
            // A primary that stops being a valid READY target mid-transfer must not keep gaining: return authority to the secondary.
            if (transferStartMs != null) return abortTransfer(pending, snapshot, seams, "primary-not-ready", "", CrossfadeHandoffWait.PrimaryReady)
            return CrossfadeHandoffExecutionResult.Awaiting(CrossfadeHandoffWait.PrimaryReady)
        }
        val fresh = secondary.handoffSnapshot(key) // FRESH: the terminal snapshot is stale once the secondary keeps playing
        if (state != pending) return CrossfadeHandoffExecutionResult.Inactive
        if (fresh == null) return failHandoff(pending, snapshot, CrossfadeHandoffFailure.SecondarySnapshotUnavailable)
        observePositions(seams, facts, fresh, now)
        if (transferStartMs != null) return continueTransfer(pending, snapshot, seams, facts, fresh, now)
        // CF-2L4 correction: a stream that is not currently advancing (BUFFERING, paused, suppressed) has no projection to trust, so
        // nothing is decided on it: no transfer entry and no seek on its frozen raw position (a pending reposition still reports itself).
        if (!facts.isAdvancing || !fresh.isAdvancing) {
            val landing = handoffSeekTargetMs != null && facts.positionMs < (handoffSeekTargetMs ?: 0L) - NATURAL_TAKEOVER_MAX_LAG_MS
            return CrossfadeHandoffExecutionResult.Awaiting(if (landing) CrossfadeHandoffWait.PrimarySeek else CrossfadeHandoffWait.PositionConfidence)
        }

        // Continuity is decided on projected positions when both clocks are trustworthy; otherwise only a GROSS raw mismatch (beyond
        // anything sampling granularity can explain) may reposition, and anything finer waits for credible evidence (no seek).
        val confident = primaryClock.isConfident(now) && secondaryClock.isConfident(now)
        val primaryPos = if (confident) primaryClock.projectedMs(now) ?: facts.positionMs else facts.positionMs
        val secondaryPos = if (confident) secondaryClock.projectedMs(now) ?: fresh.positionMs else fresh.positionMs
        val decision = decideNaturalTakeover(facts.copy(positionMs = primaryPos), secondaryPos, handoffSeekTargetMs, handoffSeekLeadMs)
        if (!confident) {
            val grossRaw = kotlin.math.abs(fresh.positionMs - facts.positionMs) > NATURAL_TAKEOVER_MAX_LAG_MS
            if (decision == NaturalTakeoverDecision.TakeOver || (decision is NaturalTakeoverDecision.SeekPrimary && !grossRaw)) {
                return CrossfadeHandoffExecutionResult.Awaiting(CrossfadeHandoffWait.PositionConfidence)
            }
        }
        when (decision) {
            NaturalTakeoverDecision.AwaitSeekLanding ->
                return CrossfadeHandoffExecutionResult.Awaiting(CrossfadeHandoffWait.PrimarySeek)
            is NaturalTakeoverDecision.SeekPrimary -> {
                val previousTarget = handoffSeekTargetMs
                val previousLead = handoffSeekLeadMs
                val seekTo = decision.positionMs.coerceAtMost(fresh.durationMs)
                handoffSeekTargetMs = seekTo
                handoffSeekLeadMs = decision.leadMs
                naturalReconcileRequests++
                logTransfer(seams) {
                    "RECONCILE_REQUEST key=$key n=$naturalReconcileRequests stage=${if (previousTarget == null) "initial" else "reseek"} " +
                        "requestedPositionMs=$seekTo leadMs=${decision.leadMs} confident=$confident ${positionsLine(facts, fresh, now)}"
                }
                val reconciliation = try {
                    seams.reconciler.reconcile(key, fresh.copy(positionMs = seekTo))
                } catch (e: Exception) {
                    Log.w(TAG, "post-AUTO primary reconciliation threw", e)
                    if (state != pending) return CrossfadeHandoffExecutionResult.Inactive
                    return failHandoff(pending, snapshot, CrossfadeHandoffFailure.PrimaryReconciliationException)
                }
                if (state != pending) return CrossfadeHandoffExecutionResult.Inactive
                logTransfer(seams) {
                    "RECONCILE_RESULT key=$key n=$naturalReconcileRequests " + when (reconciliation) {
                        is CrossfadePrimaryReconciliationResult.Rejected -> "rejected reason=${reconciliation.reason}"
                        else -> "succeeded"
                    }
                }
                if (reconciliation is CrossfadePrimaryReconciliationResult.Rejected) {
                    val notPropagatedYet = reconciliation.reason == CrossfadePrimaryReconciliationRejection.PhysicalIndexMismatch ||
                        reconciliation.reason == CrossfadePrimaryReconciliationRejection.CurrentOccurrenceMismatch
                    if (notPropagatedYet) {
                        // The controller has not caught up with the player's AUTO transition: nothing was sought; retry.
                        handoffSeekTargetMs = previousTarget
                        handoffSeekLeadMs = previousLead
                        return CrossfadeHandoffExecutionResult.Awaiting(CrossfadeHandoffWait.PrimaryReady)
                    }
                    return failHandoff(pending, snapshot, CrossfadeHandoffFailure.PrimaryReconciliationRejected(reconciliation.reason))
                }
                // A primary seek is a REAL discontinuity: never project the primary through it. The secondary keeps its clock.
                primaryClock.invalidate()
                primaryExpiredLogged = false
                return CrossfadeHandoffExecutionResult.Awaiting(CrossfadeHandoffWait.PrimarySeek)
            }
            NaturalTakeoverDecision.TakeOver -> return beginTransfer(seams, facts, fresh, now)
        }
    }

    /**
     * CF-2L4: feeds both raw observations into their projected clocks with the SAME [now], and emits the bounded projection events.
     * Never mutates anything but the two clocks and their log flags.
     */
    private fun observePositions(
        seams: CrossfadeNaturalHandoffSeams,
        facts: PrimaryTakeoverFacts,
        fresh: SecondaryHandoffSnapshot,
        now: Long,
    ) {
        // A stream that is not advancing must never be projected through elapsed time: its clock is dropped (untrusted) and must
        // re-anchor and re-earn confidence from real physical movement once it advances again. Never mistaken for a seek.
        if (facts.isAdvancing) {
            primaryNotAdvancingLogged = false
            primaryDecision = primaryClock.observe(facts.positionMs, now, fresh.durationMs)
        } else {
            primaryDecision = PositionSampleDecision.Stale
            if (primaryClock.hasAnchor || !primaryNotAdvancingLogged) logNotAdvancing(seams, "primary", facts.positionMs, primaryNotAdvancingLogged)
            primaryNotAdvancingLogged = true
            primaryClock.invalidate()
        }
        if (fresh.isAdvancing) {
            secondaryNotAdvancingLogged = false
            secondaryDecision = secondaryClock.observe(fresh.positionMs, now, fresh.durationMs)
        } else {
            secondaryDecision = PositionSampleDecision.Stale
            if (secondaryClock.hasAnchor || !secondaryNotAdvancingLogged) logNotAdvancing(seams, "secondary", fresh.positionMs, secondaryNotAdvancingLogged)
            secondaryNotAdvancingLogged = true
            secondaryClock.invalidate()
        }
        if (facts.isAdvancing) logPositionDecision(seams, "primary", primaryDecision, facts.positionMs, primaryClock.projectedMs(now))
        if (fresh.isAdvancing) logPositionDecision(seams, "secondary", secondaryDecision, fresh.positionMs, secondaryClock.projectedMs(now))
        primaryExpiredLogged = logExpiry(seams, "primary", primaryClock, now, primaryExpiredLogged)
        secondaryExpiredLogged = logExpiry(seams, "secondary", secondaryClock, now, secondaryExpiredLogged)
    }

    private fun logNotAdvancing(seams: CrossfadeNaturalHandoffSeams, stream: String, rawMs: Long, alreadyLogged: Boolean) {
        if (!alreadyLogged) logTransfer(seams) { "POSITION_NOT_ADVANCING stream=$stream rawMs=$rawMs" }
    }

    private fun logPositionDecision(seams: CrossfadeNaturalHandoffSeams, stream: String, decision: PositionSampleDecision, raw: Long, projected: Long?) {
        // Bounded: anchors, calibrations and discontinuities always; routine stale/refresh samples only inside the short transfer window.
        val routine = decision == PositionSampleDecision.Stale || decision == PositionSampleDecision.Refreshed
        if (routine && transferStartMs == null) return
        val event = when (decision) {
            PositionSampleDecision.Anchored -> "POSITION_ANCHOR"
            PositionSampleDecision.Calibrated, PositionSampleDecision.Refreshed -> "POSITION_REFRESH"
            PositionSampleDecision.Stale -> "POSITION_STALE_RAW"
            PositionSampleDecision.ForwardDiscontinuity, PositionSampleDecision.BackwardDiscontinuity, PositionSampleDecision.Invalid -> "POSITION_DISCONTINUITY"
        }
        logTransfer(seams) { "$event stream=$stream decision=$decision rawMs=$raw projectedMs=${projected ?: "n/a"}" }
    }

    private fun logExpiry(
        seams: CrossfadeNaturalHandoffSeams,
        stream: String,
        clock: NaturalHandoffPositionClock,
        now: Long,
        alreadyLogged: Boolean,
    ): Boolean {
        val expired = clock.isExpired(now)
        if (expired && !alreadyLogged) logTransfer(seams) { "POSITION_EXPIRED stream=$stream ageMs=${clock.ageMs(now)}" }
        return expired
    }

    /**
     * CF-2L2: the primary is qualified (exact AUTO + occurrence + READY + fresh snapshot + both position clocks confident + within the
     * entry tolerance on PROJECTED positions). Starts the internal transfer envelope WITHOUT touching a gain or a player: primary is
     * still 0 and secondary still 1. Ticks follow only through later driver evaluations.
     */
    private fun beginTransfer(
        seams: CrossfadeNaturalHandoffSeams,
        facts: PrimaryTakeoverFacts,
        fresh: SecondaryHandoffSnapshot,
        now: Long,
    ): CrossfadeHandoffExecutionResult {
        transferStartMs = now
        logTransfer(seams) { "TRANSFER_START key=${(state as CrossfadeState.HandoffPending).key} ${positionsLine(facts, fresh, now)}" }
        return CrossfadeHandoffExecutionResult.Awaiting(CrossfadeHandoffWait.OwnershipTransfer)
    }

    /**
     * CF-2L2: one transfer evaluation (ownership, real primary facts and a fresh secondary snapshot were already revalidated by the
     * caller, and both clocks were fed at [now]). A raw discontinuity, an expired/untrusted projection, or a PROJECTED difference
     * beyond [NATURAL_TRANSFER_ABORT_TOLERANCE_MS] aborts back to the secondary; progress >= 1 completes (only with both clocks still
     * confident); otherwise the complementary equal-power pair is applied SECONDARY first, then PRIMARY only if that succeeded.
     */
    private fun continueTransfer(
        pending: CrossfadeState.HandoffPending,
        snapshot: CrossfadeRuntimeSnapshot,
        seams: CrossfadeNaturalHandoffSeams,
        facts: PrimaryTakeoverFacts,
        fresh: SecondaryHandoffSnapshot,
        now: Long,
    ): CrossfadeHandoffExecutionResult {
        val key = pending.key
        val start = transferStartMs ?: return CrossfadeHandoffExecutionResult.Awaiting(CrossfadeHandoffWait.PrimarySeek)
        val line = { positionsLine(facts, fresh, now) }
        // CF-2L4 correction: a stream that stopped advancing (BUFFERING, pause, suppression, stall) cannot carry the transfer: return to
        // the secondary, never complete on a projected timeline across a real stall.
        if (!fresh.isAdvancing) return abortTransfer(pending, snapshot, seams, "secondary_not_advancing", line(), CrossfadeHandoffWait.PositionConfidence)
        if (!facts.isAdvancing) return abortTransfer(pending, snapshot, seams, "primary_not_advancing", line(), CrossfadeHandoffWait.PositionConfidence)
        if (primaryDecision.isDiscontinuity || secondaryDecision.isDiscontinuity) {
            val reason = if (primaryDecision == PositionSampleDecision.Invalid || secondaryDecision == PositionSampleDecision.Invalid) "position_invalid" else "raw_discontinuity"
            return abortTransfer(pending, snapshot, seams, reason, line(), CrossfadeHandoffWait.PositionConfidence)
        }
        val projectedPrimary = primaryClock.projectedMs(now)
        val projectedSecondary = secondaryClock.projectedMs(now)
        if (!primaryClock.isConfident(now) || !secondaryClock.isConfident(now) || projectedPrimary == null || projectedSecondary == null) {
            return abortTransfer(pending, snapshot, seams, "position_projection_expired", line(), CrossfadeHandoffWait.PositionConfidence)
        }
        val projectedDelta = projectedSecondary - projectedPrimary
        if (kotlin.math.abs(projectedDelta) > NATURAL_TRANSFER_ABORT_TOLERANCE_MS) {
            return abortTransfer(pending, snapshot, seams, "projected_divergence", line(), CrossfadeHandoffWait.PrimarySeek)
        }
        val progress = naturalTransferProgress(start, now)
        if (progress >= 1f) return completeTransfer(pending, snapshot, seams, facts, fresh, now)

        val gains = naturalTransferGains(progress)
        if (!secondary.setGain(key, gains.secondary)) {
            if (state != pending) return CrossfadeHandoffExecutionResult.Inactive
            return failHandoff(pending, snapshot, CrossfadeHandoffFailure.SecondaryGainWriteFailed)
        }
        if (state != pending) return CrossfadeHandoffExecutionResult.Inactive
        if (!primaryGain.apply(key, gains.primary)) {
            if (state != pending) return CrossfadeHandoffExecutionResult.Inactive
            return failHandoff(pending, snapshot, CrossfadeHandoffFailure.PrimaryGainWriteFailed)
        }
        if (state != pending) return CrossfadeHandoffExecutionResult.Inactive
        logTransfer(seams) {
            "TRANSFER_TICK progress=$progress primaryGain=${gains.primary} secondaryGain=${gains.secondary} ${positionsLine(facts, fresh, now)}"
        }
        return CrossfadeHandoffExecutionResult.Awaiting(CrossfadeHandoffWait.OwnershipTransfer)
    }

    /**
     * CF-2L2: final ownership change, only after the envelope ended and both position clocks are still confident. Order: secondary
     * silenced (gain exactly 0) -> primary restored to 1 (releases primary-gain ownership) -> writes verified -> secondary abandoned
     * (already silent) -> coordinator HandoffSucceeded. Never primary 1 together with an audible secondary being reset, and never an
     * intentional 0/0 gap.
     */
    private fun completeTransfer(
        pending: CrossfadeState.HandoffPending,
        snapshot: CrossfadeRuntimeSnapshot,
        seams: CrossfadeNaturalHandoffSeams,
        facts: PrimaryTakeoverFacts,
        fresh: SecondaryHandoffSnapshot,
        now: Long,
    ): CrossfadeHandoffExecutionResult {
        val key = pending.key
        val silenced = secondary.setGain(key, 0f)
        if (state != pending) return CrossfadeHandoffExecutionResult.Inactive
        if (!silenced) return failHandoff(pending, snapshot, CrossfadeHandoffFailure.SecondaryGainWriteFailed)
        val restored = primaryGain.restore(key)
        if (state != pending) return CrossfadeHandoffExecutionResult.Inactive
        if (!restored) return failHandoff(pending, snapshot, CrossfadeHandoffFailure.PrimaryGainRestoreFailed)
        logTransfer(seams) { "TRANSFER_COMPLETE ${positionsLine(facts, fresh, now)}" }
        val abandoned = secondary.abandon(key) // secondary is silent (gain 0) before it is reset
        if (state != pending) return CrossfadeHandoffExecutionResult.Inactive
        if (!abandoned) return failHandoff(pending, snapshot, CrossfadeHandoffFailure.SecondaryAbandonFailed)
        applyReduction(reduceCrossfade(state, CrossfadeEvent.HandoffSucceeded(key)), snapshot)
        return settleAfterSuccess(key, seams)
    }

    /**
     * CF-2L2: leaves the transfer WITHOUT completing: the secondary is returned to full audible authority first, then the primary to
     * silence. The runtime stays HandoffPending (reconciliation resumes), nothing is abandoned and the audible B timeline is never
     * moved. [waitingFor] is what the aborted evaluation reports; [evidence] is the DEBUG positions line.
     */
    private fun abortTransfer(
        pending: CrossfadeState.HandoffPending,
        snapshot: CrossfadeRuntimeSnapshot,
        seams: CrossfadeNaturalHandoffSeams,
        reason: String,
        evidence: String,
        waitingFor: CrossfadeHandoffWait,
    ): CrossfadeHandoffExecutionResult {
        val key = pending.key
        transferStartMs = null
        logTransfer(seams) { "TRANSFER_ABORT reason=$reason $evidence" }
        val secondaryBack = secondary.setGain(key, 1f)
        if (state != pending) return CrossfadeHandoffExecutionResult.Inactive
        if (!secondaryBack) return failHandoff(pending, snapshot, CrossfadeHandoffFailure.SecondaryGainWriteFailed)
        val primaryBack = primaryGain.apply(key, 0f)
        if (state != pending) return CrossfadeHandoffExecutionResult.Inactive
        if (!primaryBack) return failHandoff(pending, snapshot, CrossfadeHandoffFailure.PrimaryGainWriteFailed)
        return CrossfadeHandoffExecutionResult.Awaiting(waitingFor)
    }

    /** DEBUG evidence line: RAW and PROJECTED positions, ages and the per-stream sample decisions (no titles, ids or paths). */
    private fun positionsLine(facts: PrimaryTakeoverFacts, fresh: SecondaryHandoffSnapshot, now: Long): String {
        val projectedPrimary = primaryClock.projectedMs(now)
        val projectedSecondary = secondaryClock.projectedMs(now)
        val projectedDelta = if (projectedPrimary != null && projectedSecondary != null) projectedSecondary - projectedPrimary else null
        return "primaryPositionMs=${facts.positionMs} secondaryPositionMs=${fresh.positionMs} deltaMs=${fresh.positionMs - facts.positionMs} " +
            "rawPrimaryMs=${facts.positionMs} rawSecondaryMs=${fresh.positionMs} projectedPrimaryMs=${projectedPrimary ?: "n/a"} " +
            "projectedSecondaryMs=${projectedSecondary ?: "n/a"} rawDeltaMs=${fresh.positionMs - facts.positionMs} " +
            "projectedDeltaMs=${projectedDelta ?: "n/a"} primaryProjectionAgeMs=${primaryClock.ageMs(now) ?: "n/a"} " +
            "secondaryProjectionAgeMs=${secondaryClock.ageMs(now) ?: "n/a"} primaryAdvancing=${facts.isAdvancing} secondaryAdvancing=${fresh.isAdvancing} " +
            "positionDecision=primary:$primaryDecision/secondary:$secondaryDecision"
    }

    private fun logTransfer(seams: CrossfadeNaturalHandoffSeams, message: String) {
        try {
            seams.transferLog?.invoke(message)
        } catch (e: Exception) {
            Log.w(TAG, "transfer log failed", e)
        }
    }

    /** Lazy variant: the message is only built when a DEBUG sink exists (nothing is allocated in release or without a sink). */
    private inline fun logTransfer(seams: CrossfadeNaturalHandoffSeams, message: () -> String) {
        val sink = seams.transferLog ?: return
        try {
            sink(message())
        } catch (e: Exception) {
            Log.w(TAG, "transfer log failed", e)
        }
    }

    private fun failHandoff(
        pending: CrossfadeState.HandoffPending,
        snapshot: CrossfadeRuntimeSnapshot,
        failure: CrossfadeHandoffFailure,
    ): CrossfadeHandoffExecutionResult {
        if (state != pending) return CrossfadeHandoffExecutionResult.Inactive
        applyReduction(reduceCrossfade(state, CrossfadeEvent.HandoffFailed(pending.key)), snapshot)
        return CrossfadeHandoffExecutionResult.Failed(failure)
    }

    /**
     * CF-2I1: pure, exact-key observation of the secondary's audio-session id. Null when closed, when [key] is not the
     * currently owned transition, or when the secondary has no valid session. Never mutates state and never cancels:
     * an absent session is observational, not a crossfade failure.
     */
    fun secondaryAudioSessionSnapshot(key: CrossfadeTransitionKey): SecondaryAudioSessionSnapshot? {
        if (closed) return null
        val active = state as? CrossfadeState.Active ?: return null
        if (active.key != key) return null
        return secondary.audioSessionSnapshot(key)
    }

    /**
     * CF-2L1 continuity: a Pause-style cancellation while B is already audible on the secondary must not leave the primary
     * (now the logical track) materially out of alignment with that audible timeline, or resume would rewind or skip B merely
     * because crossfade ownership ended. If the silent primary is materially behind OR ahead of the FRESH secondary position,
     * perform one best-effort same-item reconciliation (one seek; commands are ordered) to the fresh secondary position before
     * the normal restore/abandon cleanup. Only the Pause reason, only after the exact AUTO transition, only when materially out
     * of alignment in either direction; never throws.
     */
    private fun carryNaturalPositionBeforePauseCleanup(reason: CrossfadeCancelReason) {
        if (reason != CrossfadeCancelReason.Pause) return
        val seams = naturalHandoff ?: return
        val pending = state as? CrossfadeState.HandoffPending ?: return
        val key = pending.key
        if (naturalObservedKey != key) return
        try {
            val facts = seams.primaryFacts() ?: return
            if (facts.physicalIndex != key.toPlaybackIndex) return
            val fresh = secondary.handoffSnapshot(key) ?: return
            // Symmetric: a materially AHEAD silent primary would also skip B forward on resume. (Positions are non-negative.)
            if (kotlin.math.abs(fresh.positionMs - facts.positionMs) <= NATURAL_TAKEOVER_MAX_LAG_MS) return
            seams.reconciler.reconcile(key, fresh)
        } catch (e: Exception) {
            Log.w(TAG, "pause continuity carry failed", e)
        }
    }

    /** Synchronous cancellation from the owner of playback state. Harmless when idle. */
    fun cancel(reason: CrossfadeCancelReason) {
        if (closed) return
        carryNaturalPositionBeforePauseCleanup(reason)
        cancel(reason, snapshotProvider())
    }

    /** Idempotent teardown: cancels ownership, then releases the secondary. Nothing resurrects afterwards. */
    fun close() {
        if (closed) return
        val toCancel = state
        closed = true
        state = CrossfadeState.Idle
        dueMark = null
        clearNaturalHandoff()
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
        observeSecondarySession(key)
    }

    /** CF-2I1: one read-only observation per exact preparation, only if that key now owns Ready. Failures are swallowed. */
    private fun observeSecondarySession(key: CrossfadeTransitionKey) {
        if (closed || (state as? CrossfadeState.Ready)?.key != key) return
        try {
            audioSessionObserver.onSecondarySessionObserved(key, primaryAudioSessionId(), secondary.audioSessionSnapshot(key)?.audioSessionId)
        } catch (e: Exception) {
            Log.w(TAG, "audio-session observation failed", e)
        }
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
        if (state !is CrossfadeState.Fading && state !is CrossfadeState.HandoffPending) clearNaturalHandoff()
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
        CrossfadeUnavailableReason.EqualizerEnabled -> CrossfadeCancelReason.PlanInvalidated
        CrossfadeUnavailableReason.RepeatOne,
        CrossfadeUnavailableReason.NoNextOccurrence -> CrossfadeCancelReason.RepeatChanged
        else -> CrossfadeCancelReason.PlanInvalidated
    }

    private companion object {
        const val TAG = "WavdropCrossfade"
    }
}
