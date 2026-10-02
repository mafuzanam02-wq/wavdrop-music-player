package com.launchpoint.wavdrop.playback

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/** Monotonic time source for fade timing. Never wall-clock. */
internal fun interface CrossfadeMonotonicClock {
    fun nowMs(): Long
}

/** Production monotonic clock. */
internal object ElapsedRealtimeCrossfadeClock : CrossfadeMonotonicClock {
    override fun nowMs(): Long = SystemClock.elapsedRealtime()
}

/**
 * Narrow main-thread scheduling seam. The driver keeps at most one callback pending; [cancelAll] must drop
 * every callback this scheduler posted (and only those).
 */
internal interface CrossfadeTimingScheduler {
    fun postDelayed(delayMs: Long, block: () -> Unit)
    fun cancelAll()
}

/** Main-looper scheduler. Removes only the runnables it posted, never unrelated work on the handler. */
internal class MainLooperCrossfadeTimingScheduler(
    private val handler: Handler = Handler(Looper.getMainLooper()),
) : CrossfadeTimingScheduler {
    private val pending = mutableSetOf<Runnable>()

    override fun postDelayed(delayMs: Long, block: () -> Unit) {
        lateinit var runnable: Runnable
        runnable = Runnable {
            pending.remove(runnable)
            block()
        }
        pending += runnable
        handler.postDelayed(runnable, delayMs.coerceAtLeast(0L))
    }

    override fun cancelAll() {
        pending.forEach { handler.removeCallbacks(it) }
        pending.clear()
    }
}

/**
 * CF-2C7D: scheduling-only driver over [CrossfadePreparationRuntime]. It decides WHEN the runtime's existing
 * operations run (pre-fade evaluation/observation, BeginFade, repeated FadeTick) and never computes progress,
 * elapsed fade time or gains: the coordinator stays the timing authority and the driver only supplies the
 * monotonic clock reading. The runtime's [CrossfadePreparationRuntime.state] is the only lifecycle; the driver
 * tracks just started/stopped/closed plus a generation token so stale callbacks are no-ops.
 *
 * Exactly one callback is ever pending: a pulse finishes, then schedules at most one next pulse through the
 * scheduler (never recursively). A terminal FadeTick (or a pulse that starts in HandoffPending) runs the runtime handoff in
 * the same pulse; success resumes pre-fade polling, a genuine failure halts the run (restartable). The
 * driver does not own, close or reset the runtime. Main-thread confined; no production code constructs it yet.
 */
internal class CrossfadeTimingDriver(
    private val runtime: CrossfadePreparationRuntime,
    private val scheduler: CrossfadeTimingScheduler,
    private val clock: CrossfadeMonotonicClock,
    private val configuredDurationMsProvider: () -> Long,
    private val currentDurationMsProvider: () -> Long?,
    private val currentPositionMsProvider: () -> Long,
) {
    private var started = false
    private var closed = false
    private var generation = 0L

    /** Idempotent. Schedules the first pulse for the next scheduler turn (delay 0). */
    fun start() {
        if (closed || started) return
        started = true
        schedule(0L)
    }

    /** Idempotent. Invalidates any pending callback; the runtime and players are left untouched. */
    fun stop() {
        if (!started) return
        started = false
        invalidate()
    }

    /** Idempotent and terminal. Does not close the runtime (the runtime has its own owner). */
    fun close() {
        if (closed) return
        closed = true
        started = false
        invalidate()
    }

    private fun invalidate() {
        generation++
        scheduler.cancelAll()
    }

    private fun schedule(delayMs: Long) {
        val bound = generation
        scheduler.postDelayed(delayMs) { pulse(bound) }
    }

    private fun pulse(bound: Long) {
        if (bound != generation || !started || closed) return // stale callback
        val nextDelay = try {
            runPulse()
        } catch (e: Exception) {
            // Infrastructure failure (provider/clock/runtime): leave runtime state alone and keep the loop alive.
            Log.w(TAG, "crossfade timing pulse failed", e)
            nextDelayFor(runtime.state)
        }
        if (nextDelay != null && bound == generation && started && !closed) schedule(nextDelay)
    }

    /** One pulse; returns the next delay, or null when scheduling should stop. */
    private fun runPulse(): Long? {
        val before = runtime.state
        // Already-pending entry: hand off before any provider read or preparation (one attempt per pulse).
        if (before is CrossfadeState.HandoffPending) return handOff(before.key)
        val configured = configuredDurationMsProvider()
        val current = currentDurationMsProvider()
        runtime.evaluatePreparation(configured, current) // audible states: ownership + explicit OFF only (CF-2C7C)

        val after = runtime.state
        when {
            before is CrossfadeState.Fading -> {
                // Only the same transition may be ticked; anything else means it ended or was replaced.
                if (after is CrossfadeState.Fading && after.key == before.key) {
                    if (runtime.executeFadeTick(before.key, clock.nowMs()) == FadeTickExecutionResult.HandoffPending) {
                        // Same pulse: do not wait a cadence turn while primary ~0 gain and secondary is full.
                        val pending = runtime.state as? CrossfadeState.HandoffPending ?: return nextDelayFor(runtime.state)
                        return handOff(pending.key)
                    }
                }
            }
            after is CrossfadeState.Ready -> {
                val observation = runtime.observePrimaryPosition(after.key, currentPositionMsProvider())
                if (observation is FadeWindowObservation.Due) {
                    runtime.executeBeginFade(observation, clock.nowMs())
                }
            }
        }
        return nextDelayFor(runtime.state)
    }

    /**
     * Executes the runtime handoff for the exact pending [key] (at most once per pulse) and decides the cadence.
     * Succeeded resumes pre-fade polling (fresh observations next turn). A genuine Failed halts the automatic run
     * (no retry, restartable); Cancelled / Inactive follow the resulting runtime state. The runtime owns all handoff
     * mechanics; the driver only decides when.
     */
    private fun handOff(key: CrossfadeTransitionKey): Long? = when (runtime.executeHandoff(key)) {
        CrossfadeHandoffExecutionResult.Succeeded -> PRE_FADE_POLL_INTERVAL_MS
        is CrossfadeHandoffExecutionResult.Failed -> {
            haltAfterHandoffFailure()
            null
        }
        is CrossfadeHandoffExecutionResult.Cancelled,
        CrossfadeHandoffExecutionResult.Inactive -> nextDelayFor(runtime.state)
    }

    /** Fail-closed stop after a genuine handoff failure: no callback remains and a later [start] begins afresh. */
    private fun haltAfterHandoffFailure() {
        started = false
        invalidate()
    }

    private fun nextDelayFor(state: CrossfadeState): Long? = when (state) {
        is CrossfadeState.Fading -> FADE_TICK_INTERVAL_MS
        is CrossfadeState.HandoffPending -> null
        else -> PRE_FADE_POLL_INTERVAL_MS
    }

    internal companion object {
        const val PRE_FADE_POLL_INTERVAL_MS = 250L
        const val FADE_TICK_INTERVAL_MS = 50L
        private const val TAG = "WavdropCrossfade"
    }
}
