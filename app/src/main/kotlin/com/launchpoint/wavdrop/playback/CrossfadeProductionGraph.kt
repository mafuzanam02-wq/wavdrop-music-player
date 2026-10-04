package com.launchpoint.wavdrop.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player

/** The primary's physical duration when usable; Media3's C.TIME_UNSET (negative) and 0 are unknown (null). */
internal fun usableCrossfadePrimaryDuration(durationMs: Long): Long? = durationMs.takeIf { it > 0L }

/** Production adapter: delegates exactly once, unchanged, to the primary reconciliation (PlayerController). */
internal fun crossfadePrimaryReconciler(
    reconcile: (CrossfadeTransitionKey, SecondaryHandoffSnapshot) -> CrossfadePrimaryReconciliationResult,
): CrossfadePrimaryReconciler = CrossfadePrimaryReconciler { key, snapshot -> reconcile(key, snapshot) }

/** The service-owned runtime plus the (never started here) timing driver built against that exact runtime. */
internal class CrossfadeProductionGraph(
    val runtime: CrossfadePreparationRuntime,
    val timingDriver: CrossfadeTimingDriver,
)

/**
 * Composition only. Builds the runtime and a timing driver over that same runtime; it does NOT start the driver
 * (the service-owned activation policy, CF-2E2, starts/stops it from the persisted duration). The configured duration
 * is a plain synchronous provider supplied by the service (no DataStore here). Nothing here touches a player.
 */
internal fun createCrossfadeProductionGraph(
    snapshotProvider: () -> CrossfadeRuntimeSnapshot,
    backendFactory: () -> SecondaryPlayerBackend,
    primaryGainBackend: PrimaryGainBackend,
    reconcilePrimary: (CrossfadeTransitionKey, SecondaryHandoffSnapshot) -> CrossfadePrimaryReconciliationResult,
    scheduler: CrossfadeTimingScheduler,
    clock: CrossfadeMonotonicClock,
    configuredDurationMsProvider: () -> Long,
    primaryDurationMs: () -> Long,
    primaryPositionMs: () -> Long,
    primaryAudioSessionId: () -> Int = { 0 },
    audioSessionObserver: CrossfadeAudioSessionObserver = CrossfadeAudioSessionObserver.NoOp,
    // CF-2L1: both supplied -> natural-AUTO handoff (secondary stays audible until the primary is READY on the target).
    primaryTakeoverFacts: (() -> PrimaryTakeoverFacts?)? = null,
    reconcilePrimaryAfterNaturalTransition: ((CrossfadeTransitionKey, SecondaryHandoffSnapshot) -> CrossfadePrimaryReconciliationResult)? = null,
    // CF-2L2: DEBUG-only transfer observability sink (null in release builds).
    naturalTransferLog: ((String) -> Unit)? = null,
    mediaItemFactory: (com.launchpoint.wavdrop.data.model.Song) -> MediaItem = { it.toPlaybackMediaItem() },
): CrossfadeProductionGraph {
    val runtime = CrossfadePreparationRuntime(
        snapshotProvider = snapshotProvider,
        backendFactory = backendFactory,
        mediaItemFactory = mediaItemFactory,
        primaryGainBackend = primaryGainBackend,
        primaryReconciler = crossfadePrimaryReconciler(reconcilePrimary),
        primaryAudioSessionId = primaryAudioSessionId,
        audioSessionObserver = audioSessionObserver,
        naturalHandoff = if (primaryTakeoverFacts != null && reconcilePrimaryAfterNaturalTransition != null) {
            CrossfadeNaturalHandoffSeams(
                primaryFacts = primaryTakeoverFacts,
                reconciler = crossfadePrimaryReconciler(reconcilePrimaryAfterNaturalTransition),
                nowElapsedRealtimeMs = { clock.nowMs() }, // the driver's own monotonic clock
                transferLog = naturalTransferLog,
            )
        } else {
            null
        },
    )
    val driver = CrossfadeTimingDriver(
        runtime = runtime,
        scheduler = scheduler,
        clock = clock,
        configuredDurationMsProvider = configuredDurationMsProvider,
        currentDurationMsProvider = { usableCrossfadePrimaryDuration(primaryDurationMs()) },
        currentPositionMsProvider = primaryPositionMs,
    )
    return CrossfadeProductionGraph(runtime, driver)
}

/**
 * Teardown order: invalidate timing callbacks FIRST, then close the runtime (which restores any lowered primary
 * gain and releases the secondary). The caller must still release the primary player only afterwards. Both
 * closes are idempotent, so calling this twice is harmless.
 */
internal fun closeCrossfadeGraph(driver: CrossfadeTimingDriver?, runtime: CrossfadePreparationRuntime?) {
    driver?.close()
    runtime?.close()
}

/**
 * CF-2F1: key-less, synchronous cleanup when the authoritative primary player reports a playback error. The runtime
 * owns the cleanup (Fading/HandoffPending restore primary gain before abandoning the secondary; Armed/Ready abandon
 * the secondary; Idle is harmless). The timing driver is deliberately left alone: activation belongs to the
 * persisted-duration policy and bad-media queue recovery stays with PlayerController. A null runtime (gate false) is a no-op.
 */
internal fun recoverCrossfadeFromPrimaryPlaybackError(runtime: CrossfadePreparationRuntime?) {
    runtime?.cancel(CrossfadeCancelReason.PlaybackError)
}

/**
 * CF-2G1: key-less, synchronous crossfade cleanup for an explicit pause that reached the session player. Must run
 * BEFORE the pause is forwarded to the primary so a Fading/HandoffPending cleanup can still restore the primary
 * gain. Same runtime-owned cleanup as other cancellations; the timing driver is left running and a null runtime
 * (gate false) is a no-op. Deliberately not wired to onIsPlayingChanged (that can fire for non-user reasons).
 */
internal fun recoverCrossfadeFromExplicitPause(runtime: CrossfadePreparationRuntime?) {
    runtime?.cancel(CrossfadeCancelReason.Pause)
}

/**
 * CF-2G2: key-less, synchronous crossfade cleanup for an explicit same-track position seek (app UI seek or an
 * external-controller scrub), run BEFORE the seek is applied. Never used for crossfade-owned seeks (CF-2D2 primary
 * reconciliation) or other internal app-controller seeks. Uses [CrossfadeCancelReason.Seek]; ManualNavigation is
 * reserved for next/previous. The timing driver is left running; a null runtime (gate false) is a no-op.
 */
internal fun recoverCrossfadeFromExplicitSeek(runtime: CrossfadePreparationRuntime?) {
    runtime?.cancel(CrossfadeCancelReason.Seek)
}

/**
 * CF-2G3: key-less, synchronous crossfade cleanup for an explicit user NEXT or PREVIOUS command (app skipToNext/
 * skipToPrevious or an external controller), run BEFORE navigation is applied or deferred. Also used when PREVIOUS
 * resolves to restart-current (the initiating command was PREVIOUS, not a scrub, so never [CrossfadeCancelReason.Seek]).
 * Not used for bad-media recovery or natural transitions. The timing driver is left running; a null runtime (gate
 * false) is a no-op.
 */
internal fun recoverCrossfadeFromExplicitNavigation(runtime: CrossfadePreparationRuntime?) {
    runtime?.cancel(CrossfadeCancelReason.ManualNavigation)
}

/** CF-2H1: the cancel reason of an explicit repeat-mode change (a named seam so a pure test can assert it). */
internal val REPEAT_CHANGE_CANCEL_REASON = CrossfadeCancelReason.RepeatChanged

/**
 * CF-2H1: key-less, synchronous crossfade cleanup for an explicit user repeat-mode change, run BEFORE the new repeat
 * mode is applied to logical state or Media3. Ownership ends from user intent (even when the new mode would not
 * invalidate the planned target); the runtime's own `crossfadeOwnershipLossReason` stays as the defensive fallback.
 * The timing driver is left running; a null runtime (gate false) is a no-op.
 */
internal fun recoverCrossfadeFromRepeatChange(runtime: CrossfadePreparationRuntime?) {
    runtime?.cancel(REPEAT_CHANGE_CANCEL_REASON)
}

/** CF-2H2: the cancel reason of an explicit logical shuffle toggle (a named seam so a pure test can assert it). */
internal val SHUFFLE_CHANGE_CANCEL_REASON = CrossfadeCancelReason.ShuffleChanged

/**
 * CF-2H2: key-less, synchronous crossfade cleanup for an explicit LOGICAL shuffle toggle (`PlayerController.toggleShuffle`),
 * run BEFORE shuffle planning or the queue-generation bump. Cancels from user intent even if the toggle later no-ops.
 * Native Media3 shuffle attempts (reasserted off) are not a logical shuffle and never reach this. The runtime's generation
 * check (`QueueMutation`) stays as the defensive fallback. The timing driver is left running; a null runtime is a no-op.
 */
internal fun recoverCrossfadeFromShuffleChange(runtime: CrossfadePreparationRuntime?) {
    runtime?.cancel(SHUFFLE_CHANGE_CANCEL_REASON)
}

/** CF-2H3A: the cancel reason of an explicit Play Next-family queue mutation (a named seam so a pure test can assert it). */
internal val PLAY_NEXT_MUTATION_CANCEL_REASON = CrossfadeCancelReason.QueueMutation

/**
 * CF-2H3A: key-less, synchronous crossfade cleanup for an explicit Play Next-family command (playNext, playAllNext,
 * moveToPlayNext), run BEFORE the queue-generation bump and any queue/Media3 mutation. Cancels from user intent even if
 * the command then falls back or no-ops. Internal helpers (insert/append/playFromQueue) never notify, so one command
 * yields one cancel. The runtime's generation check (also `QueueMutation`) stays as the defensive fallback. The timing
 * driver is left running; a null runtime is a no-op.
 */
internal fun recoverCrossfadeFromPlayNextMutation(runtime: CrossfadePreparationRuntime?) {
    runtime?.cancel(PLAY_NEXT_MUTATION_CANCEL_REASON)
}

/** CF-2H3B: the cancel reason of an explicit Add to Queue-family mutation (a named seam so a pure test can assert it). */
internal val ADD_TO_QUEUE_MUTATION_CANCEL_REASON = CrossfadeCancelReason.QueueMutation

/**
 * CF-2H3B: key-less, synchronous crossfade cleanup for an explicit tail-append command (addToQueue, addAllToQueue), run
 * BEFORE planning and the generation bump. Cancels from user intent even if the command then no-ops or starts a new
 * queue. The shared append helper never notifies (it is also reached from the Play Next fallbacks, which own their own
 * seam), so one command yields one cancel. The runtime generation check stays as the defensive fallback. The timing
 * driver is left running; a null runtime is a no-op.
 */
internal fun recoverCrossfadeFromAddToQueueMutation(runtime: CrossfadePreparationRuntime?) {
    runtime?.cancel(ADD_TO_QUEUE_MUTATION_CANCEL_REASON)
}

/** CF-2H3C: the cancel reason of an explicit arbitrary future queue reorder (a named seam so a pure test can assert it). */
internal val QUEUE_REORDER_CANCEL_REASON = CrossfadeCancelReason.QueueMutation

/**
 * CF-2H3C: key-less, synchronous crossfade cleanup for an explicit arbitrary future reorder (moveQueueItemUp,
 * moveQueueItemDown, moveQueueItemTo), run BEFORE validation and the generation bump. Cancels from user intent even if the
 * request then no-ops. The private swap helper never notifies, and moveToPlayNext keeps its own Play Next seam, so one
 * command yields one cancel. The runtime generation check stays as the defensive fallback. The timing driver is left
 * running; a null runtime is a no-op.
 */
internal fun recoverCrossfadeFromQueueReorder(runtime: CrossfadePreparationRuntime?) {
    runtime?.cancel(QUEUE_REORDER_CANCEL_REASON)
}

/** CF-2H3D: the cancel reason of an explicit queue removal or bulk clear (a named seam so a pure test can assert it). */
internal val QUEUE_REMOVAL_CANCEL_REASON = CrossfadeCancelReason.QueueMutation

/**
 * CF-2H3D: key-less, synchronous crossfade cleanup for an explicit queue-only removal command (removeFromQueue,
 * clearEarlierQueue, clearUpNext; all preserve the current occurrence), run BEFORE validation and the generation bump.
 * Cancels from user intent even if the request then no-ops. The shared bulk-clear helper never notifies, and library
 * deletion (handleSongDeleted) is a separate boundary that does not use this seam, so one command yields one cancel.
 * The runtime generation check stays as the defensive fallback. The timing driver is left running; a null runtime is a
 * no-op.
 */
internal fun recoverCrossfadeFromQueueRemoval(runtime: CrossfadePreparationRuntime?) {
    runtime?.cancel(QUEUE_REMOVAL_CANCEL_REASON)
}

/** CF-2H3E: the cancel reason of a library song deletion (a named seam so a pure test can assert it). */
internal val LIBRARY_DELETION_CANCEL_REASON = CrossfadeCancelReason.QueueMutation

/**
 * CF-2H3E: key-less, synchronous crossfade cleanup for a library song deletion observed by
 * PlayerController.handleSongDeleted (the single public deletion boundary, covering the Unresolved, NonCurrent and
 * Current routes), run BEFORE the current occurrence is resolved and before any planner, generation bump, Media3
 * mutation or transport change. For a current-song deletion, Fading/HandoffPending restore the primary before the
 * existing continuation replaces the queue. The private deletion helpers never notify, so one deletion yields one
 * cancel. The runtime generation check stays as the defensive fallback. The timing driver is left running; a null
 * runtime is a no-op.
 */
internal fun recoverCrossfadeFromLibraryDeletion(runtime: CrossfadePreparationRuntime?) {
    runtime?.cancel(LIBRARY_DELETION_CANCEL_REASON)
}

/** CF-2H3F: the cancel reason of an explicit whole-queue replacement (a named seam so a pure test can assert it). */
internal val QUEUE_REPLACEMENT_CANCEL_REASON = CrossfadeCancelReason.QueueMutation

/**
 * CF-2H3F: key-less, synchronous crossfade cleanup for an explicit user playback start that replaces the active queue
 * (playSong, playSearchResultPreservingQueue, playExternalUri, both playFromQueue overloads, playFromQueueShuffled), run
 * BEFORE validation and any logical playback mutation. The queue-start internals (playFromQueueInternal,
 * playPreservedSearchPlan, the pending-request drain) never notify, and the Play Next / Add to Queue fallbacks that
 * start a queue use those internals and keep their own seam, so one user command yields one cancel. The runtime
 * generation check stays as the defensive fallback. The timing driver is left running; a null runtime is a no-op.
 */
internal fun recoverCrossfadeFromQueueReplacement(runtime: CrossfadePreparationRuntime?) {
    runtime?.cancel(QUEUE_REPLACEMENT_CANCEL_REASON)
}

/** CF-2H3G: the cancel reason of a service-owned playback-resumption adoption (a named seam so a pure test can assert it). */
internal val PLAYBACK_RESUMPTION_CANCEL_REASON = CrossfadeCancelReason.QueueMutation

/**
 * CF-2H3G: only a resumption that will actually be ADOPTED replaces the authoritative queue and generation, so crossfade
 * ownership ends only for a Ready mapping with isForPlayback == true. A mere query (isForPlayback == false, which only
 * exposes media items), an Unavailable result or a mapping failure leave the old playback state authoritative and must
 * not cancel. Used by PlaybackService.onPlaybackResumption; PlayerController.adoptPlaybackResumption stays
 * crossfade-agnostic and never cancels.
 */
internal fun shouldCancelCrossfadeForPlaybackResumption(resultReady: Boolean, isForPlayback: Boolean): Boolean =
    resultReady && isForPlayback

/**
 * CF-2H3G: key-less, synchronous crossfade cleanup run immediately before a service-owned playback resumption is
 * adopted (before the generation bump, queue replacement and repeat/shuffle application). The timing driver is left
 * running; a null runtime (gate false) is a no-op. The runtime generation check stays as the defensive fallback.
 */
internal fun recoverCrossfadeFromPlaybackResumption(runtime: CrossfadePreparationRuntime?) {
    runtime?.cancel(PLAYBACK_RESUMPTION_CANCEL_REASON)
}

/**
 * CF-2F2: only the authoritative primary's terminal states end crossfade ownership. STATE_IDLE and STATE_ENDED are
 * terminal; STATE_BUFFERING and STATE_READY are not (transient buffering is tolerated by the existing lifecycle rules).
 */
internal fun isPrimaryTerminalPlaybackState(playbackState: Int): Boolean =
    playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED

/** CF-2F2: the cancel reason of a primary terminal playback state (distinct from PlaybackError and Pause). */
internal val PRIMARY_TERMINAL_STATE_CANCEL_REASON = CrossfadeCancelReason.PrimaryPlaybackTerminated

/**
 * CF-2F2: key-less, synchronous cleanup when the authoritative primary reports STATE_IDLE or STATE_ENDED (observed in
 * PlaybackService.onPlaybackStateChanged, before the asynchronous widget work). Fading/HandoffPending restore the primary
 * gain before abandoning the secondary; a repeated terminal callback (e.g. error then IDLE, pause then IDLE) finds the
 * runtime already Idle and does nothing. The timing driver is left running; a null runtime (gate false) is a no-op. The
 * snapshot-based `!isPlaying -> Pause` ownership check stays as the broad defensive fallback.
 */
internal fun recoverCrossfadeFromPrimaryTerminalState(runtime: CrossfadePreparationRuntime?) {
    runtime?.cancel(PRIMARY_TERMINAL_STATE_CANCEL_REASON)
}

/** CF-2F4: the cancel reason of an authoritative MediaController disconnection (the existing ControllerDisconnected). */
internal val CONTROLLER_DISCONNECTED_CANCEL_REASON = CrossfadeCancelReason.ControllerDisconnected

/**
 * CF-2F4: key-less, synchronous cleanup when the CURRENT (authoritative) MediaController disconnects, notified by
 * PlayerController after its identity guard passes and BEFORE it clears the controller reference, so an audible
 * Fading/HandoffPending cleanup can still restore the primary gain. A stale or superseded controller's disconnect never
 * reaches this. Reconnection stays demand-driven and nothing is carried over. The snapshot `controllerConnected` check
 * stays as the defensive fallback. The timing driver is left running; a null runtime (gate false) is a no-op.
 */
internal fun recoverCrossfadeFromControllerDisconnected(runtime: CrossfadePreparationRuntime?) {
    runtime?.cancel(CONTROLLER_DISCONNECTED_CANCEL_REASON)
}

/** CF-2F5: which Media3-authoritative interruption (if any) a primary callback reports. */
internal enum class PrimaryPlaybackInterruption {
    None,
    AudioFocus,
    AudioRoute,
}

/**
 * CF-2F5: classifies `Player.Listener.onPlayWhenReadyChanged(playWhenReady, reason)`. Only a pause whose Media3 reason is
 * AUDIO_FOCUS_LOSS or AUDIO_BECOMING_NOISY is an interruption. USER_REQUEST stays with CF-2G1; REMOTE, END_OF_MEDIA_ITEM,
 * SUPPRESSED_TOO_LONG and any resume (`playWhenReady == true`) are not classified.
 */
internal fun classifyPrimaryPlayWhenReadyInterruption(playWhenReady: Boolean, reason: Int): PrimaryPlaybackInterruption {
    if (playWhenReady) return PrimaryPlaybackInterruption.None
    return when (reason) {
        Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS -> PrimaryPlaybackInterruption.AudioFocus
        Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY -> PrimaryPlaybackInterruption.AudioRoute
        else -> PrimaryPlaybackInterruption.None
    }
}

/**
 * CF-2F5: classifies `Player.Listener.onPlaybackSuppressionReasonChanged(reason)`. TRANSIENT_AUDIO_FOCUS_LOSS is audio focus;
 * UNSUITABLE_AUDIO_ROUTE and UNSUITABLE_AUDIO_OUTPUT are route interruptions. NONE (suppression lifted) and SCRUBBING are not
 * interruptions.
 */
internal fun classifyPrimarySuppressionInterruption(suppressionReason: Int): PrimaryPlaybackInterruption =
    when (suppressionReason) {
        Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS -> PrimaryPlaybackInterruption.AudioFocus
        Player.PLAYBACK_SUPPRESSION_REASON_UNSUITABLE_AUDIO_ROUTE,
        Player.PLAYBACK_SUPPRESSION_REASON_UNSUITABLE_AUDIO_OUTPUT -> PrimaryPlaybackInterruption.AudioRoute
        else -> PrimaryPlaybackInterruption.None
    }

/** CF-2F5: the cancel reason of a Media3 audio-focus / route interruption (existing Pause; the primary was paused/suppressed). */
internal val PRIMARY_INTERRUPTION_CANCEL_REASON = CrossfadeCancelReason.Pause

/**
 * CF-2F5: key-less, synchronous cleanup when Media3 reports an audio-focus or route interruption of the authoritative
 * primary. Makes the snapshot `!isPlaying -> Pause` fallback synchronous for these signals only. No resurrection when focus
 * or the route returns. The timing driver is left running; a null runtime (gate false) is a no-op.
 */
internal fun recoverCrossfadeFromPrimaryInterruption(runtime: CrossfadePreparationRuntime?) {
    runtime?.cancel(PRIMARY_INTERRUPTION_CANCEL_REASON)
}

/** CF-2I2: only an OFF -> ON Equalizer change terminates an owned crossfade; ON -> OFF never resurrects or cancels. */
internal fun shouldCancelCrossfadeForEqualizerChange(previousEnabled: Boolean, newEnabled: Boolean): Boolean =
    !previousEnabled && newEnabled

/**
 * CF-2I2: key-less, synchronous cleanup when the Equalizer becomes enabled. The primary alone carries the Equalizer, so an
 * Armed/Ready preparation is abandoned and an audible Fading/HandoffPending overlap restores the primary then abandons the
 * secondary (existing PlanInvalidated cancellation; no new reason). The timing driver is left running and nothing is
 * resurrected when EQ is disabled again; a null runtime (gate false) is a no-op.
 */
internal fun recoverCrossfadeFromEqualizerEnabled(runtime: CrossfadePreparationRuntime?) {
    runtime?.cancel(CrossfadeCancelReason.PlanInvalidated)
}
