package com.launchpoint.wavdrop.ui.screen.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.launchpoint.wavdrop.data.backup.AutoBackupRepository
import com.launchpoint.wavdrop.data.backup.BackupVerificationRepository
import com.launchpoint.wavdrop.data.backup.BackupVerificationResult
import com.launchpoint.wavdrop.data.settings.AppSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface BackupVerificationUiState {
    data object Loading : BackupVerificationUiState
    data class Ready(val result: BackupVerificationResult) : BackupVerificationUiState
}

@HiltViewModel
class BackupVerificationViewModel @Inject constructor(
    private val verificationRepository: BackupVerificationRepository,
    private val autoBackupRepository: AutoBackupRepository,
    private val appSettingsRepository: AppSettingsRepository,
) : ViewModel() {

    private val _uiState =
        MutableStateFlow<BackupVerificationUiState>(BackupVerificationUiState.Loading)
    val uiState: StateFlow<BackupVerificationUiState> = _uiState.asStateFlow()

    private val _backupNowState = MutableStateFlow<ExportUiState>(ExportUiState.Idle)
    val backupNowState: StateFlow<ExportUiState> = _backupNowState.asStateFlow()

    private var started = false

    /** Runs the first verification when the screen opens; safe across recompositions. */
    fun verifyOnEntry() {
        if (started) return
        started = true
        verifyAgain()
    }

    fun verifyAgain() {
        _uiState.value = BackupVerificationUiState.Loading
        viewModelScope.launch {
            _uiState.value =
                BackupVerificationUiState.Ready(verificationRepository.verifyLatestBackup())
        }
    }

    /** Creates a backup via the existing backup path, then re-verifies. */
    fun createBackupNow() {
        if (_backupNowState.value == ExportUiState.Exporting) return
        _backupNowState.value = ExportUiState.Exporting
        viewModelScope.launch {
            _backupNowState.value = when (val result = autoBackupRepository.runNow()) {
                is AutoBackupRepository.Result.Success           -> ExportUiState.Success
                is AutoBackupRepository.Result.NoFolderSelected  -> ExportUiState.Error("No backup folder selected.")
                is AutoBackupRepository.Result.FolderUnavailable -> ExportUiState.Error(result.message)
                is AutoBackupRepository.Result.Failure           -> ExportUiState.Error(result.message)
                else                                             -> ExportUiState.Idle
            }
            verifyAgain()
        }
    }

    /** Saves a freshly picked backup folder (same semantics as Backup & Migration), then re-verifies. */
    fun onBackupFolderPicked(uri: String, permissionGranted: Boolean) {
        viewModelScope.launch {
            appSettingsRepository.setAutoBackupFolderUri(uri)
            if (permissionGranted) {
                appSettingsRepository.setNeedsAutoBackupFolderSelectionAfterRestore(false)
            }
            verifyAgain()
        }
    }
}
