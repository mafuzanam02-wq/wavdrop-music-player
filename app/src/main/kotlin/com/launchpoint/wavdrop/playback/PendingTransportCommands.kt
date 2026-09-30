package com.launchpoint.wavdrop.playback

import com.launchpoint.wavdrop.data.model.Song

/**
 * Typed, bounded model for user transport commands captured while the [MediaController] is
 * temporarily unavailable (Phase 7 — controller-null hardening).
 *
 * Each concern is a single latest-wins slot in PlayerController — never an unbounded lambda or
 * command list (O(1) per class). Slots are drained exactly once on reconnect, against the current
 * controller generation, and each is stale-validated at execution.
 *
 * Supersession rules (mirrored in PlayerController and covered by PendingTransportCommandsTest):
 *  - Play/Pause  — latest DESIRED state wins (a later PAUSE replaces an earlier PLAY). Derived from
 *    authoritative playback state at tap time; a raw toggle is never replayed.
 *  - Seek        — latest position wins, BOUND to its queue occurrence; dropped if generation,
 *    index, indexed song, or the current occurrence changes before reconnect.
 *  - Navigation  — at most one pending next/previous intent, latest direction wins; recomputed
 *    against the live queue at execution so a mutated queue cannot replay a stale skip.
 *  - Any queue-replacing/restore/jump request captured while disconnected supersedes all of the
 *    above (they refer to a queue/session that the replacement invalidates).
 */
internal enum class NavigationIntent { NEXT, PREVIOUS }

internal data class PendingSeek(
    val queueGeneration: Long,
    val playbackIndex: Int,
    val targetSongId: Long,
    val positionMs: Long,
)

internal sealed interface PendingSeekResolution {
    data class Apply(val positionMs: Long) : PendingSeekResolution
    data object Discard : PendingSeekResolution
}

internal fun capturePendingSeek(
    queueGeneration: Long,
    playbackQueue: List<Song>,
    currentPlaybackIndex: Int?,
    positionMs: Long,
): PendingSeek? {
    val playbackIndex = currentPlaybackIndex?.takeIf { it in playbackQueue.indices } ?: return null
    return PendingSeek(
        queueGeneration = queueGeneration,
        playbackIndex = playbackIndex,
        targetSongId = playbackQueue[playbackIndex].id,
        positionMs = positionMs,
    )
}

internal object PendingTransport {

    /** Desired play/pause state derived at tap time (STATE_INTENT) — not a raw toggle to replay. */
    fun desiredPlayWhenReady(currentlyPlaying: Boolean): Boolean = !currentlyPlaying

    fun resolveSeek(
        seek: PendingSeek,
        currentQueueGeneration: Long,
        playbackQueue: List<Song>,
        currentPlaybackIndex: Int?,
    ): PendingSeekResolution {
        if (seek.queueGeneration != currentQueueGeneration) return PendingSeekResolution.Discard
        if (seek.playbackIndex !in playbackQueue.indices) return PendingSeekResolution.Discard
        if (playbackQueue[seek.playbackIndex].id != seek.targetSongId) {
            return PendingSeekResolution.Discard
        }
        if (currentPlaybackIndex != seek.playbackIndex) return PendingSeekResolution.Discard
        return PendingSeekResolution.Apply(seek.positionMs)
    }
}

/**
 * Whether an authoritative controller reconnection must actively synchronize the live player queue
 * to the app-owned queue/order exactly once.
 *
 * True only when the queue is marked dirty ([playerQueueNeedsSync]) AND no queue-replacing / restore
 * / jump request captured while disconnected supersedes it (those reload the player themselves and
 * clear the dirty flag). A stale/superseded `buildAsync` completion never reaches this decision —
 * only the authoritative generation drains pending state (see [ControllerAttemptOwnership]) — so the
 * sync runs, and the dirty flag is cleared, exactly once per authoritative reconnect.
 */
internal fun reconnectRequiresQueueSync(
    playerQueueNeedsSync: Boolean,
    supersededByQueueRequest: Boolean,
): Boolean = playerQueueNeedsSync && !supersededByQueueRequest
