package com.launchpoint.wavdrop.ui.screen.monthlyreports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.launchpoint.wavdrop.data.model.MonthlyReportSummary
import com.launchpoint.wavdrop.data.model.MonthYear
import com.launchpoint.wavdrop.data.repository.SongRepository
import com.launchpoint.wavdrop.data.repository.StatsRepository
import com.launchpoint.wavdrop.data.stats.MonthlyReportBuilder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

sealed interface MonthlyReportsUiState {
    data object Loading : MonthlyReportsUiState
    data object NoData : MonthlyReportsUiState
    data class Content(
        val availableMonths: List<MonthYear>,
        val selectedMonth: MonthYear,
        val report: MonthlyReportSummary,
    ) : MonthlyReportsUiState
}

@HiltViewModel
class MonthlyReportsViewModel @Inject constructor(
    songRepository: SongRepository,
    statsRepository: StatsRepository,
) : ViewModel() {

    private val _selectedMonth = MutableStateFlow<MonthYear?>(null)

    val uiState: StateFlow<MonthlyReportsUiState> = combine(
        songRepository.songs,
        statsRepository.allTrackStatsEntities(),
        statsRepository.allListenEvents(),
        _selectedMonth,
    ) { songs, stats, events, requestedMonth ->
        val months = MonthlyReportBuilder.availableMonths(stats, events)
        if (months.isEmpty()) return@combine MonthlyReportsUiState.NoData
        val selected = requestedMonth?.takeIf { it in months } ?: months.first()
        val report = MonthlyReportBuilder.build(
            month = selected,
            songs = songs,
            stats = stats,
            events = events,
        )
        MonthlyReportsUiState.Content(
            availableMonths = months,
            selectedMonth = selected,
            report = report,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MonthlyReportsUiState.Loading,
    )

    fun selectMonth(month: MonthYear) {
        _selectedMonth.value = month
    }
}
