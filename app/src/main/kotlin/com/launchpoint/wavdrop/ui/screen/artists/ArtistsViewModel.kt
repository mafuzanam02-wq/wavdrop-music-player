package com.launchpoint.wavdrop.ui.screen.artists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.launchpoint.wavdrop.data.grouping.ArtistGrouper
import com.launchpoint.wavdrop.data.model.ArtistSummary
import com.launchpoint.wavdrop.data.model.Song
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

sealed interface ArtistsUiState {
    data object Loading : ArtistsUiState
    data object Empty   : ArtistsUiState
    data class  Ready(val artists: List<ArtistSummary>) : ArtistsUiState
}

private data class SongsWithArtists(
    val songs: List<Song>,
    val artists: List<ArtistSummary>,
)

@HiltViewModel
class ArtistsViewModel @Inject constructor(
    songRepository: SongRepository,
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    fun setSearchQuery(query: String) { _searchQuery.value = query }

    // Grouping runs only when the song list changes, not on every search keystroke.
    // Songs are kept alongside artists so filterArtists can do title/album deep search.
    private val allGrouped: StateFlow<SongsWithArtists?> = songRepository.songs
        .map { songs -> SongsWithArtists(songs, ArtistGrouper.group(songs)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val uiState: StateFlow<ArtistsUiState> = combine(allGrouped, _searchQuery) { grouped, query ->
        when {
            grouped == null           -> ArtistsUiState.Loading
            grouped.artists.isEmpty() -> ArtistsUiState.Empty
            else                      -> ArtistsUiState.Ready(
                LibrarySearch.filterArtists(
                    artists = grouped.artists,
                    songs   = grouped.songs,
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
