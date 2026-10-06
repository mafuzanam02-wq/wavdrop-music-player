package com.launchpoint.wavdrop.ui.screen.wrapped

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.launchpoint.wavdrop.data.local.entity.TrackListenEventEntity
import com.launchpoint.wavdrop.data.local.entity.TrackStatsEntity
import com.launchpoint.wavdrop.data.model.MonthYear
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.model.WrappedPeriod
import com.launchpoint.wavdrop.data.model.WrappedScope
import com.launchpoint.wavdrop.data.model.WrappedSummary
import com.launchpoint.wavdrop.data.repository.SongRepository
import com.launchpoint.wavdrop.data.repository.StatsRepository
import com.launchpoint.wavdrop.data.settings.AppSettingsRepository
import com.launchpoint.wavdrop.data.settings.WrappedBackgroundIntensity
import com.launchpoint.wavdrop.data.settings.WrappedFallbackTheme
import com.launchpoint.wavdrop.data.settings.WrappedVisualStyle
import com.launchpoint.wavdrop.data.stats.WrappedBuilder
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

sealed interface WrappedUiState {
    data object Loading : WrappedUiState
    data object Empty : WrappedUiState
    data class Content(
        val selectedScope: WrappedScope,
        val availableYears: List<Int>,
        val selectedYear: Int,
        val availableMonths: List<MonthYear>,
        val selectedMonth: MonthYear,
        val currentPeriod: WrappedPeriod,
        val summary: WrappedSummary,
        val showMilestoneCelebrations: Boolean,
        val useArtworkBackgrounds: Boolean,
        val backgroundIntensity: WrappedBackgroundIntensity,
        val fallbackTheme: WrappedFallbackTheme,
        val visualStyle: WrappedVisualStyle,
    ) : WrappedUiState
}

data class WrappedStorySnapshot(
    val reportKey: String,
    val page: Int,
    val isPlaying: Boolean,
    val progress: Float,
)

internal class WrappedStorySnapshotStore {
    private var snapshot: WrappedStorySnapshot? = null

    fun save(reportKey: String, page: Int, isPlaying: Boolean, progress: Float) {
        snapshot = WrappedStorySnapshot(
            reportKey = reportKey,
            page = page.coerceAtLeast(0),
            isPlaying = isPlaying,
            progress = progress.coerceIn(0f, 1f),
        )
    }

    fun get(reportKey: String, pageCount: Int): WrappedStorySnapshot? {
        if (pageCount <= 0) return null
        return snapshot
            ?.takeIf { it.reportKey == reportKey }
            ?.let {
                it.copy(
                    page = it.page.coerceIn(0, pageCount - 1),
                    progress = it.progress.coerceIn(0f, 1f),
                )
            }
    }

    fun clearIfReportChanged(reportKey: String) {
        if (snapshot?.reportKey != reportKey) {
            snapshot = null
        }
    }
}

@HiltViewModel
class WrappedViewModel @Inject constructor(
    songRepository: SongRepository,
    statsRepository: StatsRepository,
    appSettingsRepository: AppSettingsRepository,
) : ViewModel() {

    private val zone = ZoneId.systemDefault()
    private val storySnapshotStore = WrappedStorySnapshotStore()
    private val selectedScope = MutableStateFlow(WrappedScope.MONTHLY)
    private val selectedYear = MutableStateFlow<Int?>(null)
    private val selectedMonth = MutableStateFlow<MonthYear?>(null)
    private val selection = combine(
        selectedScope,
        selectedYear,
        selectedMonth,
    ) { scope, year, month ->
        WrappedSelectionRequest(
            scope = scope,
            year = year,
            month = month,
        )
    }
    private val visualPreferences = combine(
        appSettingsRepository.wrappedUseArtworkBackgrounds,
        appSettingsRepository.wrappedBackgroundIntensity,
        appSettingsRepository.wrappedFallbackTheme,
        appSettingsRepository.wrappedVisualStyle,
    ) { useArtworkBackgrounds, backgroundIntensity, fallbackTheme, visualStyle ->
        WrappedVisualPreferences(
            useArtworkBackgrounds = useArtworkBackgrounds,
            backgroundIntensity = backgroundIntensity,
            fallbackTheme = fallbackTheme,
            visualStyle = visualStyle,
        )
    }
    private val songData = combine(
        songRepository.songs,
        statsRepository.allTrackStatsEntities(),
    ) { songs, stats -> songs to stats }

    val uiState: StateFlow<WrappedUiState> = wrappedStateFlow(
        songData = songData,
        analyticsTimestamps = statsRepository.analyticsEventTimestamps(),
        selection = selection,
        showMilestoneCelebrations = appSettingsRepository.showMilestoneCelebrations,
        visualPreferences = visualPreferences,
        eventsInRange = statsRepository::listenEventsInRange,
        zone = zone,
    )
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope        = viewModelScope,
            started      = SharingStarted.WhileSubscribed(5_000),
            initialValue = WrappedUiState.Loading,
        )

    fun selectScope(scope: WrappedScope) {
        selectedScope.value = scope
    }

    fun selectYear(year: Int) {
        selectedYear.value = year
    }

    fun selectMonth(month: MonthYear) {
        selectedMonth.value = month
    }

    fun saveStorySnapshot(
        reportKey: String,
        page: Int,
        isPlaying: Boolean,
        progress: Float,
    ) {
        storySnapshotStore.save(reportKey, page, isPlaying, progress)
    }

    fun getStorySnapshot(reportKey: String, pageCount: Int): WrappedStorySnapshot? =
        storySnapshotStore.get(reportKey, pageCount)

    fun clearStorySnapshotIfReportChanged(reportKey: String) {
        storySnapshotStore.clearIfReportChanged(reportKey)
    }
}

internal data class WrappedSelectionRequest(
    val scope: WrappedScope,
    val year: Int?,
    val month: MonthYear?,
)

internal data class ResolvedWrappedSelection(
    val scope: WrappedScope,
    val year: Int,
    val month: MonthYear,
    val period: WrappedPeriod,
)

internal fun resolveWrappedSelection(
    request: WrappedSelectionRequest,
    availableYears: List<Int>,
    availableMonths: List<MonthYear>,
    zone: ZoneId,
): ResolvedWrappedSelection? {
    val year = request.year?.takeIf { it in availableYears }
        ?: availableYears.firstOrNull()
        ?: return null
    val month = request.month?.takeIf { it in availableMonths }
        ?: availableMonths.firstOrNull()
        ?: return null
    val period = when (request.scope) {
        WrappedScope.MONTHLY -> WrappedPeriod.month(month, zone)
        WrappedScope.YEARLY -> WrappedPeriod.year(year, zone)
        WrappedScope.ALL_TIME -> return null
    }
    return ResolvedWrappedSelection(
        scope = request.scope,
        year = year,
        month = month,
        period = period,
    )
}

internal data class WrappedVisualPreferences(
    val useArtworkBackgrounds: Boolean,
    val backgroundIntensity: WrappedBackgroundIntensity,
    val fallbackTheme: WrappedFallbackTheme,
    val visualStyle: WrappedVisualStyle,
)

internal fun WrappedPeriod.toWrappedReportKey(): String = when (this) {
    WrappedPeriod.AllTime -> "ALL_TIME"
    is WrappedPeriod.Yearly -> "YEARLY:$year"
    is WrappedPeriod.Monthly ->
        "MONTHLY:${month.year}-${month.month.toString().padStart(2, '0')}"
}

/** The resolved Wrapped choice: availability comes from lightweight timestamps; the period (if any) decides what is subscribed. */
internal sealed interface WrappedPlan {
    val years: List<Int>
    val months: List<MonthYear>

    data class Empty(override val years: List<Int>, override val months: List<MonthYear>) : WrappedPlan
    data class AllTime(override val years: List<Int>, override val months: List<MonthYear>) : WrappedPlan
    data class Period(
        override val years: List<Int>,
        override val months: List<MonthYear>,
        val resolved: ResolvedWrappedSelection,
    ) : WrappedPlan
}

/**
 * Wrapped data flow (WC-02). Availability (years/months with PLAY or SKIP activity) comes from timestamps only. ALL_TIME is built from
 * aggregate stats and subscribes to NO event stream. Yearly/Monthly subscribe only to the selected period's events through
 * [eventsInRange]; a selection change cancels the previous range subscription (flatMapLatest) and an unchanged plan does not
 * resubscribe. Output equals building from the full history because the period builder only reads events inside its range.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun wrappedStateFlow(
    songData: Flow<Pair<List<Song>, List<TrackStatsEntity>>>,
    analyticsTimestamps: Flow<List<Long>>,
    selection: Flow<WrappedSelectionRequest>,
    showMilestoneCelebrations: Flow<Boolean>,
    visualPreferences: Flow<WrappedVisualPreferences>,
    eventsInRange: (fromMs: Long, toMs: Long) -> Flow<List<TrackListenEventEntity>>,
    zone: ZoneId = ZoneId.systemDefault(),
): Flow<WrappedUiState> =
    combine(analyticsTimestamps, selection) { timestamps, request ->
        val years = WrappedBuilder.availableYearsFromTimestamps(timestamps, zone)
        val months = WrappedBuilder.availableMonthsFromTimestamps(timestamps, zone)
        if (request.scope == WrappedScope.ALL_TIME) {
            WrappedPlan.AllTime(years, months)
        } else {
            resolveWrappedSelection(request, years, months, zone)?.let { WrappedPlan.Period(years, months, it) }
                ?: WrappedPlan.Empty(years, months)
        }
    }
        .distinctUntilChanged()
        .flatMapLatest { plan ->
            when (plan) {
                is WrappedPlan.Empty -> flowOf(WrappedUiState.Empty)
                is WrappedPlan.AllTime -> combine(songData, showMilestoneCelebrations, visualPreferences) { songAndStats, showMilestones, visualPrefs ->
                    WrappedUiState.Content(
                        selectedScope = WrappedScope.ALL_TIME,
                        availableYears = plan.years,
                        selectedYear = plan.years.firstOrNull() ?: 0,
                        availableMonths = plan.months,
                        selectedMonth = plan.months.firstOrNull() ?: MonthYear(2020, 1),
                        currentPeriod = WrappedPeriod.AllTime,
                        summary = WrappedBuilder.buildAllTime(songAndStats.first, songAndStats.second),
                        showMilestoneCelebrations = showMilestones,
                        useArtworkBackgrounds = visualPrefs.useArtworkBackgrounds,
                        backgroundIntensity = visualPrefs.backgroundIntensity,
                        fallbackTheme = visualPrefs.fallbackTheme,
                        visualStyle = visualPrefs.visualStyle,
                    )
                }
                is WrappedPlan.Period -> {
                    val range = plan.resolved.period.range
                    combine(
                        songData,
                        eventsInRange(range.fromMs, range.toMs),
                        showMilestoneCelebrations,
                        visualPreferences,
                    ) { songAndStats, events, showMilestones, visualPrefs ->
                        WrappedUiState.Content(
                            selectedScope = plan.resolved.scope,
                            availableYears = plan.years,
                            selectedYear = plan.resolved.year,
                            availableMonths = plan.months,
                            selectedMonth = plan.resolved.month,
                            currentPeriod = plan.resolved.period,
                            summary = WrappedBuilder.buildPeriod(
                                period = plan.resolved.period,
                                songs = songAndStats.first,
                                events = events,
                            ),
                            showMilestoneCelebrations = showMilestones,
                            useArtworkBackgrounds = visualPrefs.useArtworkBackgrounds,
                            backgroundIntensity = visualPrefs.backgroundIntensity,
                            fallbackTheme = visualPrefs.fallbackTheme,
                            visualStyle = visualPrefs.visualStyle,
                        )
                    }
                }
            }
        }
