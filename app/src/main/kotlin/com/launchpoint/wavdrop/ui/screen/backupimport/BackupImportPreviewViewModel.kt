package com.launchpoint.wavdrop.ui.screen.backupimport

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.launchpoint.wavdrop.data.backup.WavdropBackup
import com.launchpoint.wavdrop.data.backup.WavdropBackupImportApplyResult
import com.launchpoint.wavdrop.data.backup.WavdropBackupImportRepository
import com.launchpoint.wavdrop.data.backup.WavdropBackupParser
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface BackupImportUiState {
    data object Idle    : BackupImportUiState
    data object Loading : BackupImportUiState

    data class Preview(
        val format               : String,
        val version              : Int,
        val exportedAt           : String,
        val songCount            : Int,
        val statsCount           : Int,
        val baselineCount        : Int,
        val lyricsOverridesCount : Int,
        val hasPreferences       : Boolean,
        val playlistCount        : Int,
        val listenEventsCount    : Int,
    ) : BackupImportUiState

    data object Applying : BackupImportUiState

    data class Applied(val result: WavdropBackupImportApplyResult) : BackupImportUiState

    data class Error(val message: String) : BackupImportUiState
}

@HiltViewModel
class BackupImportPreviewViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val importRepository: WavdropBackupImportRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<BackupImportUiState>(BackupImportUiState.Idle)
    val uiState: StateFlow<BackupImportUiState> = _uiState.asStateFlow()

    private var parsedBackup: WavdropBackup? = null

    // ── File loading ──────────────────────────────────────────────────────────

    fun processFile(uri: Uri) {
        _uiState.value = BackupImportUiState.Loading
        viewModelScope.launch {
            _uiState.value = runCatching { readAndParse(uri) }.getOrElse { e ->
                BackupImportUiState.Error(e.message ?: "Failed to read backup file.")
            }
        }
    }

    private suspend fun readAndParse(uri: Uri): BackupImportUiState {
        val content = withContext(Dispatchers.IO) {
            context.contentResolver
                .openInputStream(uri)
                ?.bufferedReader()
                ?.use { it.readText() }
        } ?: return BackupImportUiState.Error("Could not open the selected file.")

        val result = WavdropBackupParser.parse(content)
        val backup = result.backup
            ?: return BackupImportUiState.Error(result.error ?: "Invalid backup file.")

        parsedBackup = backup

        return BackupImportUiState.Preview(
            format               = WavdropBackupParser.SUPPORTED_FORMAT,
            version              = WavdropBackupParser.SUPPORTED_VERSION,
            exportedAt           = backup.exportedAt.ifBlank { "Unknown" },
            songCount            = backup.songs.size,
            statsCount           = backup.trackStats.size,
            baselineCount        = backup.importBaselines.size,
            lyricsOverridesCount = backup.lyricsOverrides.size,
            hasPreferences       = backup.preferences != null,
            playlistCount        = backup.playlists.size,
            listenEventsCount    = backup.listenEvents.size,
        )
    }

    // ── Apply ─────────────────────────────────────────────────────────────────

    fun applyImport() {
        val backup = parsedBackup ?: return
        if (_uiState.value !is BackupImportUiState.Preview) return

        _uiState.value = BackupImportUiState.Applying
        viewModelScope.launch {
            _uiState.value = runCatching {
                withContext(Dispatchers.IO) { importRepository.applyImport(backup) }
            }.fold(
                onSuccess = { BackupImportUiState.Applied(it) },
                onFailure = { e ->
                    BackupImportUiState.Error(e.message ?: "Import failed. Please try again.")
                },
            )
        }
    }
}
