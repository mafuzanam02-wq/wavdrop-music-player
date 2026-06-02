package com.launchpoint.wavdrop.ui.screen.reports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.launchpoint.wavdrop.data.model.ListeningReportSummary
import com.launchpoint.wavdrop.data.repository.SongRepository
import com.launchpoint.wavdrop.data.repository.StatsRepository
import com.launchpoint.wavdrop.data.stats.ListeningReportBuilder
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

sealed interface ReportsUiState {
    data object Loading : ReportsUiState
    data object Empty : ReportsUiState
    data class Content(val report: ListeningReportSummary) : ReportsUiState
}

@HiltViewModel
class ReportsViewModel @Inject constructor(
    songRepository: SongRepository,
    statsRepository: StatsRepository,
) : ViewModel() {

    val uiState: StateFlow<ReportsUiState> = combine(
        songRepository.songs,
        statsRepository.allTrackStatsEntities(),
    ) { songs, stats ->
        val report = ListeningReportBuilder.build(songs = songs, stats = stats)
        if (report.hasListeningStats) {
            ReportsUiState.Content(report)
        } else {
            ReportsUiState.Empty
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ReportsUiState.Loading,
    )
}
