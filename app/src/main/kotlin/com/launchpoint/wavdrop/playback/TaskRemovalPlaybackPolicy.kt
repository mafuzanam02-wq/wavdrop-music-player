package com.launchpoint.wavdrop.playback

/**
 * Task-removal decision for [PlaybackService].
 *
 * Media3's default `MediaSessionService.onTaskRemoved` (verified in 1.11.1 bytecode) stops the
 * service unless playback is ongoing AND a session is actually playing, so a paused-but-resumable
 * session is destroyed on a Recents swipe and headset/notification PLAY can no longer reach
 * Wavdrop. The service-owned player is the source of truth: a real Media3 queue/item means the
 * session is resumable; a stale logical queue over an empty player is not.
 */
internal object TaskRemovalPlaybackPolicy {
    fun shouldKeepSession(mediaItemCount: Int, hasCurrentMediaItem: Boolean): Boolean =
        mediaItemCount > 0 || hasCurrentMediaItem
}
