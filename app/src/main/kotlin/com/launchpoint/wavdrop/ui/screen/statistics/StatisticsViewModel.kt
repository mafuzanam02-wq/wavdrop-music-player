package com.launchpoint.wavdrop.ui.screen.statistics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.launchpoint.wavdrop.data.local.entity.TrackListenEventEntity
import com.launchpoint.wavdrop.data.model.StatsDashboardSummary
import com.launchpoint.wavdrop.data.repository.SongRepository
import com.launchpoint.wavdrop.data.repository.StatsRepository
import com.launchpoint.wavdrop.data.stats.StatsDashboardBuilder
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface StatisticsUiState {
    data object Loading : StatisticsUiState
    data object Empty : StatisticsUiState
    data class Content(val summary: StatsDashboardSummary) : StatisticsUiState
}

data class StatisticsInsights(
    val favoritesCount: Int,
    val currentStreakDays: Int,
    val mostActiveDayOfWeek: DayOfWeek?,
)

@HiltViewModel
class StatisticsViewModel @Inject constructor(
    songRepository: SongRepository,
    statsRepository: StatsRepository,
) : ViewModel() {

    val uiState: StateFlow<StatisticsUiState> = combine(
        songRepository.songs,
        statsRepository.allTrackStatsEntities(),
    ) { songs, stats ->
        val summary = StatsDashboardBuilder.build(songs = songs, stats = stats)
        if (summary.hasVisibleStats()) {
            StatisticsUiState.Content(summary)
        } else {
            StatisticsUiState.Empty
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = StatisticsUiState.Loading,
    )

    val insightsState: StateFlow<StatisticsInsights> = combine(
        statsRepository.allListenEvents(),
        statsRepository.favoriteSongIds(),
    ) { events, favoriteIds ->
        buildInsights(events, favoriteIds)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = StatisticsInsights(
            favoritesCount = 0,
            currentStreakDays = 0,
            mostActiveDayOfWeek = null,
        ),
    )

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    /**
     * Manual refresh trigger. The primary update path is automatic: Room invalidates
     * [TrackStatsDao.getAllStats] on every write, which propagates through [uiState] via the
     * reactive combine above. This function provides a visible affordance for the user to
     * confirm the latest data is shown, and gives any in-flight IO writes a moment to land.
     */
    fun refresh() {
        if (_isRefreshing.value) return
        viewModelScope.launch {
            _isRefreshing.value = true
            // Brief pause: lets any pending StatsTracker IO coroutines complete and Room to
            // deliver the resulting invalidation before the indicator disappears.
            delay(400L)
            _isRefreshing.value = false
        }
    }

    private fun StatsDashboardSummary.hasVisibleStats(): Boolean =
        totalPlayCount > 0 || totalSkipCount > 0 || totalListeningTimeMs > 0L
}

private fun buildInsights(
    events: List<TrackListenEventEntity>,
    favoriteIds: Set<Long>,
): StatisticsInsights {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val yearStart = today.withDayOfYear(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val yearEnd = today.withDayOfYear(1).plusYears(1).atStartOfDay(zone).toInstant().toEpochMilli()

    val playEvents = events.filter {
        it.eventType == TrackListenEventEntity.TYPE_PLAY &&
            it.occurredAt >= yearStart && it.occurredAt < yearEnd
    }

    val sortedPlayDays = playEvents
        .map { Instant.ofEpochMilli(it.occurredAt).atZone(zone).toLocalDate() }
        .toSortedSet()
        .toList()

    val mostActiveDayOfWeek = playEvents
        .groupingBy { Instant.ofEpochMilli(it.occurredAt).atZone(zone).dayOfWeek }
        .eachCount()
        .maxByOrNull { it.value }
        ?.key

    return StatisticsInsights(
        favoritesCount = favoriteIds.size,
        currentStreakDays = currentStreak(sortedPlayDays, today),
        mostActiveDayOfWeek = mostActiveDayOfWeek,
    )
}

private fun currentStreak(sortedDays: List<LocalDate>, today: LocalDate): Int {
    if (sortedDays.isEmpty()) return 0
    // Return 0 if the last play day is older than yesterday — streak is broken.
    if (sortedDays.last() < today.minusDays(1)) return 0
    var streak = 1
    for (i in sortedDays.lastIndex - 1 downTo 0) {
        if (sortedDays[i + 1] == sortedDays[i].plusDays(1)) streak++ else break
    }
    return streak
}
