package com.launchpoint.wavdrop.playback

import android.util.Log
import androidx.media3.common.MediaItem
import com.launchpoint.wavdrop.data.model.Song

/**
 * CF-2M4: decides WHEN the engine's NEXT slot should be prepared, reusing the existing CF-1/CF-2 planning verbatim
 * ([planCrossfadeFromRuntimeSnapshot] + [bindCrossfadeTransition] + [crossfadeOwnershipLossReason]); there is no second rule
 * for "what is B". It does not start the old CF-2L runtime or secondary, and it never starts B.
 *
 * Trigger: a main-looper poll at [CrossfadeTimingDriver.PRE_FADE_POLL_INTERVAL_MS] (the cadence the CF-2L runtime used), plus
 * the synchronous explicit-cancellation hook ([cancel], the shared [CrossfadeCancelSink] lifecycle point). Preparation is
 * therefore armed as soon as an eligible exact transition exists while playing, NOT at the fade-start instant, so a large
 * NEXT queue is grafted well before it is needed.
 *
 * One pulse captures exactly one snapshot; the queue materialized for NEXT is that snapshot's own `playbackQueue`, so target
 * selection and graft always describe the same generation. Main-thread confined.
 */
internal class NextSlotPreparationDriver(
    private val preparation: NextSlotPreparation<*>,
    private val snapshotProvider: () -> CrossfadeRuntimeSnapshot,
    /** Read-only Song -> MediaItem materialization (same representation as CURRENT). Called only when a new request is needed. */
    private val materialize: (List<Song>) -> List<MediaItem>,
    private val configuredDurationMsProvider: () -> Long,
    private val currentDurationMsProvider: () -> Long?,
    private val scheduler: CrossfadeTimingScheduler,
) : CrossfadeCancelSink {
    private var started = false
    private var closed = false
    private var generation = 0L

    fun start() {
        if (closed || started) return
        started = true
        schedule(0L)
    }

    /** Idempotent. Invalidates the pending pulse; preparation ownership is left to [cancel]/[evaluate]. */
    fun stop() {
        if (!started) return
        started = false
        invalidatePulse()
    }

    fun close() {
        if (closed) return
        closed = true
        started = false
        invalidatePulse()
        preparation.invalidate(CrossfadeCancelReason.ServiceStopping)
    }

    /** The shared synchronous explicit-cancellation point: any cancel reason ends NEXT's ownership. */
    override fun cancel(reason: CrossfadeCancelReason) {
        if (closed) return
        preparation.invalidate(reason)
    }

    /** One evaluation: end stale ownership, then (idempotently) request the exact eligible transition. */
    fun evaluate() {
        if (closed) return
        // CF-2M5: a retiring player occupies NEXT during an overlap; do not plan or materialize anything until it is recycled.
        if (!preparation.accepting) return
        val snapshot = snapshotProvider()

        val active = preparation.state.key
        if (active != null) {
            crossfadeOwnershipLossReason(snapshot, active)?.let { preparation.invalidate(it) }
        }
        if (!snapshot.controllerConnected) {
            preparation.invalidate(CrossfadeCancelReason.ControllerDisconnected)
            return
        }
        val plan = planCrossfadeFromRuntimeSnapshot(configuredDurationMsProvider(), snapshot, currentDurationMsProvider())
        if (plan !is CrossfadeTransitionPlan.Eligible) {
            preparation.invalidate(CrossfadeCancelReason.PlanInvalidated)
            return
        }
        val key = bindCrossfadeTransition(snapshot, plan)
        if (key == null) {
            preparation.invalidate(CrossfadeCancelReason.PlanInvalidated)
            return
        }
        if (preparation.state.key == key) return // idempotent: same exact occurrence already owned (Preparing/Ready/Failed)
        preparation.request(
            NextSlotRequest(
                key = key,
                queue = materialize(snapshot.playbackQueue),
                repeatMode = snapshot.repeatMode.toPlayerRepeatMode(),
            ),
        )
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
        if (bound != generation || !started || closed) return
        try {
            evaluate()
        } catch (e: Exception) {
            // Infrastructure failure: fail closed (no half-owned NEXT) and keep the loop alive.
            Log.w(TAG, "next-slot preparation pulse failed", e)
            preparation.invalidate(CrossfadeCancelReason.PlanInvalidated)
        }
        if (bound == generation && started && !closed) schedule(CrossfadeTimingDriver.PRE_FADE_POLL_INTERVAL_MS)
    }

    private companion object {
        const val TAG = "WavdropNextSlot"
    }
}

/**
 * CF-2M4: the NEXT-slot analogue of [applyCrossfadeConfiguredDurationChange]: same pure decision, applied to the engine
 * driver. Disable invalidates NEXT BEFORE stopping the poll; Start is idempotent.
 */
internal fun applyNextSlotConfiguredDurationChange(
    previousDurationMs: Long?,
    newDurationMs: Long,
    driver: NextSlotPreparationDriver?,
): CrossfadeActivationDecision {
    val decision = decideCrossfadeDriverActivation(previousDurationMs, newDurationMs)
    when (decision) {
        CrossfadeActivationDecision.Start -> driver?.start()
        CrossfadeActivationDecision.Disable -> {
            driver?.cancel(CrossfadeCancelReason.ConfigurationDisabled)
            driver?.stop()
        }
        CrossfadeActivationDecision.NoOp,
        CrossfadeActivationDecision.UpdateOnly -> Unit
    }
    return decision
}
