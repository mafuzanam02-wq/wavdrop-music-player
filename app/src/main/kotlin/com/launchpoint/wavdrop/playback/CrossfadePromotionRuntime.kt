package com.launchpoint.wavdrop.playback

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Player

/**
 * CF-2M5 timing: the one timing constant of the promotion path. It is independent of the retired CF-2L handoff tolerances
 * (80/150/200/350 ms), which no longer exist in source.
 */
internal object CrossfadePromotionTiming {
    /**
     * The fade must END at least this long before A's natural end. It absorbs main-looper tick jitter, the gap between the
     * player's reported position and what is audible (AudioTrack latency), and the stop/clear of the retiring player.
     * 500 ms is a conservative initial value chosen from architecture reasoning and the pure timing tests, NOT from device
     * evidence; CF-2M7 may adjust it.
     */
    const val END_MARGIN_MS = 500L
}

/**
 * Explicit overlap lifecycle. Only one overlap may own the engine; identity is the positional [CrossfadeTransitionKey] plus
 * explicit slot ids. There is no handoff state: B is authoritative from the moment of promotion.
 */
internal sealed interface PromotionOverlapState {
    data object Idle : PromotionOverlapState

    /** Promotion is being committed (synchronous); never observable across looper turns on the success path. */
    data class Starting(val key: CrossfadeTransitionKey, val outgoingSlotId: Int, val incomingSlotId: Int) : PromotionOverlapState

    /** B is the logical CURRENT and audible; A (retiring) fades out until [startedAtMs] + [durationMs]. */
    data class Overlap(
        val key: CrossfadeTransitionKey,
        val outgoingSlotId: Int,
        val incomingSlotId: Int,
        val startedAtMs: Long,
        val durationMs: Long,
    ) : PromotionOverlapState

    /** Fade complete: A is being stopped/cleared and recycled (synchronous). */
    data class Retiring(val key: CrossfadeTransitionKey, val outgoingSlotId: Int, val incomingSlotId: Int) : PromotionOverlapState
}

/**
 * CF-2M6: WHY an active overlap is being settled. After a promotion B is already the logical CURRENT, so every interruption
 * resolves the same way: CUT the retiring A, settle to B only, then let the requested logical action act on B. The one
 * exception is a focus DUCK, which is not an interruption at all (it never reaches this type: the gain composer attenuates both
 * audible players and the overlap continues). Nothing here ever restores A or seeks B.
 */
internal enum class PromotionInterruption(val label: String) {
    Pause("PAUSE"),
    Seek("SEEK"),
    Navigation("NAVIGATION"),
    RepeatChanged("REPEAT_CHANGED"),
    ShuffleChanged("SHUFFLE_CHANGED"),
    QueueMutated("QUEUE_MUTATED"),
    TransientFocusLoss("FOCUS_LOSS_TRANSIENT"),
    PermanentFocusLoss("FOCUS_LOSS_PERMANENT"),
    AudioBecomingNoisy("AUDIO_BECOMING_NOISY"),
    /** The current (incoming B) player reported an error: handled ONCE by the normal current-player recovery. */
    CurrentError("CURRENT_ERROR"),
    /** The current player reached IDLE/ENDED outside a normal transition (physical death or natural end). */
    CurrentTerminal("CURRENT_TERMINAL"),
    RetiringError("RETIRING_ERROR"),
    RetiringEnded("RETIRING_ENDED"),
    ConfigurationDisabled("CROSSFADE_OFF"),
    /** The CF-1 plan no longer holds; today the only producer is the Equalizer becoming enabled. */
    PlanInvalidated("PLAN_INVALIDATED"),
    ControllerDisconnected("CONTROLLER_DISCONNECTED"),
    Teardown("TEARDOWN"),
    /** A command reached the façade that no explicit hook announced (defense in depth; always settles first). */
    FacadeCommand("FACADE_COMMAND"),
    /** Any other shared cancellation reason: fail closed. */
    Other("OTHER");

    companion object {
        /** The ONE mapping from the shared cancellation family to the overlap policy. Total: no reason is ever ignored. */
        fun from(reason: CrossfadeCancelReason): PromotionInterruption = when (reason) {
            CrossfadeCancelReason.Pause -> Pause
            CrossfadeCancelReason.Seek -> Seek
            CrossfadeCancelReason.ManualNavigation -> Navigation
            CrossfadeCancelReason.RepeatChanged -> RepeatChanged
            CrossfadeCancelReason.ShuffleChanged -> ShuffleChanged
            CrossfadeCancelReason.QueueMutation, CrossfadeCancelReason.QueueBecameDirty -> QueueMutated
            CrossfadeCancelReason.PlaybackError -> CurrentError
            CrossfadeCancelReason.PrimaryPlaybackTerminated -> CurrentTerminal
            CrossfadeCancelReason.ConfigurationDisabled -> ConfigurationDisabled
            CrossfadeCancelReason.PlanInvalidated -> PlanInvalidated
            CrossfadeCancelReason.ControllerDisconnected -> ControllerDisconnected
            CrossfadeCancelReason.ServiceStopping -> Teardown
            else -> Other
        }
    }
}

/** Bounded record of the last promotion attempt, for tests and DEBUG diagnostics. */
internal sealed interface PromotionOutcome {
    data class Completed(val key: CrossfadeTransitionKey) : PromotionOutcome
    /** CF-2M6: the overlap was settled to B only by [interruption]; [retiringRecycled] is false when A had to be quarantined. */
    data class Interrupted(val key: CrossfadeTransitionKey, val interruption: PromotionInterruption, val retiringRecycled: Boolean) : PromotionOutcome
    data class Skipped(val key: CrossfadeTransitionKey, val reason: String) : PromotionOutcome
    data class Rejected(val key: CrossfadeTransitionKey, val reason: PromotionRejection) : PromotionOutcome
    data class Failed(val key: CrossfadeTransitionKey, val step: PromotionStep, val survivorSlotId: Int) : PromotionOutcome
    data class Aborted(val key: CrossfadeTransitionKey, val reason: String) : PromotionOutcome
}

/**
 * CF-2M5: owns ONLY the overlap lifecycle: when the fade window is due for the exact Ready transition, promote B through the
 * engine, run the equal-power fade from a monotonic clock, and retire A. It plans nothing (it reuses the CF-1 plan/key and
 * ownership rules), converts no Songs, owns no stats/session/focus policy, and never seeks, re-prepares or hands off B.
 *
 * CF-2M6 interaction/error policy: every interruption goes through [settleOverlap] with an explicit [PromotionInterruption];
 * A is cut and B (already the logical CURRENT) continues alone, then the requested action runs against B. A focus duck is the one
 * exception and never reaches it. Main-thread confined.
 */
internal class CrossfadePromotionRuntime<P : Player>(
    private val engine: PlayerEngine<P>,
    private val snapshotProvider: () -> CrossfadeRuntimeSnapshot,
    private val configuredDurationMsProvider: () -> Long,
    private val scheduler: CrossfadeTimingScheduler,
    private val clock: CrossfadeMonotonicClock,
    /** DEBUG-only concise diagnostics (no titles, paths or song ids); null in release. */
    private val debugLog: ((String) -> Unit)? = null,
) : CrossfadeCancelSink {

    var state: PromotionOverlapState = PromotionOverlapState.Idle
        private set
    var lastOutcome: PromotionOutcome? = null
        private set

    private var started = false
    private var closed = false
    private var generation = 0L

    // A transition whose window was missed or whose promotion failed is not retried by the poll (no storm).
    private var settledKey: CrossfadeTransitionKey? = null

    // CF-2M6: the engine reports the interruptions only it can see (logical focus loss / noisy / pause at its own play-state
    // boundary, a command reaching the façade, the retiring player's own end/error) through this one seam.
    init { engine.overlapInterruptionSink = { settleOverlap(it) } }

    // ── lifecycle ────────────────────────────────────────────────────────────────────────────────────────────────────────

    fun start() {
        if (closed || started) return
        started = true
        schedule(0L)
    }

    /** Idempotent. Invalidates the pending pulse. An overlap in flight is NOT left half-run: it is cut by [cancel]. */
    fun stop() {
        if (!started) return
        started = false
        invalidatePulse()
    }

    /** Idempotent teardown. Closed first, so settling the overlap schedules nothing and no stale tick can run afterwards. */
    fun close() {
        if (closed) return
        closed = true
        started = false
        settleOverlap(PromotionInterruption.Teardown)
        engine.overlapInterruptionSink = null
        invalidatePulse()
    }

    private fun invalidatePulse() {
        generation++
        scheduler.cancelAll()
    }

    private fun schedule(delayMs: Long) {
        val bound = generation
        scheduler.postDelayed(delayMs) { pulse(bound) }
    }

    private fun pulse(bound: Long) {
        if (bound != generation || !started || closed) return // stale callback of an earlier overlap or lifecycle
        try {
            if (state is PromotionOverlapState.Overlap) tick() else evaluate()
        } catch (e: Exception) {
            Log.w(TAG, "promotion pulse failed", e)
            abortOverlap("pulse failure")
        }
        if (bound == generation && started && !closed) {
            schedule(if (state is PromotionOverlapState.Overlap) CrossfadeCadence.FADE_TICK_INTERVAL_MS else CrossfadeCadence.PRE_FADE_POLL_INTERVAL_MS)
        }
    }

    // ── pre-fade: is the window due for the exact Ready key? ─────────────────────────────────────────────────────────────

    fun evaluate() {
        if (closed || state !is PromotionOverlapState.Idle) return
        val ready = engine.nextPreparation.state as? NextSlotState.Ready ?: return
        val key = ready.key
        if (settledKey == key) return
        val snapshot = snapshotProvider()
        if (crossfadeOwnershipLossReason(snapshot, key) != null) return // the preparation owner invalidates it
        val current = engine.currentPlayer
        val durationMs = current.duration
        if (durationMs == C.TIME_UNSET || durationMs <= 0L) return
        val plan = planCrossfadeFromRuntimeSnapshot(configuredDurationMsProvider(), snapshot, durationMs)
        if (plan !is CrossfadeTransitionPlan.Eligible) return
        if (bindCrossfadeTransition(snapshot, plan) != key) return

        val positionMs = current.currentPosition
        val plannedMs = plan.effectiveDurationMs
        val fadeStartMs = durationMs - plannedMs - CrossfadePromotionTiming.END_MARGIN_MS
        if (positionMs < fadeStartMs) return // not due yet

        // Due. A late observation shortens the fade so it still ends END_MARGIN before A's natural end.
        val effectiveMs = minOf(plannedMs, durationMs - positionMs - CrossfadePromotionTiming.END_MARGIN_MS)
        if (effectiveMs < CrossfadeRules.MIN_ENABLED_DURATION_MS) {
            settledKey = key
            lastOutcome = PromotionOutcome.Skipped(key, "insufficient tail")
            debug("PROMOTION_ABORT", key, "reason=insufficient_tail")
            engine.nextPreparation.invalidate(CrossfadeCancelReason.MissedWindow)
            return
        }
        begin(key, effectiveMs)
    }

    // ── promotion ────────────────────────────────────────────────────────────────────────────────────────────────────────

    private fun begin(key: CrossfadeTransitionKey, effectiveMs: Long) {
        debug("PROMOTION_BEGIN", key, "durationMs=$effectiveMs")
        when (val result = engine.promoteReadyNext(key)) {
            is PromotionStartResult.Rejected -> {
                settledKey = key
                lastOutcome = PromotionOutcome.Rejected(key, result.reason)
                debug("PROMOTION_ABORT", key, "reason=${result.reason}")
            }
            is PromotionStartResult.Failed -> {
                settledKey = key
                lastOutcome = PromotionOutcome.Failed(key, result.step, result.survivorSlotId)
                debug("PROMOTION_ABORT", key, "step=${result.step} survivor=${result.survivorSlotId}")
            }
            is PromotionStartResult.Promoted -> {
                state = PromotionOverlapState.Starting(key, result.outgoingSlotId, result.incomingSlotId)
                generation++ // any earlier scheduled pulse belongs to the previous phase
                scheduler.cancelAll()
                try {
                    engine.stripRetiringTail()
                    state = PromotionOverlapState.Overlap(key, result.outgoingSlotId, result.incomingSlotId, clock.nowMs(), effectiveMs)
                    debug("PROMOTION_COMMITTED", key, "out=${result.outgoingSlotId} in=${result.incomingSlotId}")
                } catch (e: Exception) {
                    Log.w(TAG, "retiring tail strip failed", e)
                    abortOverlap("tail strip failure")
                    return
                }
                if (started) schedule(CrossfadeCadence.FADE_TICK_INTERVAL_MS)
            }
        }
    }

    // ── overlap ──────────────────────────────────────────────────────────────────────────────────────────────────────────

    /** One fade step. Progress comes from the monotonic elapsed time, never from counting ticks. */
    fun tick() {
        val overlap = state as? PromotionOverlapState.Overlap ?: return
        val elapsedMs = (clock.nowMs() - overlap.startedAtMs).coerceAtLeast(0L)
        val progress = (elapsedMs.toDouble() / overlap.durationMs).coerceIn(0.0, 1.0).toFloat()
        try {
            if (progress >= 1f) {
                complete(overlap)
            } else {
                val gains = CrossfadeGainCurve.equalPower(progress)
                engine.setFadeGains(outgoing = gains.outgoing, incoming = gains.incoming)
                debug("OVERLAP_TICK", overlap.key, "progress=${"%.2f".format(progress)}", sampled = true)
            }
        } catch (e: Exception) {
            Log.w(TAG, "fade tick failed", e)
            abortOverlap("fade gain failure")
        }
    }

    private fun complete(overlap: PromotionOverlapState.Overlap) {
        state = PromotionOverlapState.Retiring(overlap.key, overlap.outgoingSlotId, overlap.incomingSlotId)
        debug("RETIRE_BEGIN", overlap.key, "out=${overlap.outgoingSlotId}")
        // Exact terminal gains: A = 0, B = 1, then stop/clear/recycle A (not released).
        engine.setFadeGains(outgoing = 0f, incoming = 1f)
        val recycled = engine.finishRetirement()
        settledKey = overlap.key
        lastOutcome = if (recycled) PromotionOutcome.Completed(overlap.key) else PromotionOutcome.Aborted(overlap.key, "retiring cleanup failed")
        state = PromotionOverlapState.Idle
        generation++
        debug("RETIRE_COMPLETE", overlap.key, "recycled=$recycled")
        if (started && !closed) schedule(CrossfadeCadence.PRE_FADE_POLL_INTERVAL_MS)
    }

    /**
     * The shared explicit-cancellation family ends here. Every `recoverCrossfadeFrom...` hook (pause, seek, navigation, repeat,
     * shuffle, queue mutations, controller disconnect, EQ enable, crossfade OFF, current error/terminal) carries a
     * [CrossfadeCancelReason] that [PromotionInterruption.from] maps to the overlap policy, so no external hook needs overlap
     * knowledge. Before an overlap exists this does nothing here (the NEXT preparation owner has its own invalidation).
     */
    override fun cancel(reason: CrossfadeCancelReason) {
        settleOverlap(PromotionInterruption.from(reason))
    }

    /**
     * CF-2M6: the ONE overlap-settlement seam. Cuts the retiring A and leaves B (already the logical CURRENT) as the only
     * authoritative player. It is physical cleanup only: it invalidates pending ticks, forces A = 0 and B = full fade, stops and
     * clears A (recycle, or quarantine if that fails), and returns to Idle. It performs NO seek/skip/pause/repeat/queue action and
     * emits no logical event, so the requested interaction runs afterwards against B exactly as it would without any overlap.
     * Idempotent: a second call (or one with no overlap) does nothing. Safe to re-enter (the state is Idle before cleanup).
     */
    fun settleOverlap(why: PromotionInterruption) {
        endOverlap(why.label) { key, recycled -> PromotionOutcome.Interrupted(key, why, recycled) }
    }

    /** An internal failure of the overlap's own machinery: the same cut, recorded as Aborted. */
    private fun abortOverlap(why: String) {
        endOverlap(why) { key, _ -> PromotionOutcome.Aborted(key, why) }
    }

    private fun endOverlap(label: String, outcome: (CrossfadeTransitionKey, Boolean) -> PromotionOutcome) {
        val ended = state
        val key = when (ended) {
            is PromotionOverlapState.Overlap -> ended.key
            is PromotionOverlapState.Starting -> ended.key
            is PromotionOverlapState.Retiring -> ended.key
            PromotionOverlapState.Idle -> null
        }
        // An engine-side retiring player with no runtime state would be inconsistent; still cut it rather than leave two audible.
        if (key == null && engine.retiringPlayer == null) return
        generation++
        scheduler.cancelAll()
        state = PromotionOverlapState.Idle
        val recycled = try {
            engine.finishRetirement() // B -> 1, A -> 0, A stopped and cleared; B (the logical CURRENT) is untouched
        } catch (e: Exception) {
            Log.w(TAG, "overlap settlement cleanup failed", e)
            false
        }
        if (key != null) {
            settledKey = key
            lastOutcome = outcome(key, recycled)
            val slots = when (ended) {
                is PromotionOverlapState.Overlap -> "current=${ended.incomingSlotId} retiring=${ended.outgoingSlotId}"
                is PromotionOverlapState.Starting -> "current=${ended.incomingSlotId} retiring=${ended.outgoingSlotId}"
                is PromotionOverlapState.Retiring -> "current=${ended.incomingSlotId} retiring=${ended.outgoingSlotId}"
                PromotionOverlapState.Idle -> ""
            }
            debug("OVERLAP_SETTLE", key, "reason=$label $slots recycled=$recycled")
        }
        if (started && !closed) schedule(CrossfadeCadence.PRE_FADE_POLL_INTERVAL_MS)
    }

    private fun debug(event: String, key: CrossfadeTransitionKey, extra: String, sampled: Boolean = false) {
        val log = debugLog ?: return
        // OVERLAP_TICK is sampled to a handful of lines per overlap rather than every 50 ms.
        if (sampled && tickLogGate++ % 10 != 0) return
        log("$event gen=${key.queueGeneration} from=${key.fromPlaybackIndex} to=${key.toPlaybackIndex} $extra")
    }

    private var tickLogGate = 0

    private companion object {
        const val TAG = "WavdropCrossfade"
    }
}

/**
 * CF-2M5: the promotion analogue of [applyNextSlotConfiguredDurationChange]: same pure decision. Disable cuts any overlap
 * (via the cancel hook) BEFORE stopping the poll.
 */
internal fun applyPromotionConfiguredDurationChange(
    previousDurationMs: Long?,
    newDurationMs: Long,
    runtime: CrossfadePromotionRuntime<*>?,
): CrossfadeActivationDecision {
    val decision = decideCrossfadeDriverActivation(previousDurationMs, newDurationMs)
    when (decision) {
        CrossfadeActivationDecision.Start -> runtime?.start()
        CrossfadeActivationDecision.Disable -> {
            runtime?.cancel(CrossfadeCancelReason.ConfigurationDisabled)
            runtime?.stop()
        }
        CrossfadeActivationDecision.NoOp,
        CrossfadeActivationDecision.UpdateOnly -> Unit
    }
    return decision
}
