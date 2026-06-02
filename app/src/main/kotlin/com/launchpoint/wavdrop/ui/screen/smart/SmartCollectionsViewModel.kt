package com.launchpoint.wavdrop.ui.screen.smart

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.launchpoint.wavdrop.data.model.SmartCollection
import com.launchpoint.wavdrop.data.repository.SmartCollectionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class SmartCollectionsViewModel @Inject constructor(
    private val repository: SmartCollectionRepository,
) : ViewModel() {

    val collections: StateFlow<List<SmartCollection>> = repository.observeSmartCollections()
        .stateIn(
            scope        = viewModelScope,
            started      = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )
}
