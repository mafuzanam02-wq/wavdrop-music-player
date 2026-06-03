package com.launchpoint.wavdrop.ui.screen.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.launchpoint.wavdrop.data.backup.WavdropBackupRepository
import com.launchpoint.wavdrop.data.repository.SongRepository
import com.launchpoint.wavdrop.data.settings.AppSettingsRepository
import com.launchpoint.wavdrop.data.settings.LibraryScanMode
import com.launchpoint.wavdrop.data.settings.LibraryScanSettings
import com.launchpoint.wavdrop.data.settings.LibraryScanSettingsRepository
import com.launchpoint.wavdrop.data.settings.ResumeBehaviorSettings
import com.launchpoint.wavdrop.data.settings.ResumeBehaviorSettingsRepository
import com.launchpoint.wavdrop.data.settings.StartupDestination
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface ExportUiState {
    data object Idle : ExportUiState
    data object Exporting : ExportUiState
    data object Success : ExportUiState
    data class Error(val message: String) : ExportUiState
}

sealed interface LibraryScanUiState {
    data object Idle : LibraryScanUiState
    data object Scanning : LibraryScanUiState
    data object Complete : LibraryScanUiState
    data class Error(val message: String) : LibraryScanUiState
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val backupRepository: WavdropBackupRepository,
    private val appSettingsRepository: AppSettingsRepository,
    private val scanSettingsRepository: LibraryScanSettingsRepository,
    private val resumeBehaviorRepository: ResumeBehaviorSettingsRepository,
    private val songRepository: SongRepository,
) : ViewModel() {

    private val _exportUiState = MutableStateFlow<ExportUiState>(ExportUiState.Idle)
    val exportUiState: StateFlow<ExportUiState> = _exportUiState.asStateFlow()

    val libraryScanSettings: StateFlow<LibraryScanSettings> =
        scanSettingsRepository.settings.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = LibraryScanSettings(),
        )

    val startupDestination: StateFlow<StartupDestination> =
        appSettingsRepository.startupDestination.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = StartupDestination.SONGS,
        )

    val resumeBehaviorSettings: StateFlow<ResumeBehaviorSettings> =
        resumeBehaviorRepository.settings.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ResumeBehaviorSettings(),
        )

    private val _libraryScanUiState =
        MutableStateFlow<LibraryScanUiState>(LibraryScanUiState.Idle)
    val libraryScanUiState: StateFlow<LibraryScanUiState> =
        _libraryScanUiState.asStateFlow()

    fun exportTo(uri: Uri) {
        _exportUiState.value = ExportUiState.Exporting
        viewModelScope.launch {
            _exportUiState.value = runCatching {
                backupRepository.exportToUri(uri)
            }.fold(
                onSuccess = { ExportUiState.Success },
                onFailure = { e ->
                    ExportUiState.Error(e.message ?: "Export failed. Please try again.")
                },
            )
        }
    }

    fun setScanMode(scanMode: LibraryScanMode) {
        viewModelScope.launch {
            scanSettingsRepository.setScanMode(scanMode)
        }
    }

    fun setMinimumTrackDurationSeconds(seconds: Int) {
        viewModelScope.launch {
            scanSettingsRepository.setMinimumTrackDurationSeconds(seconds)
        }
    }

    fun addSelectedFolderUri(folderUri: String) {
        viewModelScope.launch {
            scanSettingsRepository.addSelectedFolderUri(folderUri)
        }
    }

    fun removeSelectedFolderUri(folderUri: String) {
        viewModelScope.launch {
            scanSettingsRepository.removeSelectedFolderUri(folderUri)
        }
    }

    fun setStartupDestination(destination: StartupDestination) {
        viewModelScope.launch {
            appSettingsRepository.setStartupDestination(destination)
        }
    }

    fun setRememberLastTrack(enabled: Boolean) {
        viewModelScope.launch { resumeBehaviorRepository.setRememberLastTrack(enabled) }
    }

    fun setRememberPosition(enabled: Boolean) {
        viewModelScope.launch { resumeBehaviorRepository.setRememberPosition(enabled) }
    }

    fun setRestoreQueue(enabled: Boolean) {
        viewModelScope.launch { resumeBehaviorRepository.setRestoreQueue(enabled) }
    }

    fun rescanLibrary() {
        if (_libraryScanUiState.value == LibraryScanUiState.Scanning) return
        _libraryScanUiState.value = LibraryScanUiState.Scanning
        viewModelScope.launch {
            _libraryScanUiState.value = runCatching {
                songRepository.sync()
            }.fold(
                onSuccess = { LibraryScanUiState.Complete },
                onFailure = { e ->
                    LibraryScanUiState.Error(e.message ?: "Library scan failed. Please try again.")
                },
            )
        }
    }
}
