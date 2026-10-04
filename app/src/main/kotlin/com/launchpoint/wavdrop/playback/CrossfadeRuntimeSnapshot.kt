package com.launchpoint.wavdrop.playback

import com.launchpoint.wavdrop.data.model.Song

/**
 * Immutable, occurrence-safe view of the logical playback truth that [PlayerController] owns
 * (CF-2B1). A future service-side crossfade engine reads this instead of reaching into controller
 * state. Pure data: no player, controller, context or mutable collection is held.
 *
 * [currentPlaybackIndex] comes from the controller's existing occurrence-safe resolution and is
 * null when the current occurrence cannot be safely established (never a first-song-id guess).
 * [controllerConnected] is a runtime ownership signal only; CF-1 deliberately ignores it.
 */
internal data class CrossfadeRuntimeSnapshot(
    val queueGeneration: Long,
    val playbackQueue: List<Song>,
    val currentPlaybackIndex: Int?,
    val repeatMode: RepeatMode,
    val shuffleEnabled: Boolean,
    val isPlaying: Boolean,
    val isExternalPlayback: Boolean,
    val playerQueueNeedsSync: Boolean,
    val controllerConnected: Boolean,
    /** CF-2I2: runtime compatibility fact only (the service composes it; the controller never owns EQ settings). */
    val equalizerEnabled: Boolean = false,
)

/** Delegates to CF-1 [planCrossfadeTransition]; an unknown occurrence maps to an invalid index. */
internal fun planCrossfadeFromRuntimeSnapshot(
    configuredDurationMs: Long,
    snapshot: CrossfadeRuntimeSnapshot,
    currentDurationOverrideMs: Long?,
): CrossfadeTransitionPlan = planCrossfadeTransition(
    configuredDurationMs = configuredDurationMs,
    playbackQueue = snapshot.playbackQueue,
    currentPlaybackIndex = snapshot.currentPlaybackIndex ?: -1,
    repeatMode = snapshot.repeatMode,
    isPlaying = snapshot.isPlaying,
    isExternalPlayback = snapshot.isExternalPlayback,
    playerQueueNeedsSync = snapshot.playerQueueNeedsSync,
    equalizerEnabled = snapshot.equalizerEnabled,
    currentDurationMs = currentDurationOverrideMs,
)

/**
 * Final occurrence binding of an eligible CF-1 plan: (generation, current, next). Null when the
 * snapshot cannot safely own a transition. Positional only; song ids are never consulted.
 */
internal fun bindCrossfadeTransition(
    snapshot: CrossfadeRuntimeSnapshot,
    plan: CrossfadeTransitionPlan.Eligible,
): CrossfadeTransitionKey? {
    val from = snapshot.currentPlaybackIndex ?: return null
    val to = plan.nextPlaybackIndex
    val indices = snapshot.playbackQueue.indices
    if (snapshot.queueGeneration < 0L) return null
    if (from !in indices || to !in indices || from == to) return null
    if (snapshot.isExternalPlayback || snapshot.playerQueueNeedsSync || !snapshot.isPlaying) return null
    return CrossfadeTransitionKey(snapshot.queueGeneration, from, to)
}

/**
 * Whether this snapshot still describes the source occurrence that armed [key]. Says nothing about
 * why an index changed (natural AUTO vs manual navigation) — that is classified from player callbacks.
 */
internal fun CrossfadeRuntimeSnapshot.ownsCrossfadeSource(key: CrossfadeTransitionKey): Boolean =
    queueGeneration == key.queueGeneration &&
        currentPlaybackIndex == key.fromPlaybackIndex &&
        key.fromPlaybackIndex in playbackQueue.indices &&
        key.toPlaybackIndex in playbackQueue.indices &&
        !isExternalPlayback &&
        !playerQueueNeedsSync &&
        isPlaying
