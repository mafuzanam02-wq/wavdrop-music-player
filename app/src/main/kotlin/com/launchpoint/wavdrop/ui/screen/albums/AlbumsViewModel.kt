package com.launchpoint.wavdrop.ui.screen.albums

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.launchpoint.wavdrop.data.grouping.AlbumGrouper
import com.launchpoint.wavdrop.data.model.AlbumSummary
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

enum class AlbumSortMode(val label: String) {
    NAME_ASC("Name A–Z"),
    NAME_DESC("Name Z–A"),
    ARTIST_ASC("Artist A–Z"),
    MOST_SONGS("Most songs"),
    LONGEST_DURATION("Longest duration");

    val usesAlphabetIndex: Boolean
        get() = this == NAME_ASC || this == NAME_DESC

    companion object {
        val DEFAULT: AlbumSortMode = NAME_ASC
    }
}

sealed interface AlbumsUiState {
    data object Loading : AlbumsUiState
    data object Empty   : AlbumsUiState
    data class  Ready(val albums: List<AlbumSummary>) : AlbumsUiState
}

internal fun sortAlbums(
    albums: List<AlbumSummary>,
    mode: AlbumSortMode = AlbumSortMode.DEFAULT,
): List<AlbumSummary> {
    val nameAscending = compareBy<AlbumSummary>(
        { MusicTextNormalizer.normalizeStrict(it.albumKey) },
        { MusicTextNormalizer.normalizeStrict(it.artist) },
        { it.albumKey },
        { it.albumId },
    )
    val comparator = when (mode) {
        AlbumSortMode.NAME_ASC -> nameAscending
        AlbumSortMode.NAME_DESC -> compareByDescending<AlbumSummary> {
            MusicTextNormalizer.normalizeStrict(it.albumKey)
        }.thenBy {
            MusicTextNormalizer.normalizeStrict(it.artist)
        }.thenBy {
            it.albumKey
        }.thenBy {
            it.albumId
        }
        AlbumSortMode.ARTIST_ASC -> compareBy<AlbumSummary>(
            { MusicTextNormalizer.normalizeStrict(it.artist) },
            { MusicTextNormalizer.normalizeStrict(it.albumKey) },
            { it.albumKey },
            { it.albumId },
        )
        AlbumSortMode.MOST_SONGS -> compareByDescending<AlbumSummary> {
            it.songCount
        }.then(nameAscending)
        AlbumSortMode.LONGEST_DURATION -> compareByDescending<AlbumSummary> {
            it.totalDurationMs
        }.then(nameAscending)
    }
    return albums.sortedWith(comparator)
}

internal fun prepareAlbums(
    albums: List<AlbumSummary>,
    query: String,
    sortMode: AlbumSortMode,
): List<AlbumSummary> =
    sortAlbums(LibrarySearch.filterAlbums(albums, query), sortMode)

@HiltViewModel
class AlbumsViewModel @Inject constructor(
    songRepository: SongRepository,
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _sortMode = MutableStateFlow(AlbumSortMode.DEFAULT)
    val sortMode: StateFlow<AlbumSortMode> = _sortMode.asStateFlow()

    fun setSearchQuery(query: String) { _searchQuery.value = query }

    fun setSortMode(mode: AlbumSortMode) { _sortMode.value = mode }

    // Grouping runs only when the song list changes, not on every search keystroke.
    private val allAlbums: StateFlow<List<AlbumSummary>?> = songRepository.songs
        .map { songs -> AlbumGrouper.group(songs) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val uiState: StateFlow<AlbumsUiState> = combine(
        allAlbums,
        _searchQuery,
        _sortMode,
    ) { albums, query, sort ->
        when {
            albums == null   -> AlbumsUiState.Loading
            albums.isEmpty() -> AlbumsUiState.Empty
            else             -> AlbumsUiState.Ready(prepareAlbums(albums, query, sort))
        }
    }.stateIn(
        scope        = viewModelScope,
        started      = SharingStarted.WhileSubscribed(5_000),
        initialValue = AlbumsUiState.Loading,
    )
}
