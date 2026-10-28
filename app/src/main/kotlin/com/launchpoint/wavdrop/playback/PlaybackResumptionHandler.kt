package com.launchpoint.wavdrop.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import com.google.common.util.concurrent.ForwardingListenableFuture
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.playback.PlaybackSessionSnapshot
import com.launchpoint.wavdrop.data.settings.ResumeBehaviorSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.Executor

/*
 * The `MediaSession.Callback.onPlaybackResumption` logic, extracted from PlaybackService so its contract is testable.
 *
 * Media3 1.11.1 (verified in the MediaSessionImpl / MediaLibrarySessionImpl bytecode) calls it from exactly two places:
 *  - isForPlayback = true : a play request on a session whose player holds no media. The result is APPLIED to the session player
 *    (MediaUtils.setMediaItemsWithStartIndexAndPosition, then Util.handlePlayButtonAction). This is the only authoritative case.
 *  - isForPlayback = false: MediaLibrarySessionImpl.getRecentMediaItemAtDeviceBootTime (the "recent item" browse request). The
 *    result is only turned into a LibraryResult item list; it is NEVER installed. It must therefore be a pure read.
 *
 * So: RETRIEVAL (map the persisted logical snapshot) never touches PlayerController; ADOPTION (queue, generation, physical window,
 * crossfade cancellation) happens only for isForPlayback = true, and only if no newer queue authority arrived while the snapshot was
 * being mapped (latest wins). A bounded query returns just the saved current item: the "recent" contract needs one item, and the
 * full logical queue is never materialized for it.
 *
 * QUEUE RESTORATION vs PLAYBACK INTENT. Restoring the saved queue is not playback: it is owed to the saved session whatever the user does
 * next, so adoption is never withheld because the intent changed. The PLAY is a separate decision, and Media3 makes it unconditionally:
 * after the result is applied (and after a failure too) it issues one PLAY on the session player, blind to a PAUSE that reached the
 * still-empty player while the session was being restored. A request for playback therefore takes the newest cold-PLAY ticket the moment
 * it is received, in command order, so a later PAUSE/selection/PLAY supersedes it through the existing [ColdPlayAuthority]. Media3's PLAY is
 * made inside the callback it attached to the returned future, so the returned future wraps the listener Media3 attaches: while that
 * callback runs, the PLAY is explicitly the request's own and the session player refuses it only if the request's ticket was superseded.
 * Ownership is therefore carried by the callback itself, not inferred from the order of PLAYs, callbacks or looper turns; nothing is
 * queued, so there is nothing to consume wrongly, clean up, or leak when a callback is delayed, reordered or never runs.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal class PlaybackResumptionHandler(
    private val scope: CoroutineScope,
    private val loadSnapshot: suspend () -> PlaybackSessionSnapshot?,
    private val loadSettings: suspend () -> ResumeBehaviorSettings,
    private val loadSongs: suspend () -> List<Song>,
    private val playerController: PlayerController,
    /** Ends crossfade ownership; invoked only when a resumption is really about to be adopted. */
    private val cancelCrossfade: () -> Unit,
    private val log: (String) -> Unit,
    /**
     * The cold-PLAY authority shared with the session player (production: `PlayerController.coldPlayAuthority`). The default is a
     * private instance, which keeps the handler usable on its own but orders nothing against a session player.
     */
    private val coldPlay: ColdPlayAuthority = ColdPlayAuthority(),
) {
    fun resume(
        isForPlayback: Boolean,
        /** Full-mode only: the transport modes Media3 does not carry (repeat; native shuffle off), applied after adoption. */
        applyFullModeTransport: (PlaybackResumptionPlan) -> Unit,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        val rawFuture = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
        log("onPlaybackResumption invoked isForPlayback=$isForPlayback")
        val authority = playerController.resumptionAuthority()
        // A playback request owns the newest cold-PLAY ticket from the moment it is received (synchronously, in command order);
        // a query is a pure read and owns nothing.
        val ticket = if (isForPlayback) coldPlay.begin() else null
        // Completing the request ends its pending state (the restoration is over); the ownership of Media3's PLAY is the ticket's epoch,
        // which completing does not change, and it is read through Media3's own callback, so nothing here depends on callback timing.
        val future = object {
            fun set(value: MediaSession.MediaItemsWithStartPosition) { ticket?.let(coldPlay::complete); rawFuture.set(value) }
            fun setException(error: Throwable) { ticket?.let(coldPlay::complete); rawFuture.setException(error) }
        }
        scope.launch {
            runCatching {
                PlaybackResumptionMapper.map(
                    snapshot = loadSnapshot(),
                    settings = loadSettings(),
                    availableSongs = loadSongs(),
                    boundedModeFor = playerController::boundedModeForResumption,
                )
            }.fold(
                onSuccess = { result ->
                    when (result) {
                        is PlaybackResumptionResult.Ready -> {
                            val plan = result.plan
                            if (!isForPlayback) {
                                log("onPlaybackResumption query ready queueSize=${plan.playbackQueue.size} bounded=${plan.bounded}")
                                future.set(plan.toQueryResult())
                            } else if (!playerController.isResumptionAuthorityCurrent(authority)) {
                                log("onPlaybackResumption superseded by a newer queue authority; dropped")
                                future.setException(IllegalStateException("Playback resumption superseded"))
                            } else {
                                if (shouldCancelCrossfadeForPlaybackResumption(resultReady = true, isForPlayback = true)) cancelCrossfade()
                                val media3 = playerController.adoptPlaybackResumption(plan)
                                if (!plan.bounded) applyFullModeTransport(plan)
                                log(
                                    "onPlaybackResumption adopted queueSize=${plan.playbackQueue.size} bounded=${plan.bounded} " +
                                        "startIndex=${plan.startPlaybackIndex} startPositionMs=${plan.startPositionMs}",
                                )
                                future.set(media3)
                            }
                        }
                        is PlaybackResumptionResult.Unavailable -> {
                            log("onPlaybackResumption unavailable reason=${result.reason}")
                            future.setException(IllegalStateException("Playback resumption unavailable: ${result.reason}"))
                        }
                    }
                },
                onFailure = { error ->
                    log("onPlaybackResumption failed: ${error::class.simpleName} ${error.message}")
                    future.setException(error)
                },
            )
        }
        return if (ticket == null) rawFuture else OwnedCompletionFuture(rawFuture, coldPlay, ticket)
    }
}

/**
 * The future Media3 receives for a playback request. Media3 attaches its callback with `Futures.addCallback`, i.e. `addListener`, and
 * issues its PLAY synchronously inside that callback; wrapping the listener marks that PLAY as belonging to [ticket]'s request (see
 * [ColdPlayAuthority.runAsResumptionCompletion]), however and whenever Media3's executor runs it.
 */
private class OwnedCompletionFuture<V>(
    delegate: ListenableFuture<V>,
    private val coldPlay: ColdPlayAuthority,
    private val ticket: ColdPlayTicket,
) : ForwardingListenableFuture.SimpleForwardingListenableFuture<V>(delegate) {
    override fun addListener(listener: Runnable, executor: Executor) {
        super.addListener({ coldPlay.runAsResumptionCompletion(ticket) { listener.run() } }, executor)
    }
}

/**
 * What a read-only (recent item) request gets: Full plans keep returning the whole queue, exactly as before; a bounded plan returns
 * only the saved current item, so nothing is materialized or installed for a queue that is never installed whole.
 */
internal fun PlaybackResumptionPlan.toQueryResult(): MediaSession.MediaItemsWithStartPosition =
    if (!bounded) toMedia3Resumption()
    else MediaSession.MediaItemsWithStartPosition(
        listOf(playbackQueue[startPlaybackIndex].toPlaybackMediaItem()),
        0,
        startPositionMs,
    )
