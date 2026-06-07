package com.launchpoint.wavdrop.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.launchpoint.wavdrop.BuildConfig
import com.launchpoint.wavdrop.data.settings.AppSettingsRepository
import com.launchpoint.wavdrop.data.settings.StartupDestination
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class WavdropNavViewModel @Inject constructor(
    private val appSettingsRepository: AppSettingsRepository,
) : ViewModel() {
    val startupDestination: StateFlow<StartupDestination?> =
        appSettingsRepository.startupDestination
            .map<StartupDestination, StartupDestination?> { it }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = null,
            )

    val compactMode: StateFlow<Boolean> =
        appSettingsRepository.compactMode
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = false,
            )

    val hasCompletedOnboarding: StateFlow<Boolean?> =
        appSettingsRepository.hasCompletedOnboarding
            .map<Boolean, Boolean?> { it }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = null,
            )

    val showChangelog: StateFlow<Boolean> =
        combine(
            appSettingsRepository.hasCompletedOnboarding,
            appSettingsRepository.lastSeenChangelogVersion,
        ) { completed, lastSeen ->
            completed && lastSeen < BuildConfig.VERSION_CODE
        }.stateIn(
            scope        = viewModelScope,
            started      = SharingStarted.WhileSubscribed(5_000),
            initialValue = false,
        )

    fun completeOnboarding() {
        viewModelScope.launch {
            appSettingsRepository.setHasCompletedOnboarding(true)
            // Seed the version so first-run users never see the auto-changelog
            appSettingsRepository.setLastSeenChangelogVersion(BuildConfig.VERSION_CODE)
        }
    }

    fun dismissChangelog() {
        viewModelScope.launch {
            appSettingsRepository.setLastSeenChangelogVersion(BuildConfig.VERSION_CODE)
        }
    }
}
