package com.launchpoint.wavdrop.ui.screen.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.launchpoint.wavdrop.data.local.entity.TrackStatsEntity
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.model.SmartCollection
import com.launchpoint.wavdrop.data.model.ListeningAnalyticsEmptyReason
import com.launchpoint.wavdrop.data.model.ListeningPeriodRange
import com.launchpoint.wavdrop.data.model.SmartCollectionType
import com.launchpoint.wavdrop.data.repository.SmartCollectionRepository
import com.launchpoint.wavdrop.data.repository.SongRepository
import com.launchpoint.wavdrop.data.repository.StatsRepository
import com.launchpoint.wavdrop.data.stats.InsightsSummaryBuilder
import com.launchpoint.wavdrop.data.stats.MonthScopedEvents
import com.launchpoint.wavdrop.data.stats.currentMonthEventsFlow
import com.launchpoint.wavdrop.data.stats.ListeningAnalyticsBuilder
import com.launchpoint.wavdrop.data.stats.StatsDashboardBuilder
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

sealed interface InsightsHubUiState {
    data object Loading : InsightsHubUiState
    data object Empty   : InsightsHubUiState
    data class Content(
        val totalPlayCount: Int,
        val totalListeningTimeMs: Long,
        val currentStreakDays: Int,
        val forgottenGemsCount: Int,
        val recentlyPlayedCount: Int,
        val neverPlayedCount: Int,
        val thisMonthPlayCount: Int?,
        val thisMonthListeningTimeMs: Long?,
        val thisMonthTopTrackTitle: String?,
        val mostActiveDayOfWeek: DayOfWeek?,
        val mostActiveHour: Int?,
    ) : InsightsHubUiState
}

/**
 * Insights hub data flow (WC-02): the this-month summary reads ONLY the current month's events ([thisMonthEvents], which carry the
 * month they were queried for; the ViewModel re-binds that range when an analytics event arrives after a month rollover); streak and
 * most-active weekday/hour read PLAY TIMESTAMPS ([playTimestamps], most recent first) instead of full event entities. The streak
 * keeps its current-calendar-year definition. Grouping the full-history PLAY timestamps by weekday/hour still happens in memory here
 * until WC-09 moves it out; that is intentionally out of scope for WC-02.
 */
internal fun insightsHubFlow(
    songs: Flow<List<Song>>,
    stats: Flow<List<TrackStatsEntity>>,
    thisMonthEvents: Flow<MonthScopedEvents>,
    playTimestamps: Flow<List<Long>>,
    collections: Flow<List<SmartCollection>>,
    zone: ZoneId = ZoneId.systemDefault(),
    now: () -> LocalDateTime = { LocalDateTime.now(zone) },
): Flow<InsightsHubUiState> = combine(songs, stats, thisMonthEvents, playTimestamps, collections) { songList, statList, scoped, plays, smart ->
    val summary = StatsDashboardBuilder.build(songs = songList, stats = statList)
    if (summary.totalPlayCount == 0 && summary.totalSkipCount == 0 && summary.totalListeningTimeMs == 0L) {
        InsightsHubUiState.Empty
    } else {
        val current = now()
        // The range comes from the month the rows were scoped to, never from a separate clock read, so they cannot disagree.
        val monthRange = ListeningPeriodRange.month(scoped.month.year, scoped.month.month, zone)
        val monthSummary = ListeningAnalyticsBuilder.build(
            range  = monthRange,
            songs  = songList,
            stats  = statList,
            events = scoped.events,
        )
        val hasMonthActivity = monthSummary.emptyState.reason == ListeningAnalyticsEmptyReason.HAS_ACTIVITY

        InsightsHubUiState.Content(
            totalPlayCount           = summary.totalPlayCount,
            totalListeningTimeMs     = summary.totalListeningTimeMs,
            currentStreakDays        = InsightsSummaryBuilder.currentStreakDaysFromPlayTimestamps(plays, zone, current.toLocalDate()),
            forgottenGemsCount       = smart.find { it.type == SmartCollectionType.FORGOTTEN_GEMS }?.songCount ?: 0,
            recentlyPlayedCount      = smart.find { it.type == SmartCollectionType.RECENTLY_PLAYED }?.songCount ?: 0,
            neverPlayedCount         = smart.find { it.type == SmartCollectionType.NEVER_PLAYED }?.songCount ?: 0,
            thisMonthPlayCount       = if (hasMonthActivity) monthSummary.totalPlayCount else null,
            thisMonthListeningTimeMs = if (hasMonthActivity) monthSummary.totalListeningTimeMs else null,
            thisMonthTopTrackTitle   = if (hasMonthActivity) monthSummary.topSongs.firstOrNull()?.song?.title else null,
            mostActiveDayOfWeek      = InsightsSummaryBuilder.mostActiveDayOfWeekFromPlayTimestamps(plays, zone),
            mostActiveHour           = InsightsSummaryBuilder.mostActiveHourFromPlayTimestamps(plays, zone),
        )
    }
}

@HiltViewModel
class InsightsViewModel @Inject constructor(
    songRepository: SongRepository,
    statsRepository: StatsRepository,
    smartCollectionRepository: SmartCollectionRepository,
) : ViewModel() {

    // Current-month events: the month key follows the wall clock and is re-evaluated whenever an analytics event (PLAY/SKIP) timestamp
    // emission arrives, so a screen left open across a month boundary switches range on the first event of the new month (no timer).
    private val thisMonthEvents = currentMonthEventsFlow(
        invalidation = statsRepository.analyticsEventTimestamps(),
        eventsInRange = statsRepository::listenEventsInRange,
    )

    val uiState: StateFlow<InsightsHubUiState> = insightsHubFlow(
        songs = songRepository.songs,
        stats = statsRepository.allTrackStatsEntities(),
        thisMonthEvents = thisMonthEvents,
        playTimestamps = statsRepository.playEventTimestamps(),
        collections = smartCollectionRepository.observeSmartCollections(),
    )
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope        = viewModelScope,
            started      = SharingStarted.WhileSubscribed(5_000),
            initialValue = InsightsHubUiState.Loading,
        )
}
