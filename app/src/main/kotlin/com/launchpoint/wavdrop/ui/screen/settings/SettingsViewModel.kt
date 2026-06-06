package com.launchpoint.wavdrop.ui.screen.settings

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.launchpoint.wavdrop.data.backup.WavdropBackupRepository
import com.launchpoint.wavdrop.data.repository.SongRepository
import com.launchpoint.wavdrop.data.settings.AccentColor
import com.launchpoint.wavdrop.data.settings.AppIconAliasManager
import com.launchpoint.wavdrop.data.settings.AppIconChoice
import com.launchpoint.wavdrop.data.settings.AppSettingsRepository
import com.launchpoint.wavdrop.data.settings.HeadphoneResumeMode
import com.launchpoint.wavdrop.data.settings.NotificationControlsSetting
import com.launchpoint.wavdrop.data.settings.ThemeMode
import com.launchpoint.wavdrop.data.settings.LibraryScanMode
import com.launchpoint.wavdrop.data.settings.LibraryScanSettings
import com.launchpoint.wavdrop.data.settings.LibraryScanSettingsRepository
import com.launchpoint.wavdrop.data.settings.ResumeBehaviorSettings
import com.launchpoint.wavdrop.data.settings.ResumeBehaviorSettingsRepository
import com.launchpoint.wavdrop.data.settings.StartupDestination
import com.launchpoint.wavdrop.playback.PlayerController
import com.launchpoint.wavdrop.playback.SleepTimerOption
import com.launchpoint.wavdrop.playback.SleepTimerState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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
    private val appIconAliasManager: AppIconAliasManager,
    private val scanSettingsRepository: LibraryScanSettingsRepository,
    private val resumeBehaviorRepository: ResumeBehaviorSettingsRepository,
    private val songRepository: SongRepository,
    private val playerController: PlayerController,
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

    val sleepTimerState: StateFlow<SleepTimerState> = playerController.sleepTimerState

    val notificationControlsSetting: StateFlow<NotificationControlsSetting> =
        appSettingsRepository.notificationControlsSetting.stateIn(
            scope        = viewModelScope,
            started      = SharingStarted.WhileSubscribed(5_000),
            initialValue = NotificationControlsSetting.STANDARD,
        )

    val appIconChoice: StateFlow<AppIconChoice> =
        appSettingsRepository.appIconChoice.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = AppIconChoice.MIDNIGHT_VIOLET,
        )

    val themeMode: StateFlow<ThemeMode> =
        appSettingsRepository.themeMode.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ThemeMode.SYSTEM,
        )

    val accentColor: StateFlow<AccentColor> =
        appSettingsRepository.accentColor.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = AccentColor.MIDNIGHT_VIOLET,
        )

    val compactMode: StateFlow<Boolean> =
        appSettingsRepository.compactMode.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = false,
        )

    private val _libraryScanUiState =
        MutableStateFlow<LibraryScanUiState>(LibraryScanUiState.Idle)
    val libraryScanUiState: StateFlow<LibraryScanUiState> =
        _libraryScanUiState.asStateFlow()

    private val _iconChangeEvent = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val iconChangeEvent: SharedFlow<String> = _iconChangeEvent.asSharedFlow()

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

    fun setIncludeWhatsAppVoiceNotes(enabled: Boolean) {
        viewModelScope.launch {
            scanSettingsRepository.setIncludeWhatsAppVoiceNotes(enabled)
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

    fun setPauseOnAudioDisconnect(enabled: Boolean) {
        viewModelScope.launch { resumeBehaviorRepository.setPauseOnAudioDisconnect(enabled) }
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

    fun setSleepTimer(option: SleepTimerOption) {
        playerController.setSleepTimer(option)
    }

    fun setNotificationControlsSetting(setting: NotificationControlsSetting) {
        viewModelScope.launch { appSettingsRepository.setNotificationControlsSetting(setting) }
    }

    fun setBluetoothResumeMode(mode: HeadphoneResumeMode) {
        viewModelScope.launch { resumeBehaviorRepository.setBluetoothResumeMode(mode) }
    }

    fun setWiredResumeMode(mode: HeadphoneResumeMode) {
        viewModelScope.launch { resumeBehaviorRepository.setWiredResumeMode(mode) }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { appSettingsRepository.setThemeMode(mode) }
    }

    fun setAccentColor(color: AccentColor) {
        viewModelScope.launch { appSettingsRepository.setAccentColor(color) }
    }

    fun setCompactMode(enabled: Boolean) {
        viewModelScope.launch { appSettingsRepository.setCompactMode(enabled) }
    }

    fun setAppIcon(choice: AppIconChoice) {
        viewModelScope.launch {
            appSettingsRepository.setAppIconChoice(choice)
            runCatching { appIconAliasManager.apply(choice) }
                .onSuccess { result ->
                    val message = if (result.confirmed) {
                        "Icon preference saved. Your phone's launcher controls when the icon updates."
                    } else {
                        "Icon preference saved, but the launcher icon could not be confirmed."
                    }
                    _iconChangeEvent.tryEmit(message)
                }
                .onFailure { e ->
                    Log.e(TAG, "Icon switch failed for ${choice.aliasClassName}", e)
                    _iconChangeEvent.tryEmit(
                        "Icon preference saved, but the launcher icon could not be confirmed."
                    )
                }
        }
    }

    private companion object {
        const val TAG = "Wavdrop-IconSwitch"
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
