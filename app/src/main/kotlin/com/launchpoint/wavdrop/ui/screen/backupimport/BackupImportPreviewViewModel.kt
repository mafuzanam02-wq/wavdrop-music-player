package com.launchpoint.wavdrop.ui.screen.backupimport

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.launchpoint.wavdrop.data.backup.ImportFileValidation
import com.launchpoint.wavdrop.data.backup.DesktopWavdropBackup
import com.launchpoint.wavdrop.data.backup.DesktopWavdropBackupImportRepository
import com.launchpoint.wavdrop.data.backup.DesktopWavdropBackupParser
import com.launchpoint.wavdrop.data.backup.WavdropBackup
import com.launchpoint.wavdrop.data.backup.WavdropBackupImportApplyResult
import com.launchpoint.wavdrop.data.backup.WavdropBackupImportRepository
import com.launchpoint.wavdrop.data.backup.WavdropBackupParser
import com.launchpoint.wavdrop.data.settings.AppSettingsRepository
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
        val isDesktopBackup      : Boolean = false,
        val matchedSongs         : Int = 0,
        val skippedUnmatched     : Int = 0,
        val skippedAmbiguous     : Int = 0,
        val statsWillIncrease    : Int = 0,
        val favoritesWillApply   : Int = 0,
        val warning              : String? = null,
    ) : BackupImportUiState

    data object Applying : BackupImportUiState

    data class Applied(val result: WavdropBackupImportApplyResult) : BackupImportUiState

    data class Error(val message: String) : BackupImportUiState
}

@HiltViewModel
class BackupImportPreviewViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val importRepository: WavdropBackupImportRepository,
    private val desktopImportRepository: DesktopWavdropBackupImportRepository,
    private val appSettingsRepository: AppSettingsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<BackupImportUiState>(BackupImportUiState.Idle)
    val uiState: StateFlow<BackupImportUiState> = _uiState.asStateFlow()

    private var parsedBackup: WavdropBackup? = null
    private var parsedDesktopBackup: DesktopWavdropBackup? = null

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
        // Gate by file name first: only .json files belong here. Files without a
        // usable display name fall through to the content sniff below.
        val displayName = ImportFileValidation.displayName(context, uri)
        val nameLooksRight = ImportFileValidation.isLikelyWavdropBackupFileName(displayName)
        if (displayName != null && !nameLooksRight) {
            return BackupImportUiState.Error(ImportFileValidation.WAVDROP_WRONG_FILE_MESSAGE)
        }

        val content = withContext(Dispatchers.IO) {
            context.contentResolver
                .openInputStream(uri)
                ?.bufferedReader()
                ?.use { it.readText() }
        } ?: return BackupImportUiState.Error("Could not open the selected file.")

        // Structural sniff before full parsing — rejects arbitrary JSON/binary
        // content without running the parser over it.
        if (!ImportFileValidation.isLikelyWavdropBackupContent(content)) {
            return BackupImportUiState.Error(ImportFileValidation.WAVDROP_NOT_A_BACKUP_MESSAGE)
        }

        if (DesktopWavdropBackupParser.isDesktopBackupContent(content)) {
            val result = DesktopWavdropBackupParser.parse(content)
            val backup = result.backup
                ?: return BackupImportUiState.Error(userFacingParseError(result.error))
            val plan = withContext(Dispatchers.IO) {
                desktopImportRepository.previewImport(backup)
            }
            parsedBackup = null
            parsedDesktopBackup = backup
            return BackupImportUiState.Preview(
                format               = DesktopWavdropBackupParser.APP_NAME,
                version              = backup.schemaVersion,
                exportedAt           = backup.exportedAt.ifBlank { "Unknown" },
                songCount            = backup.songs.size,
                statsCount           = backup.songs.size,
                baselineCount        = 0,
                lyricsOverridesCount = 0,
                hasPreferences       = false,
                playlistCount        = backup.playlists.size,
                listenEventsCount    = backup.listenEvents.size,
                isDesktopBackup      = true,
                matchedSongs         = plan.matchedCount,
                skippedUnmatched     = plan.unmatchedCount,
                skippedAmbiguous     = plan.ambiguousCount,
                statsWillIncrease    = plan.statsWillIncreaseCount,
                favoritesWillApply   = plan.favoritesWillApplyCount,
                warning              = "Desktop song IDs are not Android song IDs. Songs will be matched by title, artist, and album metadata.",
            )
        }

        val result = WavdropBackupParser.parse(content)
        val backup = result.backup
            ?: return BackupImportUiState.Error(userFacingParseError(result.error))

        parsedBackup = backup
        parsedDesktopBackup = null

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

    /**
     * Maps technical parser errors (malformed JSON, wrong format, missing schema
     * fields) to a calm user-facing message. Version errors stay specific so users
     * know a newer backup needs a newer app.
     */
    private fun userFacingParseError(error: String?): String = when {
        error == null -> ImportFileValidation.WAVDROP_NOT_A_BACKUP_MESSAGE
        error.startsWith("Unsupported backup version") -> error
        // Already plain language; tells the user the file is damaged rather than wrong.
        error.startsWith("Backup integrity check failed") -> error
        else -> ImportFileValidation.WAVDROP_NOT_A_BACKUP_MESSAGE
    }

    // ── Post-restore folder selection ─────────────────────────────────────────

    /**
     * Saves the folder picked right after a restore. The persistent pending flag is
     * cleared only when the persistable URI permission was actually granted; a save
     * without permission would still leave auto-backup unable to write.
     */
    fun saveAutoBackupFolder(uri: String, permissionGranted: Boolean) {
        viewModelScope.launch {
            appSettingsRepository.setAutoBackupFolderUri(uri)
            appSettingsRepository.setLastAutoBackupAtMillis(0L)
            if (permissionGranted) {
                appSettingsRepository.setNeedsAutoBackupFolderSelectionAfterRestore(false)
            }
        }
    }

    // ── Apply ─────────────────────────────────────────────────────────────────

    fun applyImport() {
        if (_uiState.value !is BackupImportUiState.Preview) return

        _uiState.value = BackupImportUiState.Applying
        viewModelScope.launch {
            _uiState.value = runCatching {
                withContext(Dispatchers.IO) {
                    parsedDesktopBackup?.let { desktopImportRepository.applyImport(it) }
                        ?: parsedBackup?.let { importRepository.applyImport(it) }
                        ?: error("No parsed backup to import.")
                }
            }.fold(
                onSuccess = { BackupImportUiState.Applied(it) },
                onFailure = { e ->
                    BackupImportUiState.Error(e.message ?: "Import failed. Please try again.")
                },
            )
        }
    }
}
