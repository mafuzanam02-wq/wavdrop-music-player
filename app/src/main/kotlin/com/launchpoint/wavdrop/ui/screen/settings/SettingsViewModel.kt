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

    fun setAutoResumeOnHeadphones(enabled: Boolean) {
        viewModelScope.launch { resumeBehaviorRepository.setAutoResumeOnHeadphones(enabled) }
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
            runCatching { applyAppIcon(choice) }
                .onSuccess { confirmed ->
                    val message = if (confirmed) {
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

    /**
     * Applies the selected launcher alias using explicit manifest class names.
     * Enables the chosen alias first (so the launcher always has an active entry),
     * then disables all others.
     *
     * Returns true if getComponentEnabledSetting confirms the selected alias is
     * COMPONENT_ENABLED_STATE_ENABLED after the switch; false otherwise.
     */
    private fun applyAppIcon(choice: AppIconChoice): Boolean {
        val pkg = context.packageName
        val pm  = context.packageManager

        // Enable chosen alias FIRST — always keep at least one launcher entry active.
        val enableCn = ComponentName(pkg, choice.aliasClassName)
        pm.setComponentEnabledSetting(
            enableCn,
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP,
        )
        Log.d(TAG, "setComponentEnabledSetting ENABLED → ${choice.aliasClassName}")

        // Disable all other aliases now that the replacement is active.
        AppIconChoice.entries.filter { it != choice }.forEach { other ->
            pm.setComponentEnabledSetting(
                ComponentName(pkg, other.aliasClassName),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
            Log.d(TAG, "setComponentEnabledSetting DISABLED → ${other.aliasClassName}")
        }

        // Read back actual state for every launcher component and log diagnostics.
        val mainActivityState = pm.getComponentEnabledSetting(
            ComponentName(pkg, "com.launchpoint.wavdrop.MainActivity")
        )
        Log.d(TAG, "STATE MainActivity                          = $mainActivityState")
        AppIconChoice.entries.forEach { entry ->
            val state   = pm.getComponentEnabledSetting(ComponentName(pkg, entry.aliasClassName))
            val marker  = if (entry == choice) "★" else " "
            Log.d(TAG, "STATE $marker ${entry.aliasClassName} = $state")
        }

        // Confirm the selected alias is now explicitly enabled.
        val selectedState = pm.getComponentEnabledSetting(enableCn)
        val confirmed     = selectedState == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        if (!confirmed) {
            Log.w(TAG, "Alias not confirmed enabled after switch: state=$selectedState")
        }
        return confirmed
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
