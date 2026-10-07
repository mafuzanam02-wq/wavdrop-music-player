package com.launchpoint.wavdrop.playback

import androidx.media3.common.Player

/**
 * Occurrence identity of one crossfade transition: (queueGeneration, from, to) PLAYBACK positions, never a song id, so duplicate
 * songs are irrelevant and a callback from an older queue generation can never affect a newer transition.
 */
internal data class CrossfadeTransitionKey(
    val queueGeneration: Long,
    val fromPlaybackIndex: Int,
    val toPlaybackIndex: Int,
)

/** Why a crossfade attempt (a NEXT preparation or an active overlap) is cancelled. Mapped to overlap policy by [PromotionInterruption]. */
internal enum class CrossfadeCancelReason {
    Pause,
    Seek,
    ManualNavigation,
    QueueMutation,
    ShuffleChanged,
    RepeatChanged,
    ExternalPlayback,
    QueueBecameDirty,
    ControllerDisconnected,
    PlaybackError,
    /** The authoritative current player reached STATE_IDLE or STATE_ENDED (no onPlayerError callback needed). */
    PrimaryPlaybackTerminated,
    MissedWindow,
    ServiceStopping,
    /** The configured crossfade duration is now OFF. */
    ConfigurationDisabled,
    /** The CF-1 plan no longer holds (e.g. duration or next occurrence changed, or EQ became enabled). */
    PlanInvalidated,
}

/**
 * Why a still-owned transition no longer belongs to the live runtime state, or null when it does.
 * Pure, occurrence-based (generation + positions); song ids are never consulted.
 */
internal fun crossfadeOwnershipLossReason(
    snapshot: CrossfadeRuntimeSnapshot,
    key: CrossfadeTransitionKey,
): CrossfadeCancelReason? = when {
    !snapshot.controllerConnected -> CrossfadeCancelReason.ControllerDisconnected
    snapshot.isExternalPlayback -> CrossfadeCancelReason.ExternalPlayback
    snapshot.playerQueueNeedsSync -> CrossfadeCancelReason.QueueBecameDirty
    !snapshot.isPlaying -> CrossfadeCancelReason.Pause
    // EQ became enabled; an overlap would mix processed and unprocessed audio (defensive fallback to the service seam).
    snapshot.equalizerEnabled -> CrossfadeCancelReason.PlanInvalidated
    // ST-1: an armed sleep boundary means the CF-1 plan no longer holds (the current occurrence is the last audible one).
    snapshot.sleepBoundaryArmed -> CrossfadeCancelReason.PlanInvalidated
    snapshot.queueGeneration != key.queueGeneration -> CrossfadeCancelReason.QueueMutation
    snapshot.currentPlaybackIndex != key.fromPlaybackIndex -> CrossfadeCancelReason.ManualNavigation
    key.fromPlaybackIndex !in snapshot.playbackQueue.indices ||
        key.toPlaybackIndex !in snapshot.playbackQueue.indices -> CrossfadeCancelReason.QueueMutation
    // The repeat semantics must still resolve the key's EXACT automatic target (covers Repeat ONE, and e.g.
    // last -> first under Repeat ALL once repeat becomes OFF); harmless repeat changes stay owned.
    QueueNavigator.automaticNextIndex(
        queueSize = snapshot.playbackQueue.size,
        currentIndex = key.fromPlaybackIndex,
        repeatMode = snapshot.repeatMode,
    ) != key.toPlaybackIndex -> CrossfadeCancelReason.RepeatChanged
    else -> null
}

/**
 * The one synchronous "explicit cancellation" lifecycle point. Every `recoverCrossfadeFrom...` family function ends here. The
 * NEXT-slot preparation driver and the promotion runtime both implement it, so one hook invalidates a pending preparation and
 * settles an active overlap (a null sink, i.e. nothing built with the gate false, is a no-op).
 */
internal fun interface CrossfadeCancelSink {
    fun cancel(reason: CrossfadeCancelReason)
}

// ── explicit-cancellation hooks ──────────────────────────────────────────────────────────────────────────────────────────
// Key-less and synchronous. Each runs BEFORE its action is applied (so an active overlap is settled to B first, CF-2M6) and is
// notified once per user command by the owner of that command. A null sink (gate false) is a no-op.

/** The authoritative current player reported a playback error (bad-media queue recovery stays with PlayerController). */
internal fun recoverCrossfadeFromPrimaryPlaybackError(runtime: CrossfadeCancelSink?) {
    runtime?.cancel(CrossfadeCancelReason.PlaybackError)
}

/** An explicit pause that reached the session player. Not wired to onIsPlayingChanged (that can fire for non-user reasons). */
internal fun recoverCrossfadeFromExplicitPause(runtime: CrossfadeCancelSink?) {
    runtime?.cancel(CrossfadeCancelReason.Pause)
}

/** An explicit same-track position seek (app UI or external-controller scrub), before the seek is applied. */
internal fun recoverCrossfadeFromExplicitSeek(runtime: CrossfadeCancelSink?) {
    runtime?.cancel(CrossfadeCancelReason.Seek)
}

/** An explicit user NEXT or PREVIOUS command (also PREVIOUS resolving to restart-current), before navigation. Not for bad-media recovery or natural transitions. */
internal fun recoverCrossfadeFromExplicitNavigation(runtime: CrossfadeCancelSink?) {
    runtime?.cancel(CrossfadeCancelReason.ManualNavigation)
}

/** The cancel reason of an explicit repeat-mode change (a named seam so a pure test can assert it). */
internal val REPEAT_CHANGE_CANCEL_REASON = CrossfadeCancelReason.RepeatChanged

/** An explicit user repeat-mode change, before the new mode is applied. */
internal fun recoverCrossfadeFromRepeatChange(runtime: CrossfadeCancelSink?) {
    runtime?.cancel(REPEAT_CHANGE_CANCEL_REASON)
}

/** The cancel reason of an explicit LOGICAL shuffle toggle (native Media3 shuffle attempts are reasserted off and never reach this). */
internal val SHUFFLE_CHANGE_CANCEL_REASON = CrossfadeCancelReason.ShuffleChanged

/** PlayerController.toggleShuffle, before shuffle planning or the queue-generation bump. */
internal fun recoverCrossfadeFromShuffleChange(runtime: CrossfadeCancelSink?) {
    runtime?.cancel(SHUFFLE_CHANGE_CANCEL_REASON)
}

/** The cancel reason when a sleep-timer terminal boundary becomes armed (the CF-1 plan no longer holds). Settles an active overlap to B. */
internal val SLEEP_BOUNDARY_CANCEL_REASON = CrossfadeCancelReason.PlanInvalidated

/** A sleep-timer terminal boundary was just armed on the logical CURRENT occurrence: end any owned preparation/overlap, once. */
internal fun recoverCrossfadeFromSleepBoundary(runtime: CrossfadeCancelSink?) {
    runtime?.cancel(SLEEP_BOUNDARY_CANCEL_REASON)
}

/** The cancel reason of an explicit Play Next-family queue mutation. */
internal val PLAY_NEXT_MUTATION_CANCEL_REASON = CrossfadeCancelReason.QueueMutation

/** playNext, playAllNext, moveToPlayNext: before the generation bump and any queue/Media3 mutation. One command, one cancel. */
internal fun recoverCrossfadeFromPlayNextMutation(runtime: CrossfadeCancelSink?) {
    runtime?.cancel(PLAY_NEXT_MUTATION_CANCEL_REASON)
}

/** The cancel reason of an explicit Add to Queue-family mutation. */
internal val ADD_TO_QUEUE_MUTATION_CANCEL_REASON = CrossfadeCancelReason.QueueMutation

/** addToQueue, addAllToQueue: before planning and the generation bump. */
internal fun recoverCrossfadeFromAddToQueueMutation(runtime: CrossfadeCancelSink?) {
    runtime?.cancel(ADD_TO_QUEUE_MUTATION_CANCEL_REASON)
}

/** The cancel reason of an explicit arbitrary future queue reorder. */
internal val QUEUE_REORDER_CANCEL_REASON = CrossfadeCancelReason.QueueMutation

/** moveQueueItemUp, moveQueueItemDown, moveQueueItemTo: before validation and the generation bump. */
internal fun recoverCrossfadeFromQueueReorder(runtime: CrossfadeCancelSink?) {
    runtime?.cancel(QUEUE_REORDER_CANCEL_REASON)
}

/** The cancel reason of an explicit queue removal or bulk clear. */
internal val QUEUE_REMOVAL_CANCEL_REASON = CrossfadeCancelReason.QueueMutation

/** removeFromQueue, clearEarlierQueue, clearUpNext: before validation and the generation bump. */
internal fun recoverCrossfadeFromQueueRemoval(runtime: CrossfadeCancelSink?) {
    runtime?.cancel(QUEUE_REMOVAL_CANCEL_REASON)
}

/** The cancel reason of a library song deletion. */
internal val LIBRARY_DELETION_CANCEL_REASON = CrossfadeCancelReason.QueueMutation

/** PlayerController.handleSongDeleted (the single public deletion boundary), before the current occurrence is resolved. */
internal fun recoverCrossfadeFromLibraryDeletion(runtime: CrossfadeCancelSink?) {
    runtime?.cancel(LIBRARY_DELETION_CANCEL_REASON)
}

/** The cancel reason of an explicit whole-queue replacement. */
internal val QUEUE_REPLACEMENT_CANCEL_REASON = CrossfadeCancelReason.QueueMutation

/** An explicit user playback start that replaces the active queue, before validation and any logical mutation. */
internal fun recoverCrossfadeFromQueueReplacement(runtime: CrossfadeCancelSink?) {
    runtime?.cancel(QUEUE_REPLACEMENT_CANCEL_REASON)
}

/** The cancel reason of a service-owned playback-resumption adoption. */
internal val PLAYBACK_RESUMPTION_CANCEL_REASON = CrossfadeCancelReason.QueueMutation

/**
 * Only a resumption that will actually be ADOPTED replaces the authoritative queue, so ownership ends only for a Ready mapping
 * with isForPlayback == true; a mere query, an Unavailable result or a mapping failure must not cancel.
 */
internal fun shouldCancelCrossfadeForPlaybackResumption(resultReady: Boolean, isForPlayback: Boolean): Boolean =
    resultReady && isForPlayback

/** Run immediately before a service-owned playback resumption is adopted. */
internal fun recoverCrossfadeFromPlaybackResumption(runtime: CrossfadeCancelSink?) {
    runtime?.cancel(PLAYBACK_RESUMPTION_CANCEL_REASON)
}

/** STATE_IDLE and STATE_ENDED are terminal for the authoritative current player; BUFFERING and READY are not. */
internal fun isPrimaryTerminalPlaybackState(playbackState: Int): Boolean =
    playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED

/** The cancel reason of a current-player terminal playback state (distinct from PlaybackError and Pause). */
internal val PRIMARY_TERMINAL_STATE_CANCEL_REASON = CrossfadeCancelReason.PrimaryPlaybackTerminated

/** The current player reports STATE_IDLE or STATE_ENDED; a repeated terminal callback (error then IDLE) is idempotent downstream. */
internal fun recoverCrossfadeFromPrimaryTerminalState(runtime: CrossfadeCancelSink?) {
    runtime?.cancel(PRIMARY_TERMINAL_STATE_CANCEL_REASON)
}

/** The cancel reason of an authoritative MediaController disconnection. */
internal val CONTROLLER_DISCONNECTED_CANCEL_REASON = CrossfadeCancelReason.ControllerDisconnected

/** The CURRENT authoritative MediaController disconnects (identity-guarded by PlayerController), before it clears its reference. */
internal fun recoverCrossfadeFromControllerDisconnected(runtime: CrossfadeCancelSink?) {
    runtime?.cancel(CONTROLLER_DISCONNECTED_CANCEL_REASON)
}

/** Which Media3-authoritative interruption (if any) a physical-player callback reports. */
internal enum class PrimaryPlaybackInterruption {
    None,
    AudioFocus,
    AudioRoute,
}

/**
 * Classifies onPlayWhenReadyChanged(playWhenReady, reason). Only a pause whose Media3 reason is AUDIO_FOCUS_LOSS or
 * AUDIO_BECOMING_NOISY is an interruption; USER_REQUEST, REMOTE, END_OF_MEDIA_ITEM, SUPPRESSED_TOO_LONG and any resume are not.
 */
internal fun classifyPrimaryPlayWhenReadyInterruption(playWhenReady: Boolean, reason: Int): PrimaryPlaybackInterruption {
    if (playWhenReady) return PrimaryPlaybackInterruption.None
    return when (reason) {
        Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS -> PrimaryPlaybackInterruption.AudioFocus
        Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY -> PrimaryPlaybackInterruption.AudioRoute
        else -> PrimaryPlaybackInterruption.None
    }
}

/** Classifies onPlaybackSuppressionReasonChanged: transient focus loss is audio focus; unsuitable route/output are route interruptions. */
internal fun classifyPrimarySuppressionInterruption(suppressionReason: Int): PrimaryPlaybackInterruption =
    when (suppressionReason) {
        Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS -> PrimaryPlaybackInterruption.AudioFocus
        Player.PLAYBACK_SUPPRESSION_REASON_UNSUITABLE_AUDIO_ROUTE,
        Player.PLAYBACK_SUPPRESSION_REASON_UNSUITABLE_AUDIO_OUTPUT -> PrimaryPlaybackInterruption.AudioRoute
        else -> PrimaryPlaybackInterruption.None
    }

/** The cancel reason of a physical audio-focus / route interruption (existing Pause). */
internal val PRIMARY_INTERRUPTION_CANCEL_REASON = CrossfadeCancelReason.Pause

/** Media3 reports an audio-focus or route interruption of the authoritative current player. No resurrection when it returns. */
internal fun recoverCrossfadeFromPrimaryInterruption(runtime: CrossfadeCancelSink?) {
    runtime?.cancel(PRIMARY_INTERRUPTION_CANCEL_REASON)
}

/** Only an OFF -> ON Equalizer change ends crossfade ownership; ON -> OFF never resurrects or cancels. */
internal fun shouldCancelCrossfadeForEqualizerChange(previousEnabled: Boolean, newEnabled: Boolean): Boolean =
    !previousEnabled && newEnabled

/** The Equalizer becomes enabled (the engine's players carry no mirrored EQ; CF-2M9 owns any future support). */
internal fun recoverCrossfadeFromEqualizerEnabled(runtime: CrossfadeCancelSink?) {
    runtime?.cancel(CrossfadeCancelReason.PlanInvalidated)
}

/**
 * Crossfade stays unavailable while the Equalizer is on, and also until the persisted EQ state has been read after a service
 * (re)creation: an unknown state is treated conservatively as "on", so a recreated service can never start a crossfade before it
 * knows whether the Equalizer is enabled.
 */
internal fun crossfadeEqualizerBlocks(equalizerStateKnown: Boolean, equalizerEnabled: Boolean): Boolean =
    !equalizerStateKnown || equalizerEnabled
