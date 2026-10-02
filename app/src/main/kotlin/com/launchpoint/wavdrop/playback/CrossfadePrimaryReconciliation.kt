package com.launchpoint.wavdrop.playback

import android.util.Log
import com.launchpoint.wavdrop.data.model.Song

/** Why a primary handoff reconciliation was refused. Nothing was sought when one of these is reported. */
internal enum class CrossfadePrimaryReconciliationRejection {
    ControllerUnavailable,
    QueueGenerationChanged,
    PlayerQueueDirty,
    InvalidSnapshot,
    TargetOutOfBounds,
    CurrentOccurrenceMismatch,
    PhysicalIndexMismatch,
    TargetMismatch,
    SeekFailed,
}

internal sealed interface CrossfadePrimaryReconciliationResult {
    /** The exact validated seek was issued to the primary without error. */
    data object Succeeded : CrossfadePrimaryReconciliationResult

    data class Rejected(val reason: CrossfadePrimaryReconciliationRejection) : CrossfadePrimaryReconciliationResult
}

/** Pure outcome of validating a reconciliation request. */
internal sealed interface CrossfadePrimaryReconciliationPlan {
    data class Seek(val targetIndex: Int, val positionMs: Long) : CrossfadePrimaryReconciliationPlan
    data class Reject(val reason: CrossfadePrimaryReconciliationRejection) : CrossfadePrimaryReconciliationPlan
}

/**
 * CF-2D2: decides whether the authoritative primary may be repositioned to [CrossfadeTransitionKey.toPlaybackIndex]
 * at the secondary's physical [snapshot] position. Purely positional/generation based (no song ids). Every
 * condition must hold or the request is rejected with no seek: live controller, exact queue generation, a clean
 * physical queue, a valid snapshot (never clamped), a distinct in-bounds target, the exact logical AND physical
 * source occurrence, and the automatic-next target still resolving to the key's target. Queued Song metadata
 * duration is deliberately not compared: the secondary's physical position is authoritative.
 */
internal fun planCrossfadePrimaryReconciliation(
    key: CrossfadeTransitionKey,
    snapshot: SecondaryHandoffSnapshot,
    queueGeneration: Long,
    playbackQueueSize: Int,
    currentPlaybackIndex: Int?,
    physicalCurrentIndex: Int?,
    repeatMode: RepeatMode,
    playerQueueNeedsSync: Boolean,
    controllerAvailable: Boolean,
): CrossfadePrimaryReconciliationPlan {
    fun reject(reason: CrossfadePrimaryReconciliationRejection) = CrossfadePrimaryReconciliationPlan.Reject(reason)
    if (!controllerAvailable) return reject(CrossfadePrimaryReconciliationRejection.ControllerUnavailable)
    if (queueGeneration != key.queueGeneration) return reject(CrossfadePrimaryReconciliationRejection.QueueGenerationChanged)
    if (playerQueueNeedsSync) return reject(CrossfadePrimaryReconciliationRejection.PlayerQueueDirty)
    if (snapshot.positionMs < 0L || snapshot.durationMs <= 0L || snapshot.positionMs > snapshot.durationMs) {
        return reject(CrossfadePrimaryReconciliationRejection.InvalidSnapshot)
    }
    if (key.toPlaybackIndex !in 0 until playbackQueueSize) return reject(CrossfadePrimaryReconciliationRejection.TargetOutOfBounds)
    if (key.toPlaybackIndex == key.fromPlaybackIndex) return reject(CrossfadePrimaryReconciliationRejection.TargetMismatch)
    if (key.fromPlaybackIndex !in 0 until playbackQueueSize || currentPlaybackIndex != key.fromPlaybackIndex) {
        return reject(CrossfadePrimaryReconciliationRejection.CurrentOccurrenceMismatch)
    }
    if (physicalCurrentIndex != key.fromPlaybackIndex) return reject(CrossfadePrimaryReconciliationRejection.PhysicalIndexMismatch)
    if (QueueNavigator.automaticNextIndex(playbackQueueSize, key.fromPlaybackIndex, repeatMode) != key.toPlaybackIndex) {
        return reject(CrossfadePrimaryReconciliationRejection.TargetMismatch)
    }
    return CrossfadePrimaryReconciliationPlan.Seek(key.toPlaybackIndex, snapshot.positionMs)
}

/**
 * Executes a validated plan through [seekTo] (exactly one call for a Seek plan, none for a Reject). A throwing
 * seek is reported as SeekFailed, never as success and never rethrown, and [onSeekSucceeded] is NOT invoked. After
 * a successful seek [onSeekSucceeded] receives the target index (outside the seek's try block, so its own
 * failures are never misreported as a failed seek). Playback intent is untouched.
 */
internal fun executeCrossfadePrimaryReconciliation(
    plan: CrossfadePrimaryReconciliationPlan,
    onSeekSucceeded: (targetIndex: Int) -> Unit = {},
    seekTo: (index: Int, positionMs: Long) -> Unit,
): CrossfadePrimaryReconciliationResult {
    val seek = when (plan) {
        is CrossfadePrimaryReconciliationPlan.Reject -> return CrossfadePrimaryReconciliationResult.Rejected(plan.reason)
        is CrossfadePrimaryReconciliationPlan.Seek -> plan
    }
    try {
        seekTo(seek.targetIndex, seek.positionMs)
    } catch (e: Exception) {
        Log.w("WavdropCrossfade", "primary handoff seek failed", e)
        return CrossfadePrimaryReconciliationResult.Rejected(CrossfadePrimaryReconciliationRejection.SeekFailed)
    }
    onSeekSucceeded(seek.targetIndex)
    return CrossfadePrimaryReconciliationResult.Succeeded
}

/**
 * Deterministic NowPlaying state right after a successful reconciliation seek, built only from the validated
 * target occurrence and secondary [snapshot]; it never consults a MediaController. Installs the target index,
 * its queue song, the snapshot position and physical duration, and the live queue/shuffle/repeat. Playback intent
 * (isPlaying) and seekability are preserved. The buffered position is conservatively reset to the known-played
 * position (the old item's buffer is meaningless for the new one) and refreshes on the next normal sync. Stats are
 * deliberately not touched here (transition/stat ownership belongs to CF-2D3). An out-of-range target returns
 * [current] unchanged.
 */
internal fun reconcileCrossfadeNowPlayingState(
    current: NowPlayingState,
    playbackQueue: List<Song>,
    targetPlaybackIndex: Int,
    snapshot: SecondaryHandoffSnapshot,
    shuffleEnabled: Boolean,
    repeatMode: RepeatMode,
): NowPlayingState {
    val target = playbackQueue.getOrNull(targetPlaybackIndex) ?: return current
    return current.copy(
        song = target,
        queue = playbackQueue,
        currentIndex = targetPlaybackIndex,
        shuffleEnabled = shuffleEnabled,
        repeatMode = repeatMode,
        positionMs = snapshot.positionMs,
        durationMs = snapshot.durationMs,
        bufferedPositionMs = snapshot.positionMs,
    )
}

/**
 * Narrow seam over the primary's handoff reconciliation (production: [PlayerController.reconcileCrossfadePrimary]).
 * The runtime depends only on this, never on a controller. The default never pretends success.
 */
internal fun interface CrossfadePrimaryReconciler {
    fun reconcile(
        key: CrossfadeTransitionKey,
        snapshot: SecondaryHandoffSnapshot,
    ): CrossfadePrimaryReconciliationResult

    object Unavailable : CrossfadePrimaryReconciler {
        override fun reconcile(
            key: CrossfadeTransitionKey,
            snapshot: SecondaryHandoffSnapshot,
        ): CrossfadePrimaryReconciliationResult = CrossfadePrimaryReconciliationResult.Rejected(
            CrossfadePrimaryReconciliationRejection.ControllerUnavailable,
        )
    }
}
