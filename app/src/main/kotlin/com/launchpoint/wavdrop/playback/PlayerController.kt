package com.launchpoint.wavdrop.playback

import android.content.ComponentName
import android.content.Context
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
) {
    private var mediaController: MediaController? = null

    private var pendingPlaybackRequest: PlaybackRequest? = null
    private var pendingRestorePositionMs: Long? = null
    private var hasRestoredSession = false
    private var libraryQueue: List<Song> = emptyList()
    private var playbackOrder: List<Int> = emptyList()
    private var playbackQueue: List<Song> = emptyList()
    private var shuffleEnabled: Boolean = false
    private var repeatMode: RepeatMode = RepeatMode.OFF

    private val _nowPlayingState = MutableStateFlow(NowPlayingState())
    val nowPlayingState: StateFlow<NowPlayingState> = _nowPlayingState.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var positionTickerJob: Job? = null

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
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
            // Belt-and-suspenders: covers queue auto-advance and any REPEAT_ONE loop where
            // Media3 does fire onMediaItemTransition. The primary loop-boundary signal is
            // onPositionDiscontinuity below; this acts as a fallback for missed callbacks.
            syncNowPlayingState(fromTransition = true)
            saveSessionAsync()
        }

        /**
         * Primary REPEAT_ONE detection. Media3 does NOT reliably fire onMediaItemTransition
         * on every loop of the same item — it may fire 0 or 1 times. However,
         * onPositionDiscontinuity with DISCONTINUITY_REASON_AUTO_TRANSITION fires on every
         * automatic position jump, including each REPEAT_ONE restart and each queue
         * auto-advance. We use it as the definitive "new listening session" signal.
         *
         * Seeks ([DISCONTINUITY_REASON_SEEK]) do NOT trigger this path, so user scrubbing
         * within a song never resets the stats session.
         */
        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                val song = libraryQueue.getOrNull(newPosition.mediaItemIndex) ?: return
                statsTracker.onSongSelected(song)
                if (mediaController?.isPlaying == true) {
                    statsTracker.onPlaybackStarted()
                }
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
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
                        }
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

        statsTracker.onSongSelected(startSong)
        _nowPlayingState.update {
            it.copy(
                song = startSong,
                queue = playbackQueue,
                currentIndex = playbackStartIndex,
                shuffleEnabled = shuffleEnabled,
                repeatMode = repeatMode,
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
        controller.play()
        saveSessionAsync()
    }

    fun togglePlayPause() {
        val controller = mediaController ?: return
        if (controller.isPlaying) controller.pause() else controller.play()
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
            PreviousQueueAction.RestartCurrent -> controller.seekTo(0L)
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
        _nowPlayingState.update { it.copy(positionMs = clamped) }
        saveSessionAsync()
    }

    fun restoreSessionIfNeeded(availableSongs: List<Song>) {
        if (hasRestoredSession) return
        hasRestoredSession = true
        scope.launch {
            val snapshot = sessionRepository.load() ?: return@launch
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
                    song           = startSong,
                    queue          = playbackQueue,
                    currentIndex   = playbackIndex,
                    shuffleEnabled = shuffleEnabled,
                    repeatMode     = repeatMode,
                    positionMs     = snapshot.positionMs,
                )
            }

            val controller = mediaController
            if (controller == null) {
                pendingRestorePositionMs = snapshot.positionMs
                return@launch
            }
            controller.repeatMode       = repeatMode.toPlayerRepeatMode()
            controller.shuffleModeEnabled = false
            controller.setMediaItems(
                libraryQueue.map { it.toMediaItem() },
                mappedQueue.indexOf(startSong).coerceAtLeast(0),
                snapshot.positionMs,
            )
            controller.prepare()
        }
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
                syncPosition()
                delay(500)
            }
        }
    }

    private fun stopPositionTicker() {
        positionTickerJob?.cancel()
        positionTickerJob = null
    }

    private fun syncPosition() {
        val controller = mediaController ?: return
        _nowPlayingState.update {
            it.copy(
                positionMs         = controller.currentPosition.coerceAtLeast(0L),
                durationMs         = controller.duration.let { d -> if (d < 0) 0L else d },
                bufferedPositionMs = controller.bufferedPosition.coerceAtLeast(0L),
                isSeekable         = controller.isCurrentMediaItemSeekable,
            )
        }
    }

    private fun seekToQueueIndex(controller: MediaController, index: Int) {
        val shouldPlay = controller.isPlaying
        controller.seekTo(index, 0L)
        if (shouldPlay) controller.play()
    }

    private fun rebuildPlaybackQueue(currentQueueIndex: Int) {
        playbackOrder = QueueNavigator.buildPlaybackOrder(
            queueSize = libraryQueue.size,
            currentIndex = currentQueueIndex,
            shuffleEnabled = shuffleEnabled,
        )
        playbackQueue = playbackOrder.mapNotNull { libraryQueue.getOrNull(it) }
    }

    private fun syncNowPlayingState(fromTransition: Boolean = false) {
        val controller = mediaController
        val currentQueueIndex = controller?.currentMediaItemIndex ?: currentQueueIndex()
        val currentSong = currentQueueIndex?.let { libraryQueue.getOrNull(it) }
        val currentPlaybackIndex = currentQueueIndex
            ?.let(playbackOrder::indexOf)
            ?.takeIf { it >= 0 }
            ?: _nowPlayingState.value.currentIndex
        // fromTransition=true on every onMediaItemTransition (auto-advance, REPEAT_ONE, seek).
        // The song-ID guard alone misses REPEAT_ONE because the ID doesn't change, leaving
        // playCountedForCurrent stuck at true and the second play never counted.
        if (currentSong != null && (fromTransition || currentSong.id != _nowPlayingState.value.song?.id)) {
            statsTracker.onSongSelected(currentSong)
            // During auto-advance / repeat the player stays in isPlaying=true, so
            // onIsPlayingChanged(true) never fires. Start the new session explicitly.
            if (controller?.isPlaying == true) {
                statsTracker.onPlaybackStarted()
            }
        }
        _nowPlayingState.update {
            it.copy(
                song               = currentSong ?: it.song,
                isPlaying          = controller?.isPlaying ?: it.isPlaying,
                queue              = playbackQueue,
                currentIndex       = currentPlaybackIndex,
                shuffleEnabled     = shuffleEnabled,
                repeatMode         = repeatMode,
                positionMs         = controller?.currentPosition?.coerceAtLeast(0L) ?: it.positionMs,
                durationMs         = controller?.duration?.let { d -> if (d < 0) 0L else d } ?: it.durationMs,
                bufferedPositionMs = controller?.bufferedPosition?.coerceAtLeast(0L) ?: it.bufferedPositionMs,
                isSeekable         = controller?.isCurrentMediaItemSeekable ?: it.isSeekable,
            )
        }
    }

    private fun currentQueueIndex(): Int? {
        mediaController?.currentMediaItemIndex
            ?.takeIf { it in libraryQueue.indices }
            ?.let { return it }

        val currentSongId = _nowPlayingState.value.song?.id ?: return null
        return libraryQueue.indexOfFirst { it.id == currentSongId }
            .takeIf { it >= 0 }
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
