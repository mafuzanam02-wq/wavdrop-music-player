package com.launchpoint.wavdrop.ui.screen.trackdetails

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.launchpoint.wavdrop.data.model.PlaylistSummary
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.model.TrackStats
import com.launchpoint.wavdrop.data.repository.PlaylistRepository
import com.launchpoint.wavdrop.data.repository.SongRepository
import com.launchpoint.wavdrop.data.repository.StatsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.launchpoint.wavdrop.data.repository.PlaylistOperationResult
import javax.inject.Inject

sealed interface TrackDetailsUiState {
    data object Loading  : TrackDetailsUiState
    data object NotFound : TrackDetailsUiState
    data class  Ready(
        val song: Song,
        val stats: TrackStats?,
        val isFavorite: Boolean,
    ) : TrackDetailsUiState
}

@HiltViewModel
class TrackDetailsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val songRepository: SongRepository,
    private val statsRepository: StatsRepository,
    private val playlistRepository: PlaylistRepository,
) : ViewModel() {

    private val songId: Long = checkNotNull(savedStateHandle["songId"])

    val uiState: StateFlow<TrackDetailsUiState> = combine(
        songRepository.observeSongById(songId),
        statsRepository.observeStats(songId),
        statsRepository.favoriteSongIds(),
    ) { song, stats, favorites ->
        if (song == null) TrackDetailsUiState.NotFound
        else TrackDetailsUiState.Ready(
            song       = song,
            stats      = stats,
            isFavorite = songId in favorites,
        )
    }.stateIn(
        scope        = viewModelScope,
        started      = SharingStarted.WhileSubscribed(5_000),
        initialValue = TrackDetailsUiState.Loading,
    )

    val playlists: StateFlow<List<PlaylistSummary>> = playlistRepository.observePlaylists()
        .stateIn(
            scope        = viewModelScope,
            started      = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    fun toggleFavorite() {
        val state = uiState.value as? TrackDetailsUiState.Ready ?: return
        viewModelScope.launch {
            statsRepository.toggleFavorite(state.song.id, state.song.uri)
        }
    }

    fun addToPlaylist(playlistId: Long) {
        val song = (uiState.value as? TrackDetailsUiState.Ready)?.song ?: return
        viewModelScope.launch { playlistRepository.addSongToPlaylist(song.id, playlistId) }
    }

    fun createPlaylistAndAdd(name: String, onResult: (PlaylistOperationResult) -> Unit = {}) {
        val song = (uiState.value as? TrackDetailsUiState.Ready)?.song ?: return
        viewModelScope.launch {
            val result = playlistRepository.createPlaylist(name)
            if (result is PlaylistOperationResult.Success) {
                playlistRepository.addSongToPlaylist(song.id, result.playlistId)
            }
            onResult(result)
        }
    }
}
