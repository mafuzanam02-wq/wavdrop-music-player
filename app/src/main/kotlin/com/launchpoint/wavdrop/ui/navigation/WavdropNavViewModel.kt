package com.launchpoint.wavdrop.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.launchpoint.wavdrop.data.settings.AppSettingsRepository
import com.launchpoint.wavdrop.data.settings.StartupDestination
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class WavdropNavViewModel @Inject constructor(
    appSettingsRepository: AppSettingsRepository,
) : ViewModel() {
    val startupDestination: StateFlow<StartupDestination?> =
        appSettingsRepository.startupDestination
            .map<StartupDestination, StartupDestination?> { it }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = null,
            )
}
