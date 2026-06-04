package com.launchpoint.wavdrop.ui.screen.playlists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.launchpoint.wavdrop.data.model.PlaylistSummary
import com.launchpoint.wavdrop.data.playlists.PlaylistArtworkBuilder
import com.launchpoint.wavdrop.data.repository.PlaylistOperationResult
import com.launchpoint.wavdrop.data.repository.PlaylistRepository
import com.launchpoint.wavdrop.data.repository.SongRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PlaylistListItem(
    val playlist: PlaylistSummary,
    val artworkUris: List<String>,
)

data class PlaylistsUiState(
    val isLoading: Boolean = false,
    val playlists: List<PlaylistListItem> = emptyList(),
)

@HiltViewModel
class PlaylistsViewModel @Inject constructor(
    private val playlistRepository: PlaylistRepository,
    private val songRepository: SongRepository,
) : ViewModel() {

    val uiState: StateFlow<PlaylistsUiState> = combine(
        playlistRepository.observePlaylists(),
        playlistRepository.observeAllPlaylistSongs(),
        songRepository.songs,
    ) { playlists, playlistSongs, songs ->
        val songsById = songs.associateBy { it.id }
        val playlistSongsById = playlistSongs.groupBy { it.playlistId }
        val items = playlists.map { playlist ->
            val orderedSongs = playlistSongsById[playlist.id].orEmpty()
                .sortedBy { it.position }
                .mapNotNull { playlistSong -> songsById[playlistSong.songId] }
            PlaylistListItem(
                playlist    = playlist,
                artworkUris = PlaylistArtworkBuilder.buildArtworkUris(orderedSongs),
            )
        }
        PlaylistsUiState(playlists = items)
    }
        .stateIn(
            scope        = viewModelScope,
            started      = SharingStarted.WhileSubscribed(5_000),
            initialValue = PlaylistsUiState(isLoading = true),
        )

    fun createPlaylist(name: String, onResult: (PlaylistOperationResult) -> Unit = {}) {
        viewModelScope.launch { onResult(playlistRepository.createPlaylist(name)) }
    }

    fun renamePlaylist(id: Long, name: String, onResult: (PlaylistOperationResult) -> Unit = {}) {
        viewModelScope.launch { onResult(playlistRepository.renamePlaylist(id, name)) }
    }

    fun deletePlaylist(id: Long) {
        viewModelScope.launch { playlistRepository.deletePlaylist(id) }
    }
}
