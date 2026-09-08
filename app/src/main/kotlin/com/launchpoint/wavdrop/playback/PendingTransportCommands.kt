package com.launchpoint.wavdrop.playback

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
 *  - Seek        — latest position wins, BOUND to the track it was issued against; dropped if the
 *    current track changed before reconnect (never seeks a different song to an old position).
 *  - Navigation  — at most one pending next/previous intent, latest direction wins; recomputed
 *    against the live queue at execution so a mutated queue cannot replay a stale skip.
 *  - Any queue-replacing/restore/jump request captured while disconnected supersedes all of the
 *    above (they refer to a queue/session that the replacement invalidates).
 */
internal enum class NavigationIntent { NEXT, PREVIOUS }

internal data class PendingSeek(val positionMs: Long, val targetSongId: Long)

internal object PendingTransport {

    /** Desired play/pause state derived at tap time (STATE_INTENT) — not a raw toggle to replay. */
    fun desiredPlayWhenReady(currentlyPlaying: Boolean): Boolean = !currentlyPlaying

    /**
     * A deferred seek applies only when its captured track is still current on reconnect; otherwise
     * it is stale and must be dropped so it never seeks a different song to an old position.
     */
    fun shouldApplySeek(seek: PendingSeek, currentSongId: Long?): Boolean =
        currentSongId != null && currentSongId == seek.targetSongId
}

/**
 * Whether toggling shuffle must mark the app-owned queue as diverged from the live player
 * (`PlayerController.playerQueueNeedsSync`).
 *
 * The pre-fix implementation ANDed this with controller availability
 * (`controller != null && !requiresCurrentItemReplacement`), so a toggle performed while the
 * controller was momentarily unavailable never recorded the divergence. On a WARM reconnect
 * (PlaybackService/session still alive, so the player is never reloaded from the persisted session
 * as it is on a COLD start) the player's internal traversal order was left stale with nothing
 * pending to synchronize it — the app-owned playbackQueue/playbackOrder disagreed with the live
 * player's order until some later queue operation happened to trigger a sync.
 *
 * Controller availability is therefore irrelevant to whether a sync is required: the app-owned
 * order is authoritative and any change to it must be recorded. [requiresCurrentItemReplacement]
 * stays honoured for parity with [QueueMutation.ShuffleToggleResult]: a model that swaps the current
 * item would reload the player outright rather than mark it dirty for deferred synchronization.
 */
internal fun shuffleToggleRequiresQueueSync(requiresCurrentItemReplacement: Boolean): Boolean =
    !requiresCurrentItemReplacement

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
