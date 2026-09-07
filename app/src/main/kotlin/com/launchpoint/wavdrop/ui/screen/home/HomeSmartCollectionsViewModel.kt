package com.launchpoint.wavdrop.ui.screen.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.launchpoint.wavdrop.data.model.SmartCollectionType
import com.launchpoint.wavdrop.data.settings.HomeLayoutSettingsRepository
import com.launchpoint.wavdrop.data.settings.HomeSmartCollectionSelection
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Drives the Home Smart Collections selector (WU-01). Every mutation keeps the selection at
 * exactly [HomeSmartCollectionSelection.HOME_SMART_COLLECTION_SLOTS] unique collections and
 * persists immediately, so Home updates reactively with no Save step and the stored state is
 * never left at 0/1/2/4+ or with duplicates.
 */
@HiltViewModel
class HomeSmartCollectionsViewModel @Inject constructor(
    private val repository: HomeLayoutSettingsRepository,
) : ViewModel() {

    val selection: StateFlow<List<SmartCollectionType>> = repository.settings
        .map { it.homeSmartCollections }
        .stateIn(
            scope        = viewModelScope,
            started      = SharingStarted.WhileSubscribed(5_000),
            initialValue = HomeSmartCollectionSelection.DEFAULT,
        )

    /** All collections not currently selected, offered under "Other collections". */
    val available: StateFlow<List<SmartCollectionType>> = selection
        .map { selected -> HOME_SMART_COLLECTION_PRIORITY.filterNot { it in selected } }
        .stateIn(
            scope        = viewModelScope,
            started      = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    fun moveUp(index: Int) = reorder(index, index - 1)

    fun moveDown(index: Int) = reorder(index, index + 1)

    private fun reorder(from: Int, to: Int) {
        val current = selection.value
        if (from !in current.indices || to !in current.indices) return
        val updated = current.toMutableList().apply { add(to, removeAt(from)) }
        persist(updated)
    }

    /**
     * Explicit replace: swaps [existing] out for [replacement] in the same slot, keeping the
     * other two selections and their order. No-op if the replacement is already selected.
     */
    fun replace(existing: SmartCollectionType, replacement: SmartCollectionType) {
        val current = selection.value
        val slot = current.indexOf(existing)
        if (slot < 0 || replacement in current) return
        val updated = current.toMutableList().apply { this[slot] = replacement }
        persist(updated)
    }

    private fun persist(types: List<SmartCollectionType>) {
        viewModelScope.launch { repository.setHomeSmartCollections(types) }
    }
}
