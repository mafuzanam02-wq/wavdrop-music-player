package com.launchpoint.wavdrop.ui.screen.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.util.Log
import com.launchpoint.wavdrop.data.model.PlaylistSummary
import com.launchpoint.wavdrop.data.model.SmartCollection
import com.launchpoint.wavdrop.data.model.SmartCollectionType
import com.launchpoint.wavdrop.data.local.entity.TrackListenEventEntity
import com.launchpoint.wavdrop.data.local.entity.TrackStatsEntity
import com.launchpoint.wavdrop.data.model.ListeningPeriodRange
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.model.HomeWrappedPreview
import com.launchpoint.wavdrop.data.repository.PlaylistRepository
import com.launchpoint.wavdrop.data.repository.SmartCollectionRepository
import com.launchpoint.wavdrop.data.repository.SongRepository
import com.launchpoint.wavdrop.ui.scan.LibraryScanCoordinator
import com.launchpoint.wavdrop.ui.scan.LibraryScanUiState
import com.launchpoint.wavdrop.data.repository.StatsRepository
import com.launchpoint.wavdrop.data.repository.localDayRefreshFlow
import com.launchpoint.wavdrop.data.search.LibrarySearchIndex
import com.launchpoint.wavdrop.data.search.SongSort
import com.launchpoint.wavdrop.data.settings.AppIconChoice
import com.launchpoint.wavdrop.data.settings.AppSettingsRepository
import com.launchpoint.wavdrop.data.settings.HomeLayoutSettings
import com.launchpoint.wavdrop.data.settings.HomeLayoutSettingsRepository
import com.launchpoint.wavdrop.data.settings.HomeSmartCollectionSelection
import com.launchpoint.wavdrop.data.settings.LibraryScanMode
import com.launchpoint.wavdrop.data.settings.LibraryScanSettings
import com.launchpoint.wavdrop.data.settings.LibraryScanSettingsRepository
import com.launchpoint.wavdrop.data.settings.SearchTapBehavior
import com.launchpoint.wavdrop.data.settings.SongSortMode
import com.launchpoint.wavdrop.data.stats.MostPlayedBuilder
import com.launchpoint.wavdrop.data.stats.HomeWrappedPreviewBuilder
import com.launchpoint.wavdrop.playback.NowPlayingState
import com.launchpoint.wavdrop.playback.PlayerController
import com.launchpoint.wavdrop.playback.SleepTimerOption
import com.launchpoint.wavdrop.playback.SleepTimerState
import dagger.hilt.android.lifecycle.HiltViewModel
import com.launchpoint.wavdrop.ui.components.GroupedSearchResults
import com.launchpoint.wavdrop.ui.components.groupedSearchResults
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

/** Maps the already-bounded, SQL-ranked stats rows (live songs only) onto the current song objects, preserving SQL order. */
internal fun homePreviewSongs(rankedStats: List<TrackStatsEntity>, songsById: Map<Long, Song>): List<Song> =
    rankedStats.mapNotNull { songsById[it.songId] }

/** The song list plus its id lookup, built together once per library emission (WC-11). Song ids are the table's primary key. */
internal class HomeLibraryProjection(val songs: List<Song>, val songsById: Map<Long, Song>) {
    companion object {
        val EMPTY = HomeLibraryProjection(emptyList(), emptyMap())
        fun of(songs: List<Song>) = HomeLibraryProjection(songs, songs.associateBy { it.id })
    }
}

/** One projection per library emission; the `build` seam lets tests count how often the O(N) map is constructed. */
internal fun homeLibraryProjectionFlow(
    songs: Flow<List<Song>?>,
    build: (List<Song>) -> HomeLibraryProjection = HomeLibraryProjection::of,
): Flow<HomeLibraryProjection> = songs.map { build(it.orEmpty()) }

/** Combines the shared library projection with the other dashboard inputs; it never builds a song lookup itself. */
internal fun homeDashboardFlow(
    library: Flow<HomeLibraryProjection>,
    previewStats: Flow<Pair<List<TrackStatsEntity>, List<TrackStatsEntity>>>,
    playlists: Flow<List<PlaylistSummary>>,
    smartCollections: Flow<List<SmartCollection>>,
    wrapped: Flow<HomeWrappedPreview?>,
): Flow<HomeDashboardUiState> = combine(
    library, previewStats, playlists, smartCollections, wrapped,
) { lib, (recentStats, mostStats), playlistList, smart, latestWrapped ->
    HomeDashboardUiState(
        totalSongs = lib.songs.size,
        recentlyPlayed = homePreviewSongs(recentStats, lib.songsById),
        mostPlayed = homePreviewSongs(mostStats, lib.songsById),
        playlists = playlistList.take(DASHBOARD_COLLECTION_PREVIEW_LIMIT),
        smartCollections = smart,
        wrapped = latestWrapped,
    )
}

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
    val wrapped: HomeWrappedPreview? = null,
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

    // WC-01 / Phase 9: filtering runs off the Main thread with a short debounce. The normalized
    // search index is rebuilt only when the library changes (allSongs emits) — mapLatest cancels a
    // stale rebuild during a rescan — so a keystroke only runs cheap substring matching, not a
    // full re-normalization of the library. A null index preserves the Loading state; an empty
    // library preserves Empty. Search semantics (matched fields, ordering, empty-state) are unchanged.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val librarySongIndex: Flow<LibrarySearchIndex?> = allSongs
        .mapLatest { songs -> songs?.let { LibrarySearchIndex.from(it) } }
        .flowOn(Dispatchers.Default)

    val uiState: StateFlow<HomeUiState> = combine(
        librarySongIndex,
        _searchQuery.debounce(SEARCH_DEBOUNCE_MS),
    ) { index, query ->
        when {
            index == null -> HomeUiState.Loading
            index.songs.isEmpty() -> HomeUiState.Empty
            else            -> HomeUiState.Songs(index.filterSongs(query))
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

    // Phase 9: the grouped search index is rebuilt only when the library changes (mapLatest cancels
    // a stale rebuild during a rescan); each keystroke runs only cheap substring matching, and a
    // newer query supersedes an in-flight search. Debounce (200 ms) and semantics are unchanged.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val songSearchIndex: Flow<LibrarySearchIndex> = librarySongs
        .mapLatest { songs -> LibrarySearchIndex.from(songs) }
        .flowOn(Dispatchers.Default)

    @OptIn(ExperimentalCoroutinesApi::class)
    val songSearchResults: StateFlow<GroupedSearchResults> =
        combine(songSearchIndex, _searchQuery.debounce(200)) { index, query -> index to query }
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

    // Shared Home library lookup (WC-11): the O(N) songsById map is built ONCE per song-library emission and reused by the dashboard
    // previews and the Wrapped card; playlist / stats / Smart Collection / Wrapped emissions never rebuild it.
    private val libraryProjection: StateFlow<HomeLibraryProjection> = homeLibraryProjectionFlow(allSongs)
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope        = viewModelScope,
            started      = SharingStarted.WhileSubscribed(5_000),
            initialValue = HomeLibraryProjection.EMPTY,
        )

    // Wrapped preview (WC-05): Home observes one scalar latest PLAY/SKIP timestamp, then ONE small aggregate for the latest activity
    // year (one row per live active song + the all-event PLAY total) instead of the year's event rows and a full WrappedSummary.
    // flatMapLatest cancels the previous year's query when the latest activity moves to a new year; distinctUntilChanged suppresses
    // Room's table-level invalidation when out-of-year inserts leave the aggregate unchanged.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val wrappedPreview: StateFlow<HomeWrappedPreview?> = statsRepository.latestAnalyticsEventAt()
        .distinctUntilChanged()
        .flatMapLatest { latestAt ->
            val selection = homeWrappedYearSelection(latestAt) ?: return@flatMapLatest flowOf(null)
            combine(
                libraryProjection,
                statsRepository
                    .homeWrappedActivity(selection.range.fromMs, selection.range.toMs)
                    .distinctUntilChanged(),
            ) { library, activity ->
                HomeWrappedPreviewBuilder.build(
                    year = selection.year,
                    songsById = library.songsById,
                    activity = activity,
                )
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope        = viewModelScope,
            started      = SharingStarted.WhileSubscribed(5_000),
            initialValue = null,
        )

    // Home Smart Collections projection (WU-01): the already-computed collections are filtered
    // and ordered by the user's configured selection. observeSmartCollections() still does all
    // the expensive membership work exactly once; changing the selection only re-runs the cheap
    // selectHomeSmartCollections sort/take. distinctUntilChanged on the ordered ids keeps
    // unrelated Home-layout edits (e.g. toggling a section) from re-triggering this combine.
    private val homeSmartCollections: Flow<List<SmartCollection>> = combine(
        smartCollectionRepository.observeSmartCollections(),
        homeLayoutRepository.settings
            .map { it.homeSmartCollections }
            .distinctUntilChanged(),
    ) { collections, configuredOrder ->
        selectHomeSmartCollections(collections, configuredOrder)
    }

    // Bounded Home previews (WC-04): SQLite ranks, filters (live songs only, before LIMIT) and limits to
    // DASHBOARD_SONG_PREVIEW_LIMIT rows each, so Home never receives or sorts the whole track_stats table.
    private val previewStats: Flow<Pair<List<TrackStatsEntity>, List<TrackStatsEntity>>> = combine(
        statsRepository.recentlyListenedPreview(DASHBOARD_SONG_PREVIEW_LIMIT),
        statsRepository.mostPlayedPreview(DASHBOARD_SONG_PREVIEW_LIMIT),
    ) { recent, most -> recent to most }

    val dashboardState: StateFlow<HomeDashboardUiState> = homeDashboardFlow(
        library = libraryProjection,
        previewStats = previewStats,
        playlists = playlistRepository.observePlaylists(),
        smartCollections = homeSmartCollections,
        wrapped = wrappedPreview,
    ).stateIn(
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

    // One scan operation owner for every Home/Songs entry point (initial sync, pull-to-refresh, top-bar Rescan,
    // empty-state Rescan). `scanState` is the single authoritative operation state; a second start while Scanning is a no-op.
    private val libraryScan = LibraryScanCoordinator()
    val scanState: StateFlow<LibraryScanUiState> = libraryScan.state

    /** Pull-to-refresh spinner: derived from [scanState], so it can never contradict it. */
    val isRefreshing: StateFlow<Boolean> = scanState
        .map { it == LibraryScanUiState.Scanning }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private var hasSynced = false

    fun syncIfNeeded() {
        if (hasSynced) return
        hasSynced = true
        // The result is recorded in scanState (Failed/EmptyPreserved surface as a warning over the preserved library);
        // the coordinator also turns an unexpected exception into Error so nothing can crash the app (WB-02).
        rescanLibrary()
    }

    /** Explicit rescan of MediaStore / music folders. Every Home/Songs rescan entry point calls this. */
    fun rescanLibrary() {
        libraryScan.start(viewModelScope) { repository.sync() }
    }

    fun dismissScanMessage() = libraryScan.dismiss()

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

    fun setSleepTimer(option: SleepTimerOption, finishCurrentTrack: Boolean = false) =
        playerController.setSleepTimer(option, finishCurrentTrack)

    fun setCustomSleepTimer(durationMs: Long, finishCurrentTrack: Boolean = false) =
        playerController.setCustomSleepTimer(durationMs, finishCurrentTrack)
}

internal fun isFolderModeNeedsSelection(settings: LibraryScanSettings): Boolean =
    settings.scanMode == LibraryScanMode.SELECTED_FOLDERS &&
        settings.selectedFolderUris.isEmpty()

// Home projection (WU-01): [configuredOrder] is the user's chosen top slots, in display order.
// The remaining types (in the product fallback order below) trail it so that an empty configured
// collection is still skipped and back-filled — preserving Home's long-standing "hide empty smart
// collections, keep the row full" behavior. When configuredOrder is the default three the effective
// priority is unchanged from before this feature.
internal fun selectHomeSmartCollections(
    collections: List<SmartCollection>,
    configuredOrder: List<SmartCollectionType> = HomeSmartCollectionSelection.DEFAULT,
    limit: Int = DASHBOARD_COLLECTION_PREVIEW_LIMIT,
): List<SmartCollection> {
    if (limit <= 0) return emptyList()
    val effectivePriority =
        configuredOrder + HOME_SMART_COLLECTION_PRIORITY.filterNot { it in configuredOrder }
    val priorityByType = effectivePriority
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

internal data class HomeWrappedYearSelection(
    val year: Int,
    val range: ListeningPeriodRange,
)

internal fun latestAnalyticsEventAt(events: List<TrackListenEventEntity>): Long? =
    events
        .asSequence()
        .filter {
            it.eventType == TrackListenEventEntity.TYPE_PLAY ||
                it.eventType == TrackListenEventEntity.TYPE_SKIP
        }
        .maxOfOrNull { it.occurredAt }

internal fun homeWrappedYearSelection(
    latestAt: Long?,
    zone: ZoneId = ZoneId.systemDefault(),
): HomeWrappedYearSelection? {
    latestAt ?: return null
    val year = Instant.ofEpochMilli(latestAt).atZone(zone).year
    return HomeWrappedYearSelection(
        year = year,
        range = ListeningPeriodRange.year(year, zone),
    )
}
