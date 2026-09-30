package com.launchpoint.wavdrop.playback

import com.launchpoint.wavdrop.data.model.Song

/**
 * How a shuffle toggle's new playback order reaches the physical Media3 playlist (G-1).
 *
 * The current item is never replaced, re-prepared or re-sought: the playlist is transformed around
 * it, so the next natural boundary stays a pure Media3 AUTO transition.
 */
internal sealed interface ShufflePhysicalSyncPlan {
    /** The physical playlist already matches the new order; nothing to do. */
    data object NoOp : ShufflePhysicalSyncPlan

    /** Live sync is not provably safe; keep the existing deferred `playerQueueNeedsSync` recovery. */
    data object MarkDirty : ShufflePhysicalSyncPlan

    /**
     * Replace the suffix strictly after [oldCurrentPhysicalIndex] with [desiredSuffix], then the
     * prefix strictly before it with [desiredPrefix]. The current item shifts to
     * [newCurrentPhysicalIndex] as the prefix length changes.
     */
    data class ReplaceAroundCurrent(
        val oldCurrentPhysicalIndex: Int,
        val newCurrentPhysicalIndex: Int,
        val desiredPrefix: List<Song>,
        val desiredSuffix: List<Song>,
    ) : ShufflePhysicalSyncPlan
}

/** Only [ShufflePhysicalSyncPlan.MarkDirty] leaves the player queue needing a later sync. */
internal val ShufflePhysicalSyncPlan.leavesPlayerQueueDirty: Boolean
    get() = this is ShufflePhysicalSyncPlan.MarkDirty

/**
 * Pure decision for synchronizing a shuffle toggle to the physical Media3 playlist.
 *
 * Live sync requires that the physical queue is *known* aligned with the old logical queue:
 * controller present, [playerQueueNeedsSync] false, both current indexes valid, the controller's
 * current index equal to [oldCurrentPlaybackIndex], and its item count equal to the old queue
 * size. The current occurrence is identified by index (never by song id); the new queue must hold
 * an equal song at [newCurrentPlaybackIndex] as a sanity check of the shuffle model's guarantee.
 * Any violated invariant → [ShufflePhysicalSyncPlan.MarkDirty], never a guess.
 */
internal fun planShufflePhysicalSync(
    oldPlaybackQueue: List<Song>,
    newPlaybackQueue: List<Song>,
    oldCurrentPlaybackIndex: Int,
    newCurrentPlaybackIndex: Int,
    controllerAvailable: Boolean,
    controllerCurrentIndex: Int?,
    controllerMediaItemCount: Int?,
    playerQueueNeedsSync: Boolean,
): ShufflePhysicalSyncPlan {
    if (!controllerAvailable) return ShufflePhysicalSyncPlan.MarkDirty
    if (playerQueueNeedsSync) return ShufflePhysicalSyncPlan.MarkDirty
    if (oldCurrentPlaybackIndex !in oldPlaybackQueue.indices) return ShufflePhysicalSyncPlan.MarkDirty
    if (newCurrentPlaybackIndex !in newPlaybackQueue.indices) return ShufflePhysicalSyncPlan.MarkDirty
    if (controllerCurrentIndex != oldCurrentPlaybackIndex) return ShufflePhysicalSyncPlan.MarkDirty
    if (controllerMediaItemCount != oldPlaybackQueue.size) return ShufflePhysicalSyncPlan.MarkDirty
    if (newPlaybackQueue.size != oldPlaybackQueue.size) return ShufflePhysicalSyncPlan.MarkDirty
    if (newPlaybackQueue[newCurrentPlaybackIndex] != oldPlaybackQueue[oldCurrentPlaybackIndex]) {
        return ShufflePhysicalSyncPlan.MarkDirty
    }
    if (oldCurrentPlaybackIndex == newCurrentPlaybackIndex && oldPlaybackQueue == newPlaybackQueue) {
        return ShufflePhysicalSyncPlan.NoOp
    }
    return ShufflePhysicalSyncPlan.ReplaceAroundCurrent(
        oldCurrentPhysicalIndex = oldCurrentPlaybackIndex,
        newCurrentPhysicalIndex = newCurrentPlaybackIndex,
        desiredPrefix = newPlaybackQueue.subList(0, newCurrentPlaybackIndex).toList(),
        desiredSuffix = newPlaybackQueue.subList(newCurrentPlaybackIndex + 1, newPlaybackQueue.size).toList(),
    )
}

/** Where a live shuffle sync stands; drives the `playerQueueNeedsSync` flag. */
internal enum class ShuffleLiveSyncProgress { BeforeMutation, Succeeded, Failed }

/**
 * The `playerQueueNeedsSync` value for [this] plan at [progress].
 *
 * A live replacement is dirty before and during the Media3 mutation (callbacks can observe the
 * intermediate physical order) and clean only after both replacements succeeded; a failure leaves
 * it dirty so deferred recovery stays authoritative. `NoOp` is clean, `MarkDirty` is dirty.
 */
internal fun ShufflePhysicalSyncPlan.playerQueueNeedsSync(progress: ShuffleLiveSyncProgress): Boolean =
    when (this) {
        ShufflePhysicalSyncPlan.NoOp -> false
        ShufflePhysicalSyncPlan.MarkDirty -> true
        is ShufflePhysicalSyncPlan.ReplaceAroundCurrent -> progress != ShuffleLiveSyncProgress.Succeeded
    }
