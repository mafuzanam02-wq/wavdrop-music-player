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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

enum class FolderSortMode { NAME, MOST_SONGS, LONGEST }

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

    private val _sortMode = MutableStateFlow(FolderSortMode.NAME)
    val sortMode: StateFlow<FolderSortMode> = _sortMode.asStateFlow()

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setSortMode(mode: FolderSortMode) {
        _sortMode.value = mode
    }

    // Grouping runs only when the song list changes, not on every search keystroke.
    private val allFolders: StateFlow<List<FolderSummary>?> = songRepository.songs
        .map { songs -> FolderGrouper.groupSongsByFolder(songs) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val uiState: StateFlow<FoldersUiState> = combine(allFolders, _searchQuery, _sortMode) { folders, query, sort ->
        when {
            folders == null   -> FoldersUiState.Loading
            folders.isEmpty() -> FoldersUiState.Empty
            else              -> {
                val filtered = LibrarySearch.filterFolders(folders, query)
                val sorted = when (sort) {
                    FolderSortMode.NAME       -> filtered.sortedBy { it.displayName.lowercase() }
                    FolderSortMode.MOST_SONGS -> filtered.sortedByDescending { it.songCount }
                    FolderSortMode.LONGEST    -> filtered.sortedByDescending { it.totalDurationMs }
                }
                FoldersUiState.Ready(sorted)
            }
        }
    }.stateIn(
        scope        = viewModelScope,
        started      = SharingStarted.WhileSubscribed(5_000),
        initialValue = FoldersUiState.Loading,
    )
}
