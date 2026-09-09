package com.launchpoint.wavdrop.ui.screen.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.launchpoint.wavdrop.data.model.PlaylistSummary
import com.launchpoint.wavdrop.data.model.SmartCollection
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.repository.PlaylistRepository
import com.launchpoint.wavdrop.data.repository.SmartCollectionRepository
import com.launchpoint.wavdrop.data.repository.SongRepository
import com.launchpoint.wavdrop.data.repository.StatsRepository
import com.launchpoint.wavdrop.data.search.LibrarySearchIndex
import com.launchpoint.wavdrop.data.settings.AppSettingsRepository
import com.launchpoint.wavdrop.data.settings.SearchTapBehavior
import com.launchpoint.wavdrop.playback.NowPlayingState
import com.launchpoint.wavdrop.playback.PlayerController
import com.launchpoint.wavdrop.ui.components.GroupedSearchResults
import com.launchpoint.wavdrop.ui.components.groupedSearchResults
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class GlobalSearchViewModel @Inject constructor(
    private val songRepository: SongRepository,
    private val playerController: PlayerController,
    private val statsRepository: StatsRepository,
    private val appSettingsRepository: AppSettingsRepository,
    private val smartCollectionRepository: SmartCollectionRepository,
    private val playlistRepository: PlaylistRepository,
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    fun setSearchQuery(query: String) { _searchQuery.value = query }

    val allSongs: StateFlow<List<Song>> = songRepository.songs
        .stateIn(
            scope        = viewModelScope,
            started      = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    val smartCollections: StateFlow<List<SmartCollection>> =
        smartCollectionRepository.observeSmartCollections()
            .stateIn(
                scope        = viewModelScope,
                started      = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyList(),
            )

    val playlists: StateFlow<List<PlaylistSummary>> = playlistRepository.observePlaylists()
        .stateIn(
            scope        = viewModelScope,
            started      = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    // Phase 9: the normalized search projection is rebuilt only when the library, playlists, or
    // smart collections actually change — NOT on every keystroke. mapLatest cancels a stale rebuild
    // if the library changes again mid-build (e.g. during a rescan).
    private val searchIndex =
        combine(
            songRepository.songs,
            smartCollectionRepository.observeSmartCollections(),
            playlistRepository.observePlaylists(),
        ) { songs, collections, lists -> Triple(songs, lists, collections) }
            .mapLatest { (songs, lists, collections) ->
                LibrarySearchIndex.from(songs = songs, playlists = lists, smartCollections = collections)
            }
            .flowOn(Dispatchers.Default)

    // Per-keystroke work is now only cheap substring matching against the precomputed index.
    // mapLatest supersedes an in-flight search when a newer query (or index) arrives, so stale
    // filter jobs do not stack. Debounce (200 ms) and result semantics/ordering are unchanged.
    val filteredResults: StateFlow<GroupedSearchResults> =
        combine(searchIndex, _searchQuery.debounce(200)) { index, query -> index to query }
            .mapLatest { (index, query) -> groupedSearchResults(index = index, query = query) }
            .flowOn(Dispatchers.Default)
            .stateIn(
                scope        = viewModelScope,
                started      = SharingStarted.WhileSubscribed(5_000),
                initialValue = GroupedSearchResults(
                    songs  = emptyList(), artists = emptyList(), albums  = emptyList(),
                    playlists = emptyList(), smartCollections = emptyList(), folders = emptyList(),
                ),
            )

    val nowPlayingState: StateFlow<NowPlayingState> = playerController.nowPlayingState

    val favoriteSongIds: StateFlow<Set<Long>> = statsRepository.favoriteSongIds()
        .stateIn(
            scope        = viewModelScope,
            started      = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptySet(),
        )

    fun playSearchResult(song: Song) {
        viewModelScope.launch {
            when (appSettingsRepository.searchTapBehavior.first()) {
                SearchTapBehavior.REPLACE_QUEUE -> {
                    val queue = allSongs.value.ifEmpty { listOf(song) }
                    playerController.playFromQueue(queue = queue, startSong = song)
                }
                SearchTapBehavior.PRESERVE_QUEUE ->
                    playerController.playSearchResultPreservingQueue(song)
            }
        }
    }

    fun playNext(song: Song) = playerController.playNext(song)

    fun addToQueue(song: Song) = playerController.addToQueue(song)

    fun toggleFavorite(songId: Long) {
        val song = allSongs.value.firstOrNull { it.id == songId } ?: return
        viewModelScope.launch { statsRepository.toggleFavorite(songId, song.uri) }
    }
}
