package com.launchpoint.wavdrop.ui.screen.smart

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.launchpoint.wavdrop.data.local.entity.TrackListenEventEntity
import com.launchpoint.wavdrop.data.local.entity.TrackStatsEntity
import com.launchpoint.wavdrop.data.model.ListeningPeriodRange
import com.launchpoint.wavdrop.data.model.MostPlayedDisplayLimit
import com.launchpoint.wavdrop.data.model.MostPlayedPeriod
import com.launchpoint.wavdrop.data.model.SmartCollectionType
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.model.SongStatsSummary
import com.launchpoint.wavdrop.data.repository.PlaylistRepository
import com.launchpoint.wavdrop.data.repository.PlaylistOperationResult
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import com.launchpoint.wavdrop.data.repository.SmartCollectionRepository
import com.launchpoint.wavdrop.data.repository.SongRepository
import com.launchpoint.wavdrop.data.repository.StatsRepository
import com.launchpoint.wavdrop.data.settings.AppSettingsRepository
import com.launchpoint.wavdrop.data.smart.SmartCollectionBuilder
import com.launchpoint.wavdrop.data.stats.MostPlayedBuilder
import com.launchpoint.wavdrop.data.stats.currentMonthEventsFlow
import com.launchpoint.wavdrop.playback.PlayerController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface SaveAsPlaylistResult {
    data class Success(val name: String, val added: Int) : SaveAsPlaylistResult
    data class DuplicateName(val name: String) : SaveAsPlaylistResult
    data object Empty : SaveAsPlaylistResult
    data object Error : SaveAsPlaylistResult
}

data class SmartCollectionDetailsUiState(
    val isLoading: Boolean = false,
    val songs: List<Song>,
    val totalEligibleCount: Int = songs.size,
    val visibleLimit: Int? = null,
    val mostPlayedSummaries: List<SongStatsSummary> = emptyList(),
    val mostPlayedPeriod: MostPlayedPeriod = MostPlayedPeriod.ALL_TIME,
    val mostPlayedDisplayLimit: MostPlayedDisplayLimit = MostPlayedDisplayLimit.TOP_25,
    val favoriteSongIds: Set<Long>,
    val currentSongId: Long?,
) {
    val capFooterText: String?
        get() = smartCollectionCapFooterText(
            visibleCount = songs.size,
            totalEligibleCount = totalEligibleCount,
            visibleLimit = visibleLimit,
        )
}

private val DATE_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM yyyy", Locale.US)
private const val MAX_SAVE_ATTEMPTS = 99

@HiltViewModel
class SmartCollectionDetailsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val songRepository: SongRepository,
    private val smartCollectionRepository: SmartCollectionRepository,
    private val statsRepository: StatsRepository,
    private val playerController: PlayerController,
    private val appSettingsRepository: AppSettingsRepository,
    private val playlistRepository: PlaylistRepository,
) : ViewModel() {

    val type: SmartCollectionType? = SmartCollectionType.fromRouteValue(savedStateHandle["type"])
    val isInvalidType: Boolean = type == null

    val title: String       = if (type != null) SmartCollectionBuilder.titleFor(type) else ""
    val description: String = if (type != null) SmartCollectionBuilder.descriptionFor(type) else ""

    private val mostPlayedPeriod = MutableStateFlow(MostPlayedPeriod.ALL_TIME)
    private val mostPlayedDisplayLimit = MutableStateFlow(MostPlayedDisplayLimit.TOP_25)

    init {
        if (type == SmartCollectionType.MOST_PLAYED) {
            viewModelScope.launch {
                mostPlayedPeriod.value = appSettingsRepository.mostPlayedPeriod.first()
                mostPlayedDisplayLimit.value = appSettingsRepository.mostPlayedDisplayLimit.first()
            }
        }
    }

    private val mostPlayedSummaries = mostPlayedSummariesFlow(
        songs = songRepository.songs,
        stats = statsRepository.allTrackStatsEntities(),
        period = mostPlayedPeriod,
        limit = mostPlayedDisplayLimit,
        eventsInRange = statsRepository::listenEventsInRange,
        invalidation = statsRepository.analyticsEventTimestamps(),
    )

    val uiState: StateFlow<SmartCollectionDetailsUiState> = when {
        type == null -> MutableStateFlow(
            SmartCollectionDetailsUiState(
                isLoading       = false,
                songs           = emptyList(),
                favoriteSongIds = emptySet(),
                currentSongId   = null,
            ),
        )
        type == SmartCollectionType.MOST_PLAYED -> combine(
            mostPlayedSummaries,
            mostPlayedPeriod,
            mostPlayedDisplayLimit,
            statsRepository.favoriteSongIds(),
            playerController.nowPlayingState,
        ) { summaries, period, limit, favorites, nowPlaying ->
            SmartCollectionDetailsUiState(
                isLoading = false,
                songs = summaries.map { it.song },
                mostPlayedSummaries = summaries,
                mostPlayedPeriod = period,
                mostPlayedDisplayLimit = limit,
                favoriteSongIds = favorites,
                currentSongId = nowPlaying.song?.id,
            )
        }.stateIn(
            scope        = viewModelScope,
            started      = SharingStarted.WhileSubscribed(5_000),
            initialValue = SmartCollectionDetailsUiState(
                isLoading       = true,
                songs           = emptyList(),
                favoriteSongIds = emptySet(),
                currentSongId   = null,
            ),
        )
        else -> combine(
            smartCollectionRepository.observeSongResultForCollection(type),
            statsRepository.favoriteSongIds(),
            playerController.nowPlayingState,
        ) { result, favorites, nowPlaying ->
            SmartCollectionDetailsUiState(
                isLoading = false,
                songs = result.songs,
                totalEligibleCount = result.totalEligibleCount,
                visibleLimit = result.visibleLimit,
                favoriteSongIds = favorites,
                currentSongId = nowPlaying.song?.id,
            )
        }.stateIn(
            scope        = viewModelScope,
            started      = SharingStarted.WhileSubscribed(5_000),
            initialValue = SmartCollectionDetailsUiState(
                isLoading       = true,
                songs           = emptyList(),
                favoriteSongIds = emptySet(),
                currentSongId   = null,
            ),
        )
    }

    fun setMostPlayedPeriod(period: MostPlayedPeriod) {
        mostPlayedPeriod.value = period
        viewModelScope.launch { appSettingsRepository.setMostPlayedPeriod(period) }
    }

    fun setMostPlayedDisplayLimit(limit: MostPlayedDisplayLimit) {
        mostPlayedDisplayLimit.value = limit
        viewModelScope.launch { appSettingsRepository.setMostPlayedDisplayLimit(limit) }
    }

    fun playSong(song: Song) {
        val queue = uiState.value.songs
        if (queue.isEmpty()) return
        if (type == SmartCollectionType.RECENTLY_PLAYED && playerController.jumpToSongById(song.id)) return
        playerController.playFromQueue(queue = queue, startSong = song)
    }

    fun playNext(song: Song)   = playerController.playNext(song)
    fun addToQueue(song: Song) = playerController.addToQueue(song)

    fun playAll() {
        val songs = uiState.value.songs
        val first = songs.firstOrNull() ?: return
        playerController.playFromQueue(queue = songs, startSong = first)
    }

    fun shufflePlay() {
        val songs = uiState.value.songs
        if (songs.isEmpty()) return
        playerController.playFromQueueShuffled(queue = songs)
    }

    fun toggleFavorite(songId: Long) {
        val song = uiState.value.songs.firstOrNull { it.id == songId } ?: return
        viewModelScope.launch { statsRepository.toggleFavorite(songId, song.uri) }
    }

    fun saveAsPlaylist(onResult: (SaveAsPlaylistResult) -> Unit) {
        val songs = uiState.value.songs
        if (songs.isEmpty()) { onResult(SaveAsPlaylistResult.Empty); return }
        val dateLabel = LocalDate.now().format(DATE_FORMATTER)
        val baseName  = "$title — $dateLabel"
        viewModelScope.launch {
            for (attempt in 1..MAX_SAVE_ATTEMPTS) {
                val candidate = if (attempt == 1) baseName else "$baseName ($attempt)"
                when (val result = playlistRepository.createPlaylist(candidate)) {
                    is PlaylistOperationResult.Success -> {
                        val addResult = playlistRepository.addSongsToPlaylist(
                            result.playlistId,
                            smartCollectionPlaylistSongIds(songs),
                        )
                        onResult(SaveAsPlaylistResult.Success(name = candidate, added = addResult.added))
                        return@launch
                    }
                    is PlaylistOperationResult.DuplicateName -> continue
                    is PlaylistOperationResult.BlankName     -> { onResult(SaveAsPlaylistResult.Error); return@launch }
                }
            }
            onResult(SaveAsPlaylistResult.DuplicateName(baseName))
        }
    }
}

internal fun smartCollectionCapFooterText(
    visibleCount: Int,
    totalEligibleCount: Int,
    visibleLimit: Int?,
): String? =
    if (visibleLimit != null && totalEligibleCount > visibleCount) {
        "Showing top $visibleCount of $totalEligibleCount qualifying songs"
    } else {
        null
    }

internal fun smartCollectionPlaylistSongIds(songs: List<Song>): List<Long> =
    songs.map { it.id }

/**
 * Most Played data flow (WC-02). ALL_TIME is built from aggregate TrackStats and subscribes to NO event stream. THIS_MONTH subscribes
 * only to the wall-clock current month's events through [eventsInRange]; the month key is re-evaluated on every [invalidation]
 * emission (analytics event timestamps), so a screen left open across a month boundary switches range on the first event of the new
 * month. Switching back to ALL_TIME cancels that subscription (flatMapLatest); returning to THIS_MONTH re-resolves the month.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun mostPlayedSummariesFlow(
    songs: Flow<List<Song>>,
    stats: Flow<List<TrackStatsEntity>>,
    period: Flow<MostPlayedPeriod>,
    limit: Flow<MostPlayedDisplayLimit>,
    eventsInRange: (fromMs: Long, toMs: Long) -> Flow<List<TrackListenEventEntity>>,
    invalidation: Flow<*>,
    zone: ZoneId = ZoneId.systemDefault(),
    nowMs: () -> Long = { System.currentTimeMillis() },
): Flow<List<SongStatsSummary>> = period.flatMapLatest { selected ->
    when (selected) {
        MostPlayedPeriod.ALL_TIME -> combine(songs, stats, limit) { songList, statList, displayLimit ->
            MostPlayedBuilder.build(
                songs = songList, stats = statList, events = emptyList(), period = MostPlayedPeriod.ALL_TIME,
                limit = displayLimit, zone = zone,
            )
        }
        MostPlayedPeriod.THIS_MONTH -> combine(
            songs, stats, currentMonthEventsFlow(invalidation, eventsInRange, zone, nowMs), limit,
        ) { songList, statList, scoped, displayLimit ->
            // Anchor the builder at the start of the month the rows were scoped to so rows and month can never disagree.
            val anchorMs = ListeningPeriodRange.month(scoped.month.year, scoped.month.month, zone).fromMs
            MostPlayedBuilder.build(
                songs = songList, stats = statList, events = scoped.events, period = MostPlayedPeriod.THIS_MONTH,
                limit = displayLimit, nowMs = anchorMs, zone = zone,
            )
        }
    }
}
