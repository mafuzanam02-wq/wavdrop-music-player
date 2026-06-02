package com.launchpoint.wavdrop.ui.screen.folders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.launchpoint.wavdrop.data.library.FolderGrouper
import com.launchpoint.wavdrop.data.model.FolderSummary
import com.launchpoint.wavdrop.data.repository.SongRepository
import com.launchpoint.wavdrop.data.search.LibrarySearch
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

sealed interface FoldersUiState {
    data object Loading : FoldersUiState
    data object Empty : FoldersUiState
    data class Ready(val folders: List<FolderSummary>) : FoldersUiState
}

@HiltViewModel
class FoldersViewModel @Inject constructor(
    songRepository: SongRepository,
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    val uiState: StateFlow<FoldersUiState> = combine(songRepository.songs, _searchQuery) { songs, query ->
        when {
            songs.isEmpty() -> FoldersUiState.Empty
            else -> FoldersUiState.Ready(
                LibrarySearch.filterFolders(
                    folders = FolderGrouper.groupSongsByFolder(songs),
                    query = query,
                )
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = FoldersUiState.Loading,
    )
}
