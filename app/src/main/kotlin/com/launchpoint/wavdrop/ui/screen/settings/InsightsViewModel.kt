package com.launchpoint.wavdrop.ui.screen.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.launchpoint.wavdrop.data.repository.SongRepository
import com.launchpoint.wavdrop.data.repository.StatsRepository
import com.launchpoint.wavdrop.data.stats.InsightsSummaryBuilder
import com.launchpoint.wavdrop.data.stats.StatsDashboardBuilder
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

sealed interface InsightsHubUiState {
    data object Loading : InsightsHubUiState
    data object Empty   : InsightsHubUiState
    data class Content(
        val totalPlayCount: Int,
        val totalListeningTimeMs: Long,
        val currentStreakDays: Int,
    ) : InsightsHubUiState
}

@HiltViewModel
class InsightsViewModel @Inject constructor(
    songRepository: SongRepository,
    statsRepository: StatsRepository,
) : ViewModel() {

    val uiState: StateFlow<InsightsHubUiState> = combine(
        songRepository.songs,
        statsRepository.allTrackStatsEntities(),
        statsRepository.allListenEvents(),
    ) { songs, stats, events ->
        val summary = StatsDashboardBuilder.build(songs = songs, stats = stats)
        if (summary.totalPlayCount == 0 && summary.totalSkipCount == 0 && summary.totalListeningTimeMs == 0L) {
            InsightsHubUiState.Empty
        } else {
            InsightsHubUiState.Content(
                totalPlayCount       = summary.totalPlayCount,
                totalListeningTimeMs = summary.totalListeningTimeMs,
                currentStreakDays    = InsightsSummaryBuilder.currentStreakDays(events),
            )
        }
    }.stateIn(
        scope        = viewModelScope,
        started      = SharingStarted.WhileSubscribed(5_000),
        initialValue = InsightsHubUiState.Loading,
    )
}
