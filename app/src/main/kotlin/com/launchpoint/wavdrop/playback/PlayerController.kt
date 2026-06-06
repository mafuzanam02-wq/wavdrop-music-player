package com.launchpoint.wavdrop.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
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
import javax.inject.Inject
import javax.inject.Singleton

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
        const val EXTERNAL_AUDIO_SONG_ID = Long.MIN_VALUE

        // Set to true to enable verbose stats lifecycle logs for on-device debugging.
        // MUST be false (or removed) before a release build.
        const val DEBUG_STATS = true
    }

    private var mediaController: MediaController? = null

    private var pendingPlaybackRequest: PlaybackRequest? = null
    private var pendingExternalPlaybackRequest: ExternalPlaybackRequest? = null
    private var pendingRestorePositionMs: Long? = null
    private var hasRestoredSession = false
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

    private val _nowPlayingState = MutableStateFlow(NowPlayingState())
    val nowPlayingState: StateFlow<NowPlayingState> = _nowPlayingState.asStateFlow()

    private val _sleepTimerState = MutableStateFlow(SleepTimerState())
    val sleepTimerState: StateFlow<SleepTimerState> = _sleepTimerState.asStateFlow()

    // Set to true by onBluetoothDeviceRemoved / onWiredDeviceRemoved when the device
    // disconnects while playback is active. Consumed (cleared) at the start of each
    // resume attempt so a single disconnect triggers at most one resume.
    private var wasInterruptedByBluetooth = false
    private var wasInterruptedByWired = false

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
                    val externalRequest = pendingExternalPlaybackRequest
                    val restorePos = pendingRestorePositionMs
                    pendingPlaybackRequest = null
                    pendingExternalPlaybackRequest = null
                    pendingRestorePositionMs = null
                    when {
                        externalRequest != null -> playExternalUri(
                            uri = externalRequest.uri,
                            displayName = externalRequest.displayName,
                        )
                        playRequest != null -> playFromQueue(playRequest.queue, playRequest.startSong)
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
        isExternalPlayback = false
        val normalizedQueue = queue.ifEmpty { listOf(startSong) }
        val originalStartIndex = normalizedQueue.indexOfFirst { it.id == startSong.id }
            .takeIf { it >= 0 } ?: 0

        libraryQueue = normalizedQueue
        rebuildPlaybackQueue(currentQueueIndex = originalStartIndex)
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
            pendingPlaybackRequest = PlaybackRequest(normalizedQueue, startSong)
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
        if (controller.isPlaying) controller.pause() else controller.play()
        syncNowPlayingState()
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
        if (hasRestoredSession || isExternalPlayback) return
        hasRestoredSession = true
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

            libraryQueue   = mappedQueue
            shuffleEnabled = snapshot.shuffleEnabled
            repeatMode     = snapshot.repeatMode
            val startLibraryIndex = mappedQueue.indexOf(startSong).coerceAtLeast(0)
            rebuildPlaybackQueue(currentQueueIndex = startLibraryIndex)
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
        wasInterruptedByBluetooth = mediaController?.isPlaying == true
    }

    /**
     * Called by PlaybackService when a wired audio output is removed.
     * Records whether playback was active at that moment so [resumeForWiredHeadphones] can
     * honour the RESUME_IF_INTERRUPTED mode.
     */
    fun onWiredDeviceRemoved() {
        wasInterruptedByWired = mediaController?.isPlaying == true
    }

    /**
     * Resumes the last session and starts playback after a Bluetooth audio device connects.
     */
    fun resumeForBluetooth(availableSongs: List<Song>) {
        scope.launch {
            val settings = resumeBehaviorRepository.settings.first()
            val interrupted = wasInterruptedByBluetooth
            wasInterruptedByBluetooth = false
            if (!settings.bluetoothResumeMode.shouldResume(interrupted)) return@launch
            if (!settings.rememberLastTrack) return@launch

            val controller = mediaController ?: return@launch
            if (controller.isPlaying) return@launch

            delay(1_000L)

            if (controller.isPlaying) return@launch

            val song = _nowPlayingState.value.song
            if (song != null && libraryQueue.isNotEmpty()) {
                lastKnownPositionMs = -1L
                statsTracker.onSongSelected(song)
                controller.play()
            } else {
                resumeSessionCold(availableSongs, settings)
            }
        }
    }

    /**
     * Resumes the last session and starts playback after wired headphones connect.
     */
    fun resumeForWiredHeadphones(availableSongs: List<Song>) {
        if (wiredResumeJob?.isActive == true) return
        wiredResumeJob = scope.launch {
            val settings = resumeBehaviorRepository.settings.first()
            val interrupted = wasInterruptedByWired
            wasInterruptedByWired = false
            if (!settings.wiredResumeMode.shouldResume(interrupted)) return@launch
            if (!settings.rememberLastTrack) return@launch

            val controller = mediaController ?: return@launch
            if (controller.isPlaying) return@launch

            delay(1_000L)

            if (controller.isPlaying) return@launch

            val song = _nowPlayingState.value.song
            if (song != null && libraryQueue.isNotEmpty()) {
                lastKnownPositionMs = -1L
                statsTracker.onSongSelected(song)
                controller.play()
            } else {
                resumeSessionCold(availableSongs, settings)
            }
        }
    }

    private suspend fun resumeSessionCold(
        availableSongs: List<Song>,
        settings: ResumeBehaviorSettings,
    ) {
        val rawSnapshot = sessionRepository.load() ?: return
        val snapshot = PlaybackSessionRules.applyResumeBehavior(rawSnapshot, settings) ?: return

        val idSet = availableSongs.associateBy { it.id }
        val mappedQueue = snapshot.queueSongIds.mapNotNull { idSet[it] }
        val startSong = PlaybackSessionRules.resolveStartSong(
            sessionSongId = snapshot.currentSongId,
            sessionIndex  = PlaybackSessionRules.clampIndex(snapshot.currentIndex, mappedQueue.size),
            mappedQueue   = mappedQueue,
        ) ?: return

        libraryQueue   = mappedQueue
        shuffleEnabled = snapshot.shuffleEnabled
        repeatMode     = snapshot.repeatMode
        val startLibraryIndex = mappedQueue.indexOf(startSong).coerceAtLeast(0)
        rebuildPlaybackQueue(currentQueueIndex = startLibraryIndex)
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

        val controller = mediaController ?: return
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
    }

    private fun saveSessionAsync() {
        if (isExternalPlayback) return
        if (libraryQueue.isEmpty()) return
        val state = _nowPlayingState.value
        val controller = mediaController
        val positionMs = controller?.currentPosition?.coerceAtLeast(0L) ?: state.positionMs

        val currentLibraryIndex = controller?.currentMediaItem?.mediaId?.toLongOrNull()
            ?.let { id -> libraryQueue.indexOfFirst { it.id == id } }
            ?.takeIf { it >= 0 }
            ?: libraryQueue.indexOfFirst { it.id == state.song?.id }.takeIf { it >= 0 }
            ?: 0

        val snapshot = PlaybackSessionSnapshot(
            queueSongIds   = libraryQueue.map { it.id },
            currentSongId  = state.song?.id,
            currentIndex   = currentLibraryIndex,
            positionMs     = positionMs,
            repeatMode     = repeatMode,
            shuffleEnabled = shuffleEnabled,
            updatedAtMs    = System.currentTimeMillis(),
        )
        scope.launch { sessionRepository.save(snapshot) }
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
        val syncedCurrentSongId = if (playerQueueNeedsSync) {
            controller?.currentMediaItem?.mediaId?.toLongOrNull() ?: _nowPlayingState.value.song?.id
        } else {
            null
        }
        val currentPlaybackIndex = syncedCurrentSongId
            ?.let { id -> playbackQueue.indexOfFirst { it.id == id } }
            ?.takeIf { it >= 0 }
            ?: controller?.currentMediaItemIndex?.takeIf { it in playbackQueue.indices }
            ?: _nowPlayingState.value.currentIndex.takeIf { it in playbackQueue.indices }
            ?: 0
        val currentSong = syncedCurrentSongId
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

    private fun MediaController.safePositionMs(durationMs: Long): Long =
        currentPosition.coerceAtLeast(0L).coerceForDuration(durationMs)

    private fun MediaController.safeDurationMs(fallbackMs: Long): Long =
        duration.takeIf { it > 0L } ?: fallbackMs.coerceAtLeast(0L)

    private fun MediaController.safeBufferedPositionMs(durationMs: Long): Long =
        bufferedPosition.coerceAtLeast(0L).coerceForDuration(durationMs)

    private fun Long.coerceForDuration(durationMs: Long): Long =
        if (durationMs > 0L) coerceIn(0L, durationMs) else coerceAtLeast(0L)

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
                    .setTitle(title)
                    .setArtist(artist)
                    .setAlbumTitle(album)
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
    )

    private data class ExternalPlaybackRequest(
        val uri: Uri,
        val displayName: String?,
    )
}
