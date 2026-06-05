package com.launchpoint.wavdrop.ui.screen.smart

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.launchpoint.wavdrop.data.model.MostPlayedDisplayLimit
import com.launchpoint.wavdrop.data.model.MostPlayedPeriod
import com.launchpoint.wavdrop.data.model.SmartCollectionType
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.model.SongStatsSummary
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

data class SmartCollectionDetailsUiState(
    val isLoading: Boolean = false,
    val songs: List<Song>,
    val mostPlayedSummaries: List<SongStatsSummary> = emptyList(),
    val mostPlayedPeriod: MostPlayedPeriod = MostPlayedPeriod.ALL_TIME,
    val mostPlayedDisplayLimit: MostPlayedDisplayLimit = MostPlayedDisplayLimit.TOP_25,
    val favoriteSongIds: Set<Long>,
    val currentSongId: Long?,
)

@HiltViewModel
class SmartCollectionDetailsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val songRepository: SongRepository,
    private val smartCollectionRepository: SmartCollectionRepository,
    private val statsRepository: StatsRepository,
    private val playerController: PlayerController,
    private val appSettingsRepository: AppSettingsRepository,
) : ViewModel() {

    val type: SmartCollectionType = SmartCollectionType.valueOf(
        checkNotNull(savedStateHandle["type"]),
    )

    val title: String       = SmartCollectionBuilder.titleFor(type)
    val description: String = SmartCollectionBuilder.descriptionFor(type)

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

    val uiState: StateFlow<SmartCollectionDetailsUiState> = if (type == SmartCollectionType.MOST_PLAYED) {
        combine(
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
        }
    } else {
        combine(
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
        }
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
}
