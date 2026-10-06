package com.launchpoint.wavdrop.ui.screen.monthlyreports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.launchpoint.wavdrop.data.local.entity.TrackListenEventEntity
import com.launchpoint.wavdrop.data.local.entity.TrackStatsEntity
import com.launchpoint.wavdrop.data.model.ListeningPeriodRange
import com.launchpoint.wavdrop.data.model.MonthlyReportSummary
import com.launchpoint.wavdrop.data.model.MonthYear
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.repository.SongRepository
import com.launchpoint.wavdrop.data.repository.StatsRepository
import com.launchpoint.wavdrop.data.stats.MonthlyReportBuilder
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
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

/** The resolved month choice: available months come from lightweight timestamps; [selected] is null when there is no activity. */
internal data class MonthlyReportPlan(
    val availableMonths: List<MonthYear>,
    val selected: MonthYear?,
)

/**
 * Monthly Reports data flow (WC-02): available months are derived from PLAY+SKIP TIMESTAMPS only; once a month is resolved the report
 * subscribes ONLY to that month's events through [eventsInRange] (cancellation-safe: a month change cancels the previous range
 * subscription, and an unchanged plan does not resubscribe). Output is identical to building from the full event history, because
 * the report builder only reads events inside the month's inclusive range.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun monthlyReportsFlow(
    songs: Flow<List<Song>>,
    stats: Flow<List<TrackStatsEntity>>,
    analyticsTimestamps: Flow<List<Long>>,
    requestedMonth: Flow<MonthYear?>,
    eventsInRange: (fromMs: Long, toMs: Long) -> Flow<List<TrackListenEventEntity>>,
    zone: ZoneId = ZoneId.systemDefault(),
): Flow<MonthlyReportsUiState> =
    combine(analyticsTimestamps, requestedMonth) { timestamps, requested ->
        val months = MonthlyReportBuilder.availableMonthsFromTimestamps(timestamps, zone)
        MonthlyReportPlan(months, requested?.takeIf { it in months } ?: months.firstOrNull())
    }
        .distinctUntilChanged()
        .flatMapLatest { plan ->
            val selected = plan.selected ?: return@flatMapLatest flowOf(MonthlyReportsUiState.NoData)
            val range = ListeningPeriodRange.month(selected.year, selected.month, zone)
            combine(songs, stats, eventsInRange(range.fromMs, range.toMs)) { songList, statList, events ->
                MonthlyReportsUiState.Content(
                    availableMonths = plan.availableMonths,
                    selectedMonth = selected,
                    report = MonthlyReportBuilder.build(
                        month = selected,
                        songs = songList,
                        stats = statList,
                        events = events,
                        zone = zone,
                    ),
                )
            }
        }

@HiltViewModel
class MonthlyReportsViewModel @Inject constructor(
    songRepository: SongRepository,
    statsRepository: StatsRepository,
) : ViewModel() {

    private val _selectedMonth = MutableStateFlow<MonthYear?>(null)

    val uiState: StateFlow<MonthlyReportsUiState> = monthlyReportsFlow(
        songs = songRepository.songs,
        stats = statsRepository.allTrackStatsEntities(),
        analyticsTimestamps = statsRepository.analyticsEventTimestamps(),
        requestedMonth = _selectedMonth,
        eventsInRange = statsRepository::listenEventsInRange,
    )
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope        = viewModelScope,
            started      = SharingStarted.WhileSubscribed(5_000),
            initialValue = MonthlyReportsUiState.Loading,
        )

    fun selectMonth(month: MonthYear) {
        _selectedMonth.value = month
    }
}
