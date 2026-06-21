package com.launchpoint.wavdrop.ui.screen.wrapped

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import com.launchpoint.wavdrop.data.stats.WrappedBuilder
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
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
    ) : WrappedUiState
}

@HiltViewModel
class WrappedViewModel @Inject constructor(
    songRepository: SongRepository,
    statsRepository: StatsRepository,
    appSettingsRepository: AppSettingsRepository,
) : ViewModel() {

    private val zone = ZoneId.systemDefault()
    private val selectedScope = MutableStateFlow(WrappedScope.YEARLY)
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
    ) { useArtworkBackgrounds, backgroundIntensity, fallbackTheme ->
        WrappedVisualPreferences(
            useArtworkBackgrounds = useArtworkBackgrounds,
            backgroundIntensity = backgroundIntensity,
            fallbackTheme = fallbackTheme,
        )
    }
    private val songData = combine(
        songRepository.songs,
        statsRepository.allTrackStatsEntities(),
    ) { songs, stats -> songs to stats }

    val uiState: StateFlow<WrappedUiState> = combine(
        songData,
        statsRepository.allListenEvents(),
        selection,
        appSettingsRepository.showMilestoneCelebrations,
        visualPreferences,
    ) { songAndStats, events, request, showMilestones, visualPrefs ->
        val songs: List<Song> = songAndStats.first
        val stats: List<TrackStatsEntity> = songAndStats.second
        val years = WrappedBuilder.availableYears(events, zone)
        val months = WrappedBuilder.availableMonths(events, zone)

        if (request.scope == WrappedScope.ALL_TIME) {
            return@combine WrappedUiState.Content(
                selectedScope = WrappedScope.ALL_TIME,
                availableYears = years,
                selectedYear = years.firstOrNull() ?: 0,
                availableMonths = months,
                selectedMonth = months.firstOrNull() ?: MonthYear(2020, 1),
                currentPeriod = WrappedPeriod.AllTime,
                summary = WrappedBuilder.buildAllTime(songs, stats),
                showMilestoneCelebrations = showMilestones,
                useArtworkBackgrounds = visualPrefs.useArtworkBackgrounds,
                backgroundIntensity = visualPrefs.backgroundIntensity,
                fallbackTheme = visualPrefs.fallbackTheme,
            )
        }

        val resolved = resolveWrappedSelection(
            request = request,
            availableYears = years,
            availableMonths = months,
            zone = zone,
        ) ?: return@combine WrappedUiState.Empty

        WrappedUiState.Content(
            selectedScope = resolved.scope,
            availableYears = years,
            selectedYear = resolved.year,
            availableMonths = months,
            selectedMonth = resolved.month,
            currentPeriod = resolved.period,
            summary = WrappedBuilder.buildPeriod(
                period = resolved.period,
                songs = songs,
                events = events,
            ),
            showMilestoneCelebrations = showMilestones,
            useArtworkBackgrounds = visualPrefs.useArtworkBackgrounds,
            backgroundIntensity = visualPrefs.backgroundIntensity,
            fallbackTheme = visualPrefs.fallbackTheme,
        )
    }
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

private data class WrappedVisualPreferences(
    val useArtworkBackgrounds: Boolean,
    val backgroundIntensity: WrappedBackgroundIntensity,
    val fallbackTheme: WrappedFallbackTheme,
)
