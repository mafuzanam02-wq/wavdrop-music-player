package com.launchpoint.wavdrop.ui.permission

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.launchpoint.wavdrop.data.settings.AudioPermissionHistoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Holds the persisted "ever granted" fact for [AudioPermissionGate]. `null` until it has been read. */
@HiltViewModel
class AudioPermissionViewModel @Inject constructor(
    private val history: AudioPermissionHistoryRepository,
) : ViewModel() {

    private val _hasEverGranted = MutableStateFlow<Boolean?>(null)
    val hasEverGranted: StateFlow<Boolean?> = _hasEverGranted.asStateFlow()

    init {
        viewModelScope.launch {
            val stored = history.hasEverGranted.first()
            _hasEverGranted.value = _hasEverGranted.value == true || stored
        }
    }

    /** Called when the permission is observed granted. Writes at most once per ViewModel. */
    fun recordGranted() {
        if (_hasEverGranted.value == true) return
        _hasEverGranted.value = true
        viewModelScope.launch { withContext(NonCancellable) { history.markGranted() } }
    }
}
