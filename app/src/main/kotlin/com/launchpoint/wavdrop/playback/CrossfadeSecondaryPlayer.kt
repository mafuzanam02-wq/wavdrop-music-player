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
 * player abstraction: it can only load one silent item, reset, or release. There is no play.
 */
internal interface SecondaryPlayerBackend {
    /** Silently loads exactly [item] and prepares it. Callbacks must carry [attempt]. */
    fun prepare(attempt: Long, item: MediaItem, callbacks: SecondaryBackendCallbacks)

    /** Stops, clears media, and leaves the player silent (volume 0, playWhenReady false). */
    fun reset()

    fun release()
}

/**
 * Owns and silently prepares the future secondary player (CF-2B2). Never plays, never touches the
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
    private var terminalDelivered = false

    val currentKey: CrossfadeTransitionKey? get() = activeKey

    private val callbacks = object : SecondaryBackendCallbacks {
        override fun onReady(attempt: Long, durationMs: Long) = handleReady(attempt, durationMs)
        override fun onError(attempt: Long) = handleError(attempt)
    }

    /** Supersedes any previous preparation. Returns false (and does nothing) after [release]. */
    fun prepare(key: CrossfadeTransitionKey, song: Song): Boolean {
        if (released) return false
        val attempt = ++attemptCounter
        activeAttempt = attempt
        activeKey = key
        terminalDelivered = false
        val b = backend ?: backendFactory().also { backend = it }
        b.prepare(attempt, mediaItemFactory(song), callbacks)
        return true
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
        terminalDelivered = false
    }

    private fun handleReady(attempt: Long, durationMs: Long) {
        val key = ownedKeyFor(attempt) ?: return
        if (durationMs <= 0L) {
            // Unknown/unusable duration cannot be revalidated against CF-1: fail closed.
            failTerminally(key)
            return
        }
        terminalDelivered = true
        listener.onSecondaryReady(key, durationMs)
    }

    private fun handleError(attempt: Long) {
        val key = ownedKeyFor(attempt) ?: return
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

    private fun ownedKeyFor(attempt: Long): CrossfadeTransitionKey? {
        if (released || terminalDelivered || attempt != activeAttempt) return null
        return activeKey
    }

    private companion object {
        const val NO_ATTEMPT = -1L
    }
}

/**
 * The real secondary ExoPlayer: same media audio attributes as the primary but it does NOT handle
 * audio focus or becoming-noisy, starts silent with playWhenReady false, has no offload, no EQ and
 * no MediaSession. It is never told to play.
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
