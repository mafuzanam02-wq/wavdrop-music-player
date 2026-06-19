package com.launchpoint.wavdrop.ui.screen.playlists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.launchpoint.wavdrop.data.model.PlaylistSummary
import com.launchpoint.wavdrop.data.playlists.PlaylistArtworkBuilder
import com.launchpoint.wavdrop.data.repository.PlaylistOperationResult
import com.launchpoint.wavdrop.data.repository.PlaylistRepository
import com.launchpoint.wavdrop.data.repository.SongRepository
import com.launchpoint.wavdrop.data.text.MusicTextNormalizer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class PlaylistSortMode(val label: String) {
    NAME_ASC("Name A–Z"),
    NAME_DESC("Name Z–A"),
    RECENTLY_EDITED("Recently edited"),
    RECENTLY_CREATED("Recently created"),
    TRACK_COUNT("Track count");

    companion object {
        val DEFAULT: PlaylistSortMode = NAME_ASC
    }
}

data class PlaylistListItem(
    val playlist: PlaylistSummary,
    val artworkUris: List<String>,
)

data class PlaylistsUiState(
    val isLoading: Boolean = false,
    val playlists: List<PlaylistListItem> = emptyList(),
    val totalPlaylistCount: Int = 0,
    val searchQuery: String = "",
    val sortMode: PlaylistSortMode = PlaylistSortMode.DEFAULT,
)

internal fun filterPlaylistItems(
    playlists: List<PlaylistListItem>,
    query: String,
): List<PlaylistListItem> {
    val normalizedQuery = MusicTextNormalizer.normalizeSearch(query)
    if (normalizedQuery.isEmpty()) return playlists
    return playlists.filter { item ->
        MusicTextNormalizer.normalizeSearch(item.playlist.name).contains(normalizedQuery)
    }
}

internal fun sortPlaylistItems(
    playlists: List<PlaylistListItem>,
    mode: PlaylistSortMode = PlaylistSortMode.DEFAULT,
): List<PlaylistListItem> {
    val nameAscending = compareBy<PlaylistListItem>(
        { MusicTextNormalizer.normalizeStrict(it.playlist.name) },
        { it.playlist.id },
    )
    val comparator = when (mode) {
        PlaylistSortMode.NAME_ASC -> nameAscending
        PlaylistSortMode.NAME_DESC -> compareByDescending<PlaylistListItem> {
            MusicTextNormalizer.normalizeStrict(it.playlist.name)
        }.thenBy { it.playlist.id }
        PlaylistSortMode.RECENTLY_EDITED -> compareByDescending<PlaylistListItem> {
            it.playlist.updatedAt
        }.then(nameAscending)
        PlaylistSortMode.RECENTLY_CREATED -> compareByDescending<PlaylistListItem> {
            it.playlist.createdAt
        }.then(nameAscending)
        PlaylistSortMode.TRACK_COUNT -> compareByDescending<PlaylistListItem> {
            it.playlist.songCount
        }.then(nameAscending)
    }
    return playlists.sortedWith(comparator)
}

internal fun preparePlaylistItems(
    playlists: List<PlaylistListItem>,
    query: String,
    sortMode: PlaylistSortMode,
): List<PlaylistListItem> =
    sortPlaylistItems(filterPlaylistItems(playlists, query), sortMode)

@HiltViewModel
class PlaylistsViewModel @Inject constructor(
    private val playlistRepository: PlaylistRepository,
    private val songRepository: SongRepository,
) : ViewModel() {

    private val searchQuery = MutableStateFlow("")
    private val sortMode = MutableStateFlow(PlaylistSortMode.DEFAULT)

    private val allPlaylistItems: StateFlow<List<PlaylistListItem>?> = combine(
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
        items
    }.map<List<PlaylistListItem>, List<PlaylistListItem>?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val uiState: StateFlow<PlaylistsUiState> = combine(
        allPlaylistItems,
        searchQuery,
        sortMode,
    ) { allItems, query, selectedSort ->
        if (allItems == null) {
            PlaylistsUiState(
                isLoading = true,
                searchQuery = query,
                sortMode = selectedSort,
            )
        } else {
            PlaylistsUiState(
                playlists = preparePlaylistItems(allItems, query, selectedSort),
                totalPlaylistCount = allItems.size,
                searchQuery = query,
                sortMode = selectedSort,
            )
        }
    }.stateIn(
        scope        = viewModelScope,
        started      = SharingStarted.WhileSubscribed(5_000),
        initialValue = PlaylistsUiState(isLoading = true),
    )

    fun setSearchQuery(query: String) {
        searchQuery.value = query
    }

    fun setSortMode(mode: PlaylistSortMode) {
        sortMode.value = mode
    }

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
