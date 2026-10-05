package com.launchpoint.wavdrop.playback

import androidx.media3.common.Player

/** What [PlaybackService.onTaskRemoved] does for a Recents swipe (the UI task disappearing, not the process). */
internal enum class TaskRemovalDecision {
    /** A real session-facing queue/item exists: keep the service and session (playing or paused) so transport can still reach it. */
    KeepSession,

    /** Nothing resumable: delegate to Media3's default (pause + stopSelf) so an empty session does not live forever. */
    DefaultStop,
}

/**
 * Task-removal decision for [PlaybackService].
 *
 * Media3's default `MediaSessionService.onTaskRemoved` (verified in 1.11.1 bytecode) stops the
 * service unless playback is ongoing AND a session is actually playing, so a paused-but-resumable
 * session is destroyed on a Recents swipe and headset/notification PLAY can no longer reach
 * Wavdrop. The session-facing player is the source of truth: a real Media3 queue/item means the
 * session is resumable; a stale logical queue over an empty player is not.
 *
 * With the two-slot engine the session-facing player is the [SessionFacade], which presents only the logical CURRENT
 * player. A prepared NEXT queue or a RETIRING player therefore never counts, so they can neither keep an otherwise
 * empty session alive nor be affected by the swipe (the decision reads state and changes nothing).
 */
internal object TaskRemovalPlaybackPolicy {
    fun shouldKeepSession(mediaItemCount: Int, hasCurrentMediaItem: Boolean): Boolean =
        mediaItemCount > 0 || hasCurrentMediaItem

    /** The decision for the session-facing [player] (null = no session player: nothing to keep). Read-only. */
    fun decide(player: Player?): TaskRemovalDecision =
        if (player != null && shouldKeepSession(player.mediaItemCount, player.currentMediaItem != null)) {
            TaskRemovalDecision.KeepSession
        } else {
            TaskRemovalDecision.DefaultStop
        }
}
