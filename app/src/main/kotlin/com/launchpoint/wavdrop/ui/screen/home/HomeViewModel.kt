package com.launchpoint.wavdrop.ui.screen.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.util.Log
import com.launchpoint.wavdrop.data.model.PlaylistSummary
import com.launchpoint.wavdrop.data.model.SmartCollection
import com.launchpoint.wavdrop.data.model.SmartCollectionType
import com.launchpoint.wavdrop.data.local.entity.TrackListenEventEntity
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.model.WrappedSummary
import com.launchpoint.wavdrop.data.repository.PlaylistRepository
import com.launchpoint.wavdrop.data.repository.SmartCollectionRepository
import com.launchpoint.wavdrop.data.repository.SongRepository
import com.launchpoint.wavdrop.data.repository.StatsRepository
import com.launchpoint.wavdrop.data.repository.localDayRefreshFlow
import com.launchpoint.wavdrop.data.search.LibrarySearch
import com.launchpoint.wavdrop.data.search.SongSort
import com.launchpoint.wavdrop.data.settings.AppIconChoice
import com.launchpoint.wavdrop.data.settings.AppSettingsRepository
import com.launchpoint.wavdrop.data.settings.HomeLayoutSettings
import com.launchpoint.wavdrop.data.settings.HomeLayoutSettingsRepository
import com.launchpoint.wavdrop.data.settings.LibraryScanMode
import com.launchpoint.wavdrop.data.settings.LibraryScanSettings
import com.launchpoint.wavdrop.data.settings.LibraryScanSettingsRepository
import com.launchpoint.wavdrop.data.settings.SearchTapBehavior
import com.launchpoint.wavdrop.data.settings.SongSortMode
import com.launchpoint.wavdrop.data.stats.MostPlayedBuilder
import com.launchpoint.wavdrop.data.stats.WrappedBuilder
import com.launchpoint.wavdrop.playback.NowPlayingState
import com.launchpoint.wavdrop.playback.PlayerController
import com.launchpoint.wavdrop.playback.SleepTimerOption
import com.launchpoint.wavdrop.playback.SleepTimerState
import dagger.hilt.android.lifecycle.HiltViewModel
import com.launchpoint.wavdrop.ui.components.GroupedSearchResults
import com.launchpoint.wavdrop.ui.components.buildGroupedSearchResults
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject

private const val DASHBOARD_SONG_PREVIEW_LIMIT = 4
private const val DASHBOARD_COLLECTION_PREVIEW_LIMIT = 3

// Matches the debounce already used by songSearchResults so both Home search pipelines
// coalesce keystrokes consistently (WC-01).
private const val SEARCH_DEBOUNCE_MS = 200L

sealed interface HomeUiState {
    data object Loading : HomeUiState
    data object Empty   : HomeUiState
    data class  Songs(val songs: List<Song>) : HomeUiState
}

data class HomeDashboardUiState(
    val totalSongs: Int = 0,
    val recentlyPlayed: List<Song> = emptyList(),
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
    private val appSettingsRepository: AppSettingsRepository,
    private val libraryScanSettingsRepository: LibraryScanSettingsRepository,
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    fun setSearchQuery(query: String) { _searchQuery.value = query }

    fun setSongSortMode(mode: SongSortMode) {
        viewModelScope.launch { appSettingsRepository.setSongSortMode(mode) }
    }

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

    // WC-01: filtering runs off the Main thread with a short debounce, mirroring the
    // songSearchResults pipeline below. Debouncing only the query (not allSongs) keeps
    // Loading/Empty and library-change updates reactive — a new allSongs emission still
    // recombines immediately — while keystrokes coalesce and cancel obsolete filter work.
    // Search semantics (LibrarySearch.filterSongs, ordering, empty-state) are unchanged.
    val uiState: StateFlow<HomeUiState> = combine(
        allSongs,
        _searchQuery.debounce(SEARCH_DEBOUNCE_MS),
    ) { songs, query ->
        when {
            songs == null -> HomeUiState.Loading
            songs.isEmpty() -> HomeUiState.Empty
            else            -> HomeUiState.Songs(LibrarySearch.filterSongs(songs, query))
        }
    }
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope        = viewModelScope,
            started      = SharingStarted.WhileSubscribed(5_000),
            initialValue = HomeUiState.Loading,
        )

    val songSortMode: StateFlow<SongSortMode> = appSettingsRepository.songSortMode.stateIn(
        scope        = viewModelScope,
        started      = SharingStarted.WhileSubscribed(5_000),
        initialValue = SongSortMode.DEFAULT,
    )

    val searchTapBehavior: StateFlow<SearchTapBehavior> = appSettingsRepository.searchTapBehavior.stateIn(
        scope        = viewModelScope,
        started      = SharingStarted.Eagerly,
        initialValue = SearchTapBehavior.DEFAULT,
    )

    // Month key (year*12 + month) that advances at each local month boundary. Driven by the
    // existing day-boundary ticker (localDayRefreshFlow) — a month rollover is always a day
    // rollover — and distinctUntilChanged so it only changes monthly, not daily (WC-02 follow-up).
    private val currentMonthKey: Flow<Int> = localDayRefreshFlow()
        .map { nowMs ->
            val ym = YearMonth.from(Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault()))
            ym.year * 12 + ym.monthValue
        }
        .distinctUntilChanged()

    // WC-02: only MOST_PLAYED_THIS_MONTH needs raw listen events, and only those in the current
    // month. For every other sort mode this emits an empty list once and never re-subscribes, so
    // appending a PLAY/SKIP event no longer recomputes the Songs list. When the THIS_MONTH sort is
    // active we observe just the current-month window (observeInRange) instead of the whole table,
    // so only in-range inserts trigger a re-sort. Sort semantics are unchanged — MostPlayedBuilder
    // still derives the exact counts from these events.
    //
    // The (mode, monthKey) pair is distinctUntilChanged so the month window is recomputed — and the
    // DB query re-subscribed — when the month rolls over while the app stays open, without
    // re-subscribing on every day tick. Non-month modes still resolve to a single empty emission.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val songsSortEvents: Flow<List<TrackListenEventEntity>> =
        combine(songSortMode, currentMonthKey) { mode, monthKey -> mode to monthKey }
            .distinctUntilChanged()
            .flatMapLatest { (mode, _) ->
                if (mode == SongSortMode.MOST_PLAYED_THIS_MONTH) {
                    val zone  = ZoneId.systemDefault()
                    val today = LocalDate.now(zone)
                    val start = today.withDayOfMonth(1).atStartOfDay(zone).toInstant().toEpochMilli()
                    val end   = today.withDayOfMonth(1).plusMonths(1)
                        .atStartOfDay(zone).toInstant().toEpochMilli() - 1
                    statsRepository.listenEventsInRange(start, end)
                } else {
                    flowOf(emptyList())
                }
            }

    // songsUiState intentionally does NOT combine with _searchQuery so that typing
    // in the Songs search bar does not trigger a full re-sort of the library on every
    // keystroke. When search is active, SongsScreen shows songSearchResults instead.
    val songsUiState: StateFlow<HomeUiState> = combine(
        allSongs,
        songSortMode,
        statsRepository.allPlayCounts(),
        songsSortEvents,
    ) { songs, sortMode, allTimePlayCounts, events ->
        when {
            songs == null -> HomeUiState.Loading
            songs.isEmpty() -> HomeUiState.Empty
            else -> {
                val thisMonthPlayCounts =
                    if (sortMode == SongSortMode.MOST_PLAYED_THIS_MONTH) {
                        MostPlayedBuilder.thisMonthPlayCounts(songs = songs, events = events)
                    } else {
                        emptyMap()
                    }
                HomeUiState.Songs(
                    SongSort.sortSongs(
                        songs = songs,
                        mode = sortMode,
                        allTimePlayCounts = allTimePlayCounts,
                        thisMonthPlayCounts = thisMonthPlayCounts,
                    ),
                )
            }
        }
    }.stateIn(
        scope        = viewModelScope,
        started      = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeUiState.Loading,
    )

    val songSearchResults: StateFlow<GroupedSearchResults> =
        combine(_searchQuery.debounce(200), librarySongs) { query, songs ->
            buildGroupedSearchResults(songs = songs, query = query)
        }
            .flowOn(Dispatchers.Default)
            .stateIn(
                scope        = viewModelScope,
                started      = SharingStarted.WhileSubscribed(5_000),
                initialValue = GroupedSearchResults(
                    songs  = emptyList(), artists = emptyList(), albums  = emptyList(),
                    playlists = emptyList(), smartCollections = emptyList(), folders = emptyList(),
                ),
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
    }
        .flowOn(Dispatchers.Default)
        .stateIn(
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
                .filter { it.lastListenedAt > 0 }
                .sortedByDescending { it.lastListenedAt }
                .mapNotNull { songsById[it.songId] }
                .take(DASHBOARD_SONG_PREVIEW_LIMIT),
            mostPlayed = stats
                .filter { it.playCount > 0 }
                .sortedByDescending { it.playCount }
                .mapNotNull { songsById[it.songId] }
                .take(DASHBOARD_SONG_PREVIEW_LIMIT),
            playlists = playlists.take(DASHBOARD_COLLECTION_PREVIEW_LIMIT),
            smartCollections = selectHomeSmartCollections(smartCollections),
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

    val folderModeNeedsSelection: StateFlow<Boolean> =
        libraryScanSettingsRepository.settings
            .map { isFolderModeNeedsSelection(it) }
            .stateIn(
                scope        = viewModelScope,
                started      = SharingStarted.WhileSubscribed(5_000),
                initialValue = false,
            )

    val needsFolderReselectionAfterRestore: StateFlow<Boolean> =
        appSettingsRepository.needsFolderReselectionAfterRestore
            .stateIn(
                scope        = viewModelScope,
                started      = SharingStarted.WhileSubscribed(5_000),
                initialValue = false,
            )

    val nowPlayingState: StateFlow<NowPlayingState> = playerController.nowPlayingState

    val sleepTimerState: StateFlow<SleepTimerState> = playerController.sleepTimerState

    val appIconChoice: StateFlow<AppIconChoice> = appSettingsRepository.appIconChoice.stateIn(
        scope        = viewModelScope,
        started      = SharingStarted.WhileSubscribed(5_000),
        initialValue = AppIconChoice.DEFAULT,
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

    private var hasSynced = false

    fun syncIfNeeded() {
        if (hasSynced) return
        hasSynced = true
        // sync() contains scan failures and returns a typed result; this catch is a final guard
        // so no unexpected error can escape the coroutine and crash the app (WB-02).
        viewModelScope.launch {
            runCatching { repository.sync() }
                .onFailure { Log.e("HomeViewModel", "Library sync failed", it) }
        }
    }

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    /** Explicit rescan of MediaStore / music folders, triggered by pull-to-refresh. */
    fun refreshLibrary() {
        if (_isRefreshing.value) return
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                // sync() never throws for scan failures (returns a typed Failed result); this
                // catch guards against any other unexpected error so refresh state still clears
                // and the app does not crash (WB-02).
                runCatching { repository.sync() }
                    .onFailure { Log.e("HomeViewModel", "Library refresh failed", it) }
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun playSong(song: Song) {
        val queue = (uiState.value as? HomeUiState.Songs)?.songs.orEmpty()
        playerController.playFromQueue(queue = queue, startSong = song)
    }

    fun playRecentlyPlayedSong(song: Song) {
        if (playerController.jumpToSongById(song.id)) return
        val queue = (uiState.value as? HomeUiState.Songs)?.songs.orEmpty()
        playerController.playFromQueue(queue = queue, startSong = song)
    }

    fun playSongFromLibraryQueue(song: Song) {
        val queue = allSongs.value.orEmpty()
        playerController.playFromQueue(queue = queue.ifEmpty { listOf(song) }, startSong = song)
    }

    fun playSearchResult(song: Song) {
        viewModelScope.launch {
            val behavior = appSettingsRepository.searchTapBehavior.first()
            Log.d(SEARCH_TAG, "search result tap behavior=$behavior songId=${song.id}")
            when (behavior) {
                SearchTapBehavior.REPLACE_QUEUE -> playSongFromLibraryQueue(song)
                SearchTapBehavior.PRESERVE_QUEUE -> playerController.playSearchResultPreservingQueue(song)
            }
        }
    }

    fun playSongFromSongsList(song: Song) {
        val queue = (songsUiState.value as? HomeUiState.Songs)?.songs.orEmpty()
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

    fun shuffleSongsList() {
        val songs = (songsUiState.value as? HomeUiState.Songs)?.songs.orEmpty()
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

    fun setSleepTimer(option: SleepTimerOption) = playerController.setSleepTimer(option)

    fun setCustomSleepTimer(durationMs: Long) = playerController.setCustomSleepTimer(durationMs)
}

internal fun isFolderModeNeedsSelection(settings: LibraryScanSettings): Boolean =
    settings.scanMode == LibraryScanMode.SELECTED_FOLDERS &&
        settings.selectedFolderUris.isEmpty()

internal fun selectHomeSmartCollections(
    collections: List<SmartCollection>,
    limit: Int = DASHBOARD_COLLECTION_PREVIEW_LIMIT,
): List<SmartCollection> {
    if (limit <= 0) return emptyList()
    val priorityByType = HOME_SMART_COLLECTION_PRIORITY
        .withIndex()
        .associate { (index, type) -> type to index }
    return collections
        .filter { it.songCount > 0 }
        .sortedWith(
            compareBy<SmartCollection> { priorityByType[it.type] ?: Int.MAX_VALUE }
                .thenBy { it.type.ordinal },
        )
        .take(limit)
}

internal val HOME_SMART_COLLECTION_PRIORITY = listOf(
    SmartCollectionType.ALWAYS_FINISH,
    SmartCollectionType.FORGOTTEN_GEMS,
    SmartCollectionType.USUALLY_ABANDON,
    SmartCollectionType.NEVER_PLAYED,
    SmartCollectionType.RECENTLY_PLAYED,
    SmartCollectionType.FAVORITES,
    SmartCollectionType.MOST_PLAYED,
    SmartCollectionType.RECENTLY_ADDED,
    SmartCollectionType.MOST_SKIPPED,
    SmartCollectionType.LONG_TRACKS,
    SmartCollectionType.SHORT_TRACKS,
)

private const val SEARCH_TAG = "WavdropSearchPlayback"
