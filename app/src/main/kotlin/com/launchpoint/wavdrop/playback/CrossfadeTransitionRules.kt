package com.launchpoint.wavdrop.playback

import com.launchpoint.wavdrop.data.model.Song
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Crossfade contract (CF-1): pure rules only.
 *
 * Nothing here touches a player, volume, queue or setting. It defines the limits, the eligibility
 * decision and the gain curve that a later two-player engine and a later settings screen must both
 * use, so they cannot disagree. Crossfade is decided by playback *positions* (occurrences) in the
 * playback queue, never by song id: duplicate songs are valid, and the same song at two positions
 * is two different occurrences.
 *
 * When crossfade is OFF (or unavailable) the native Media3 gapless path from G-1 is untouched.
 */
internal object CrossfadeRules {
    /** Configured duration meaning "crossfade off". */
    const val OFF_MS = 0L
    const val MIN_ENABLED_DURATION_MS = 1_000L
    const val MAX_DURATION_MS = 12_000L

    /**
     * Normalizes a requested duration: zero or negative is OFF ([OFF_MS]); any positive value is
     * bounded to [MIN_ENABLED_DURATION_MS]..[MAX_DURATION_MS].
     */
    fun normalizeDurationMs(requestedMs: Long): Long =
        if (requestedMs <= OFF_MS) OFF_MS else requestedMs.coerceIn(MIN_ENABLED_DURATION_MS, MAX_DURATION_MS)

    fun isEnabled(configuredMs: Long): Boolean = normalizeDurationMs(configuredMs) != OFF_MS
}

internal enum class CrossfadeUnavailableReason {
    Disabled,
    ExternalPlayback,
    PlayerQueueNeedsSync,
    NotPlaying,
    RepeatOne,
    QueueTooShort,
    InvalidCurrentIndex,
    NoNextOccurrence,
    UnknownDuration,
    DurationTooShort,
}

internal sealed interface CrossfadeTransitionPlan {
    /**
     * A crossfade may run. [startAtPositionMs] is where in the current track the overlap window
     * begins (`currentDurationMs - effectiveDurationMs`).
     */
    data class Eligible(
        val nextPlaybackIndex: Int,
        val effectiveDurationMs: Long,
        val startAtPositionMs: Long,
    ) : CrossfadeTransitionPlan

    data class Unavailable(val reason: CrossfadeUnavailableReason) : CrossfadeTransitionPlan
}

/**
 * Decides whether the transition from [currentPlaybackIndex] to the automatic next occurrence can
 * be a crossfade. Fails closed on anything uncertain.
 *
 * - [playbackQueue] is the queue in playback order (`playbackOrder.map { libraryQueue[it] }`); the
 *   next occurrence comes from [QueueNavigator.automaticNextIndex], the same navigation the rest of
 *   the app uses. Repeat All may wrap last → 0; Repeat One is never eligible (it stays
 *   Media3-native); Repeat Off has no next occurrence at the end.
 * - [currentDurationMs] is the live duration of the current occurrence when known (the player's
 *   value); when null, the queued [Song.duration] is used. The next occurrence's duration is its
 *   queued [Song.duration].
 * - The overlap never consumes more than half of either track:
 *   `min(configured, currentDuration / 2, nextDuration / 2)`, and below
 *   [CrossfadeRules.MIN_ENABLED_DURATION_MS] the transition is unavailable.
 */
internal fun planCrossfadeTransition(
    configuredDurationMs: Long,
    playbackQueue: List<Song>,
    currentPlaybackIndex: Int,
    repeatMode: RepeatMode,
    isPlaying: Boolean,
    isExternalPlayback: Boolean,
    playerQueueNeedsSync: Boolean,
    currentDurationMs: Long? = null,
): CrossfadeTransitionPlan {
    fun unavailable(reason: CrossfadeUnavailableReason) = CrossfadeTransitionPlan.Unavailable(reason)

    val configuredMs = CrossfadeRules.normalizeDurationMs(configuredDurationMs)
    if (configuredMs == CrossfadeRules.OFF_MS) return unavailable(CrossfadeUnavailableReason.Disabled)
    if (isExternalPlayback) return unavailable(CrossfadeUnavailableReason.ExternalPlayback)
    // A crossfade must never start against a physical playlist whose occurrence order is not authoritative.
    if (playerQueueNeedsSync) return unavailable(CrossfadeUnavailableReason.PlayerQueueNeedsSync)
    if (!isPlaying) return unavailable(CrossfadeUnavailableReason.NotPlaying)
    if (repeatMode == RepeatMode.ONE) return unavailable(CrossfadeUnavailableReason.RepeatOne)
    if (playbackQueue.size < 2) return unavailable(CrossfadeUnavailableReason.QueueTooShort)
    if (currentPlaybackIndex !in playbackQueue.indices) {
        return unavailable(CrossfadeUnavailableReason.InvalidCurrentIndex)
    }

    val nextPlaybackIndex = QueueNavigator.automaticNextIndex(
        queueSize = playbackQueue.size,
        currentIndex = currentPlaybackIndex,
        repeatMode = repeatMode,
    )?.takeIf { it != currentPlaybackIndex } ?: return unavailable(CrossfadeUnavailableReason.NoNextOccurrence)

    val currentMs = currentDurationMs ?: playbackQueue[currentPlaybackIndex].duration
    val nextMs = playbackQueue[nextPlaybackIndex].duration
    if (currentMs <= 0L || nextMs <= 0L) return unavailable(CrossfadeUnavailableReason.UnknownDuration)

    val effectiveMs = min(configuredMs, min(currentMs / 2, nextMs / 2))
    if (effectiveMs < CrossfadeRules.MIN_ENABLED_DURATION_MS) {
        return unavailable(CrossfadeUnavailableReason.DurationTooShort)
    }

    return CrossfadeTransitionPlan.Eligible(
        nextPlaybackIndex = nextPlaybackIndex,
        effectiveDurationMs = effectiveMs,
        startAtPositionMs = currentMs - effectiveMs,
    )
}

/** Player gains for one instant of a crossfade; each is in `0.0..1.0`. */
internal data class CrossfadeGains(
    val outgoing: Float,
    val incoming: Float,
)

/** Equal-power crossfade curve for the future two-player engine (no volume is changed in CF-1). */
internal object CrossfadeGainCurve {
    /**
     * [progress] is the normalized position in the overlap, clamped to `0.0..1.0` (NaN counts as 0).
     * Outgoing is `cos(p * PI / 2)` (1 → 0), incoming is `sin(p * PI / 2)` (0 → 1), so
     * `outgoing² + incoming² == 1` throughout and perceived loudness stays roughly constant.
     */
    fun equalPower(progress: Float): CrossfadeGains {
        val p = if (progress.isNaN()) 0.0 else progress.toDouble().coerceIn(0.0, 1.0)
        val angle = p * PI / 2.0
        return CrossfadeGains(
            outgoing = cos(angle).toFloat().coerceIn(0f, 1f),
            incoming = sin(angle).toFloat().coerceIn(0f, 1f),
        )
    }
}
