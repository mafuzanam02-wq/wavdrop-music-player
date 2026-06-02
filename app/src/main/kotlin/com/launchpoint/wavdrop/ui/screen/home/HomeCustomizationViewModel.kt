package com.launchpoint.wavdrop.ui.screen.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.launchpoint.wavdrop.data.settings.HomeLayoutSettings
import com.launchpoint.wavdrop.data.settings.HomeLayoutSettingsRepository
import com.launchpoint.wavdrop.data.settings.HomeSectionId
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class HomeCustomizationViewModel @Inject constructor(
    private val repository: HomeLayoutSettingsRepository,
) : ViewModel() {

    val settings: StateFlow<HomeLayoutSettings> = repository.settings.stateIn(
        scope        = viewModelScope,
        started      = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeLayoutSettings(),
    )

    fun setSectionVisible(id: HomeSectionId, visible: Boolean) {
        viewModelScope.launch { repository.setSectionVisible(id, visible) }
    }
}
