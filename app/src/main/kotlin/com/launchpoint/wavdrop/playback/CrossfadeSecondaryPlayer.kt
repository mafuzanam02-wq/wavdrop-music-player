package com.launchpoint.wavdrop.playback

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.launchpoint.wavdrop.data.model.Song

/**
 * Semantic results of a secondary preparation, for a later slice to feed into CF-2A. Each is
 * reported at most once per preparation and always carries the occurrence-bound key.
 */
internal interface CrossfadeSecondaryListener {
    /** The secondary for [key] is ready and silent; [preparedDurationMs] is its actual duration. */
    fun onSecondaryReady(key: CrossfadeTransitionKey, preparedDurationMs: Long)

    fun onSecondaryFailed(key: CrossfadeTransitionKey)

    object NoOp : CrossfadeSecondaryListener {
        override fun onSecondaryReady(key: CrossfadeTransitionKey, preparedDurationMs: Long) = Unit
        override fun onSecondaryFailed(key: CrossfadeTransitionKey) = Unit
    }
}

/** Callbacks from a backend, tagged with the preparation attempt that produced them. */
internal interface SecondaryBackendCallbacks {
    fun onReady(attempt: Long, durationMs: Long)
    fun onError(attempt: Long)
}

/**
 * Minimal seam over the real secondary player so ownership logic is JVM-testable. Not a generic
 * player abstraction: it can load one silent item, start that already-prepared item once at an explicit
 * gain, reset, or release.
 */
internal interface SecondaryPlayerBackend {
    /** Silently loads exactly [item] and prepares it. Callbacks must carry [attempt]. */
    fun prepare(attempt: Long, item: MediaItem, callbacks: SecondaryBackendCallbacks)

    /**
     * Starts the item this backend already prepared, at [initialGain] (finite, 0..1). The gain is applied
     * BEFORE playback begins so the secondary is never audible at an unintended level. Returns false (and
     * does nothing) for an invalid gain or when nothing is prepared and ready.
     */
    fun start(initialGain: Float): Boolean

    /** Stops, clears media, and leaves the player silent (volume 0, playWhenReady false). */
    fun reset()

    fun release()
}

/**
 * Normalized fade progress for an observation that arrived [latenessMs] past the planned start of an
 * [effectiveDurationMs] overlap. Pure arithmetic (no clock). Null (fail closed) for a negative lateness, a
 * non-positive duration, or a lateness beyond the duration.
 */
internal fun initialCrossfadeProgress(latenessMs: Long, effectiveDurationMs: Long): Float? {
    if (latenessMs < 0L || effectiveDurationMs <= 0L || latenessMs > effectiveDurationMs) return null
    return (latenessMs.toDouble() / effectiveDurationMs.toDouble()).toFloat()
}

/** Initial incoming gain from the single CF-1 curve; null when the progress inputs are invalid. */
internal fun initialIncomingGain(latenessMs: Long, effectiveDurationMs: Long): Float? =
    initialCrossfadeProgress(latenessMs, effectiveDurationMs)?.let { CrossfadeGainCurve.equalPower(it).incoming }

/** A start gain must be finite and within 0..1; the ownership layer never coerces. */
internal fun isValidInitialGain(gain: Float): Boolean = isValidCrossfadeGain(gain)

/**
 * Owns and silently prepares the future secondary player (CF-2B2); CF-2C2 adds a once-only, key-bound start.
 * Never touches the
 * primary player, MediaSession, audio focus or EQ. Identity is [CrossfadeTransitionKey] plus an
 * internal monotonically increasing attempt token; a callback is honoured only if its attempt is
 * the active one. Song ids play no role. Main-thread confined.
 */
internal class CrossfadeSecondaryPlayer(
    private val backendFactory: () -> SecondaryPlayerBackend,
    private val listener: CrossfadeSecondaryListener,
    private val mediaItemFactory: (Song) -> MediaItem = { it.toPlaybackMediaItem() },
) {
    private var backend: SecondaryPlayerBackend? = null
    private var released = false
    private var attemptCounter = 0L
    private var activeAttempt = NO_ATTEMPT
    private var activeKey: CrossfadeTransitionKey? = null
    private var phase = Phase.None

    val currentKey: CrossfadeTransitionKey? get() = activeKey

    private enum class Phase { None, Preparing, Prepared, Started }

    private val callbacks = object : SecondaryBackendCallbacks {
        override fun onReady(attempt: Long, durationMs: Long) = handleReady(attempt, durationMs)
        override fun onError(attempt: Long) = handleError(attempt)
    }

    /** Supersedes any previous preparation. Returns false (and does nothing) after [release]. */
    fun prepare(key: CrossfadeTransitionKey, song: Song): Boolean {
        if (released) return false
        // Superseding a secondary that is already playing must stop it first; never let ownership move on
        // while the old item keeps running.
        val wasStarted = phase == Phase.Started
        val attempt = ++attemptCounter
        activeAttempt = attempt
        activeKey = key
        phase = Phase.Preparing
        val b = backend ?: backendFactory().also { backend = it }
        if (wasStarted) b.reset()
        b.prepare(attempt, mediaItemFactory(song), callbacks)
        return true
    }

    /**
     * Starts the exact prepared secondary for [key] once, at [initialIncomingGain]. Occurrence-bound: it
     * succeeds only when [key] is the active preparation, that preparation reached Ready successfully, it was
     * not started before, and the gain is finite within 0..1. Everything else fails closed with no backend
     * call. Song ids play no role.
     */
    fun start(key: CrossfadeTransitionKey, initialIncomingGain: Float): Boolean {
        if (released || activeKey != key || phase != Phase.Prepared) return false
        if (!isValidInitialGain(initialIncomingGain)) return false
        val attempt = activeAttempt
        val backendStarted = backend?.start(initialIncomingGain) == true
        if (!backendStarted) return false
        // The backend call may have re-entered us (e.g. a synchronous error callback). Success means the SAME
        // attempt still owns the start afterwards; never report true for a lost or superseded preparation.
        if (activeAttempt == attempt && activeKey == key && phase == Phase.Prepared) {
            phase = Phase.Started
            return true
        }
        return false
    }

    /** Occurrence-bound: only abandons when [key] is the active preparation. */
    fun abandon(key: CrossfadeTransitionKey): Boolean {
        if (released || activeKey != key) return false
        invalidateActive()
        backend?.reset()
        return true
    }

    /** Unconditional, idempotent teardown. Nothing can be reported afterwards. */
    fun release() {
        if (released) return
        released = true
        invalidateActive()
        val b = backend
        backend = null
        b?.release()
    }

    private fun invalidateActive() {
        activeAttempt = ++attemptCounter
        activeKey = null
        phase = Phase.None
    }

    private fun handleReady(attempt: Long, durationMs: Long) {
        // Ready is accepted once, only while the current attempt is still Preparing.
        val key = liveKeyFor(attempt, requirePhase = Phase.Preparing) ?: return
        if (durationMs <= 0L) {
            // Unknown/unusable duration cannot be revalidated against CF-1: fail closed.
            failTerminally(key)
            return
        }
        phase = Phase.Prepared
        listener.onSecondaryReady(key, durationMs)
    }

    private fun handleError(attempt: Long) {
        // A real player can fail after Ready or after start: any live phase of the current attempt qualifies.
        val key = liveKeyFor(attempt, requirePhase = null) ?: return
        failTerminally(key)
    }

    /**
     * Terminal failure: ownership is dropped BEFORE the external listener runs, so late callbacks from
     * this attempt are rejected and a preparation the listener starts synchronously is left intact.
     */
    private fun failTerminally(failedKey: CrossfadeTransitionKey) {
        invalidateActive()
        backend?.reset()
        listener.onSecondaryFailed(failedKey)
    }

    /** The active key iff [attempt] is the current attempt, the owner is live, and (if given) in [requirePhase]. */
    private fun liveKeyFor(attempt: Long, requirePhase: Phase?): CrossfadeTransitionKey? {
        if (released || attempt != activeAttempt || phase == Phase.None) return null
        if (requirePhase != null && phase != requirePhase) return null
        return activeKey
    }

    private companion object {
        const val NO_ATTEMPT = -1L
    }
}

/**
 * The real secondary ExoPlayer: same media audio attributes as the primary but it does NOT handle
 * audio focus or becoming-noisy, starts silent with playWhenReady false, has no offload, no EQ and
 * no MediaSession. It plays only via [start], with an explicit validated initial gain.
 */
@UnstableApi
internal class ExoSecondaryPlayerBackend(
    context: Context,
    audioAttributes: AudioAttributes,
) : SecondaryPlayerBackend {
    private val player: ExoPlayer = ExoPlayer.Builder(context)
        .setAudioAttributes(audioAttributes, /* handleAudioFocus= */ false)
        .setHandleAudioBecomingNoisy(false)
        .build()
        .apply {
            volume = 0f
            playWhenReady = false
        }
    private var activeListener: Player.Listener? = null

    override fun prepare(attempt: Long, item: MediaItem, callbacks: SecondaryBackendCallbacks) {
        detachListener()
        silence()
        // A fresh listener per attempt carries that attempt's token.
        val attemptListener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) callbacks.onReady(attempt, player.duration)
            }

            override fun onPlayerError(error: PlaybackException) = callbacks.onError(attempt)
        }
        activeListener = attemptListener
        player.addListener(attemptListener)
        player.setMediaItem(item)
        player.prepare()
    }

    override fun start(initialGain: Float): Boolean {
        if (!initialGain.isFinite() || initialGain !in 0f..1f) return false
        if (player.mediaItemCount != 1 || player.playbackState != Player.STATE_READY) return false
        player.volume = initialGain // intended gain first, so it is never audible at a stale level
        player.play()
        return true
    }

    override fun reset() {
        detachListener()
        silence()
        player.stop()
        player.clearMediaItems()
    }

    override fun release() {
        detachListener()
        player.release()
    }

    private fun silence() {
        player.playWhenReady = false
        player.volume = 0f
    }

    private fun detachListener() {
        activeListener?.let { player.removeListener(it) }
        activeListener = null
    }
}
