package com.launchpoint.wavdrop.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.playback.PlaybackSessionSnapshot
import com.launchpoint.wavdrop.data.settings.ResumeBehaviorSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

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
) {
    fun resume(
        isForPlayback: Boolean,
        /** Full-mode only: the transport modes Media3 does not carry (repeat; native shuffle off), applied after adoption. */
        applyFullModeTransport: (PlaybackResumptionPlan) -> Unit,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
        log("onPlaybackResumption invoked isForPlayback=$isForPlayback")
        val authority = playerController.resumptionAuthority()
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
        return future
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
