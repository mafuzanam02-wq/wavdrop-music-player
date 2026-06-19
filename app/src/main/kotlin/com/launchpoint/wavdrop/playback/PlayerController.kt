package com.launchpoint.wavdrop.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.launchpoint.wavdrop.BuildConfig
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.playback.PlaybackSessionRepository
import com.launchpoint.wavdrop.data.playback.PlaybackSessionRules
import com.launchpoint.wavdrop.data.playback.PlaybackSessionSnapshot
import com.launchpoint.wavdrop.data.settings.HeadphoneResumeMode
import com.launchpoint.wavdrop.data.settings.ResumeBehaviorSettings
import com.launchpoint.wavdrop.data.settings.ResumeBehaviorSettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

internal class PlaybackSessionRestoreClaim {
    private var claimed = false

    @Synchronized
    fun claim(): Boolean {
        if (claimed) return false
        claimed = true
        return true
    }
}

internal enum class PlaybackSessionPersistenceAction {
    NONE,
    CLEAR,
    SAVE,
}

internal fun playbackSessionPersistenceAction(
    isExternalPlayback: Boolean,
    queueIsEmpty: Boolean,
): PlaybackSessionPersistenceAction = when {
    isExternalPlayback -> PlaybackSessionPersistenceAction.NONE
    queueIsEmpty       -> PlaybackSessionPersistenceAction.CLEAR
    else               -> PlaybackSessionPersistenceAction.SAVE
}

internal class PlaybackSessionPersistenceGate {
    private val mutex = Mutex()
    private val revisionLock = Any()
    private var latestRevision = 0L

    fun nextRevision(): Long = synchronized(revisionLock) {
        ++latestRevision
    }

    suspend fun runIfLatest(revision: Long, operation: suspend () -> Unit): Boolean =
        mutex.withLock {
            val isLatest = synchronized(revisionLock) { revision == latestRevision }
            if (!isLatest) return@withLock false
            operation()
            true
        }
}

internal fun shouldSkipStartupSessionRestore(
    isExternalPlayback: Boolean,
    hasLogicalQueue: Boolean,
    hasMediaQueue: Boolean,
): Boolean = isExternalPlayback || hasLogicalQueue || hasMediaQueue

internal const val PLAYBACK_POSITION_CHECKPOINT_INTERVAL_MS = 10_000L
internal const val PLAYBACK_POSITION_CHECKPOINT_MIN_DELTA_MS = 5_000L

internal fun shouldCheckpointPlaybackPosition(
    isPlaying: Boolean,
    isExternalPlayback: Boolean,
    queueIsEmpty: Boolean,
    nowElapsedRealtimeMs: Long,
    lastCheckpointElapsedRealtimeMs: Long,
    currentPositionMs: Long,
    lastCheckpointPositionMs: Long,
): Boolean {
    if (!isPlaying || isExternalPlayback || queueIsEmpty) return false
    if (lastCheckpointElapsedRealtimeMs < 0L || lastCheckpointPositionMs < 0L) return false
    if (nowElapsedRealtimeMs - lastCheckpointElapsedRealtimeMs <
        PLAYBACK_POSITION_CHECKPOINT_INTERVAL_MS
    ) return false
    return kotlin.math.abs(currentPositionMs - lastCheckpointPositionMs) >=
        PLAYBACK_POSITION_CHECKPOINT_MIN_DELTA_MS
}

internal sealed interface PlayAllNextPlan {
    data object NoOp : PlayAllNextPlan
    data class StartQueue(
        val queue: List<Song>,
        val startSong: Song,
    ) : PlayAllNextPlan
    data class InsertAfterCurrent(
        val songs: List<Song>,
    ) : PlayAllNextPlan
}

internal fun planPlayAllNext(
    songs: List<Song>,
    hasActiveCurrentItem: Boolean,
): PlayAllNextPlan {
    if (songs.isEmpty()) return PlayAllNextPlan.NoOp
    return if (hasActiveCurrentItem) {
        PlayAllNextPlan.InsertAfterCurrent(songs)
    } else {
        PlayAllNextPlan.StartQueue(queue = songs, startSong = songs.first())
    }
}

/**
 * Singleton bridge between the UI layer and PlaybackService.
 *
 * Connects to PlaybackService via MediaController asynchronously on first
 * instantiation. Any playSong() call received before the connection is
 * ready is queued and executed once the controller is available.
 *
 * Queue model
 * -----------
 * libraryQueue   – songs in their original source order (playlist / album / all-songs).
 *                  Used for session persistence and as the authoritative song list.
 * playbackOrder  – indices into libraryQueue that define the playback sequence.
 *                  Identity [0,1,2,...] when shuffle is OFF; shuffled when ON.
 * playbackQueue  – songs in playback order (derived: playbackOrder.map { libraryQueue[it] }).
 *                  THIS is what ExoPlayer is always loaded with.
 *
 * Because ExoPlayer holds playbackQueue, controller.currentMediaItemIndex equals the
 * playback index directly. Auto-transitions therefore follow the shuffle order naturally.
 * Manual seekTo(playbackIndex, 0L) also requires no conversion.
 */
@Singleton
class PlayerController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val statsTracker: StatsTracker,
    private val sessionRepository: PlaybackSessionRepository,
    private val resumeBehaviorRepository: ResumeBehaviorSettingsRepository,
) {
    private companion object {
        const val TAG = "WavStats-PC"
        const val SEARCH_TAG = "WavdropSearchPlayback"
        const val RESUME_TAG = "WavdropResume"
        const val EXTERNAL_AUDIO_SONG_ID = Long.MIN_VALUE
        const val BLUETOOTH_RESUME_DEBOUNCE_MS = 1_500L

        const val DEBUG_STATS = false
    }

    private enum class ResumeAttemptResult {
        STARTED,
        SKIPPED_BY_SETTING,
        SKIPPED_NO_SESSION,
        SKIPPED_CONTROLLER_NOT_READY,
        ALREADY_PLAYING,
        FAILED,
    }

    private var mediaController: MediaController? = null

    private var pendingPlaybackRequest: PlaybackRequest? = null
    private var pendingPreserveSearchRequest: PreserveSearchRequest? = null
    private var pendingExternalPlaybackRequest: ExternalPlaybackRequest? = null
    private var pendingRestorePositionMs: Long? = null
    private var pendingPreserveSearchPlan: SearchPlaybackPlan? = null
    private val sessionRestoreClaim = PlaybackSessionRestoreClaim()
    private val sessionPersistenceGate = PlaybackSessionPersistenceGate()
    private var isExternalPlayback = false

    // libraryQueue: source order. playbackOrder: indices into libraryQueue (identity or shuffled).
    // playbackQueue: songs in playback order = playbackOrder.map { libraryQueue[it] }.
    // ExoPlayer is ALWAYS loaded with playbackQueue, so currentMediaItemIndex == playback index.
    private var libraryQueue: List<Song> = emptyList()
    private var playbackOrder: List<Int> = emptyList()
    private var playbackQueue: List<Song> = emptyList()
    private var playerQueueNeedsSync: Boolean = false
    private var shuffleEnabled: Boolean = false
    private var repeatMode: RepeatMode = RepeatMode.OFF

    // Last position observed by the 500 ms ticker. Starts at -1 (uninitialized).
    // Reset to -1 whenever a new song/session starts so the loop detector doesn't see
    // a false wrap from the old song's late position to the new song's early position.
    private var lastKnownPositionMs: Long = -1L
    private var lastPositionCheckpointElapsedRealtimeMs: Long = -1L
    private var lastPositionCheckpointMs: Long = -1L

    private val _nowPlayingState = MutableStateFlow(NowPlayingState())
    val nowPlayingState: StateFlow<NowPlayingState> = _nowPlayingState.asStateFlow()

    private val _sleepTimerState = MutableStateFlow(SleepTimerState())
    val sleepTimerState: StateFlow<SleepTimerState> = _sleepTimerState.asStateFlow()

    // Set to true by onBluetoothDeviceRemoved / onWiredDeviceRemoved when the device
    // disconnects while playback is active. In-memory only; the persisted counterpart in
    // ResumeBehaviorSettingsRepository survives process death.
    private var wasInterruptedByBluetooth = false
    private var wasInterruptedByWired = false

    // Epoch-ms of the last Bluetooth resume attempt. Used to debounce devices that fire
    // separate A2DP/headset/hearing-aid connection events within a few hundred milliseconds.
    private var lastBluetoothResumeAttemptAtMs = 0L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var positionTickerJob: Job? = null
    private var wiredResumeJob: Job? = null
    private var sleepTimerJob: Job? = null

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (DEBUG_STATS) {
                val songId = _nowPlayingState.value.song?.id
                val pos = mediaController?.currentPosition ?: -1
                Log.d(TAG, "[isPlayingChanged] isPlaying=$isPlaying songId=$songId pos=$pos repeatMode=$repeatMode")
            }
            syncNowPlayingState()
            if (isPlaying) {
                if (!isExternalPlayback) {
                    statsTracker.onPlaybackStarted()
                }
                if (lastPositionCheckpointElapsedRealtimeMs < 0L) {
                    lastPositionCheckpointElapsedRealtimeMs = SystemClock.elapsedRealtime()
                    lastPositionCheckpointMs =
                        mediaController?.currentPosition?.coerceAtLeast(0L)
                            ?: _nowPlayingState.value.positionMs.coerceAtLeast(0L)
                }
                startPositionTicker()
            } else {
                if (!isExternalPlayback) {
                    statsTracker.onPlaybackPaused()
                }
                stopPositionTicker()
                syncPosition()
                saveSessionAsync()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (DEBUG_STATS) {
                Log.d(TAG, "[mediaItemTransition] reason=$reason mediaId=${mediaItem?.mediaId} repeatMode=$repeatMode isPlaying=${mediaController?.isPlaying}")
            }
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && handlePendingAutomaticTransition()) {
                return
            }
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO &&
                _sleepTimerState.value.option == SleepTimerOption.END_OF_CURRENT_SONG
            ) {
                triggerSleepTimer()
                return
            }
            // Reset position tracking so the ticker doesn't mistake the old song's
            // late position for a loop wrap on the new song.
            lastKnownPositionMs = -1L
            // Belt-and-suspenders: covers queue auto-advance and any REPEAT_ONE loop
            // where Media3 does fire this callback. The primary loop-boundary signal
            // is the position ticker below; this handles whatever IPC delivers.
            syncNowPlayingState(fromTransition = true)
            saveSessionAsync()
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (DEBUG_STATS) {
                Log.d(TAG, "[posDiscontinuity] reason=$reason old=${oldPosition.positionMs} new=${newPosition.positionMs} oldIdx=${oldPosition.mediaItemIndex} newIdx=${newPosition.mediaItemIndex} repeatMode=$repeatMode isPlaying=${mediaController?.isPlaying}")
            }
            if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                if (handlePendingAutomaticTransition()) {
                    return
                }
                if (_sleepTimerState.value.option == SleepTimerOption.END_OF_CURRENT_SONG) {
                    triggerSleepTimer()
                    return
                }
                // Reset ticker tracking so it doesn't double-detect this same boundary.
                lastKnownPositionMs = -1L
                // newPosition.mediaItemIndex is a playback index (ExoPlayer holds playbackQueue).
                val song = playbackQueue.getOrNull(newPosition.mediaItemIndex) ?: return
                if (DEBUG_STATS) Log.d(TAG, "[posDiscontinuity] AUTO_TRANSITION → songId=${song.id}")
                if (!isExternalPlayback) {
                    statsTracker.onSongSelected(song)
                    if (mediaController?.isPlaying == true) {
                        statsTracker.onPlaybackStarted()
                    }
                }
            }
            syncNowPlayingState(notifyStats = reason != Player.DISCONTINUITY_REASON_AUTO_TRANSITION)
            saveSessionAsync()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (DEBUG_STATS) {
                Log.d(TAG, "[playbackStateChanged] state=$playbackState songId=${_nowPlayingState.value.song?.id} repeatMode=$repeatMode")
            }
            if (playbackState == Player.STATE_ENDED &&
                _sleepTimerState.value.option == SleepTimerOption.END_OF_CURRENT_SONG
            ) {
                triggerSleepTimer()
                return
            }
            syncNowPlayingState()
        }
    }

    init {
        val token = SessionToken(
            context,
            ComponentName(context, PlaybackService::class.java),
        )
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener(
            {
                runCatching {
                    val controller = future.get()
                    mediaController = controller
                    controller.addListener(playerListener)
                    controller.repeatMode = repeatMode.toPlayerRepeatMode()
                    controller.shuffleModeEnabled = false
                    val playRequest = pendingPlaybackRequest
                    val preserveSearchRequest = pendingPreserveSearchRequest
                    val externalRequest = pendingExternalPlaybackRequest
                    val restorePos = pendingRestorePositionMs
                    pendingPlaybackRequest = null
                    pendingPreserveSearchRequest = null
                    pendingExternalPlaybackRequest = null
                    pendingRestorePositionMs = null
                    when {
                        externalRequest != null -> playExternalUri(
                            uri = externalRequest.uri,
                            displayName = externalRequest.displayName,
                        )
                        preserveSearchRequest != null -> playPreservedSearchPlan(
                            plan = preserveSearchRequest.plan,
                            startSong = preserveSearchRequest.startSong,
                        )
                        playRequest != null -> playFromQueueInternal(
                            queue = playRequest.queue,
                            startSong = playRequest.startSong,
                            preservePlaybackOrder = playRequest.preservePlaybackOrder,
                        )
                        restorePos != null && libraryQueue.isNotEmpty() -> {
                            // libraryQueue/playbackOrder/playbackQueue were set by
                            // restoreSessionIfNeeded; ExoPlayer was not ready at that time.
                            val startLibraryIndex = _nowPlayingState.value.song?.id
                                ?.let { id -> libraryQueue.indexOfFirst { it.id == id } }
                                ?.takeIf { it >= 0 } ?: 0
                            val startPlaybackIndex = playbackOrder.indexOf(startLibraryIndex)
                                .takeIf { it >= 0 } ?: 0
                            controller.repeatMode = repeatMode.toPlayerRepeatMode()
                            controller.shuffleModeEnabled = false
                            // Load ExoPlayer with playbackQueue (shuffle order).
                            controller.setMediaItems(
                                playbackQueue.map { it.toMediaItem() },
                                startPlaybackIndex,
                                restorePos,
                            )
                            controller.prepare()
                            syncNowPlayingState()
                        }
                        else -> syncNowPlayingState()
                    }
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    fun playSong(song: Song) {
        playFromQueue(queue = listOf(song), startSong = song)
    }

    fun playSearchResultPreservingQueue(song: Song) {
        val controller = mediaController
        val currentMediaIndex = controller?.currentMediaItemIndex
        val currentMediaSongId = controller?.currentMediaItem?.mediaId?.toLongOrNull()
        val effectiveQueueBefore = playbackQueue
        val resolvedCurrentIndex = currentPlaybackIndex()
        val plan = SearchPlaybackPlanner.preserveQueue(
            playbackQueue = effectiveQueueBefore,
            currentPlaybackIndex = resolvedCurrentIndex,
            song = song,
        )
        if (plan == null) {
            Log.d(
                SEARCH_TAG,
                "preserve search tap ignored: unresolved current index " +
                    "mediaIndex=$currentMediaIndex mediaSongId=$currentMediaSongId " +
                    "stateSongId=${_nowPlayingState.value.song?.id} before=${effectiveQueueBefore.idList()} " +
                    "tap=${song.id}",
            )
            return
        }
        Log.d(
            SEARCH_TAG,
            "preserve search tap mediaIndex=$currentMediaIndex mediaSongId=$currentMediaSongId " +
                "resolvedIndex=$resolvedCurrentIndex before=${effectiveQueueBefore.idList()} " +
                "tap=${song.id} after=${plan.queue.idList()} newIndex=${plan.currentIndex}",
        )
        playPreservedSearchPlan(plan = plan, startSong = song)
    }

    private fun playPreservedSearchPlan(
        plan: SearchPlaybackPlan,
        startSong: Song,
    ) {
        isExternalPlayback = false
        libraryQueue = plan.queue.ifEmpty { listOf(startSong) }
        playbackOrder = libraryQueue.indices.toList()
        playbackQueue = libraryQueue
        playerQueueNeedsSync = false

        val playbackStartIndex = plan.currentIndex.takeIf { it in playbackQueue.indices }
            ?: playbackQueue.indexOfFirst { it.id == startSong.id }.takeIf { it >= 0 }
            ?: 0
        pendingPreserveSearchPlan = SearchPlaybackPlan(
            queue = playbackQueue,
            currentIndex = playbackStartIndex,
        )

        lastKnownPositionMs = -1L
        statsTracker.onSongSelected(startSong)
        _nowPlayingState.update {
            it.copy(
                song = startSong,
                queue = playbackQueue,
                currentIndex = playbackStartIndex,
                shuffleEnabled = shuffleEnabled,
                repeatMode = repeatMode,
                positionMs = 0L,
                durationMs = startSong.duration.coerceAtLeast(0L),
                bufferedPositionMs = 0L,
                isSeekable = false,
            )
        }

        val controller = mediaController
        if (controller == null) {
            pendingPreserveSearchRequest = PreserveSearchRequest(
                plan = SearchPlaybackPlan(
                    queue = playbackQueue,
                    currentIndex = playbackStartIndex,
                ),
                startSong = startSong,
            )
            return
        }

        controller.repeatMode = repeatMode.toPlayerRepeatMode()
        controller.shuffleModeEnabled = false
        controller.setMediaItems(playbackQueue.map { it.toMediaItem() }, playbackStartIndex, 0L)
        controller.prepare()
        controller.play()
        saveSessionAsync()
    }

    fun playExternalUri(uri: Uri, displayName: String? = null) {
        val song = uri.toExternalSong(displayName)

        isExternalPlayback = true
        libraryQueue = listOf(song)
        playbackOrder = listOf(0)
        playbackQueue = libraryQueue
        playerQueueNeedsSync = false
        lastKnownPositionMs = -1L

        _nowPlayingState.update {
            it.copy(
                song = song,
                queue = playbackQueue,
                currentIndex = 0,
                shuffleEnabled = false,
                repeatMode = repeatMode,
                positionMs = 0L,
                durationMs = 0L,
                bufferedPositionMs = 0L,
                isSeekable = false,
            )
        }

        val controller = mediaController
        if (controller == null) {
            pendingExternalPlaybackRequest = ExternalPlaybackRequest(uri, displayName)
            return
        }

        controller.repeatMode = repeatMode.toPlayerRepeatMode()
        controller.shuffleModeEnabled = false
        controller.setMediaItems(listOf(song.toMediaItem()), 0, 0L)
        controller.prepare()
        syncNowPlayingState(notifyStats = false)
        controller.play()
    }

    fun playFromQueue(queue: List<Song>, startSong: Song) {
        playFromQueueInternal(queue = queue, startSong = startSong, preservePlaybackOrder = false)
    }

    private fun playFromQueuePreservingPlaybackOrder(queue: List<Song>, startSong: Song) {
        playFromQueueInternal(queue = queue, startSong = startSong, preservePlaybackOrder = true)
    }

    private fun playFromQueueInternal(
        queue: List<Song>,
        startSong: Song,
        preservePlaybackOrder: Boolean,
    ) {
        isExternalPlayback = false
        val normalizedQueue = queue.ifEmpty { listOf(startSong) }
        val originalStartIndex = normalizedQueue.indexOfFirst { it.id == startSong.id }
            .takeIf { it >= 0 } ?: 0

        libraryQueue = normalizedQueue
        if (preservePlaybackOrder) {
            playbackOrder = normalizedQueue.indices.toList()
            playbackQueue = normalizedQueue
        } else {
            rebuildPlaybackQueue(currentQueueIndex = originalStartIndex)
        }
        playerQueueNeedsSync = false
        // When shuffle is OFF, playbackOrder is identity so playbackStartIndex == originalStartIndex.
        // When shuffle is ON, buildPlaybackOrder places the start song at position 0.
        val playbackStartIndex = playbackOrder.indexOf(originalStartIndex).takeIf { it >= 0 } ?: 0

        lastKnownPositionMs = -1L
        statsTracker.onSongSelected(startSong)
        _nowPlayingState.update {
            it.copy(
                song = startSong,
                queue = playbackQueue,
                currentIndex = playbackStartIndex,
                shuffleEnabled = shuffleEnabled,
                repeatMode = repeatMode,
                positionMs = 0L,
                durationMs = startSong.duration.coerceAtLeast(0L),
                bufferedPositionMs = 0L,
                isSeekable = false,
            )
        }

        val controller = mediaController
        if (controller == null) {
            pendingPlaybackRequest = PlaybackRequest(
                queue = normalizedQueue,
                startSong = startSong,
                preservePlaybackOrder = preservePlaybackOrder,
            )
            return
        }
        controller.repeatMode = repeatMode.toPlayerRepeatMode()
        controller.shuffleModeEnabled = false
        // ExoPlayer is loaded with playbackQueue so auto-transitions follow shuffle order.
        controller.setMediaItems(playbackQueue.map { it.toMediaItem() }, playbackStartIndex, 0L)
        controller.prepare()
        syncNowPlayingState()
        controller.play()
        saveSessionAsync()
    }

    fun playFromQueueShuffled(queue: List<Song>) {
        val normalizedQueue = queue.ifEmpty { return }
        shuffleEnabled = true
        playFromQueue(
            queue = normalizedQueue,
            startSong = normalizedQueue.random(),
        )
    }

    fun playNext(song: Song) {
        val currentPlaybackIndex = currentPlaybackIndex()
        if (libraryQueue.isEmpty() || currentPlaybackIndex == null) {
            playSong(song)
            return
        }

        // Append the new song to the library queue and insert its index into playbackOrder
        // immediately after the current playback position.
        val newLibraryIndex = libraryQueue.size
        libraryQueue = libraryQueue + song
        val insertPlaybackIndex = currentPlaybackIndex + 1
        val newOrder = playbackOrder.toMutableList()
        newOrder.add(insertPlaybackIndex, newLibraryIndex)
        playbackOrder = newOrder
        playbackQueue = playbackOrder.mapNotNull { libraryQueue.getOrNull(it) }

        // addMediaItem at playback position — no setMediaItems/prepare/play needed.
        if (!playerQueueNeedsSync) {
            mediaController?.addMediaItem(insertPlaybackIndex, song.toMediaItem())
        }

        _nowPlayingState.update {
            it.copy(queue = playbackQueue, currentIndex = currentPlaybackIndex)
        }
        saveSessionAsync()
    }

    /** Inserts [songs] immediately after the current item in their original order. */
    fun playAllNext(songs: List<Song>) {
        val hasActiveCurrentItem = libraryQueue.isNotEmpty() && currentPlaybackIndex() != null
        when (val plan = planPlayAllNext(songs, hasActiveCurrentItem)) {
            PlayAllNextPlan.NoOp -> Unit
            is PlayAllNextPlan.StartQueue ->
                playFromQueue(queue = plan.queue, startSong = plan.startSong)
            is PlayAllNextPlan.InsertAfterCurrent -> {
                // Inserting each song at currentIndex+1 in reversed order produces the original order.
                plan.songs.asReversed().forEach { playNext(it) }
            }
        }
    }

    /** Appends [songs] to the end of the queue in their original order. */
    fun addAllToQueue(songs: List<Song>) {
        if (songs.isEmpty()) return
        songs.forEach { addToQueue(it) }
    }

    fun addToQueue(song: Song) {
        val currentPlaybackIndex = currentPlaybackIndex()
        if (libraryQueue.isEmpty() || currentPlaybackIndex == null) {
            playSong(song)
            return
        }
        val newLibraryIndex = libraryQueue.size
        libraryQueue = libraryQueue + song
        playbackOrder = playbackOrder + newLibraryIndex
        playbackQueue = playbackOrder.mapNotNull { libraryQueue.getOrNull(it) }

        // Appends to the end of the ExoPlayer playlist (playbackQueue).
        if (!playerQueueNeedsSync) {
            mediaController?.addMediaItem(song.toMediaItem())
        }

        _nowPlayingState.update {
            it.copy(
                queue = playbackQueue,
                currentIndex = currentPlaybackIndex,
                shuffleEnabled = shuffleEnabled,
                repeatMode = repeatMode,
            )
        }
        saveSessionAsync()
    }

    fun jumpToQueueItem(playbackIndex: Int) {
        val controller = mediaController ?: return
        if (playbackIndex !in playbackQueue.indices) return
        // playbackIndex is directly the ExoPlayer media item index since ExoPlayer holds playbackQueue.
        seekToPlaybackIndex(controller, playbackIndex)
    }

    fun removeFromQueue(playbackIndex: Int) {
        val currentPlaybackIndex = _nowPlayingState.value.currentIndex
        if (playbackIndex == currentPlaybackIndex) return
        if (playbackIndex !in playbackQueue.indices) return
        val removedLibraryIndex = playbackOrder.getOrNull(playbackIndex) ?: return
        if (removedLibraryIndex !in libraryQueue.indices) return

        // Remove from library queue and decrement any playback order entries above the gap.
        libraryQueue = libraryQueue.toMutableList().also { it.removeAt(removedLibraryIndex) }
        playbackOrder = playbackOrder
            .filterIndexed { index, _ -> index != playbackIndex }
            .map { index -> if (index > removedLibraryIndex) index - 1 else index }
        playbackQueue = playbackOrder.mapNotNull { libraryQueue.getOrNull(it) }

        // playbackIndex is the ExoPlayer media item index.
        if (!playerQueueNeedsSync) {
            mediaController?.removeMediaItem(playbackIndex)
        }

        val newCurrentPlaybackIndex = if (playbackIndex < currentPlaybackIndex) {
            currentPlaybackIndex - 1
        } else {
            currentPlaybackIndex
        }.coerceIn(playbackQueue.indices)
        val currentSong = playbackQueue.getOrNull(newCurrentPlaybackIndex)

        _nowPlayingState.update {
            it.copy(
                song = currentSong ?: it.song,
                queue = playbackQueue,
                currentIndex = newCurrentPlaybackIndex,
                shuffleEnabled = shuffleEnabled,
                repeatMode = repeatMode,
            )
        }
        saveSessionAsync()
    }

    fun handleSongDeleted(songId: Long) {
        val currentSong = _nowPlayingState.value.song
        if (currentSong?.id == songId) {
            handleCurrentSongDeleted()
        } else {
            // Song is queued but not the current track — remove all occurrences.
            val indicesToRemove = playbackQueue.indices
                .filter { playbackQueue[it].id == songId }
                .sortedDescending()
            indicesToRemove.forEach { removeFromQueue(it) }
        }
    }

    private fun handleCurrentSongDeleted() {
        val currentIdx = currentPlaybackIndex() ?: return
        if (currentIdx !in playbackQueue.indices) return

        // Use nextIndex (not automaticNextIndex) so RepeatMode.ONE still advances
        // past the deleted song instead of looping back to it.
        val nextIdx = QueueNavigator.nextIndex(
            queueSize    = playbackQueue.size,
            currentIndex = currentIdx,
            repeatMode   = repeatMode,
        )

        val controller = mediaController
        if (nextIdx == null || controller == null) {
            // No next song available, or controller not yet connected — clear queue and stop.
            mediaController?.pause()
            mediaController?.clearMediaItems()
            libraryQueue        = emptyList()
            playbackOrder       = emptyList()
            playbackQueue       = emptyList()
            lastKnownPositionMs = -1L
            _nowPlayingState.update {
                it.copy(
                    song         = null,
                    isPlaying    = false,
                    queue        = emptyList(),
                    currentIndex = 0,
                    positionMs   = 0L,
                    durationMs   = 0L,
                )
            }
            saveSessionAsync()
            return
        }

        // Advance to the next song first (preserving play/pause state), then remove
        // the stale entry that was at currentIdx — now behind the new current position.
        seekToPlaybackIndex(controller, nextIdx)
        removeFromQueue(currentIdx)
    }

    fun moveQueueItemUp(playbackIndex: Int) {
        val currentPlaybackIndex = _nowPlayingState.value.currentIndex
        if (playbackIndex <= currentPlaybackIndex || playbackIndex <= 0) return
        swapPlaybackItems(playbackIndex, playbackIndex - 1, currentPlaybackIndex)
    }

    fun moveQueueItemDown(playbackIndex: Int) {
        val currentPlaybackIndex = _nowPlayingState.value.currentIndex
        if (playbackIndex <= currentPlaybackIndex || playbackIndex >= playbackQueue.size - 1) return
        swapPlaybackItems(playbackIndex, playbackIndex + 1, currentPlaybackIndex)
    }

    fun moveQueueItemTo(fromPlaybackIndex: Int, toPlaybackIndex: Int) {
        val currentPlaybackIndex = _nowPlayingState.value.currentIndex
        if (fromPlaybackIndex <= currentPlaybackIndex || toPlaybackIndex <= currentPlaybackIndex) return
        if (fromPlaybackIndex == toPlaybackIndex) return
        if (fromPlaybackIndex !in playbackOrder.indices || toPlaybackIndex !in playbackOrder.indices) return

        val newOrder = playbackOrder.toMutableList()
        val item = newOrder.removeAt(fromPlaybackIndex)
        newOrder.add(toPlaybackIndex, item)
        playbackOrder = newOrder
        playbackQueue = playbackOrder.mapNotNull { libraryQueue.getOrNull(it) }

        if (!playerQueueNeedsSync) {
            mediaController?.moveMediaItem(fromPlaybackIndex, toPlaybackIndex)
        }

        _nowPlayingState.update {
            it.copy(
                queue          = playbackQueue,
                currentIndex   = currentPlaybackIndex,
                shuffleEnabled = shuffleEnabled,
                repeatMode     = repeatMode,
            )
        }
        saveSessionAsync()
    }

    fun moveToPlayNext(playbackIndex: Int) {
        val currentPlaybackIndex = _nowPlayingState.value.currentIndex
        val immediateNextIndex = currentPlaybackIndex + 1
        if (playbackIndex <= currentPlaybackIndex) return
        if (playbackIndex == immediateNextIndex) return
        if (playbackIndex !in playbackOrder.indices) return

        // Lift the entry out of playbackOrder and re-insert right after current.
        val newOrder = playbackOrder.toMutableList()
        val libraryIndex = newOrder.removeAt(playbackIndex)
        newOrder.add(immediateNextIndex, libraryIndex)
        playbackOrder = newOrder
        playbackQueue = playbackOrder.mapNotNull { libraryQueue.getOrNull(it) }

        // Move the item in ExoPlayer's playlist using playback indices.
        if (!playerQueueNeedsSync) {
            mediaController?.moveMediaItem(playbackIndex, immediateNextIndex)
        }

        _nowPlayingState.update {
            it.copy(
                queue = playbackQueue,
                currentIndex = currentPlaybackIndex,
                shuffleEnabled = shuffleEnabled,
                repeatMode = repeatMode,
            )
        }
        saveSessionAsync()
    }

    fun togglePlayPause() {
        val controller = mediaController ?: return
        if (DEBUG_STATS) Log.d(TAG, "[togglePlayPause] before: controller.isPlaying=${controller.isPlaying}")
        if (controller.isPlaying) controller.pause() else controller.play()
        syncNowPlayingState()
        if (DEBUG_STATS) Log.d(TAG, "[togglePlayPause] after syncNPS: nowPlaying.isPlaying=${_nowPlayingState.value.isPlaying}")
    }

    fun setSleepTimer(option: SleepTimerOption) {
        sleepTimerJob?.cancel()
        sleepTimerJob = null

        if (option == SleepTimerOption.OFF) {
            _sleepTimerState.value = SleepTimerState()
            return
        }

        val nowMs = System.currentTimeMillis()
        val durationMs = option.durationMs
        _sleepTimerState.value = SleepTimerState(
            option = option,
            startedAtMs = nowMs,
            endsAtMs = durationMs?.let { nowMs + it },
        )

        if (durationMs != null) {
            sleepTimerJob = scope.launch {
                delay(durationMs)
                triggerSleepTimer()
            }
        }
    }

    fun setCustomSleepTimer(durationMs: Long) {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        val nowMs = System.currentTimeMillis()
        _sleepTimerState.value = SleepTimerState(
            option = SleepTimerOption.OFF,
            startedAtMs = nowMs,
            endsAtMs = nowMs + durationMs,
            customDurationMs = durationMs,
        )
        sleepTimerJob = scope.launch {
            delay(durationMs)
            triggerSleepTimer()
        }
    }

    fun skipToNext() {
        val controller = mediaController ?: return
        val currentPlaybackIndex = currentPlaybackIndex() ?: controller.currentMediaItemIndex
        val nextPlaybackIndex = QueueNavigator.nextIndex(
            queueSize = playbackQueue.size,
            currentIndex = currentPlaybackIndex,
            repeatMode = repeatMode,
        ) ?: return
        seekToPlaybackIndex(controller, nextPlaybackIndex)
    }

    fun skipToPrevious() {
        val controller = mediaController ?: return
        val currentPlaybackIndex = currentPlaybackIndex() ?: controller.currentMediaItemIndex
        when (val action = QueueNavigator.previousAction(
            queueSize = playbackQueue.size,
            currentIndex = currentPlaybackIndex,
            currentPositionMs = controller.currentPosition,
            repeatMode = repeatMode,
        )) {
            is PreviousQueueAction.MoveTo -> seekToPlaybackIndex(controller, action.index)
            PreviousQueueAction.RestartCurrent -> {
                controller.seekTo(0L)
                syncPosition()
                saveSessionAsync()
            }
            null -> Unit
        }
    }

    fun toggleShuffle() {
        val controller = mediaController
        val currentSongId = controller?.currentMediaItem?.mediaId?.toLongOrNull()
            ?: _nowPlayingState.value.song?.id
            ?: return
        val positionMs = controller?.currentPosition?.coerceAtLeast(0L)
            ?: _nowPlayingState.value.positionMs.coerceAtLeast(0L)
        val isPlaying = controller?.isPlaying ?: _nowPlayingState.value.isPlaying

        val newShuffleEnabled = !shuffleEnabled
        val toggleModel = QueueMutation.shuffleToggleModel(
            libraryQueue = libraryQueue,
            currentSongId = currentSongId,
            shuffleEnabled = newShuffleEnabled,
        ) ?: return

        shuffleEnabled = newShuffleEnabled
        playbackOrder = toggleModel.playbackOrder
        playbackQueue = toggleModel.playbackQueue
        playerQueueNeedsSync = controller != null && !toggleModel.requiresCurrentItemReplacement

        _nowPlayingState.update {
            it.copy(
                song = toggleModel.currentSong,
                isPlaying = isPlaying,
                queue = playbackQueue,
                currentIndex = toggleModel.currentPlaybackIndex,
                shuffleEnabled = shuffleEnabled,
                positionMs = positionMs,
            )
        }
        saveSessionAsync()
    }

    fun cycleRepeatMode() {
        repeatMode = when (repeatMode) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
        mediaController?.repeatMode = repeatMode.toPlayerRepeatMode()
        _nowPlayingState.update { it.copy(repeatMode = repeatMode) }
        saveSessionAsync()
    }

    fun seekTo(positionMs: Long) {
        val controller = mediaController ?: return
        val duration = _nowPlayingState.value.durationMs
        val clamped = positionMs.coerceIn(0L, if (duration > 0) duration else positionMs)
        controller.seekTo(clamped)
        syncPosition(positionOverrideMs = clamped)
        saveSessionAsync()
    }

    fun restoreSessionIfNeeded(availableSongs: List<Song>) {
        if (hasActiveSessionForStartupRestore()) return
        if (!sessionRestoreClaim.claim()) return
        scope.launch {
            val rawSnapshot = sessionRepository.load() ?: return@launch
            val resumeSettings = resumeBehaviorRepository.settings.first()
            val snapshot = PlaybackSessionRules.applyResumeBehavior(rawSnapshot, resumeSettings)
                ?: return@launch

            val idSet = availableSongs.associateBy { it.id }
            val mappedQueue = snapshot.queueSongIds.mapNotNull { idSet[it] }
            val startSong = PlaybackSessionRules.resolveStartSong(
                sessionSongId = snapshot.currentSongId,
                sessionIndex  = PlaybackSessionRules.clampIndex(snapshot.currentIndex, mappedQueue.size),
                mappedQueue   = mappedQueue,
            ) ?: return@launch

            // A queue may have become active while DataStore and settings were loading.
            // Never replace a user-started or reconnect-restored Media3 session.
            if (hasActiveSessionForStartupRestore()) return@launch

            libraryQueue   = mappedQueue
            shuffleEnabled = snapshot.shuffleEnabled
            repeatMode     = snapshot.repeatMode
            val startLibraryIndex = mappedQueue.indexOf(startSong).coerceAtLeast(0)
            restorePlaybackQueue(
                currentQueueIndex = startLibraryIndex,
                savedPlaybackOrder = snapshot.playbackOrder,
            )
            playerQueueNeedsSync = false
            val startPlaybackIndex = playbackOrder.indexOf(startLibraryIndex).takeIf { it >= 0 } ?: 0

            _nowPlayingState.update {
                it.copy(
                    song               = startSong,
                    queue              = playbackQueue,
                    currentIndex       = startPlaybackIndex,
                    shuffleEnabled     = shuffleEnabled,
                    repeatMode         = repeatMode,
                    positionMs         = snapshot.positionMs,
                    durationMs         = startSong.duration.coerceAtLeast(0L),
                    bufferedPositionMs = 0L,
                    isSeekable         = false,
                )
            }

            val controller = mediaController
            if (controller == null) {
                pendingRestorePositionMs = snapshot.positionMs
                return@launch
            }
            controller.repeatMode         = repeatMode.toPlayerRepeatMode()
            controller.shuffleModeEnabled = false
            controller.setMediaItems(
                playbackQueue.map { it.toMediaItem() },
                startPlaybackIndex,
                snapshot.positionMs,
            )
            controller.prepare()
            syncNowPlayingState()
        }
    }

    /**
     * Called by PlaybackService when a Bluetooth audio device is removed.
     * Records whether playback was active at that moment so [resumeForBluetooth] can
     * honour the RESUME_IF_INTERRUPTED mode.
     */
    fun onBluetoothDeviceRemoved() {
        val wasPlaying = mediaController?.isPlaying == true
        wasInterruptedByBluetooth = wasPlaying
        logResume("onBluetoothDeviceRemoved: wasPlaying=$wasPlaying")
        if (wasPlaying) {
            scope.launch { resumeBehaviorRepository.setBluetoothInterruptedResumePending(true) }
        }
    }

    /**
     * Called by PlaybackService when a wired audio output is removed.
     * Records whether playback was active at that moment so [resumeForWiredHeadphones] can
     * honour the RESUME_IF_INTERRUPTED mode.
     */
    fun onWiredDeviceRemoved() {
        val wasPlaying = mediaController?.isPlaying == true
        wasInterruptedByWired = wasPlaying
        logResume("onWiredDeviceRemoved: wasPlaying=$wasPlaying")
        if (wasPlaying) {
            scope.launch { resumeBehaviorRepository.setWiredInterruptedResumePending(true) }
        }
    }

    /**
     * Resumes playback after a Bluetooth audio device connects.
     *
     * Debounces duplicate events (some devices fire A2DP + headset + hearing-aid
     * connection events within milliseconds of each other).
     */
    fun resumeForBluetooth(availableSongs: List<Song>) {
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastBluetoothResumeAttemptAtMs < BLUETOOTH_RESUME_DEBOUNCE_MS) {
            logResume(
                "resumeForBluetooth: debounced " +
                    "(${nowMs - lastBluetoothResumeAttemptAtMs}ms < ${BLUETOOTH_RESUME_DEBOUNCE_MS}ms since last attempt)",
            )
            return
        }
        lastBluetoothResumeAttemptAtMs = nowMs
        scope.launch {
            val result = attemptBluetoothResume(availableSongs)
            logResume("resumeForBluetooth: result=$result")
        }
    }

    /**
     * Resumes playback after wired headphones connect.
     *
     * Debounced by checking whether a resume job is already in flight.
     */
    fun resumeForWiredHeadphones(availableSongs: List<Song>) {
        if (wiredResumeJob?.isActive == true) {
            logResume("resumeForWiredHeadphones: debounced — wiredResumeJob already active")
            return
        }
        wiredResumeJob = scope.launch {
            val result = attemptWiredResume(availableSongs)
            logResume("resumeForWiredHeadphones: result=$result")
        }
    }

    /**
     * Core Bluetooth resume logic. Returns a [ResumeAttemptResult] to distinguish every
     * possible outcome so callers can log and the interrupted flag can be cleared only when
     * appropriate:
     * - Definitive skip (setting says no) → clear persisted flag now.
     * - Controller not ready → do NOT clear persisted flag; the next reconnect should still
     *   see interrupted=true.
     * - Attempt made (hot or cold) → clear persisted flag regardless of whether playback
     *   actually started (we made a genuine attempt).
     */
    private suspend fun attemptBluetoothResume(availableSongs: List<Song>): ResumeAttemptResult {
        val settings = resumeBehaviorRepository.settings.first()

        // Read both interrupt signals. Clear the in-memory flag immediately (already consumed).
        // The persisted flag is cleared only at decision points below.
        val inMemoryInterrupted = wasInterruptedByBluetooth
        wasInterruptedByBluetooth = false
        val persistedInterrupted = resumeBehaviorRepository.hasBluetoothInterruptedResumePending()
        val interrupted = inMemoryInterrupted || persistedInterrupted

        logResume(
            "attemptBluetoothResume: mode=${settings.bluetoothResumeMode} " +
                "interrupted=$interrupted rememberLastTrack=${settings.rememberLastTrack}",
        )

        if (!settings.rememberLastTrack) {
            if (persistedInterrupted) resumeBehaviorRepository.setBluetoothInterruptedResumePending(false)
            logResume("attemptBluetoothResume: skipped — rememberLastTrack=false")
            return ResumeAttemptResult.SKIPPED_BY_SETTING
        }

        if (!settings.bluetoothResumeMode.shouldResume(interrupted)) {
            if (persistedInterrupted) resumeBehaviorRepository.setBluetoothInterruptedResumePending(false)
            logResume("attemptBluetoothResume: skipped — mode=${settings.bluetoothResumeMode} does not resume for interrupted=$interrupted")
            return ResumeAttemptResult.SKIPPED_BY_SETTING
        }

        val controller = awaitMediaController()
        if (controller == null) {
            // Do NOT clear the persisted flag — the next reconnect should still see interrupted=true.
            logResume("attemptBluetoothResume: giving up — mediaController not ready after timeout")
            return ResumeAttemptResult.SKIPPED_CONTROLLER_NOT_READY
        }

        if (controller.isPlaying) {
            if (persistedInterrupted) resumeBehaviorRepository.setBluetoothInterruptedResumePending(false)
            logResume("attemptBluetoothResume: already playing before delay, nothing to do")
            return ResumeAttemptResult.ALREADY_PLAYING
        }

        delay(1_000L)

        if (controller.isPlaying) {
            if (persistedInterrupted) resumeBehaviorRepository.setBluetoothInterruptedResumePending(false)
            logResume("attemptBluetoothResume: already playing after delay, nothing to do")
            return ResumeAttemptResult.ALREADY_PLAYING
        }

        // We are about to make a genuine resume attempt — clear the persisted flag now.
        if (persistedInterrupted) resumeBehaviorRepository.setBluetoothInterruptedResumePending(false)

        val song = _nowPlayingState.value.song
        logResume(
            "attemptBluetoothResume: queueSize=${libraryQueue.size} " +
                "mediaItemCount=${controller.mediaItemCount} " +
                "playbackState=${stateString(controller.playbackState)}",
        )
        return if (song != null && libraryQueue.isNotEmpty()) {
            lastKnownPositionMs = -1L
            statsTracker.onSongSelected(song)
            performHotResume(controller, availableSongs, settings, "bluetooth")
            ResumeAttemptResult.STARTED
        } else {
            logResume("attemptBluetoothResume: no active queue — loading session cold")
            val coldResult = resumeSessionCold(availableSongs, settings)
            if (coldResult) ResumeAttemptResult.STARTED else ResumeAttemptResult.SKIPPED_NO_SESSION
        }
    }

    /** Core wired-headphone resume logic — same structure as [attemptBluetoothResume]. */
    private suspend fun attemptWiredResume(availableSongs: List<Song>): ResumeAttemptResult {
        val settings = resumeBehaviorRepository.settings.first()

        val inMemoryInterrupted = wasInterruptedByWired
        wasInterruptedByWired = false
        val persistedInterrupted = resumeBehaviorRepository.hasWiredInterruptedResumePending()
        val interrupted = inMemoryInterrupted || persistedInterrupted

        logResume(
            "attemptWiredResume: mode=${settings.wiredResumeMode} " +
                "interrupted=$interrupted rememberLastTrack=${settings.rememberLastTrack}",
        )

        if (!settings.rememberLastTrack) {
            if (persistedInterrupted) resumeBehaviorRepository.setWiredInterruptedResumePending(false)
            logResume("attemptWiredResume: skipped — rememberLastTrack=false")
            return ResumeAttemptResult.SKIPPED_BY_SETTING
        }

        if (!settings.wiredResumeMode.shouldResume(interrupted)) {
            if (persistedInterrupted) resumeBehaviorRepository.setWiredInterruptedResumePending(false)
            logResume("attemptWiredResume: skipped — mode=${settings.wiredResumeMode} does not resume for interrupted=$interrupted")
            return ResumeAttemptResult.SKIPPED_BY_SETTING
        }

        val controller = awaitMediaController()
        if (controller == null) {
            logResume("attemptWiredResume: giving up — mediaController not ready after timeout")
            return ResumeAttemptResult.SKIPPED_CONTROLLER_NOT_READY
        }

        if (controller.isPlaying) {
            if (persistedInterrupted) resumeBehaviorRepository.setWiredInterruptedResumePending(false)
            logResume("attemptWiredResume: already playing before delay, nothing to do")
            return ResumeAttemptResult.ALREADY_PLAYING
        }

        delay(1_000L)

        if (controller.isPlaying) {
            if (persistedInterrupted) resumeBehaviorRepository.setWiredInterruptedResumePending(false)
            logResume("attemptWiredResume: already playing after delay, nothing to do")
            return ResumeAttemptResult.ALREADY_PLAYING
        }

        if (persistedInterrupted) resumeBehaviorRepository.setWiredInterruptedResumePending(false)

        val song = _nowPlayingState.value.song
        logResume(
            "attemptWiredResume: queueSize=${libraryQueue.size} " +
                "mediaItemCount=${controller.mediaItemCount} " +
                "playbackState=${stateString(controller.playbackState)}",
        )
        return if (song != null && libraryQueue.isNotEmpty()) {
            lastKnownPositionMs = -1L
            statsTracker.onSongSelected(song)
            performHotResume(controller, availableSongs, settings, "wired")
            ResumeAttemptResult.STARTED
        } else {
            logResume("attemptWiredResume: no active queue — loading session cold")
            val coldResult = resumeSessionCold(availableSongs, settings)
            if (coldResult) ResumeAttemptResult.STARTED else ResumeAttemptResult.SKIPPED_NO_SESSION
        }
    }

    /**
     * Loads the persisted session from DataStore, hydrates the queue, and calls play().
     * Returns true if playback was started, false if the session could not be resolved.
     */
    private suspend fun resumeSessionCold(
        availableSongs: List<Song>,
        settings: ResumeBehaviorSettings,
    ): Boolean {
        if (!sessionRestoreClaim.claim()) {
            logResume("resumeSessionCold: session restore already claimed, aborting")
            return false
        }
        logResume("resumeSessionCold: availableSongs=${availableSongs.size}")
        val rawSnapshot = sessionRepository.load()
        if (rawSnapshot == null) {
            logResume("resumeSessionCold: no saved session, aborting")
            return false
        }
        val snapshot = PlaybackSessionRules.applyResumeBehavior(rawSnapshot, settings)
        if (snapshot == null) {
            logResume("resumeSessionCold: session filtered out by resume rules, aborting")
            return false
        }

        val idSet = availableSongs.associateBy { it.id }
        val mappedQueue = snapshot.queueSongIds.mapNotNull { idSet[it] }
        val startSong = PlaybackSessionRules.resolveStartSong(
            sessionSongId = snapshot.currentSongId,
            sessionIndex  = PlaybackSessionRules.clampIndex(snapshot.currentIndex, mappedQueue.size),
            mappedQueue   = mappedQueue,
        )
        if (startSong == null) {
            logResume("resumeSessionCold: could not resolve start song (mappedQueue=${mappedQueue.size}), aborting")
            return false
        }
        logResume("resumeSessionCold: songId=${startSong.id} queueSize=${mappedQueue.size} positionMs=${snapshot.positionMs}")

        libraryQueue   = mappedQueue
        shuffleEnabled = snapshot.shuffleEnabled
        repeatMode     = snapshot.repeatMode
        val startLibraryIndex = mappedQueue.indexOf(startSong).coerceAtLeast(0)
        restorePlaybackQueue(
            currentQueueIndex = startLibraryIndex,
            savedPlaybackOrder = snapshot.playbackOrder,
        )
        playerQueueNeedsSync = false
        val startPlaybackIndex = playbackOrder.indexOf(startLibraryIndex).takeIf { it >= 0 } ?: 0

        lastKnownPositionMs = -1L
        statsTracker.onSongSelected(startSong)

        _nowPlayingState.update {
            it.copy(
                song               = startSong,
                queue              = playbackQueue,
                currentIndex       = startPlaybackIndex,
                shuffleEnabled     = shuffleEnabled,
                repeatMode         = repeatMode,
                positionMs         = snapshot.positionMs,
                durationMs         = startSong.duration.coerceAtLeast(0L),
                bufferedPositionMs = 0L,
                isSeekable         = false,
            )
        }

        val controller = mediaController
        if (controller == null) {
            logResume("resumeSessionCold: mediaController null at play step, aborting")
            return false
        }
        logResume("resumeSessionCold: loading ExoPlayer and calling play()")
        controller.repeatMode         = repeatMode.toPlayerRepeatMode()
        controller.shuffleModeEnabled = false
        controller.setMediaItems(
            playbackQueue.map { it.toMediaItem() },
            startPlaybackIndex,
            snapshot.positionMs,
        )
        controller.prepare()
        syncNowPlayingState()
        controller.play()
        return true
    }

    /**
     * Executes the hot-resume play step after eligibility checks have passed.
     *
     * - If the controller has no loaded media items, falls back to [resumeSessionCold].
     * - If ExoPlayer is in STATE_IDLE or STATE_ENDED, calls [prepare] first so [play] is not a no-op.
     * - Logs immediate post-play diagnostics, then waits 700 ms and logs again.
     * - If after the delay the player is still not playing (playWhenReady=true, isPlaying=false,
     *   no error), performs one controlled retry — this covers transient audio-focus or
     *   audio-route suppression that resolves shortly after reconnect.
     */
    private suspend fun performHotResume(
        controller: MediaController,
        availableSongs: List<Song>,
        settings: ResumeBehaviorSettings,
        source: String,
    ) {
        if (controller.currentMediaItem == null || controller.mediaItemCount == 0) {
            logResume("$source: hot resume has no media item (mediaItemCount=${controller.mediaItemCount}) — falling back to cold resume")
            resumeSessionCold(availableSongs, settings)
            return
        }

        // Issue prepare() if the pipeline is not ready — play() is a no-op in STATE_IDLE/ENDED.
        when (controller.playbackState) {
            Player.STATE_IDLE, Player.STATE_ENDED -> {
                logResume("$source: hot resume prepared + play (state=${stateString(controller.playbackState)})")
                controller.prepare()
                controller.play()
            }
            else -> {
                logResume("$source: hot resume play (state=${stateString(controller.playbackState)})")
                controller.play()
            }
        }

        logResumePlayDiagnostics(controller, "$source: immediate post-play")

        // Give ExoPlayer time to settle audio focus and routing after reconnect, then verify.
        delay(700L)

        logResumePlayDiagnostics(controller, "$source: delayed post-play")

        val suppression = controller.playbackSuppressionReason
        if (suppression != Player.PLAYBACK_SUPPRESSION_REASON_NONE) {
            logResume("$source: playback suppressed reason=$suppression — not retrying")
            return
        }

        // One controlled retry if the player accepted the command but still hasn't started.
        if (controller.playWhenReady && !controller.isPlaying && controller.playerError == null
            && controller.mediaItemCount > 0
        ) {
            logResume("$source: hot resume retry play (playWhenReady=true but isPlaying=false after delay)")
            if (controller.playbackState == Player.STATE_IDLE || controller.playbackState == Player.STATE_ENDED) {
                controller.prepare()
            }
            controller.play()
            logResumePlayDiagnostics(controller, "$source: post-retry")
        }
    }

    private fun logResumePlayDiagnostics(controller: MediaController, label: String) {
        val suppression = controller.playbackSuppressionReason
        logResume(
            "$label: state=${stateString(controller.playbackState)} " +
                "playWhenReady=${controller.playWhenReady} " +
                "isPlaying=${controller.isPlaying} " +
                "suppression=${suppressionString(suppression)} " +
                "hasMediaItem=${controller.currentMediaItem != null} " +
                "mediaItemCount=${controller.mediaItemCount} " +
                "currentIndex=${controller.currentMediaItemIndex} " +
                "canPlay=${controller.availableCommands.contains(Player.COMMAND_PLAY_PAUSE)} " +
                "error=${controller.playerError}",
        )
    }

    private fun suppressionString(reason: Int): String = when (reason) {
        Player.PLAYBACK_SUPPRESSION_REASON_NONE                       -> "NONE"
        Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS -> "TRANSIENT_AUDIO_FOCUS_LOSS"
        Player.PLAYBACK_SUPPRESSION_REASON_UNSUITABLE_AUDIO_ROUTE     -> "UNSUITABLE_AUDIO_ROUTE"
        else                                                           -> "UNKNOWN($reason)"
    }

    private fun stateString(state: Int): String = when (state) {
        Player.STATE_IDLE      -> "IDLE"
        Player.STATE_BUFFERING -> "BUFFERING"
        Player.STATE_READY     -> "READY"
        Player.STATE_ENDED     -> "ENDED"
        else                   -> "UNKNOWN($state)"
    }

    /**
     * Polls for the [MediaController] to become available, up to [timeoutMs].
     *
     * Required because [resumeForBluetooth]/[resumeForWiredHeadphones] can be called from
     * [PlaybackService.onStartCommand] before the async [MediaController] future completes,
     * especially on cold start (broadcast-receiver wakeup after process death).
     */
    private suspend fun awaitMediaController(timeoutMs: Long = 3_000L): MediaController? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val c = mediaController
            if (c != null) return c
            delay(100L)
        }
        return null
    }

    private fun logResume(message: String) {
        if (BuildConfig.DEBUG) Log.d(RESUME_TAG, message)
    }

    private fun saveSessionAsync() {
        val action = playbackSessionPersistenceAction(
            isExternalPlayback = isExternalPlayback,
            queueIsEmpty = libraryQueue.isEmpty(),
        )
        if (action == PlaybackSessionPersistenceAction.NONE) return

        val revision = sessionPersistenceGate.nextRevision()
        if (action == PlaybackSessionPersistenceAction.CLEAR) {
            lastPositionCheckpointElapsedRealtimeMs = -1L
            lastPositionCheckpointMs = -1L
            scope.launch {
                sessionPersistenceGate.runIfLatest(revision) {
                    sessionRepository.clear()
                }
            }
            return
        }

        val state = _nowPlayingState.value
        val controller = mediaController
        val positionMs = controller?.currentPosition?.coerceAtLeast(0L) ?: state.positionMs
        lastPositionCheckpointElapsedRealtimeMs = SystemClock.elapsedRealtime()
        lastPositionCheckpointMs = positionMs
        val preservePlan = pendingPreserveSearchPlan

        val currentLibraryIndex = preservePlan?.currentIndex?.takeIf { it in libraryQueue.indices }
            ?: controller?.currentMediaItem?.mediaId?.toLongOrNull()
                ?.let { id -> libraryQueue.indexOfFirst { it.id == id } }
                ?.takeIf { it >= 0 }
            ?: libraryQueue.indexOfFirst { it.id == state.song?.id }.takeIf { it >= 0 }
            ?: 0

        val snapshot = PlaybackSessionSnapshot(
            queueSongIds   = libraryQueue.map { it.id },
            playbackOrder  = playbackOrder.takeIf { it.size == libraryQueue.size },
            currentSongId  = preservePlan?.currentSongId ?: state.song?.id,
            currentIndex   = currentLibraryIndex,
            positionMs     = positionMs,
            repeatMode     = repeatMode,
            shuffleEnabled = shuffleEnabled,
            updatedAtMs    = System.currentTimeMillis(),
        )
        scope.launch {
            sessionPersistenceGate.runIfLatest(revision) {
                sessionRepository.save(snapshot)
            }
        }
    }

    private fun hasActiveSessionForStartupRestore(): Boolean {
        val controller = mediaController
        return shouldSkipStartupSessionRestore(
            isExternalPlayback = isExternalPlayback,
            hasLogicalQueue = libraryQueue.isNotEmpty() || playbackQueue.isNotEmpty(),
            hasMediaQueue = controller?.mediaItemCount?.let { it > 0 } == true ||
                controller?.currentMediaItem != null,
        )
    }

    fun release() {
        stopPositionTicker()
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        scope.cancel()
        mediaController?.removeListener(playerListener)
        mediaController?.release()
        mediaController = null
    }

    private fun startPositionTicker() {
        if (positionTickerJob?.isActive == true) return
        positionTickerJob = scope.launch {
            while (true) {
                tickAndCheckLoopBoundary()
                delay(500)
            }
        }
    }

    private fun stopPositionTicker() {
        positionTickerJob?.cancel()
        positionTickerJob = null
    }

    private fun triggerSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        _sleepTimerState.value = SleepTimerState()
        mediaController?.pause()
        syncNowPlayingState()
        syncPosition()
        saveSessionAsync()
    }

    /**
     * Called by the 500 ms position ticker while playing.
     */
    private fun tickAndCheckLoopBoundary() {
        val controller = mediaController ?: return
        val durationMs = controller.safeDurationMs(fallbackMs = _nowPlayingState.value.song?.duration ?: 0L)
        val currentPos = controller.safePositionMs(durationMs)

        val prev = lastKnownPositionMs
        lastKnownPositionMs = currentPos

        if (DEBUG_STATS) {
            Log.d(TAG, "[ticker] prev=$prev cur=$currentPos dur=$durationMs repeat=$repeatMode isPlaying=${controller.isPlaying}")
        }

        if (LoopBoundaryDetector.isLoopBoundary(prev, currentPos, repeatMode)) {
            val song = controller.currentMediaItem?.mediaId?.toLongOrNull()
                ?.let { id -> libraryQueue.firstOrNull { it.id == id } }
                ?: playbackQueue.getOrNull(controller.currentMediaItemIndex)
            if (song != null) {
                if (DEBUG_STATS) Log.d(TAG, "[ticker] LOOP BOUNDARY detected prev=$prev cur=$currentPos songId=${song.id}")
                if (_sleepTimerState.value.option == SleepTimerOption.END_OF_CURRENT_SONG) {
                    triggerSleepTimer()
                    return
                }
                if (!isExternalPlayback) {
                    statsTracker.onSongSelected(song)
                    statsTracker.onPlaybackStarted()
                }
            }
        }

        _nowPlayingState.update {
            it.copy(
                positionMs         = currentPos,
                durationMs         = durationMs,
                bufferedPositionMs = controller.safeBufferedPositionMs(durationMs),
                isSeekable         = controller.isCurrentMediaItemSeekable,
            )
        }

        val nowElapsedRealtimeMs = SystemClock.elapsedRealtime()
        if (shouldCheckpointPlaybackPosition(
                isPlaying = controller.isPlaying,
                isExternalPlayback = isExternalPlayback,
                queueIsEmpty = libraryQueue.isEmpty(),
                nowElapsedRealtimeMs = nowElapsedRealtimeMs,
                lastCheckpointElapsedRealtimeMs = lastPositionCheckpointElapsedRealtimeMs,
                currentPositionMs = currentPos,
                lastCheckpointPositionMs = lastPositionCheckpointMs,
            )
        ) {
            saveSessionAsync()
        }
    }

    private fun syncPosition(positionOverrideMs: Long? = null) {
        val controller = mediaController ?: return
        _nowPlayingState.update {
            val durationMs = controller.safeDurationMs(fallbackMs = it.song?.duration ?: 0L)
            val positionMs = positionOverrideMs ?: controller.safePositionMs(durationMs)
            it.copy(
                positionMs         = positionMs.coerceForDuration(durationMs),
                durationMs         = durationMs,
                bufferedPositionMs = controller.safeBufferedPositionMs(durationMs),
                isSeekable         = controller.isCurrentMediaItemSeekable,
            )
        }
    }

    // Seek to a playback index (position in playbackQueue / ExoPlayer playlist).
    private fun seekToPlaybackIndex(controller: MediaController, playbackIndex: Int) {
        lastKnownPositionMs = -1L
        val shouldPlay = controller.isPlaying
        if (playerQueueNeedsSync) {
            syncPlayerQueueAt(controller, playbackIndex, positionMs = 0L, playWhenReady = shouldPlay)
        } else {
            controller.seekTo(playbackIndex, 0L)
        }
        if (shouldPlay) controller.play()
        syncNowPlayingState()
        saveSessionAsync()
    }

    private fun handlePendingAutomaticTransition(): Boolean {
        if (!playerQueueNeedsSync) return false
        val controller = mediaController ?: return false
        val currentPlaybackIndex = _nowPlayingState.value.currentIndex
            .takeIf { it in playbackQueue.indices }
            ?: return false
        val nextPlaybackIndex = QueueNavigator.automaticNextIndex(
            queueSize = playbackQueue.size,
            currentIndex = currentPlaybackIndex,
            repeatMode = repeatMode,
        )
        val wasPlaying = controller.isPlaying
        if (nextPlaybackIndex == null) {
            syncPlayerQueueAt(
                controller = controller,
                playbackIndex = currentPlaybackIndex,
                positionMs = 0L,
                playWhenReady = false,
            )
            controller.pause()
            syncNowPlayingState(fromTransition = true)
            saveSessionAsync()
            return true
        }

        syncPlayerQueueAt(
            controller = controller,
            playbackIndex = nextPlaybackIndex,
            positionMs = 0L,
            playWhenReady = wasPlaying,
        )
        syncNowPlayingState(fromTransition = true)
        saveSessionAsync()
        return true
    }

    private fun syncPlayerQueueAt(
        controller: MediaController,
        playbackIndex: Int,
        positionMs: Long,
        playWhenReady: Boolean,
    ) {
        playerQueueNeedsSync = false
        controller.setMediaItems(
            playbackQueue.map { it.toMediaItem() },
            playbackIndex,
            positionMs,
        )
        controller.prepare()
        if (playWhenReady) controller.play()
    }

    private fun rebuildPlaybackQueue(currentQueueIndex: Int) {
        playbackOrder = QueueNavigator.buildPlaybackOrder(
            queueSize = libraryQueue.size,
            currentIndex = currentQueueIndex,
            shuffleEnabled = shuffleEnabled,
        )
        playbackQueue = playbackOrder.mapNotNull { libraryQueue.getOrNull(it) }
    }

    private fun restorePlaybackQueue(currentQueueIndex: Int, savedPlaybackOrder: List<Int>?) {
        playbackOrder = PlaybackSessionRules.restorePlaybackOrder(
            savedPlaybackOrder = savedPlaybackOrder,
            queueSize = libraryQueue.size,
            currentQueueIndex = currentQueueIndex,
            shuffleEnabled = shuffleEnabled,
        )
        playbackQueue = playbackOrder.mapNotNull { libraryQueue.getOrNull(it) }
    }

    // Swap two items in playbackOrder (both must be strictly after current).
    // Calls moveMediaItem on the controller using playback indices.
    private fun swapPlaybackItems(fromIndex: Int, toIndex: Int, currentPlaybackIndex: Int) {
        if (fromIndex <= currentPlaybackIndex || toIndex <= currentPlaybackIndex) return
        if (fromIndex !in playbackOrder.indices || toIndex !in playbackOrder.indices) return

        val newOrder = playbackOrder.toMutableList()
        val tmp = newOrder[fromIndex]
        newOrder[fromIndex] = newOrder[toIndex]
        newOrder[toIndex] = tmp
        playbackOrder = newOrder
        playbackQueue = playbackOrder.mapNotNull { libraryQueue.getOrNull(it) }

        if (!playerQueueNeedsSync) {
            mediaController?.moveMediaItem(fromIndex, toIndex)
        }

        _nowPlayingState.update {
            it.copy(
                queue = playbackQueue,
                currentIndex = currentPlaybackIndex,
                shuffleEnabled = shuffleEnabled,
                repeatMode = repeatMode,
            )
        }
        saveSessionAsync()
    }

    private fun syncNowPlayingState(fromTransition: Boolean = false, notifyStats: Boolean = true) {
        val controller = mediaController
        val preservePlan = pendingPreserveSearchPlanForSync(controller, fromTransition)
        val syncedCurrentSongId = if (playerQueueNeedsSync) {
            controller?.currentMediaItem?.mediaId?.toLongOrNull() ?: _nowPlayingState.value.song?.id
        } else {
            null
        }
        val currentPlaybackIndex = preservePlan?.currentIndex?.takeIf { it in playbackQueue.indices }
            ?: syncedCurrentSongId
                ?.let { id -> playbackQueue.indexOfFirst { it.id == id } }
                ?.takeIf { it >= 0 }
            ?: controller?.currentMediaItemIndex?.takeIf { it in playbackQueue.indices }
            ?: _nowPlayingState.value.currentIndex.takeIf { it in playbackQueue.indices }
            ?: 0
        val currentSong = preservePlan?.currentSongId
            ?.let { id -> playbackQueue.firstOrNull { it.id == id } }
            ?: syncedCurrentSongId
                ?.let { id -> libraryQueue.firstOrNull { it.id == id } }
            ?: playbackQueue.getOrNull(currentPlaybackIndex)

        val songChanged = currentSong != null && currentSong.id != _nowPlayingState.value.song?.id
        if (DEBUG_STATS && (fromTransition || songChanged)) {
            Log.d(TAG, "[syncNPS] fromTransition=$fromTransition songChanged=$songChanged currentSongId=${currentSong?.id} prevSongId=${_nowPlayingState.value.song?.id} isPlaying=${controller?.isPlaying}")
        }

        if (!isExternalPlayback && notifyStats && currentSong != null && (fromTransition || songChanged)) {
            statsTracker.onSongSelected(currentSong)
            if (controller?.isPlaying == true) {
                statsTracker.onPlaybackStarted()
            }
        }
        _nowPlayingState.update {
            val durationMs = controller?.safeDurationMs(fallbackMs = currentSong?.duration ?: it.song?.duration ?: 0L)
                ?: it.durationMs
            val positionMs = controller?.safePositionMs(durationMs) ?: it.positionMs.coerceForDuration(durationMs)
            it.copy(
                song               = currentSong ?: it.song,
                isPlaying          = controller?.isPlaying ?: it.isPlaying,
                queue              = playbackQueue,
                currentIndex       = currentPlaybackIndex,
                shuffleEnabled     = shuffleEnabled,
                repeatMode         = repeatMode,
                positionMs         = positionMs,
                durationMs         = durationMs,
                bufferedPositionMs = controller?.safeBufferedPositionMs(durationMs) ?: it.bufferedPositionMs.coerceForDuration(durationMs),
                isSeekable         = controller?.isCurrentMediaItemSeekable ?: it.isSeekable,
            )
        }
    }

    private fun pendingPreserveSearchPlanForSync(
        controller: MediaController?,
        fromTransition: Boolean,
    ): SearchPlaybackPlan? {
        val mediaSongId = controller?.currentMediaItem?.mediaId?.toLongOrNull()
        val mediaIndex = controller?.currentMediaItemIndex
        val decision = SearchPlaybackPlanner.preserveSyncDecision(
            plan = pendingPreserveSearchPlan,
            activeQueue = playbackQueue,
            mediaSongId = mediaSongId,
            mediaIndex = mediaIndex,
            fromTransition = fromTransition,
        )
        if (decision.action == PreserveSearchSyncAction.ClearPlan) {
            pendingPreserveSearchPlan = null
        }
        return decision.plan
    }

    private fun MediaController.safePositionMs(durationMs: Long): Long =
        currentPosition.coerceAtLeast(0L).coerceForDuration(durationMs)

    private fun MediaController.safeDurationMs(fallbackMs: Long): Long =
        duration.takeIf { it > 0L } ?: fallbackMs.coerceAtLeast(0L)

    private fun MediaController.safeBufferedPositionMs(durationMs: Long): Long =
        bufferedPosition.coerceAtLeast(0L).coerceForDuration(durationMs)

    private fun Long.coerceForDuration(durationMs: Long): Long =
        if (durationMs > 0L) coerceIn(0L, durationMs) else coerceAtLeast(0L)

    private fun List<Song>.idList(): String =
        joinToString(prefix = "[", postfix = "]") { it.id.toString() }

    // Returns the library index of the current song.
    // controller.currentMediaItemIndex is a playback index; map via playbackOrder.
    private fun currentQueueIndex(): Int? {
        mediaController?.currentMediaItem?.mediaId?.toLongOrNull()
            ?.let { id -> libraryQueue.indexOfFirst { it.id == id } }
            ?.takeIf { it >= 0 }
            ?.let { return it }

        val playbackIdx = mediaController?.currentMediaItemIndex
            ?.takeIf { it in playbackOrder.indices }
        if (playbackIdx != null) return playbackOrder.getOrNull(playbackIdx)

        val currentSongId = _nowPlayingState.value.song?.id ?: return null
        return libraryQueue.indexOfFirst { it.id == currentSongId }.takeIf { it >= 0 }
    }

    private fun currentPlaybackIndex(): Int? {
        mediaController?.currentMediaItem?.mediaId?.toLongOrNull()
            ?.let { id -> playbackQueue.indexOfFirst { it.id == id } }
            ?.takeIf { it >= 0 }
            ?.let { return it }

        mediaController?.currentMediaItemIndex
            ?.takeIf { it in playbackQueue.indices }
            ?.let { return it }

        _nowPlayingState.value.currentIndex
            .takeIf { it in playbackQueue.indices }
            ?.let { return it }

        val currentSongId = _nowPlayingState.value.song?.id ?: return null
        return playbackQueue.indexOfFirst { it.id == currentSongId }.takeIf { it >= 0 }
    }

    private fun Song.toMediaItem(): MediaItem =
        MediaItem.Builder()
            .setUri(uri)
            .setMediaId(id.toString())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(displayTitle)
                    .setArtist(displayArtist)
                    .setAlbumTitle(album)
                    .setExtras(Bundle().apply { putLong("wavdrop_album_id", albumId) })
                    .build()
            )
            .build()

    private fun Uri.toExternalSong(displayName: String?): Song {
        val title = displayName
            ?.substringBeforeLast('.', missingDelimiterValue = displayName)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: lastPathSegment
                ?.substringBeforeLast('.', missingDelimiterValue = lastPathSegment.orEmpty())
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            ?: "External audio"
        return Song(
            id = EXTERNAL_AUDIO_SONG_ID,
            title = title,
            artist = "Unknown Artist",
            album = "External audio",
            albumId = 0L,
            duration = 0L,
            uri = toString(),
            dateAdded = System.currentTimeMillis() / 1_000L,
            trackNumber = 0,
            year = 0,
            folderPath = null,
            folderName = null,
        )
    }

    private fun RepeatMode.toPlayerRepeatMode(): Int = when (this) {
        RepeatMode.OFF -> Player.REPEAT_MODE_OFF
        RepeatMode.ALL -> Player.REPEAT_MODE_ALL
        RepeatMode.ONE -> Player.REPEAT_MODE_ONE
    }

    private data class PlaybackRequest(
        val queue: List<Song>,
        val startSong: Song,
        val preservePlaybackOrder: Boolean = false,
    )

    private data class PreserveSearchRequest(
        val plan: SearchPlaybackPlan,
        val startSong: Song,
    )

    private data class ExternalPlaybackRequest(
        val uri: Uri,
        val displayName: String?,
    )

}
