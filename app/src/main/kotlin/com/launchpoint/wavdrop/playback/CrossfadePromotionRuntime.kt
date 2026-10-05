package com.launchpoint.wavdrop.playback

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Player

/**
 * CF-2M5 timing: the one new constant of the promotion path. It is NOT one of the retired CF-2L handoff tolerances
 * (80/150/200/350 ms) and nothing here reuses or reinterprets them.
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

/** Bounded record of the last promotion attempt, for tests and DEBUG diagnostics. */
internal sealed interface PromotionOutcome {
    data class Completed(val key: CrossfadeTransitionKey) : PromotionOutcome
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
 * The CF-2M6 interaction/error matrix is NOT here. The shared cancel hook ([cancel]) only fails closed: if it fires while an
 * overlap exists, A is cut immediately and B (already the logical CURRENT) continues alone. Main-thread confined.
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

    fun close() {
        if (closed) return
        abortOverlap("close")
        closed = true
        started = false
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
            schedule(if (state is PromotionOverlapState.Overlap) CrossfadeTimingDriver.FADE_TICK_INTERVAL_MS else CrossfadeTimingDriver.PRE_FADE_POLL_INTERVAL_MS)
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
                engine.retiringTerminalListener = { abortOverlap("retiring ended") }
                try {
                    engine.stripRetiringTail()
                    state = PromotionOverlapState.Overlap(key, result.outgoingSlotId, result.incomingSlotId, clock.nowMs(), effectiveMs)
                    debug("PROMOTION_COMMITTED", key, "out=${result.outgoingSlotId} in=${result.incomingSlotId}")
                } catch (e: Exception) {
                    Log.w(TAG, "retiring tail strip failed", e)
                    abortOverlap("tail strip failure")
                    return
                }
                if (started) schedule(CrossfadeTimingDriver.FADE_TICK_INTERVAL_MS)
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
        if (started && !closed) schedule(CrossfadeTimingDriver.PRE_FADE_POLL_INTERVAL_MS)
    }

    /**
     * Shared explicit-cancellation lifecycle point. M5 only fails closed: an existing overlap is cut so exactly one logical
     * CURRENT (B) remains. The per-interaction product policy (pause/seek/navigation/repeat/focus/noisy/error/physical death) is
     * CF-2M6; this contract is the placeholder it will refine.
     */
    override fun cancel(reason: CrossfadeCancelReason) {
        abortOverlap("cancel:$reason")
    }

    private fun abortOverlap(why: String) {
        val key = when (val s = state) {
            is PromotionOverlapState.Overlap -> s.key
            is PromotionOverlapState.Starting -> s.key
            is PromotionOverlapState.Retiring -> s.key
            PromotionOverlapState.Idle -> return
        }
        generation++
        scheduler.cancelAll()
        try {
            engine.finishRetirement() // B -> 1, A -> 0, A stopped and cleared; B (the logical CURRENT) is untouched
        } catch (e: Exception) {
            Log.w(TAG, "overlap abort cleanup failed", e)
        }
        settledKey = key
        lastOutcome = PromotionOutcome.Aborted(key, why)
        state = PromotionOverlapState.Idle
        debug("PROMOTION_ABORT", key, "reason=$why")
        if (started && !closed) schedule(CrossfadeTimingDriver.PRE_FADE_POLL_INTERVAL_MS)
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
