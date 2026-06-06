package com.launchpoint.wavdrop.ui.screen.playlists

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.launchpoint.wavdrop.data.model.PlaylistSong
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.repository.AddToPlaylistResult
import com.launchpoint.wavdrop.data.repository.PlaylistRepository
import com.launchpoint.wavdrop.data.repository.SongRepository
import com.launchpoint.wavdrop.data.search.LibrarySearch
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AddSongsToPlaylistUiState(
    val query: String = "",
    val songs: List<Song> = emptyList(),
    val selectedSongIds: List<Long> = emptyList(),
    val existingPlaylistSongIds: Set<Long> = emptySet(),
    val isAdding: Boolean = false,
) {
    val selectedCount: Int get() = selectedSongIds.size
}

@HiltViewModel
class AddSongsToPlaylistViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val songRepository: SongRepository,
    private val playlistRepository: PlaylistRepository,
) : ViewModel() {

    private val playlistId: Long = checkNotNull(savedStateHandle["playlistId"])
    private val query = MutableStateFlow("")
    private val selectedSongIds = MutableStateFlow<List<Long>>(emptyList())
    private val isAdding = MutableStateFlow(false)

    val uiState: StateFlow<AddSongsToPlaylistUiState> = combine(
        songRepository.songs,
        query,
        selectedSongIds,
        playlistRepository.observePlaylistSongs(playlistId).map { songs ->
            songs.mapTo(mutableSetOf()) { it.songId }
        },
        isAdding,
    ) { songs, searchQuery, selected, existingIds, adding ->
        AddSongsToPlaylistUiState(
            query                   = searchQuery,
            songs                   = LibrarySearch.filterSongs(songs, searchQuery),
            selectedSongIds         = selected,
            existingPlaylistSongIds = existingIds,
            isAdding                = adding,
        )
    }.stateIn(
        scope        = viewModelScope,
        started      = SharingStarted.WhileSubscribed(5_000),
        initialValue = AddSongsToPlaylistUiState(),
    )

    fun updateQuery(value: String) {
        query.value = value
    }

    fun toggleSong(songId: Long) {
        selectedSongIds.value = if (songId in selectedSongIds.value) {
            selectedSongIds.value.filterNot { it == songId }
        } else {
            selectedSongIds.value + songId
        }
    }

    fun addSelected(onComplete: (AddToPlaylistResult) -> Unit) {
        val selected = selectedSongIds.value
        if (selected.isEmpty() || isAdding.value) return

        viewModelScope.launch {
            isAdding.value = true
            runCatching {
                playlistRepository.addSongsToPlaylist(playlistId = playlistId, songIds = selected)
            }.onSuccess { result ->
                onComplete(result)
            }
            isAdding.value = false
        }
    }
}
