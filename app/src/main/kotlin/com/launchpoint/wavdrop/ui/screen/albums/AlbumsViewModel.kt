package com.launchpoint.wavdrop.ui.screen.albums

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.launchpoint.wavdrop.data.grouping.AlbumGrouper
import com.launchpoint.wavdrop.data.model.AlbumSummary
import com.launchpoint.wavdrop.data.repository.SongRepository
import com.launchpoint.wavdrop.data.search.LibrarySearch
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

sealed interface AlbumsUiState {
    data object Loading : AlbumsUiState
    data object Empty   : AlbumsUiState
    data class  Ready(val albums: List<AlbumSummary>) : AlbumsUiState
}

@HiltViewModel
class AlbumsViewModel @Inject constructor(
    songRepository: SongRepository,
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    fun setSearchQuery(query: String) { _searchQuery.value = query }

    // Grouping runs only when the song list changes, not on every search keystroke.
    private val allAlbums: StateFlow<List<AlbumSummary>?> = songRepository.songs
        .map { songs -> AlbumGrouper.group(songs) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val uiState: StateFlow<AlbumsUiState> = combine(allAlbums, _searchQuery) { albums, query ->
        when {
            albums == null   -> AlbumsUiState.Loading
            albums.isEmpty() -> AlbumsUiState.Empty
            else             -> AlbumsUiState.Ready(LibrarySearch.filterAlbums(albums, query))
        }
    }.stateIn(
        scope        = viewModelScope,
        started      = SharingStarted.WhileSubscribed(5_000),
        initialValue = AlbumsUiState.Loading,
    )
}
