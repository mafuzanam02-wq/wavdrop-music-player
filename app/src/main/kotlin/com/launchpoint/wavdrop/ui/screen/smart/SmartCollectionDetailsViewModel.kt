package com.launchpoint.wavdrop.ui.screen.smart

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.launchpoint.wavdrop.data.model.MostPlayedDisplayLimit
import com.launchpoint.wavdrop.data.model.MostPlayedPeriod
import com.launchpoint.wavdrop.data.model.SmartCollectionType
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.model.SongStatsSummary
import com.launchpoint.wavdrop.data.repository.PlaylistRepository
import com.launchpoint.wavdrop.data.repository.PlaylistOperationResult
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import com.launchpoint.wavdrop.data.repository.SmartCollectionRepository
import com.launchpoint.wavdrop.data.repository.SongRepository
import com.launchpoint.wavdrop.data.repository.StatsRepository
import com.launchpoint.wavdrop.data.settings.AppSettingsRepository
import com.launchpoint.wavdrop.data.smart.SmartCollectionBuilder
import com.launchpoint.wavdrop.data.stats.MostPlayedBuilder
import com.launchpoint.wavdrop.playback.PlayerController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
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
    val mostPlayedSummaries: List<SongStatsSummary> = emptyList(),
    val mostPlayedPeriod: MostPlayedPeriod = MostPlayedPeriod.ALL_TIME,
    val mostPlayedDisplayLimit: MostPlayedDisplayLimit = MostPlayedDisplayLimit.TOP_25,
    val favoriteSongIds: Set<Long>,
    val currentSongId: Long?,
)

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

    private val mostPlayedSummaries = combine(
        songRepository.songs,
        statsRepository.allTrackStatsEntities(),
        statsRepository.allListenEvents(),
        mostPlayedPeriod,
        mostPlayedDisplayLimit,
    ) { songs, stats, events, period, limit ->
        MostPlayedBuilder.build(
            songs = songs,
            stats = stats,
            events = events,
            period = period,
            limit = limit,
        )
    }

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
            smartCollectionRepository.observeSongsForCollection(type),
            statsRepository.favoriteSongIds(),
            playerController.nowPlayingState,
        ) { songs, favorites, nowPlaying ->
            SmartCollectionDetailsUiState(
                isLoading = false,
                songs = songs,
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
                        playlistRepository.addSongsToPlaylist(result.playlistId, songs.map { it.id })
                        onResult(SaveAsPlaylistResult.Success(name = candidate, added = songs.size))
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
