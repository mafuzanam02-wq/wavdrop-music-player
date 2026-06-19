package com.launchpoint.wavdrop.ui.screen.artists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.launchpoint.wavdrop.data.grouping.ArtistGrouper
import com.launchpoint.wavdrop.data.model.ArtistSummary
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.repository.SongRepository
import com.launchpoint.wavdrop.data.search.LibrarySearch
import com.launchpoint.wavdrop.data.text.MusicTextNormalizer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

enum class ArtistSortMode(val label: String) {
    NAME_ASC("Name A–Z"),
    NAME_DESC("Name Z–A"),
    MOST_SONGS("Most songs"),
    MOST_ALBUMS("Most albums"),
    LONGEST_DURATION("Longest duration");

    val usesAlphabetIndex: Boolean
        get() = this == NAME_ASC || this == NAME_DESC

    companion object {
        val DEFAULT: ArtistSortMode = NAME_ASC
    }
}

sealed interface ArtistsUiState {
    data object Loading : ArtistsUiState
    data object Empty   : ArtistsUiState
    data class  Ready(val artists: List<ArtistSummary>) : ArtistsUiState
}

internal fun sortArtists(
    artists: List<ArtistSummary>,
    mode: ArtistSortMode = ArtistSortMode.DEFAULT,
): List<ArtistSummary> {
    val nameAscending = compareBy<ArtistSummary>(
        { MusicTextNormalizer.normalizeStrict(it.artistKey) },
        { it.artistKey },
    )
    val comparator = when (mode) {
        ArtistSortMode.NAME_ASC -> nameAscending
        ArtistSortMode.NAME_DESC -> compareByDescending<ArtistSummary> {
            MusicTextNormalizer.normalizeStrict(it.artistKey)
        }.thenBy {
            it.artistKey
        }
        ArtistSortMode.MOST_SONGS -> compareByDescending<ArtistSummary> {
            it.songCount
        }.then(nameAscending)
        ArtistSortMode.MOST_ALBUMS -> compareByDescending<ArtistSummary> {
            it.albumCount
        }.then(nameAscending)
        ArtistSortMode.LONGEST_DURATION -> compareByDescending<ArtistSummary> {
            it.totalDurationMs
        }.then(nameAscending)
    }
    return artists.sortedWith(comparator)
}

internal fun prepareArtists(
    artists: List<ArtistSummary>,
    songs: List<Song>,
    query: String,
    sortMode: ArtistSortMode,
): List<ArtistSummary> =
    sortArtists(
        LibrarySearch.filterArtists(
            artists = artists,
            songs = songs,
            query = query,
        ),
        sortMode,
    )

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

    private val _sortMode = MutableStateFlow(ArtistSortMode.DEFAULT)
    val sortMode: StateFlow<ArtistSortMode> = _sortMode.asStateFlow()

    fun setSearchQuery(query: String) { _searchQuery.value = query }

    fun setSortMode(mode: ArtistSortMode) { _sortMode.value = mode }

    // Grouping runs only when the song list changes, not on every search keystroke.
    // Songs are kept alongside artists so filterArtists can do title/album deep search.
    private val allGrouped: StateFlow<SongsWithArtists?> = songRepository.songs
        .map { songs -> SongsWithArtists(songs, ArtistGrouper.group(songs)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val uiState: StateFlow<ArtistsUiState> = combine(
        allGrouped,
        _searchQuery,
        _sortMode,
    ) { grouped, query, sort ->
        when {
            grouped == null           -> ArtistsUiState.Loading
            grouped.artists.isEmpty() -> ArtistsUiState.Empty
            else                      -> ArtistsUiState.Ready(
                prepareArtists(
                    artists = grouped.artists,
                    songs   = grouped.songs,
                    query   = query,
                    sortMode = sort,
                )
            )
        }
    }.stateIn(
        scope        = viewModelScope,
        started      = SharingStarted.WhileSubscribed(5_000),
        initialValue = ArtistsUiState.Loading,
    )
}
