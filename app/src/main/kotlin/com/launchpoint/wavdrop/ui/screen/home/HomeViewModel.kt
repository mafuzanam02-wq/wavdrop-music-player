package com.launchpoint.wavdrop.ui.screen.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.repository.SongRepository
import com.launchpoint.wavdrop.playback.NowPlayingState
import com.launchpoint.wavdrop.playback.PlayerController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface HomeUiState {
    data object Loading : HomeUiState
    data object Empty   : HomeUiState
    data class  Songs(val songs: List<Song>) : HomeUiState
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: SongRepository,
    private val playerController: PlayerController,
) : ViewModel() {

    val uiState: StateFlow<HomeUiState> = repository.songs
        .map { list -> if (list.isEmpty()) HomeUiState.Empty else HomeUiState.Songs(list) }
        .stateIn(
            scope        = viewModelScope,
            started      = SharingStarted.WhileSubscribed(5_000),
            initialValue = HomeUiState.Loading,
        )

    val nowPlayingState: StateFlow<NowPlayingState> = playerController.nowPlayingState

    private var hasSynced = false

    fun syncIfNeeded() {
        if (hasSynced) return
        hasSynced = true
        viewModelScope.launch { repository.sync() }
    }

    fun playSong(song: Song) = playerController.playSong(song)

    fun togglePlayPause() = playerController.togglePlayPause()
}
