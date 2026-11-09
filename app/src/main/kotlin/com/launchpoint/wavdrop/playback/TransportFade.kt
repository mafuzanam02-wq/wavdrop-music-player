package com.launchpoint.wavdrop.playback

/** Persisted-value rules of the Transport Fade setting (explicit Play / Pause / Next / Previous). Independent of Crossfade. */
object TransportFadeRules {
    const val DEFAULT_ENABLED = false
    const val DEFAULT_DURATION_MS = 300L
    const val MIN_DURATION_MS = 100L
    const val MAX_DURATION_MS = 800L
    const val STEP_MS = 50L

    /** Missing -> default; otherwise clamped to [MIN, MAX] and snapped to the nearest [STEP_MS]. */
    fun normalizeDurationMs(raw: Long?): Long {
        if (raw == null) return DEFAULT_DURATION_MS
        val clamped = raw.coerceIn(MIN_DURATION_MS, MAX_DURATION_MS)
        return ((clamped + STEP_MS / 2) / STEP_MS * STEP_MS).coerceIn(MIN_DURATION_MS, MAX_DURATION_MS)
    }

    /** Next/Previous split the duration into a fade-out and a fade-in so the whole action costs [durationMs] in total. */
    fun halfBudgetMs(durationMs: Long): Long = durationMs / 2
}

internal data class TransportFadeConfig(val enabled: Boolean, val durationMs: Long)

/** Receives the transport gain (0..1). Production: the single gain composer of [PlayerEngine]. */
internal fun interface TransportGainSink {
    fun setTransportGain(gain: Float)
}

/**
 * Short single-player fades for explicit transport. It owns ONE factor (the transport gain, default 1) that the engine's gain
 * composer multiplies with the duck and crossfade factors; it never touches a player, a volume or the crossfade engine itself.
 *
 * Every command bumps a generation and cancels the scheduler first, so a superseded fade can never write a gain. Rules:
 *  - disabled (or nothing audible to fade): the action runs immediately, no gain write, no delay;
 *  - a pending navigation is always executed before a newer command is handled (each press navigates exactly once, in order);
 *  - an aborted fade ([interrupt], [release]) leaves the gain at 1 and drops pending work.
 *
 * Main thread only.
 */
internal class TransportFadeController(
    private val sink: TransportGainSink,
    private val clock: CrossfadeMonotonicClock,
    private val scheduler: CrossfadeTimingScheduler,
    private val config: () -> TransportFadeConfig,
) {
    var gain: Float = 1f
        private set

    private var generation = 0L
    private var pendingPause: (() -> Unit)? = null
    private var pendingNavigation: (() -> Boolean)? = null
    private var fading = false
    private var inNavigation = false

    private fun setGain(value: Float) {
        val v = value.coerceIn(0f, 1f)
        if (v == gain) return
        gain = v
        sink.setTransportGain(v)
    }

    private fun duration(): Long = TransportFadeRules.normalizeDurationMs(config().durationMs)
    private fun enabled(): Boolean = config().enabled

    /** Cancels any in-flight ramp (keeping the current gain) and returns a fresh generation. */
    private fun supersede(): Long {
        scheduler.cancelAll()
        fading = false
        return ++generation
    }

    private fun flushNavigation() {
        val nav = pendingNavigation ?: return
        pendingNavigation = null
        inNavigation = true
        try { runCatching { nav() } } finally { inNavigation = false }
    }

    private fun flushPause() {
        val pause = pendingPause ?: return
        pendingPause = null
        runCatching { pause() }
        setGain(1f)
    }

    fun play(isPlaying: Boolean, start: () -> Unit) {
        // A Play issued by a navigation itself (starting the selected item) is part of that navigation: no separate fade.
        if (inNavigation) { start(); return }
        flushNavigation()
        val wasPausing = pendingPause != null
        pendingPause = null // a newer Play outranks a Pause that has not completed
        val gen = supersede()
        if (!enabled()) { setGain(1f); start(); return }
        if (isPlaying && !wasPausing && gain >= 1f) { start(); return } // already audible and steady: nothing to fade
        if (!wasPausing && !isPlaying) setGain(0f) // start at zero gain; no artificial pre-silence
        try {
            start()
        } catch (e: Throwable) {
            setGain(1f) // a failed start never leaves the player muted or a generation active
            supersede()
            throw e
        }
        ramp(gen, 1f, (duration() * (1f - gain)).toLong()) { }
    }

    fun pause(isPlaying: Boolean, doPause: () -> Unit) {
        flushNavigation()
        flushPause()
        val gen = supersede()
        if (!enabled() || !isPlaying) { setGain(1f); doPause(); return }
        pendingPause = doPause
        ramp(gen, 0f, (duration() * gain).toLong()) {
            val pause = pendingPause
            pendingPause = null
            if (pause != null) runCatching { pause() }
            setGain(1f) // baseline restored for the next Play (the player is paused, so this is inaudible)
        }
    }

    /** [doNavigate] runs the unchanged navigation policy and returns whether a different item was selected. */
    fun navigate(isPlaying: Boolean, doNavigate: () -> Boolean) {
        flushNavigation()
        flushPause()
        val gen = supersede()
        if (!enabled() || !isPlaying) { setGain(1f); doNavigate(); return }
        pendingNavigation = doNavigate
        val half = TransportFadeRules.halfBudgetMs(duration())
        ramp(gen, 0f, (half * gain).toLong()) {
            flushNavigation() // restores the gain below even if the navigation fails
            ramp(gen, 1f, half) { }
        }
    }

    /** A system/other-owner interruption (focus loss, noisy, sleep boundary...): drop pending work, never stay muted. */
    fun interrupt(honorPendingPause: Boolean = false) {
        if (!fading && pendingPause == null && pendingNavigation == null && gain >= 1f) return // idle: nothing to abort, no writes
        val pause = if (honorPendingPause) pendingPause else null
        pendingPause = null
        pendingNavigation = null // a delayed navigation never survives an interruption
        supersede()
        setGain(1f)
        pause?.let { runCatching { it() } } // the user's Pause outlives a suppression that would otherwise resume on regain
    }

    fun release() = interrupt()

    private fun ramp(gen: Long, target: Float, durationMs: Long, onDone: () -> Unit) {
        val from = gain
        if (durationMs <= 0L || from == target) { setGain(target); onDone(); return }
        fading = true
        val startedAt = clock.nowMs()
        fun step() {
            if (gen != generation) return // stale: a newer command owns the gain now
            val elapsed = clock.nowMs() - startedAt
            if (elapsed >= durationMs) {
                fading = false
                setGain(target)
                onDone()
            } else {
                setGain(from + (target - from) * (elapsed.toFloat() / durationMs))
                scheduler.postDelayed(minOf(TICK_MS, durationMs - elapsed)) { step() }
            }
        }
        scheduler.postDelayed(TICK_MS.coerceAtMost(durationMs)) { step() }
    }

    private companion object {
        const val TICK_MS = 25L
    }
}

/** The ONE production wiring of the transport fade to the engine's interruption signal (used by PlaybackService and tests). */
internal fun PlayerEngine<*>.bindTransportFade(fade: TransportFadeController) {
    onPlaybackInterrupted = { stillWantsPlay -> fade.interrupt(honorPendingPause = stillWantsPlay) }
}
