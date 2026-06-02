package com.launchpoint.wavdrop.ui.screen.wrapped

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.launchpoint.wavdrop.data.model.WrappedSummary
import com.launchpoint.wavdrop.data.repository.SongRepository
import com.launchpoint.wavdrop.data.repository.StatsRepository
import com.launchpoint.wavdrop.data.stats.WrappedBuilder
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

sealed interface WrappedUiState {
    data object Loading : WrappedUiState
    data object Empty : WrappedUiState
    data class Content(
        val availableYears: List<Int>,
        val selectedYear: Int,
        val wrapped: WrappedSummary,
    ) : WrappedUiState
}

@HiltViewModel
class WrappedViewModel @Inject constructor(
    songRepository: SongRepository,
    statsRepository: StatsRepository,
) : ViewModel() {

    private val selectedYear = MutableStateFlow<Int?>(null)

    val uiState: StateFlow<WrappedUiState> = combine(
        songRepository.songs,
        statsRepository.allListenEvents(),
        selectedYear,
    ) { songs, events, requestedYear ->
        val years = WrappedBuilder.availableYears(events)
        if (years.isEmpty()) return@combine WrappedUiState.Empty

        val year = requestedYear?.takeIf { it in years } ?: years.first()
        WrappedUiState.Content(
            availableYears = years,
            selectedYear = year,
            wrapped = WrappedBuilder.buildYear(
                year = year,
                songs = songs,
                events = events,
            ),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = WrappedUiState.Loading,
    )

    fun selectYear(year: Int) {
        selectedYear.value = year
    }
}
