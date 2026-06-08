package com.launchpoint.wavdrop.ui.screen.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.launchpoint.wavdrop.data.model.PlaylistSummary
import com.launchpoint.wavdrop.data.model.SmartCollection
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.model.WrappedSummary
import com.launchpoint.wavdrop.data.repository.PlaylistRepository
import com.launchpoint.wavdrop.data.repository.SmartCollectionRepository
import com.launchpoint.wavdrop.data.repository.SongRepository
import com.launchpoint.wavdrop.data.repository.StatsRepository
import com.launchpoint.wavdrop.data.search.LibrarySearch
import com.launchpoint.wavdrop.data.settings.AppIconChoice
import com.launchpoint.wavdrop.data.settings.AppSettingsRepository
import com.launchpoint.wavdrop.data.settings.HomeLayoutSettings
import com.launchpoint.wavdrop.data.settings.HomeLayoutSettingsRepository
import com.launchpoint.wavdrop.data.stats.WrappedBuilder
import com.launchpoint.wavdrop.playback.NowPlayingState
import com.launchpoint.wavdrop.playback.PlayerController
import com.launchpoint.wavdrop.playback.SleepTimerState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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
    val wrapped: WrappedSummary? = null,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: SongRepository,
    private val playerController: PlayerController,
    private val statsRepository: StatsRepository,
    private val playlistRepository: PlaylistRepository,
    private val smartCollectionRepository: SmartCollectionRepository,
    private val homeLayoutRepository: HomeLayoutSettingsRepository,
    appSettingsRepository: AppSettingsRepository,
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    fun setSearchQuery(query: String) { _searchQuery.value = query }

    private val allSongs: StateFlow<List<Song>?> = repository.songs
        .map<List<Song>, List<Song>?> { it }
        .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = null,
    )

    val librarySongs: StateFlow<List<Song>> = allSongs
        .map { it.orEmpty() }
        .stateIn(
            scope        = viewModelScope,
            started      = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    val uiState: StateFlow<HomeUiState> = combine(allSongs, _searchQuery) { songs, query ->
        when {
            songs == null -> HomeUiState.Loading
            songs.isEmpty() -> HomeUiState.Empty
            else            -> HomeUiState.Songs(LibrarySearch.filterSongs(songs, query))
        }
    }.stateIn(
        scope        = viewModelScope,
        started      = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeUiState.Loading,
    )

    // Wrapped preview only depends on songs + events; isolated so that playlist/stats
    // changes don't trigger a full WrappedBuilder run on every play or skip.
    private val wrappedPreview: StateFlow<WrappedSummary?> = combine(
        allSongs,
        statsRepository.allListenEvents(),
    ) { songs, events ->
        val loadedSongs = songs.orEmpty()
        WrappedBuilder.availableYears(events)
            .firstOrNull()
            ?.let { year -> WrappedBuilder.buildYear(year = year, songs = loadedSongs, events = events) }
            ?.takeIf { it.hasActivity && !it.emptyState.isEmpty }
    }.stateIn(
        scope        = viewModelScope,
        started      = SharingStarted.WhileSubscribed(5_000),
        initialValue = null,
    )

    val dashboardState: StateFlow<HomeDashboardUiState> = combine(
        allSongs,
        statsRepository.allTrackStatsEntities(),
        playlistRepository.observePlaylists(),
        smartCollectionRepository.observeSmartCollections(),
        wrappedPreview,
    ) { songs, stats, playlists, smartCollections, latestWrapped ->
        val loadedSongs = songs.orEmpty()
        val songsById = loadedSongs.associateBy { it.id }
        HomeDashboardUiState(
            totalSongs = loadedSongs.size,
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
            wrapped = latestWrapped,
        )
    }.stateIn(
        scope   = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeDashboardUiState(),
    )

    val homeLayout: StateFlow<HomeLayoutSettings> = homeLayoutRepository.settings.stateIn(
        scope        = viewModelScope,
        started      = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeLayoutSettings(),
    )

    val nowPlayingState: StateFlow<NowPlayingState> = playerController.nowPlayingState

    val sleepTimerState: StateFlow<SleepTimerState> = playerController.sleepTimerState

    val appIconChoice: StateFlow<AppIconChoice> = appSettingsRepository.appIconChoice.stateIn(
        scope        = viewModelScope,
        started      = SharingStarted.WhileSubscribed(5_000),
        initialValue = AppIconChoice.MIDNIGHT_VIOLET,
    )

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

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    /** Explicit rescan of MediaStore / music folders, triggered by pull-to-refresh. */
    fun refreshLibrary() {
        if (_isRefreshing.value) return
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                repository.sync()
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun playSong(song: Song) {
        val queue = (uiState.value as? HomeUiState.Songs)?.songs.orEmpty()
        playerController.playFromQueue(queue = queue, startSong = song)
    }

    fun playSongFromLibraryQueue(song: Song) {
        val queue = allSongs.value.orEmpty()
        playerController.playFromQueue(queue = queue.ifEmpty { listOf(song) }, startSong = song)
    }

    fun playNext(song: Song) {
        playerController.playNext(song)
    }

    fun addToQueue(song: Song) {
        playerController.addToQueue(song)
    }

    fun shuffleAll() {
        val songs = (uiState.value as? HomeUiState.Songs)?.songs.orEmpty()
        if (songs.isEmpty()) return
        playerController.playFromQueueShuffled(queue = songs)
    }

    fun toggleFavorite(songId: Long) {
        val song = allSongs.value.orEmpty().firstOrNull { it.id == songId } ?: return
        viewModelScope.launch { statsRepository.toggleFavorite(songId, song.uri) }
    }

    fun togglePlayPause() = playerController.togglePlayPause()

    fun skipToNext() = playerController.skipToNext()

    fun skipToPrevious() = playerController.skipToPrevious()

    fun toggleShuffle() = playerController.toggleShuffle()

    fun cycleRepeatMode() = playerController.cycleRepeatMode()
}
