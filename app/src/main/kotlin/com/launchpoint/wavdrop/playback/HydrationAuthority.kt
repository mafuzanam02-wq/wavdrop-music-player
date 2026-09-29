package com.launchpoint.wavdrop.playback

/** What the physical Media3 player and external-playback state say about session hydration. */
internal enum class HydrationDecision {
    /** External playback owns the player; never restore Wavdrop's queue over it. */
    SKIP_EXTERNAL,

    /** The Media3 player physically holds media; nothing to rebuild. */
    ALREADY_HYDRATED,

    /** The Media3 player is empty (a stale logical queue does not count as hydration). */
    NEEDS_HYDRATION,
}

/**
 * Physical Media3 queue authority for session hydration. The Wavdrop logical queue is not proof
 * that the player holds media, so it is deliberately not an input. A missing controller is not
 * "empty" either: callers must obtain a controller first (unavailable stays retryable).
 */
internal object HydrationAuthority {
    fun mediaQueuePresent(mediaItemCount: Int, hasCurrentMediaItem: Boolean): Boolean =
        mediaItemCount > 0 || hasCurrentMediaItem

    fun decide(isExternalPlayback: Boolean, mediaQueuePresent: Boolean): HydrationDecision = when {
        isExternalPlayback -> HydrationDecision.SKIP_EXTERNAL
        mediaQueuePresent -> HydrationDecision.ALREADY_HYDRATED
        else -> HydrationDecision.NEEDS_HYDRATION
    }

    /**
     * Re-check immediately before applying the persisted snapshot (after suspending controller,
     * persistence and settings work): only an empty, non-external player may be overwritten.
     */
    fun mayApplySnapshot(isExternalPlayback: Boolean, mediaQueuePresent: Boolean): Boolean =
        decide(isExternalPlayback, mediaQueuePresent) == HydrationDecision.NEEDS_HYDRATION
}
