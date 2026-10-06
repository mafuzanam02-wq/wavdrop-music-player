package com.launchpoint.wavdrop.playback

import androidx.media3.common.Player

/**
 * Native-gapless contract (G-1)
 * -----------------------------
 * WavDrop hands ExoPlayer a full playlist and lets Media3 advance through it. A natural end of
 * track is [MediaItemTransitionKind.Auto]: ExoPlayer moves straight to the already-queued next
 * MediaItem, and the application must not prepare, seek, play/pause, rebuild the playlist or
 * recreate the player in response. WavDrop only *follows* that transition (state, stats, session).
 *
 * This preserves Media3's gapless-capable path; it does not guarantee a seamless join for every
 * pair of files — trimming depends on container/encoder metadata, codec, decoder and device.
 *
 * Explicit setup paths (initial play, cold restore/hydration, external media, bad-media recovery,
 * user jump/queue replacement) may still call prepare/setMediaItems; they are not natural
 * transitions. Audio offload is intentionally not enabled here.
 */
internal enum class MediaItemTransitionKind { Auto, Seek, Repeat, PlaylistChanged, Unknown }

/** Maps a Media3 `MEDIA_ITEM_TRANSITION_REASON_*` value; unrecognised values are [MediaItemTransitionKind.Unknown]. */
internal fun classifyMediaItemTransition(reason: Int): MediaItemTransitionKind = when (reason) {
    Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> MediaItemTransitionKind.Auto
    Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> MediaItemTransitionKind.Seek
    Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> MediaItemTransitionKind.Repeat
    Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> MediaItemTransitionKind.PlaylistChanged
    else -> MediaItemTransitionKind.Unknown
}

/** True only for ExoPlayer's own advance to the next queued item. Repeat-one loops are [Repeat], not this. */
internal val MediaItemTransitionKind.isNaturalAdvance: Boolean
    get() = this == MediaItemTransitionKind.Auto

/**
 * Whether the app has to re-push the queue to the player at a natural boundary. True only when the
 * player's playlist is known to be out of step with the logical queue (`playerQueueNeedsSync`, e.g.
 * after a deferred shuffle reorder or a failed queue op). A synced queue must be left alone so the
 * native transition is the only thing that happens.
 */
internal fun naturalTransitionRequiresQueueResync(
    kind: MediaItemTransitionKind,
    playerQueueNeedsSync: Boolean,
): Boolean = kind.isNaturalAdvance && playerQueueNeedsSync

/**
 * A Media3 MediaController re-reports a STALE automatic discontinuity / media-item transition (the old
 * `0 -> startIndex` pair from the last real discontinuity) after EVERY controller-issued playlist mutation, including pure
 * repairs that never touch the current item (observed on real Media3: the server player emits only a timeline change). Such an
 * echo must not reach stats, the sleep timer or session ownership as if the song had changed.
 *
 * A genuine natural advance always lands on a different logical occurrence than the one Now Playing last synced, so an
 * automatic callback whose resolved occurrence equals the synced one is an echo, unless the queue can legitimately loop onto
 * the same occurrence (Repeat ONE, or Repeat ALL over a single item), which keeps the existing loop handling.
 */
internal fun isStaleAutomaticAdvanceEcho(
    resolvedIndex: Int,
    syncedIndex: Int,
    queueSize: Int,
    repeatMode: RepeatMode,
): Boolean {
    val selfLoopPossible = repeatMode == RepeatMode.ONE || (repeatMode == RepeatMode.ALL && queueSize == 1)
    return resolvedIndex == syncedIndex && !selfLoopPossible
}
