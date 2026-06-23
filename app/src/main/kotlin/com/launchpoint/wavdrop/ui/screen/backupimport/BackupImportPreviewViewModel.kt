package com.launchpoint.wavdrop.ui.screen.backupimport

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.launchpoint.wavdrop.data.backup.BackupIntegrityStatus
import com.launchpoint.wavdrop.data.backup.CleanInstallPreferenceRestoreResult
import com.launchpoint.wavdrop.data.backup.CleanInstallPreferenceRestorer
import com.launchpoint.wavdrop.data.backup.CleanInstallRecoveryPolicy
import com.launchpoint.wavdrop.data.backup.CleanInstallRecoveryOrchestrator
import com.launchpoint.wavdrop.data.backup.ImportFileValidation
import com.launchpoint.wavdrop.data.backup.DesktopWavdropBackup
import com.launchpoint.wavdrop.data.backup.DesktopWavdropBackupImportRepository
import com.launchpoint.wavdrop.data.backup.DesktopWavdropBackupParser
import com.launchpoint.wavdrop.data.backup.WavdropBackup
import com.launchpoint.wavdrop.data.backup.WavdropBackupImportApplyResult
import com.launchpoint.wavdrop.data.backup.WavdropBackupImportRepository
import com.launchpoint.wavdrop.data.backup.WavdropBackupParser
import com.launchpoint.wavdrop.data.settings.AppSettingsRepository
import com.launchpoint.wavdrop.data.repository.SongRepository
import com.launchpoint.wavdrop.data.settings.LibraryScanSettingsRepository
import com.launchpoint.wavdrop.ui.permission.hasAudioPermission
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
        /**
         * Non-null only for UNVERIFIED_LEGACY backups (v1 without a checksum).
         * Shown as a separate calm notice in the preview UI; VERIFIED backups
         * leave this null and show no integrity notice.
         */
        val legacyWarning        : String? = null,
        /**
         * Compact merge semantics notice shown for Wavdrop Android backup imports.
         * Kept separate from legacyWarning and warning so users can distinguish them.
         * Null for desktop imports.
         */
        val mergeNotice          : String? = null,
        /**
         * True when the backup contains at least one item that would produce a DB write.
         * False for empty backups, preferences-only backups, or all-no-op data.
         * Drives the Apply button enabled state for Wavdrop Android backups.
         */
        val hasMergeableData     : Boolean = true,
        /**
         * Non-null when [hasMergeableData] is false. Explains why in plain language.
         * Shown as a separate notice; kept distinct from [mergeNotice] and [legacyWarning].
         */
        val noOpReason           : String? = null,
        /** Unmatched backup tracks that will be preserved in quarantine on apply. */
        val tracksToPreserve     : Int = 0,
        /** Unmatched backup tracks already preserved from a prior import of this backup. */
        val tracksAlreadyPreserved: Int = 0,
        /**
         * Warnings from unknown optional capabilities declared by the backup.
         * Non-blocking: import proceeds, but the user must see these before applying.
         */
        val capabilityWarnings: List<String> = emptyList(),
        /** True only when the destination has no local listening/history state. */
        val cleanInstallRecovery: Boolean = false,
        /** Recovery must discard old SAF URIs and wait for a new folder selection. */
        val requiresFolderReselection: Boolean = false,
    ) : BackupImportUiState

    data object Applying : BackupImportUiState
    data object AwaitingMusicPermission : BackupImportUiState
    data object AwaitingFolderSelection : BackupImportUiState
    data object ScanningLibrary : BackupImportUiState

    data class Applied(val result: WavdropBackupImportApplyResult) : BackupImportUiState

    /**
     * Returned when the apply-time authoritative recheck determined that no persistent
     * state change was possible (all backup data already present, all-lower stats,
     * preferences-only, etc.). No data was written. UI shows a calm distinct result,
     * not "Merge complete".
     */
    data object NoChanges : BackupImportUiState

    data class Error(val message: String) : BackupImportUiState
}

@HiltViewModel
class BackupImportPreviewViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val importRepository: WavdropBackupImportRepository,
    private val desktopImportRepository: DesktopWavdropBackupImportRepository,
    private val appSettingsRepository: AppSettingsRepository,
    private val preferenceRestorer: CleanInstallPreferenceRestorer,
    private val songRepository: SongRepository,
    private val scanSettingsRepository: LibraryScanSettingsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<BackupImportUiState>(BackupImportUiState.Idle)
    val uiState: StateFlow<BackupImportUiState> = _uiState.asStateFlow()

    private var parsedBackup: WavdropBackup? = null
    private var parsedDesktopBackup: DesktopWavdropBackup? = null
    private var recoveryPreferenceResult: CleanInstallPreferenceRestoreResult? = null

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
                warning              = "Desktop stats, favorites, playlists, and listening history are matched by title, artist, and album. Backup import does not modify audio files.",
            )
        }

        val result = withContext(Dispatchers.Default) { WavdropBackupParser.parse(content) }
        val backup = result.backup
            ?: return BackupImportUiState.Error(userFacingParseError(result.error))

        parsedBackup = backup
        parsedDesktopBackup = null

        val legacyWarning = if (result.integrityStatus == BackupIntegrityStatus.UNVERIFIED_LEGACY) {
            "This is an older Wavdrop backup without integrity verification. " +
                "It can be restored, but its contents could not be verified."
        } else {
            null
        }

        val mergePreview = withContext(Dispatchers.IO) { importRepository.previewMerge(backup) }
        val cleanInstallRecovery = withContext(Dispatchers.IO) {
            importRepository.isCleanInstallRecoveryCandidate()
        }
        val requiresFolderReselection =
            cleanInstallRecovery && CleanInstallRecoveryPolicy.requiresFolderReselection(backup.preferences)

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
            warning              = null,
            legacyWarning        = legacyWarning,
            mergeNotice          = if (cleanInstallRecovery) {
                "Clean-install recovery will restore supported settings, scan this device, " +
                    "then merge history once against the populated library. Device folder permissions " +
                    "and playback mode are not transferred."
            } else {
                "This import keeps newer local listening history and adds compatible data from the backup. " +
                    "It does not replace your current history or settings."
            },
            hasMergeableData      = mergePreview.hasMergeableData ||
                (cleanInstallRecovery && backup.preferences != null),
            noOpReason            = mergePreview.noOpReason,
            tracksToPreserve      = mergePreview.newPendingTrackCount,
            tracksAlreadyPreserved = mergePreview.alreadyPendingTrackCount,
            capabilityWarnings    = result.warnings,
            cleanInstallRecovery  = cleanInstallRecovery,
            requiresFolderReselection = requiresFolderReselection,
        )
    }

    /**
     * Maps technical parser errors (malformed JSON, wrong format, missing schema
     * fields) to a calm user-facing message. Version errors stay specific so users
     * know a newer backup needs a newer app.
     */
    private fun userFacingParseError(error: String?): String = when {
        error == null -> ImportFileValidation.WAVDROP_NOT_A_BACKUP_MESSAGE
        // Surfaced verbatim: created by a newer Wavdrop that supports a higher version.
        error == WavdropBackupParser.NEWER_VERSION_ERROR -> error
        // Versioned but lower than supported — still surfaces version number.
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

    fun continueAfterMusicPermission() {
        if (_uiState.value != BackupImportUiState.AwaitingMusicPermission) return
        if (!context.hasAudioPermission()) return
        continueRecovery()
    }

    fun selectRecoveryMusicFolder(uri: String, permissionGranted: Boolean) {
        if (_uiState.value != BackupImportUiState.AwaitingFolderSelection) return
        viewModelScope.launch {
            if (!permissionGranted) {
                _uiState.value = BackupImportUiState.Error(
                    "Wavdrop could not keep access to that folder. Choose the folder again.",
                )
                return@launch
            }
            scanSettingsRepository.addSelectedFolderUri(uri)
            appSettingsRepository.setNeedsFolderReselectionAfterRestore(false)
            scanThenApplyRecovery()
        }
    }

    // ── Apply ─────────────────────────────────────────────────────────────────

    fun applyImport() {
        val preview = _uiState.value as? BackupImportUiState.Preview ?: return

        _uiState.value = BackupImportUiState.Applying
        viewModelScope.launch {
            if (preview.cleanInstallRecovery && parsedDesktopBackup == null) {
                beginCleanInstallRecovery()
            } else {
                applyCurrentImport()
            }
        }
    }

    private suspend fun beginCleanInstallRecovery() {
        val backup = parsedBackup
            ?: return setError("No parsed backup to recover.")
        recoveryPreferenceResult = preferenceRestorer.restore(backup.preferences)
        continueRecovery()
    }

    private fun continueRecovery() {
        when (
            CleanInstallRecoveryOrchestrator.nextStep(
                hasMusicPermission = context.hasAudioPermission(),
                needsFolderReselection = recoveryPreferenceResult?.needsFolderReselection == true,
            )
        ) {
            CleanInstallRecoveryOrchestrator.NextStep.REQUEST_MUSIC_PERMISSION ->
                _uiState.value = BackupImportUiState.AwaitingMusicPermission
            CleanInstallRecoveryOrchestrator.NextStep.REQUEST_FOLDER_SELECTION ->
                _uiState.value = BackupImportUiState.AwaitingFolderSelection
            CleanInstallRecoveryOrchestrator.NextStep.SCAN_LIBRARY ->
                viewModelScope.launch { scanThenApplyRecovery() }
        }
    }

    private suspend fun scanThenApplyRecovery() {
        _uiState.value = BackupImportUiState.ScanningLibrary
        val backup = parsedBackup
            ?: return setError("No parsed backup to recover.")
        val result = runCatching {
            CleanInstallRecoveryOrchestrator.scanThenApply(
                scanLibrary = {
                    withContext(Dispatchers.IO) { songRepository.sync() }
                },
                applyImport = {
                    withContext(Dispatchers.IO) { importRepository.applyImport(backup) }
                },
            )
        }.getOrElse {
            setError(it.message ?: "Library scan or import failed. Please try again.")
            return
        }
        val prefs = recoveryPreferenceResult
        _uiState.value = BackupImportUiState.Applied(
            result.copy(
                preferencesRestored = prefs?.restored == true,
                preferencesSkipped = false,
                needsAutoBackupFolderSelection = prefs?.needsAutoBackupFolderSelection == true,
                launcherIconRestored = prefs?.launcherIconRestored == true,
                cleanInstallRecovery = true,
                libraryScanCompleted = true,
                needsMusicFolderReselection = false,
                notRestoredOnThisDevice =
                    CleanInstallRecoveryOrchestrator.notRestoredOnThisDevice,
            ),
        )
    }

    private suspend fun applyCurrentImport() {
        _uiState.value = runCatching {
            withContext(Dispatchers.IO) {
                parsedDesktopBackup?.let { desktopImportRepository.applyImport(it) }
                    ?: parsedBackup?.let { importRepository.applyImport(it) }
                    ?: error("No parsed backup to import.")
            }
        }.fold(
            onSuccess = { result ->
                if (result.isNoOp) BackupImportUiState.NoChanges
                else BackupImportUiState.Applied(result)
            },
            onFailure = { e ->
                BackupImportUiState.Error(e.message ?: "Import failed. Please try again.")
            },
        )
    }

    private fun setError(message: String) {
        _uiState.value = BackupImportUiState.Error(message)
    }
}
