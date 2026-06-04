package com.launchpoint.wavdrop.ui.screen.smart

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.launchpoint.wavdrop.data.model.SmartCollection
import com.launchpoint.wavdrop.data.repository.SmartCollectionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class SmartCollectionsUiState(
    val isLoading: Boolean = false,
    val collections: List<SmartCollection> = emptyList(),
)

@HiltViewModel
class SmartCollectionsViewModel @Inject constructor(
    private val repository: SmartCollectionRepository,
) : ViewModel() {

    val uiState: StateFlow<SmartCollectionsUiState> = repository.observeSmartCollections()
        .map { SmartCollectionsUiState(collections = it) }
        .stateIn(
            scope        = viewModelScope,
            started      = SharingStarted.WhileSubscribed(5_000),
            initialValue = SmartCollectionsUiState(isLoading = true),
        )
}
