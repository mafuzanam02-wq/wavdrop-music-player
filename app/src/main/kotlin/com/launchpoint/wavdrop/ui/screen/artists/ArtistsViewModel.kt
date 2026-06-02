package com.launchpoint.wavdrop.ui.screen.artists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.launchpoint.wavdrop.data.grouping.ArtistGrouper
import com.launchpoint.wavdrop.data.model.ArtistSummary
import com.launchpoint.wavdrop.data.repository.SongRepository
import com.launchpoint.wavdrop.data.search.LibrarySearch
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

sealed interface ArtistsUiState {
    data object Loading : ArtistsUiState
    data object Empty   : ArtistsUiState
    data class  Ready(val artists: List<ArtistSummary>) : ArtistsUiState
}

@HiltViewModel
class ArtistsViewModel @Inject constructor(
    songRepository: SongRepository,
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    fun setSearchQuery(query: String) { _searchQuery.value = query }

    val uiState: StateFlow<ArtistsUiState> = combine(songRepository.songs, _searchQuery) { songs, query ->
        when {
            songs.isEmpty() -> ArtistsUiState.Empty
            else            -> ArtistsUiState.Ready(
                LibrarySearch.filterArtists(
                    artists = ArtistGrouper.group(songs),
                    songs   = songs,
                    query   = query,
                )
            )
        }
    }.stateIn(
        scope        = viewModelScope,
        started      = SharingStarted.WhileSubscribed(5_000),
        initialValue = ArtistsUiState.Loading,
    )
}
