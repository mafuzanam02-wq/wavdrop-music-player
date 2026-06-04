package com.launchpoint.wavdrop.playback

import android.content.ComponentName
import android.content.Context
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

        // Set to true to enable verbose stats lifecycle logs for on-device debugging.
        // MUST be false (or removed) before a release build.
        const val DEBUG_STATS = true
    }

    private var mediaController: MediaController? = null

    private var pendingPlaybackRequest: PlaybackRequest? = null
    private var pendingRestorePositionMs: Long? = null
    private var hasRestoredSession = false
    private var libraryQueue: List<Song> = emptyList()
    private var playbackOrder: List<Int> = emptyList()
    private var playbackQueue: List<Song> = emptyList()
    private var shuffleEnabled: Boolean = false
    private var repeatMode: RepeatMode = RepeatMode.OFF

    // Last position observed by the 500 ms ticker. Starts at -1 (uninitialized).
    // Reset to -1 whenever a new song/session starts so the loop detector doesn't see
    // a false wrap from the old song's late position to the new song's early position.
    private var lastKnownPositionMs: Long = -1L

    private val _nowPlayingState = MutableStateFlow(NowPlayingState())
    val nowPlayingState: StateFlow<NowPlayingState> = _nowPlayingState.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var positionTickerJob: Job? = null
    private var wiredResumeJob: Job? = null

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (DEBUG_STATS) {
                val songId = _nowPlayingState.value.song?.id
                val pos = mediaController?.currentPosition ?: -1
                Log.d(TAG, "[isPlayingChanged] isPlaying=$isPlaying songId=$songId pos=$pos repeatMode=$repeatMode")
            }
            syncNowPlayingState()
            if (isPlaying) {
                statsTracker.onPlaybackStarted()
                startPositionTicker()
            } else {
                statsTracker.onPlaybackPaused()
                stopPositionTicker()
                syncPosition()
                saveSessionAsync()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (DEBUG_STATS) {
                Log.d(TAG, "[mediaItemTransition] reason=$reason mediaId=${mediaItem?.mediaId} repeatMode=$repeatMode isPlaying=${mediaController?.isPlaying}")
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
                // Reset ticker tracking so it doesn't double-detect this same boundary.
                lastKnownPositionMs = -1L
                val song = libraryQueue.getOrNull(newPosition.mediaItemIndex) ?: return
                if (DEBUG_STATS) Log.d(TAG, "[posDiscontinuity] AUTO_TRANSITION → songId=${song.id}")
                statsTracker.onSongSelected(song)
                if (mediaController?.isPlaying == true) {
                    statsTracker.onPlaybackStarted()
                }
            }
            syncNowPlayingState(notifyStats = reason != Player.DISCONTINUITY_REASON_AUTO_TRANSITION)
            saveSessionAsync()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (DEBUG_STATS) {
                Log.d(TAG, "[playbackStateChanged] state=$playbackState songId=${_nowPlayingState.value.song?.id} repeatMode=$repeatMode")
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
                    val restorePos = pendingRestorePositionMs
                    pendingPlaybackRequest = null
                    pendingRestorePositionMs = null
                    when {
                        playRequest != null -> playFromQueue(playRequest.queue, playRequest.startSong)
                        restorePos != null && libraryQueue.isNotEmpty() -> {
                            val startIndex = _nowPlayingState.value.song?.id
                                ?.let { id -> libraryQueue.indexOfFirst { it.id == id } }
                                ?.takeIf { it >= 0 } ?: 0
                            controller.repeatMode = repeatMode.toPlayerRepeatMode()
                            controller.shuffleModeEnabled = false
                            controller.setMediaItems(libraryQueue.map { it.toMediaItem() }, startIndex, restorePos)
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

    fun playFromQueue(queue: List<Song>, startSong: Song) {
        val normalizedQueue = queue.ifEmpty { listOf(startSong) }
        val originalStartIndex = normalizedQueue.indexOfFirst { it.id == startSong.id }
            .takeIf { it >= 0 } ?: 0

        libraryQueue = normalizedQueue
        rebuildPlaybackQueue(currentQueueIndex = originalStartIndex)
        val playbackStartIndex = playbackOrder.indexOf(originalStartIndex)
            .takeIf { it >= 0 } ?: 0

        // Reset position tracking so the ticker doesn't see the old position as a loop wrap.
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
        controller.setMediaItems(libraryQueue.map { it.toMediaItem() }, originalStartIndex, 0L)
        controller.prepare()
        syncNowPlayingState()
        controller.play()
        saveSessionAsync()
    }

    fun playNext(song: Song) {
        val currentPlaybackIndex = currentPlaybackIndex()
        val libraryIndexOfCurrent = currentQueueIndex()

        if (libraryQueue.isEmpty() || currentPlaybackIndex == null || libraryIndexOfCurrent == null) {
            playSong(song)
            return
        }

        // Insert into libraryQueue right after the current library item so the Media3 index
        // space stays aligned with libraryQueue without touching any earlier items.
        val insertLibraryIndex = libraryIndexOfCurrent + 1
        libraryQueue = libraryQueue.toMutableList().also { it.add(insertLibraryIndex, song) }
        playbackOrder = QueueMutation.shiftPlaybackOrderForInsert(
            playbackOrder, insertLibraryIndex, currentPlaybackIndex,
        )
        playbackQueue = playbackOrder.mapNotNull { libraryQueue.getOrNull(it) }

        // addMediaItem does not touch the current item — no setMediaItems/prepare/play needed.
        mediaController?.addMediaItem(insertLibraryIndex, song.toMediaItem())

        _nowPlayingState.update {
            it.copy(
                queue = playbackQueue,
                currentIndex = currentPlaybackIndex,
            )
        }
        saveSessionAsync()
    }

    fun addToQueue(song: Song) {
        val currentPlaybackIndex = currentPlaybackIndex()
        if (libraryQueue.isEmpty() || currentPlaybackIndex == null) {
            playSong(song)
            return
        }
        val insertLibraryIndex = libraryQueue.size
        libraryQueue = libraryQueue + song
        playbackOrder = playbackOrder + insertLibraryIndex
        playbackQueue = playbackOrder.mapNotNull { libraryQueue.getOrNull(it) }

        mediaController?.addMediaItem(song.toMediaItem())

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
        val libraryIndex = playbackOrder.getOrNull(playbackIndex) ?: return
        seekToQueueIndex(controller, libraryIndex)
    }

    fun removeFromQueue(playbackIndex: Int) {
        val currentPlaybackIndex = _nowPlayingState.value.currentIndex
        if (playbackIndex == currentPlaybackIndex) return
        if (playbackIndex !in playbackQueue.indices) return
        val removedLibraryIndex = playbackOrder.getOrNull(playbackIndex) ?: return
        if (removedLibraryIndex !in libraryQueue.indices) return

        libraryQueue = libraryQueue.toMutableList().also {
            it.removeAt(removedLibraryIndex)
        }
        playbackOrder = playbackOrder
            .filterIndexed { index, _ -> index != playbackIndex }
            .map { index -> if (index > removedLibraryIndex) index - 1 else index }
        playbackQueue = playbackOrder.mapNotNull { libraryQueue.getOrNull(it) }

        mediaController?.removeMediaItem(removedLibraryIndex)

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

    fun moveQueueItemUp(playbackIndex: Int) {
        val currentPlaybackIndex = _nowPlayingState.value.currentIndex
        if (playbackIndex <= currentPlaybackIndex || playbackIndex <= 0) return
        moveQueueItemWithNativePlaylistMove(
            playbackIndex = playbackIndex,
            otherIndex = playbackIndex - 1,
            currentPlaybackIndex = currentPlaybackIndex,
        )
    }

    fun moveQueueItemDown(playbackIndex: Int) {
        val currentPlaybackIndex = _nowPlayingState.value.currentIndex
        if (playbackIndex <= currentPlaybackIndex || playbackIndex >= playbackQueue.size - 1) return
        moveQueueItemWithNativePlaylistMove(
            playbackIndex = playbackIndex,
            otherIndex = playbackIndex + 1,
            currentPlaybackIndex = currentPlaybackIndex,
        )
    }

    fun moveToPlayNext(playbackIndex: Int) {
        val currentPlaybackIndex = _nowPlayingState.value.currentIndex
        val newQueue = QueueMutation.moveToPlayNext(
            playbackQueue, playbackIndex, currentPlaybackIndex,
        ) ?: return
        applyQueueMutationByIdentity(newQueue, currentPlaybackIndex)
    }

    fun togglePlayPause() {
        val controller = mediaController ?: return
        if (controller.isPlaying) controller.pause() else controller.play()
        syncNowPlayingState()
    }

    fun skipToNext() {
        val controller = mediaController ?: return
        val nextIndex = QueueNavigator.nextQueueIndex(
            playbackOrder = playbackOrder,
            currentQueueIndex = controller.currentMediaItemIndex,
            repeatMode = repeatMode,
        ) ?: return
        seekToQueueIndex(controller, nextIndex)
    }

    fun skipToPrevious() {
        val controller = mediaController ?: return
        when (val action = QueueNavigator.previousQueueAction(
            playbackOrder = playbackOrder,
            currentQueueIndex = controller.currentMediaItemIndex,
            currentPositionMs = controller.currentPosition,
            repeatMode = repeatMode,
        )) {
            is PreviousQueueAction.MoveTo -> seekToQueueIndex(controller, action.index)
            PreviousQueueAction.RestartCurrent -> {
                controller.seekTo(0L)
                syncPosition()
                saveSessionAsync()
            }
            null -> Unit
        }
    }

    fun toggleShuffle() {
        val currentQueueIndex = currentQueueIndex() ?: return
        val currentSong = libraryQueue.getOrNull(currentQueueIndex) ?: return

        shuffleEnabled = !shuffleEnabled
        rebuildPlaybackQueue(currentQueueIndex = currentQueueIndex)
        val playbackIndex = playbackOrder.indexOf(currentQueueIndex)
            .takeIf { it >= 0 } ?: 0

        _nowPlayingState.update {
            it.copy(
                song = currentSong,
                queue = playbackQueue,
                currentIndex = playbackIndex,
                shuffleEnabled = shuffleEnabled,
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
        if (hasRestoredSession) return
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
            rebuildPlaybackQueue(currentQueueIndex = mappedQueue.indexOf(startSong).coerceAtLeast(0))
            val playbackIndex = playbackOrder.indexOf(mappedQueue.indexOf(startSong)).takeIf { it >= 0 } ?: 0

            _nowPlayingState.update {
                it.copy(
                    song               = startSong,
                    queue              = playbackQueue,
                    currentIndex       = playbackIndex,
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
                libraryQueue.map { it.toMediaItem() },
                mappedQueue.indexOf(startSong).coerceAtLeast(0),
                snapshot.positionMs,
            )
            controller.prepare()
            syncNowPlayingState()
        }
    }

    /**
     * Resumes the last session and starts playback after a Bluetooth audio device connects.
     *
     * Guards checked (in order):
     * 1. autoResumeOnBluetooth setting must be ON.
     * 2. rememberLastTrack must be ON.
     * 3. Playback must not already be active.
     * 4. A ~1 s delay lets the BT audio route stabilize; playback is re-checked after.
     *
     * Warm path (HomeViewModel already called restoreSessionIfNeeded): the session is loaded
     * and the player is prepared; this just calls play().
     * Cold path (service restarted before HomeViewModel): loads and prepares the session,
     * then calls play().
     */
    fun resumeForBluetooth(availableSongs: List<Song>) {
        scope.launch {
            val settings = resumeBehaviorRepository.settings.first()
            if (!settings.autoResumeOnBluetooth) return@launch
            if (!settings.rememberLastTrack) return@launch

            val controller = mediaController ?: return@launch
            if (controller.isPlaying) return@launch

            delay(1_000L)

            if (controller.isPlaying) return@launch

            val song = _nowPlayingState.value.song
            if (song != null && libraryQueue.isNotEmpty()) {
                // Warm path: session already loaded, player is prepared — just start.
                lastKnownPositionMs = -1L
                statsTracker.onSongSelected(song)
                controller.play()
            } else {
                // Cold path: load session from scratch before playing.
                resumeSessionCold(availableSongs, settings)
            }
        }
    }

    /**
     * Resumes the last session and starts playback after wired headphones or a wired headset
     * connects.
     *
     * Guards and timing mirror [resumeForBluetooth]; checks [autoResumeOnHeadphones] instead.
     * A [wiredResumeJob] guard prevents concurrent resume attempts when multiple
     * AudioDeviceCallback events fire in quick succession for the same physical device.
     */
    fun resumeForWiredHeadphones(availableSongs: List<Song>) {
        if (wiredResumeJob?.isActive == true) return
        wiredResumeJob = scope.launch {
            val settings = resumeBehaviorRepository.settings.first()
            if (!settings.autoResumeOnHeadphones) return@launch
            if (!settings.rememberLastTrack) return@launch

            val controller = mediaController ?: return@launch
            if (controller.isPlaying) return@launch

            delay(1_000L)

            if (controller.isPlaying) return@launch

            val song = _nowPlayingState.value.song
            if (song != null && libraryQueue.isNotEmpty()) {
                // Warm path: session already loaded, player is prepared — just start.
                lastKnownPositionMs = -1L
                statsTracker.onSongSelected(song)
                controller.play()
            } else {
                // Cold path: load session from scratch before playing.
                resumeSessionCold(availableSongs, settings)
            }
        }
    }

    /**
     * Cold-path shared by [resumeForBluetooth] and [resumeForWiredHeadphones].
     * Loads the saved session snapshot, applies resume-behavior rules, rebuilds the queue,
     * and starts playback.
     */
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
        rebuildPlaybackQueue(currentQueueIndex = mappedQueue.indexOf(startSong).coerceAtLeast(0))
        val playbackIndex = playbackOrder.indexOf(mappedQueue.indexOf(startSong)).takeIf { it >= 0 } ?: 0

        lastKnownPositionMs = -1L
        statsTracker.onSongSelected(startSong)

        _nowPlayingState.update {
            it.copy(
                song               = startSong,
                queue              = playbackQueue,
                currentIndex       = playbackIndex,
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
            libraryQueue.map { it.toMediaItem() },
            mappedQueue.indexOf(startSong).coerceAtLeast(0),
            snapshot.positionMs,
        )
        controller.prepare()
        syncNowPlayingState()
        controller.play()
    }

    private fun saveSessionAsync() {
        if (libraryQueue.isEmpty()) return
        val state = _nowPlayingState.value
        val controller = mediaController
        val positionMs = controller?.currentPosition?.coerceAtLeast(0L) ?: state.positionMs
        val currentLibraryIndex = controller?.currentMediaItemIndex
            ?.takeIf { it in libraryQueue.indices }
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

    /**
     * Called by the 500 ms position ticker while playing.
     *
     * Handles two responsibilities:
     * 1. Update NowPlayingState with the latest position/duration from the controller.
     * 2. Detect REPEAT_ONE loop boundaries when Media3 callbacks (onMediaItemTransition,
     *    onPositionDiscontinuity) are not delivered reliably over IPC.
     *
     * The detection is position-based: if the previous observed position was at least
     * [LoopBoundaryDetector.LOOP_MIN_PREV_POS_MS] and the current position is under
     * [LoopBoundaryDetector.LOOP_NEAR_START_MS], the song looped back to the start.
     * Only active when [repeatMode] == [RepeatMode.ONE].
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
            val song = currentQueueIndex()?.let { libraryQueue.getOrNull(it) }
            if (song != null) {
                if (DEBUG_STATS) Log.d(TAG, "[ticker] LOOP BOUNDARY detected prev=$prev cur=$currentPos songId=${song.id}")
                statsTracker.onSongSelected(song)
                statsTracker.onPlaybackStarted()
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

    /** One-shot position sync with no loop-detection side effect (used on pause). */
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

    private fun seekToQueueIndex(controller: MediaController, index: Int) {
        // Reset position tracking: after the seek the ticker should not see the old
        // song's position as a loop wrap when the new item starts near 0.
        lastKnownPositionMs = -1L
        val shouldPlay = controller.isPlaying
        controller.seekTo(index, 0L)
        if (shouldPlay) controller.play()
        syncNowPlayingState()
        saveSessionAsync()
    }

    private fun rebuildPlaybackQueue(currentQueueIndex: Int) {
        playbackOrder = QueueNavigator.buildPlaybackOrder(
            queueSize = libraryQueue.size,
            currentIndex = currentQueueIndex,
            shuffleEnabled = shuffleEnabled,
        )
        playbackQueue = playbackOrder.mapNotNull { libraryQueue.getOrNull(it) }
    }

    private fun moveQueueItemWithNativePlaylistMove(
        playbackIndex: Int,
        otherIndex: Int,
        currentPlaybackIndex: Int,
    ) {
        val nativeMove = QueueMutation.playbackOrderAfterNativeMove(
            playbackOrder = playbackOrder,
            playbackIndex = playbackIndex,
            otherIndex = otherIndex,
            currentPlaybackIndex = currentPlaybackIndex,
        ) ?: return
        val newLibraryQueue = libraryQueue.moveItem(
            fromIndex = nativeMove.fromLibraryIndex,
            toIndex = nativeMove.toLibraryIndex,
        ) ?: return

        libraryQueue = newLibraryQueue
        playbackOrder = nativeMove.playbackOrder
        playbackQueue = playbackOrder.mapNotNull { libraryQueue.getOrNull(it) }

        mediaController?.moveMediaItem(nativeMove.fromLibraryIndex, nativeMove.toLibraryIndex)

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

    private fun List<Song>.moveItem(fromIndex: Int, toIndex: Int): List<Song>? {
        if (fromIndex !in indices || toIndex !in indices) return null
        if (fromIndex == toIndex) return this
        val moved = toMutableList()
        val item = moved.removeAt(fromIndex)
        moved.add(toIndex, item)
        return moved
    }

    private fun applyQueueMutationByIdentity(
        newPlaybackQueue: List<Song>,
        currentPlaybackIndex: Int,
    ) {
        if (newPlaybackQueue.isEmpty()) return
        val safeCurrentPlaybackIndex = currentPlaybackIndex.coerceIn(newPlaybackQueue.indices)
        val currentSong = newPlaybackQueue.getOrNull(safeCurrentPlaybackIndex)

        libraryQueue = newPlaybackQueue
        playbackOrder = libraryQueue.indices.toList()
        playbackQueue = libraryQueue

        mediaController?.let { controller ->
            val positionMs = controller.currentPosition.coerceAtLeast(0L)
            val wasPlaying = controller.isPlaying
            controller.setMediaItems(libraryQueue.map { it.toMediaItem() }, safeCurrentPlaybackIndex, positionMs)
            controller.prepare()
            if (wasPlaying) controller.play()
        }

        _nowPlayingState.update {
            it.copy(
                song = currentSong ?: it.song,
                queue = playbackQueue,
                currentIndex = safeCurrentPlaybackIndex,
                shuffleEnabled = shuffleEnabled,
                repeatMode = repeatMode,
            )
        }
        saveSessionAsync()
    }

    private fun syncNowPlayingState(fromTransition: Boolean = false, notifyStats: Boolean = true) {
        val controller = mediaController
        val currentQueueIndex = controller?.currentMediaItemIndex ?: currentQueueIndex()
        val currentSong = currentQueueIndex?.let { libraryQueue.getOrNull(it) }
        val currentPlaybackIndex = currentQueueIndex
            ?.let(playbackOrder::indexOf)
            ?.takeIf { it >= 0 }
            ?: _nowPlayingState.value.currentIndex

        val songChanged = currentSong != null && currentSong.id != _nowPlayingState.value.song?.id
        if (DEBUG_STATS && (fromTransition || songChanged)) {
            Log.d(TAG, "[syncNPS] fromTransition=$fromTransition songChanged=$songChanged currentSongId=${currentSong?.id} prevSongId=${_nowPlayingState.value.song?.id} isPlaying=${controller?.isPlaying}")
        }

        if (notifyStats && currentSong != null && (fromTransition || songChanged)) {
            statsTracker.onSongSelected(currentSong)
            // During auto-advance / repeat the player may stay in isPlaying=true,
            // so onIsPlayingChanged(true) never fires for the new song.
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

    private fun currentQueueIndex(): Int? {
        mediaController?.currentMediaItemIndex
            ?.takeIf { it in libraryQueue.indices }
            ?.let { return it }

        val currentSongId = _nowPlayingState.value.song?.id ?: return null
        return libraryQueue.indexOfFirst { it.id == currentSongId }
            .takeIf { it >= 0 }
    }

    private fun currentPlaybackIndex(): Int? {
        _nowPlayingState.value.currentIndex
            .takeIf { it in playbackQueue.indices }
            ?.let { return it }

        val currentSongId = _nowPlayingState.value.song?.id ?: return null
        return playbackQueue.indexOfFirst { it.id == currentSongId }
            .takeIf { it >= 0 }
    }

    private fun effectiveQueue(): List<Song> =
        playbackQueue.ifEmpty { libraryQueue }

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

    private fun RepeatMode.toPlayerRepeatMode(): Int = when (this) {
        RepeatMode.OFF -> Player.REPEAT_MODE_OFF
        RepeatMode.ALL -> Player.REPEAT_MODE_ALL
        RepeatMode.ONE -> Player.REPEAT_MODE_ONE
    }

    private data class PlaybackRequest(
        val queue: List<Song>,
        val startSong: Song,
    )
}
