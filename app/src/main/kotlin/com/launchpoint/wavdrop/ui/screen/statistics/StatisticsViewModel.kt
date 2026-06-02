package com.launchpoint.wavdrop.ui.screen.statistics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.launchpoint.wavdrop.data.model.StatsDashboardSummary
import com.launchpoint.wavdrop.data.repository.SongRepository
import com.launchpoint.wavdrop.data.repository.StatsRepository
import com.launchpoint.wavdrop.data.stats.StatsDashboardBuilder
import dagger.hilt.android.lifecycle.HiltViewModel
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
