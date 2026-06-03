package com.launchpoint.wavdrop.ui.screen.settings

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.launchpoint.wavdrop.data.backup.WavdropBackupRepository
import com.launchpoint.wavdrop.data.repository.SongRepository
import com.launchpoint.wavdrop.data.settings.AccentColor
import com.launchpoint.wavdrop.data.settings.AppIconChoice
import com.launchpoint.wavdrop.data.settings.AppSettingsRepository
import com.launchpoint.wavdrop.data.settings.ThemeMode
import com.launchpoint.wavdrop.data.settings.LibraryScanMode
import com.launchpoint.wavdrop.data.settings.LibraryScanSettings
import com.launchpoint.wavdrop.data.settings.LibraryScanSettingsRepository
import com.launchpoint.wavdrop.data.settings.ResumeBehaviorSettings
import com.launchpoint.wavdrop.data.settings.ResumeBehaviorSettingsRepository
import com.launchpoint.wavdrop.data.settings.StartupDestination
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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
    @ApplicationContext private val context: Context,
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

    fun setAutoResumeOnBluetooth(enabled: Boolean) {
        viewModelScope.launch { resumeBehaviorRepository.setAutoResumeOnBluetooth(enabled) }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { appSettingsRepository.setThemeMode(mode) }
    }

    fun setAccentColor(color: AccentColor) {
        viewModelScope.launch { appSettingsRepository.setAccentColor(color) }
    }

    fun setAppIcon(choice: AppIconChoice) {
        viewModelScope.launch {
            appSettingsRepository.setAppIconChoice(choice)
            applyAppIcon(choice)
        }
    }

    private fun applyAppIcon(choice: AppIconChoice) {
        val pm = context.packageName
        val packageManager = context.packageManager

        // Enable the chosen alias FIRST so the launcher always has an active component.
        // Disabling the current alias before enabling the replacement can leave a brief
        // window with no active launcher entry, which causes Samsung/some launchers to
        // lose the shortcut instead of updating the icon.
        val enableCn = ComponentName(pm, "$pm.${choice.aliasSimpleName}")
        packageManager.setComponentEnabledSetting(
            enableCn,
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP,
        )
        Log.d(TAG, "Icon alias ENABLED: ${choice.aliasSimpleName}")

        // Disable all other aliases.
        AppIconChoice.entries.filter { it != choice }.forEach { iconChoice ->
            val disableCn = ComponentName(pm, "$pm.${iconChoice.aliasSimpleName}")
            packageManager.setComponentEnabledSetting(
                disableCn,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
            Log.d(TAG, "Icon alias DISABLED: ${iconChoice.aliasSimpleName}")
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
