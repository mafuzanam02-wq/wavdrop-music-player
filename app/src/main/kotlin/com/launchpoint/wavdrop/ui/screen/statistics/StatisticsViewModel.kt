package com.launchpoint.wavdrop.ui.screen.statistics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.launchpoint.wavdrop.data.model.StatsDashboardSummary
import com.launchpoint.wavdrop.data.repository.SongRepository
import com.launchpoint.wavdrop.data.repository.StatsRepository
import com.launchpoint.wavdrop.data.stats.StatsDashboardBuilder
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

sealed interface StatisticsUiState {
    data object Loading : StatisticsUiState
    data object Empty : StatisticsUiState
    data class Content(val summary: StatsDashboardSummary) : StatisticsUiState
}

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

    private fun StatsDashboardSummary.hasVisibleStats(): Boolean =
        totalPlayCount > 0 || totalSkipCount > 0 || totalListeningTimeMs > 0L
}
