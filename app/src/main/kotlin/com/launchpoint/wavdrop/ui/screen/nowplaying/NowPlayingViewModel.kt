package com.launchpoint.wavdrop.ui.screen.nowplaying

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.lyrics.LyricsRepository
import com.launchpoint.wavdrop.data.lyrics.LyricsResult
import com.launchpoint.wavdrop.data.model.PlaylistSong
import com.launchpoint.wavdrop.data.model.PlaylistSummary
import com.launchpoint.wavdrop.data.repository.AddToPlaylistResult
import com.launchpoint.wavdrop.data.repository.PlaylistOperationResult
import com.launchpoint.wavdrop.data.repository.PlaylistRepository
import com.launchpoint.wavdrop.data.repository.StatsRepository
import com.launchpoint.wavdrop.playback.NowPlayingState
import com.launchpoint.wavdrop.playback.PlayerController
import com.launchpoint.wavdrop.playback.SleepTimerOption
import com.launchpoint.wavdrop.playback.SleepTimerState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class NowPlayingViewModel @Inject constructor(
    private val playerController: PlayerController,
    private val statsRepository: StatsRepository,
    private val playlistRepository: PlaylistRepository,
    private val lyricsRepository: LyricsRepository,
) : ViewModel() {

    val nowPlayingState: StateFlow<NowPlayingState> = playerController.nowPlayingState
    val sleepTimerState: StateFlow<SleepTimerState> = playerController.sleepTimerState

    private val _lyricsState = MutableStateFlow<LyricsResult>(LyricsResult.NotFound)
    val lyricsState: StateFlow<LyricsResult> = _lyricsState.asStateFlow()

    val isFavorite: StateFlow<Boolean> = combine(
        nowPlayingState,
        statsRepository.favoriteSongIds(),
    ) { playing, favorites -> playing.song?.id in favorites }
        .stateIn(
            scope        = viewModelScope,
            started      = SharingStarted.WhileSubscribed(5_000),
            initialValue = false,
        )

    val hasCustomLyrics: StateFlow<Boolean> = nowPlayingState
        .map { it.song }
        .distinctUntilChanged { old, new -> old?.id == new?.id }
        .flatMapLatest { song ->
            if (song == null) {
                flowOf(false)
            } else {
                lyricsRepository.observeOverride(song.id).map { it != null }
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = false,
        )

    init {
        viewModelScope.launch {
            nowPlayingState
                .map { it.song }
                .distinctUntilChanged { old, new -> old?.id == new?.id }
                .collectLatest { song ->
                    if (song == null) {
                        _lyricsState.value = LyricsResult.NotFound
                    } else {
                        // Observe reactively: any override save/clear emits a new value
                        // immediately without needing the song to change or screen to reload.
                        lyricsRepository.observeLyrics(song).collect { result ->
                            _lyricsState.value = result
                        }
                    }
                }
        }
    }

    fun toggleFavorite() {
        val song = nowPlayingState.value.song ?: return
        viewModelScope.launch { statsRepository.toggleFavorite(song.id, song.uri) }
    }

    fun togglePlayPause() = playerController.togglePlayPause()

    fun skipToNext() = playerController.skipToNext()

    fun skipToPrevious() = playerController.skipToPrevious()

    fun toggleShuffle() = playerController.toggleShuffle()

    fun cycleRepeatMode() = playerController.cycleRepeatMode()

    fun setSleepTimer(option: SleepTimerOption) = playerController.setSleepTimer(option)

    fun setCustomSleepTimer(durationMs: Long) = playerController.setCustomSleepTimer(durationMs)

    fun seekTo(positionMs: Long) = playerController.seekTo(positionMs)

    fun jumpToQueueItem(playbackIndex: Int) = playerController.jumpToQueueItem(playbackIndex)

    fun removeFromQueue(playbackIndex: Int) = playerController.removeFromQueue(playbackIndex)

    fun moveQueueItemUp(playbackIndex: Int) = playerController.moveQueueItemUp(playbackIndex)

    fun moveQueueItemDown(playbackIndex: Int) = playerController.moveQueueItemDown(playbackIndex)

    fun moveQueueItemTo(fromIndex: Int, toIndex: Int) = playerController.moveQueueItemTo(fromIndex, toIndex)

    fun moveToPlayNext(playbackIndex: Int) = playerController.moveToPlayNext(playbackIndex)

    fun playNext(song: Song) = playerController.playNext(song)

    fun addToQueue(song: Song) = playerController.addToQueue(song)

    fun saveCustomLyrics(text: String, onComplete: () -> Unit = {}) {
        val song = nowPlayingState.value.song ?: return
        viewModelScope.launch {
            lyricsRepository.saveCustomLyrics(song, text)
            onComplete()
        }
    }

    fun clearCustomLyrics(onComplete: () -> Unit = {}) {
        val song = nowPlayingState.value.song ?: return
        viewModelScope.launch {
            lyricsRepository.clearCustomLyrics(song)
            onComplete()
        }
    }

    val playlists: StateFlow<List<PlaylistSummary>> = playlistRepository.observePlaylists()
        .stateIn(
            scope        = viewModelScope,
            started      = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    val allPlaylistSongs: StateFlow<List<PlaylistSong>> = playlistRepository.observeAllPlaylistSongs()
        .stateIn(
            scope        = viewModelScope,
            started      = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    fun addToPlaylist(playlistId: Long, onResult: (AddToPlaylistResult) -> Unit = {}) {
        val song = nowPlayingState.value.song ?: return
        viewModelScope.launch {
            val result = playlistRepository.addSongToPlaylist(song.id, playlistId)
            onResult(result)
        }
    }

    fun createPlaylistAndAdd(name: String, onResult: (PlaylistOperationResult) -> Unit = {}) {
        val song = nowPlayingState.value.song ?: return
        viewModelScope.launch {
            val result = playlistRepository.createPlaylist(name)
            if (result is PlaylistOperationResult.Success) {
                playlistRepository.addSongToPlaylist(song.id, result.playlistId)
            }
            onResult(result)
        }
    }
}
