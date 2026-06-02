package com.launchpoint.wavdrop.ui.screen.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.launchpoint.wavdrop.data.model.PlaylistSummary
import com.launchpoint.wavdrop.data.model.SmartCollection
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.repository.PlaylistRepository
import com.launchpoint.wavdrop.data.repository.SmartCollectionRepository
import com.launchpoint.wavdrop.data.repository.SongRepository
import com.launchpoint.wavdrop.data.repository.StatsRepository
import com.launchpoint.wavdrop.data.search.LibrarySearch
import com.launchpoint.wavdrop.data.settings.HomeLayoutSettings
import com.launchpoint.wavdrop.data.settings.HomeLayoutSettingsRepository
import com.launchpoint.wavdrop.playback.NowPlayingState
import com.launchpoint.wavdrop.playback.PlayerController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val DASHBOARD_SONG_PREVIEW_LIMIT = 4
private const val DASHBOARD_COLLECTION_PREVIEW_LIMIT = 3

sealed interface HomeUiState {
    data object Loading : HomeUiState
    data object Empty   : HomeUiState
    data class  Songs(val songs: List<Song>) : HomeUiState
}

data class HomeDashboardUiState(
    val totalSongs: Int = 0,
    val recentlyPlayed: List<Song> = emptyList(),
    val favorites: List<Song> = emptyList(),
    val mostPlayed: List<Song> = emptyList(),
    val playlists: List<PlaylistSummary> = emptyList(),
    val smartCollections: List<SmartCollection> = emptyList(),
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: SongRepository,
    private val playerController: PlayerController,
    private val statsRepository: StatsRepository,
    private val playlistRepository: PlaylistRepository,
    private val smartCollectionRepository: SmartCollectionRepository,
    private val homeLayoutRepository: HomeLayoutSettingsRepository,
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    fun setSearchQuery(query: String) { _searchQuery.value = query }

    val uiState: StateFlow<HomeUiState> = combine(repository.songs, _searchQuery) { songs, query ->
        when {
            songs.isEmpty() -> HomeUiState.Empty
            else            -> HomeUiState.Songs(LibrarySearch.filterSongs(songs, query))
        }
    }.stateIn(
        scope        = viewModelScope,
        started      = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeUiState.Loading,
    )

    val dashboardState: StateFlow<HomeDashboardUiState> = combine(
        repository.songs,
        statsRepository.allTrackStatsEntities(),
        playlistRepository.observePlaylists(),
        smartCollectionRepository.observeSmartCollections(),
    ) { songs, stats, playlists, smartCollections ->
        val songsById = songs.associateBy { it.id }
        HomeDashboardUiState(
            totalSongs = songs.size,
            recentlyPlayed = stats
                .filter { it.lastPlayedAt > 0 }
                .sortedByDescending { it.lastPlayedAt }
                .mapNotNull { songsById[it.songId] }
                .take(DASHBOARD_SONG_PREVIEW_LIMIT),
            favorites = stats
                .filter { it.isFavorite }
                .mapNotNull { songsById[it.songId] }
                .take(DASHBOARD_SONG_PREVIEW_LIMIT),
            mostPlayed = stats
                .filter { it.playCount > 0 }
                .sortedByDescending { it.playCount }
                .mapNotNull { songsById[it.songId] }
                .take(DASHBOARD_SONG_PREVIEW_LIMIT),
            playlists = playlists.take(DASHBOARD_COLLECTION_PREVIEW_LIMIT),
            smartCollections = smartCollections
                .filter { it.songCount > 0 }
                .take(DASHBOARD_COLLECTION_PREVIEW_LIMIT),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeDashboardUiState(),
    )

    val homeLayout: StateFlow<HomeLayoutSettings> = homeLayoutRepository.settings.stateIn(
        scope        = viewModelScope,
        started      = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeLayoutSettings(),
    )

    val nowPlayingState: StateFlow<NowPlayingState> = playerController.nowPlayingState

    val statsMap: StateFlow<Map<Long, Int>> = statsRepository.allPlayCounts()
        .stateIn(
            scope        = viewModelScope,
            started      = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyMap(),
        )

    val favoriteSongIds: StateFlow<Set<Long>> = statsRepository.favoriteSongIds()
        .stateIn(
            scope        = viewModelScope,
            started      = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptySet(),
        )

    init {
        viewModelScope.launch {
            val songs = repository.songs.first()
            playerController.restoreSessionIfNeeded(songs)
        }
    }

    private var hasSynced = false

    fun syncIfNeeded() {
        if (hasSynced) return
        hasSynced = true
        viewModelScope.launch { repository.sync() }
    }

    fun playSong(song: Song) {
        val queue = (uiState.value as? HomeUiState.Songs)?.songs.orEmpty()
        playerController.playFromQueue(queue = queue, startSong = song)
    }

    fun shuffleAll() {
        val songs = (uiState.value as? HomeUiState.Songs)?.songs.orEmpty()
        if (songs.isEmpty()) return
        val shuffled = songs.shuffled()
        playerController.playFromQueue(queue = shuffled, startSong = shuffled.first())
    }

    fun toggleFavorite(songId: Long) {
        val song = (uiState.value as? HomeUiState.Songs)?.songs?.firstOrNull { it.id == songId } ?: return
        viewModelScope.launch { statsRepository.toggleFavorite(songId, song.uri) }
    }

    fun togglePlayPause() = playerController.togglePlayPause()

    fun skipToNext() = playerController.skipToNext()

    fun skipToPrevious() = playerController.skipToPrevious()

    fun toggleShuffle() = playerController.toggleShuffle()

    fun cycleRepeatMode() = playerController.cycleRepeatMode()
}
