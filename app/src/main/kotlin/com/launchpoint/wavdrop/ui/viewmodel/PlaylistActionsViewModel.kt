package com.launchpoint.wavdrop.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.launchpoint.wavdrop.data.model.PlaylistSummary
import com.launchpoint.wavdrop.data.repository.PlaylistOperationResult
import com.launchpoint.wavdrop.data.repository.PlaylistRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Lightweight VM injected alongside any screen that needs "Add to playlist" actions.
 * Exposes the list of playlists and two write operations.
 */
@HiltViewModel
class PlaylistActionsViewModel @Inject constructor(
    private val playlistRepository: PlaylistRepository,
) : ViewModel() {

    val playlists: StateFlow<List<PlaylistSummary>> = playlistRepository.observePlaylists()
        .stateIn(
            scope        = viewModelScope,
            started      = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    fun addSongToPlaylist(songId: Long, playlistId: Long) {
        viewModelScope.launch {
            playlistRepository.addSongToPlaylist(songId = songId, playlistId = playlistId)
        }
    }

    fun createPlaylistAndAddSong(name: String, songId: Long) {
        viewModelScope.launch {
            val result = playlistRepository.createPlaylist(name)
            if (result is PlaylistOperationResult.Success) {
                playlistRepository.addSongToPlaylist(
                    songId     = songId,
                    playlistId = result.playlistId,
                )
            }
        }
    }
}
