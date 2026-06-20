package com.launchpoint.wavdrop.ui.screen.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.launchpoint.wavdrop.data.local.entity.TrackListenEventEntity
import com.launchpoint.wavdrop.data.model.ListeningAnalyticsEmptyReason
import com.launchpoint.wavdrop.data.model.ListeningPeriodRange
import com.launchpoint.wavdrop.data.model.SmartCollectionType
import com.launchpoint.wavdrop.data.repository.SmartCollectionRepository
import com.launchpoint.wavdrop.data.repository.SongRepository
import com.launchpoint.wavdrop.data.repository.StatsRepository
import com.launchpoint.wavdrop.data.stats.InsightsSummaryBuilder
import com.launchpoint.wavdrop.data.stats.ListeningAnalyticsBuilder
import com.launchpoint.wavdrop.data.stats.StatsDashboardBuilder
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
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

@HiltViewModel
class InsightsViewModel @Inject constructor(
    songRepository: SongRepository,
    statsRepository: StatsRepository,
    smartCollectionRepository: SmartCollectionRepository,
) : ViewModel() {

    val uiState: StateFlow<InsightsHubUiState> = combine(
        songRepository.songs,
        statsRepository.allTrackStatsEntities(),
        statsRepository.allListenEvents(),
        smartCollectionRepository.observeSmartCollections(),
    ) { songs, stats, events, collections ->
        val summary = StatsDashboardBuilder.build(songs = songs, stats = stats)
        if (summary.totalPlayCount == 0 && summary.totalSkipCount == 0 && summary.totalListeningTimeMs == 0L) {
            InsightsHubUiState.Empty
        } else {
            val zone = ZoneId.systemDefault()
            val now  = LocalDateTime.now(zone)

            val monthRange    = ListeningPeriodRange.month(now.year, now.monthValue, zone)
            val monthSummary  = ListeningAnalyticsBuilder.build(
                range  = monthRange,
                songs  = songs,
                stats  = stats,
                events = events,
            )
            val hasMonthActivity =
                monthSummary.emptyState.reason == ListeningAnalyticsEmptyReason.HAS_ACTIVITY

            val playEvents = events.filter { it.eventType == TrackListenEventEntity.TYPE_PLAY }
            val mostActiveDayOfWeek: DayOfWeek? = if (playEvents.isEmpty()) null else {
                playEvents
                    .groupBy { Instant.ofEpochMilli(it.occurredAt).atZone(zone).dayOfWeek }
                    .maxByOrNull { it.value.size }
                    ?.key
            }
            val mostActiveHour: Int? = if (playEvents.isEmpty()) null else {
                playEvents
                    .groupBy { Instant.ofEpochMilli(it.occurredAt).atZone(zone).hour }
                    .maxByOrNull { it.value.size }
                    ?.key
            }

            InsightsHubUiState.Content(
                totalPlayCount           = summary.totalPlayCount,
                totalListeningTimeMs     = summary.totalListeningTimeMs,
                currentStreakDays        = InsightsSummaryBuilder.currentStreakDays(events),
                forgottenGemsCount       = collections.find { it.type == SmartCollectionType.FORGOTTEN_GEMS }?.songCount ?: 0,
                recentlyPlayedCount      = collections.find { it.type == SmartCollectionType.RECENTLY_PLAYED }?.songCount ?: 0,
                neverPlayedCount         = collections.find { it.type == SmartCollectionType.NEVER_PLAYED }?.songCount ?: 0,
                thisMonthPlayCount       = if (hasMonthActivity) monthSummary.totalPlayCount else null,
                thisMonthListeningTimeMs = if (hasMonthActivity) monthSummary.totalListeningTimeMs else null,
                thisMonthTopTrackTitle   = if (hasMonthActivity) monthSummary.topSongs.firstOrNull()?.song?.title else null,
                mostActiveDayOfWeek      = mostActiveDayOfWeek,
                mostActiveHour           = mostActiveHour,
            )
        }
    }.stateIn(
        scope        = viewModelScope,
        started      = SharingStarted.WhileSubscribed(5_000),
        initialValue = InsightsHubUiState.Loading,
    )
}
